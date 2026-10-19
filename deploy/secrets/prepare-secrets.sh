#!/usr/bin/env bash
# Gera e sela os segredos do atlas (Cyber Atlas) SEM IMPRIMIR NENHUM VALOR.
#
# Uso: prepare-secrets.sh [--dir DIR] [--cert PEM] [--pki-script gen-pki.sh]
#   --dir         onde ficam os segredos (padrao ~/lab/atlas-secrets; o diretorio vira 700)
#   --cert        cert PUBLICO do sealed-secrets da VPS (padrao: DIR/sealed-secrets-vps.pem; se nao existir, baixa de
#                 ggfto/infra k8s/_certs/sealed-secrets-vps.pem com o gh)
#   --pki-script  o deploy/pki/gen-pki.sh do cyberatlas (padrao: ao lado deste script ou ../pki/)
#
# Idempotente: nunca sobrescreve um segredo gerado; so sela de novo o Secret cujo conteudo mudou. Valores so passam
# por arquivo (nunca argv, nunca log). Secret com entrada da pessoa faltando nao e selado: o script lista o que falta
# e sai 0. Saida: so nomes de arquivo e estado.
set -euo pipefail
umask 077

dir="$HOME/lab/atlas-secrets"
cert=""
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
pki_script=""
while (( $# )); do
  case "$1" in
    --dir) dir=$2; shift 2 ;;
    --cert) cert=$2; shift 2 ;;
    --pki-script) pki_script=$2; shift 2 ;;
    *) echo "argumento desconhecido: $1" >&2; exit 2 ;;
  esac
done
NS=atlas
BREAKGLASS_USER=atlas-admin
# bcrypt sem htpasswd no host: imagem fixada por digest, rede desligada, senha por stdin
HTPASSWD_IMAGE=docker.io/library/httpd:2.4.69-alpine@sha256:3440c39d8d6f54fa9ad2549e5a60c19ddd435faadc29c1ad28aa795f71888889
PKI_SANS=(DNS:atlas-hub.atlas.svc IP:10.99.0.1 IP:172.17.0.1)
KUBESEAL=${KUBESEAL:-$(command -v kubeseal || echo "$HOME/.local/bin/kubeseal")}

say() { printf '  %-44s %s\n' "$1" "$2"; }
die() { echo "erro: $*" >&2; exit 1; }

for tool in openssl kubectl sed sha256sum; do command -v "$tool" >/dev/null || die "precisa de $tool"; done
[[ -x "$KUBESEAL" ]] || die "kubeseal nao encontrado (KUBESEAL=...)"
if [[ -z "$pki_script" ]]; then
  for c in "$here/gen-pki.sh" "$here/../pki/gen-pki.sh"; do [[ -f "$c" ]] && pki_script=$c && break; done
fi

[[ -d "$dir" ]] || die "$dir nao existe"
[[ -O "$dir" ]] || die "$dir nao e seu"
if [[ $(stat -c '%a' "$dir") != 700 ]]; then
  chmod 700 "$dir"
  echo "permissao de $dir corrigida para 700"
fi
mkdir -p "$dir/sealed"
work=$(mktemp -d "$dir/.work.XXXXXX")
trap 'rm -rf "$work"' EXIT

echo "== segredos gerados (nunca sobrescritos)"
# gera com o comando dado (stdout do comando -> arquivo), sem \n no fim, 600, atomico
gen() {
  local name=$1; shift
  if [[ -e "$dir/$name" ]]; then say "$name" "ja existia"; return; fi
  "$@" | tr -d '\n' > "$work/$name"
  [[ -s "$work/$name" ]] || die "$name saiu vazio"
  chmod 600 "$work/$name"
  mv "$work/$name" "$dir/$name"
  say "$name" "gerado"
}
gen oidc-client-secret openssl rand -base64 48
gen oidc-cookie-key openssl rand -base64 48
gen postgres-password openssl rand -hex 32
gen breakglass-user printf '%s' "$BREAKGLASS_USER"

if [[ -e "$dir/breakglass-bcrypt" && ! -e "$dir/breakglass-password" ]]; then
  die "breakglass-bcrypt existe sem breakglass-password: apague os dois para gerar de novo"
fi
new_password=no
[[ -e "$dir/breakglass-password" ]] || new_password=yes
gen breakglass-password openssl rand -hex 32
if [[ -e "$dir/breakglass-bcrypt" && $new_password == yes ]]; then
  die "breakglass-password nova com breakglass-bcrypt antigo: apague breakglass-bcrypt"
fi
bcrypt_of() {
  if python3 -c 'import bcrypt' 2>/dev/null; then
    python3 -c 'import sys,bcrypt; sys.stdout.write(bcrypt.hashpw(sys.stdin.buffer.read(), bcrypt.gensalt(12)).decode())' \
      < "$dir/breakglass-password"
  else
    command -v docker >/dev/null || die "sem python3 bcrypt e sem docker para o htpasswd"
    docker run --rm -i -q --network none "$HTPASSWD_IMAGE" htpasswd -niB -C 12 "$BREAKGLASS_USER" \
      < "$dir/breakglass-password" | cut -d: -f2-
  fi
}
if [[ -e "$dir/breakglass-bcrypt" ]]; then
  say breakglass-bcrypt "ja existia"
else
  bcrypt_of | tr -d '\n' > "$work/bcrypt"
  grep -Eq '^\$2[aby]\$12\$.{53}$' "$work/bcrypt" || die "hash bcrypt invalido (nada foi gravado)"
  chmod 600 "$work/bcrypt"
  mv "$work/bcrypt" "$dir/breakglass-bcrypt"
  say breakglass-bcrypt "gerado (cost 12)"
fi

if [[ -d "$dir/pki" ]]; then
  say "pki/" "ja existia"
else
  [[ -n "$pki_script" ]] || die "gen-pki.sh nao encontrado (--pki-script)"
  bash "$pki_script" "$dir/pki" "${PKI_SANS[@]}" > /dev/null
  chmod 600 "$dir/pki"/*
  say "pki/" "gerada (${PKI_SANS[*]})"
fi

echo "== entradas da pessoa"
missing=()
# copia sem os \n do fim (de arquivo para arquivo: o valor nunca vira argumento)
strip() { sed -z 's/\n*$//' "$1" > "$2"; chmod 600 "$2"; }
# so as chaves esperadas de um .env, e todas presentes e nao vazias
env_subset() {
  local src=$1 out=$2; shift 2
  local k ok=0
  : > "$out"
  for k in "$@"; do
    if grep -Eq "^${k}=.+" "$src"; then grep -E "^${k}=" "$src" | tail -1 >> "$out"; else ok=1; fi
  done
  chmod 600 "$out"
  return $ok
}
github=no
if [[ -s "$dir/github.token" ]]; then strip "$dir/github.token" "$work/github.token"; github=yes; say github.token "ok"
else missing+=("github.token (fine-grained so ggfto/infra, Contents: Read-only)"); say github.token "FALTANDO"; fi

R2_KEYS=(RCLONE_CONFIG_R2_ACCESS_KEY_ID RCLONE_CONFIG_R2_SECRET_ACCESS_KEY RCLONE_CONFIG_R2_ENDPOINT R2_BUCKET)
r2=no
if [[ -f "$dir/r2.env" ]] && env_subset "$dir/r2.env" "$work/r2.env" "${R2_KEYS[@]}"; then r2=yes; say r2.env "ok"
else missing+=("r2.env com ${R2_KEYS[*]}"); say r2.env "FALTANDO (ou incompleto)"; fi

CF_KEYS=(ATLAS_CLOUDFLARE_TOKEN ATLAS_CLOUDFLARE_ACCOUNT_ID)
cloudflare=no
if [[ -f "$dir/cloudflare.env" ]]; then
  if env_subset "$dir/cloudflare.env" "$work/cloudflare.env" "${CF_KEYS[@]}"; then cloudflare=yes; say cloudflare.env "ok"
  else missing+=("cloudflare.env incompleto: precisa de ${CF_KEYS[*]} (ou apague o arquivo para desligar)"); say cloudflare.env "INCOMPLETO"; fi
else say cloudflare.env "ausente (fonte Cloudflare desligada)"; fi
argocd=no
if [[ -s "$dir/argocd.token" ]]; then strip "$dir/argocd.token" "$work/argocd.token"; argocd=yes; say argocd.token "ok"
else say argocd.token "ausente (fonte Argo desligada)"; fi

[[ -n "$cert" ]] || cert="$dir/sealed-secrets-vps.pem"
if [[ ! -f "$cert" ]]; then
  command -v gh >/dev/null || die "sem $cert e sem gh para buscar o cert publico"
  gh api repos/ggfto/infra/contents/k8s/_certs/sealed-secrets-vps.pem --jq .content | base64 -d > "$work/cert.pem"
  mv "$work/cert.pem" "$cert"
  echo "cert publico do sealed-secrets baixado do ggfto/infra: $cert"
fi
openssl x509 -in "$cert" -noout 2>/dev/null || die "$cert nao e um certificado"

# um arquivo por chave de um .env (o kubectl nao mistura --from-env-file com --from-file); devolve o caminho
key_file() {
  local out="$work/kv-$2"
  sed -n "s/^$2=//p" "$1" | tail -1 | tr -d '\n' > "$out"
  chmod 600 "$out"
  printf '%s' "$out"
}

echo "== sealed (namespace $NS) em $dir/sealed"
# sela um Secret a partir de argumentos --from-file/--from-env-file (caminhos, nunca valores); so se mudou
seal() {
  local name=$1; shift
  local out="$dir/sealed/sealedsecret-$name.yaml" stamp="$dir/sealed/.stamp-$name"
  kubectl create secret generic "$name" -n "$NS" "$@" --dry-run=client -o yaml > "$work/$name.yaml"
  local sum; sum=$(sha256sum "$work/$name.yaml" | cut -d' ' -f1)
  if [[ -f "$out" && -f "$stamp" && $(cat "$stamp") == "$sum" ]]; then say "sealedsecret-$name.yaml" "sem mudanca"; return; fi
  "$KUBESEAL" --cert "$cert" --format yaml --scope strict < "$work/$name.yaml" > "$work/$name.sealed"
  mv "$work/$name.sealed" "$out"
  printf '%s' "$sum" > "$stamp"
  chmod 600 "$out" "$stamp"
  say "sealedsecret-$name.yaml" "selado"
}
seal atlas-db --from-file=POSTGRES_PASSWORD="$dir/postgres-password"
seal atlas-pki --from-file=ca.crt="$dir/pki/ca.crt" --from-file=ca.key="$dir/pki/ca.key" \
  --from-file=server.crt="$dir/pki/server.crt" --from-file=server.key="$dir/pki/server.key"
if [[ $github == yes ]]; then
  args=(--from-file=QUARKUS_DATASOURCE_PASSWORD="$dir/postgres-password"
        --from-file=QUARKUS_OIDC_CREDENTIALS_SECRET="$dir/oidc-client-secret"
        --from-file=QUARKUS_OIDC_TOKEN_STATE_MANAGER_ENCRYPTION_SECRET="$dir/oidc-cookie-key"
        --from-file=ATLAS_BREAKGLASS_USER="$dir/breakglass-user"
        --from-file=ATLAS_BREAKGLASS_BCRYPT="$dir/breakglass-bcrypt"
        --from-file=ATLAS_SERVICESYML_GITHUB_TOKEN="$work/github.token")
  if [[ $cloudflare == yes ]]; then
    for k in "${CF_KEYS[@]}"; do args+=(--from-file="$k=$(key_file "$work/cloudflare.env" "$k")"); done
  fi
  [[ $argocd == yes ]] && args+=(--from-file=ATLAS_ARGOCD_TOKEN="$work/argocd.token")
  if [[ -f "$dir/cloudflare.env" && $cloudflare == no ]]; then
    say sealedsecret-atlas-secrets.yaml "NAO selado (cloudflare.env incompleto)"
  else
    seal atlas-secrets "${args[@]}"
  fi
else
  say sealedsecret-atlas-secrets.yaml "NAO selado (falta github.token)"
fi
if [[ $r2 == yes ]]; then
  args=(); for k in "${R2_KEYS[@]}"; do args+=(--from-file="$k=$(key_file "$work/r2.env" "$k")"); done
  seal atlas-backup-r2 "${args[@]}"
else say sealedsecret-atlas-backup-r2.yaml "NAO selado (falta r2.env)"; fi

echo "== proximos passos (so caminhos; nenhum valor)"
echo "  Authentik: o conteudo de $dir/oidc-client-secret vai em ATLAS_OIDC_CLIENT_SECRET (Environment da stack)"
echo "  Cofre: $dir/breakglass-password e $dir/pki/ca.key (depois apague a copia local da ca.key)"
echo "  Infra: copie $dir/sealed/sealedsecret-*.yaml para k8s/vps/_atlas/ e descomente no kustomization"
if (( ${#missing[@]} )); then
  echo "== FALTANDO (crie em $dir, 600, e rode de novo):"
  for m in "${missing[@]}"; do echo "  - $m"; done
fi
exit 0
