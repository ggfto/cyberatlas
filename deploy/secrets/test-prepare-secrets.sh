#!/usr/bin/env bash
# Teste do prepare-secrets.sh com VALORES FALSOS num diretorio de scratch (nunca no ~/lab real).
# Uso: test-prepare-secrets.sh <scratch-novo> <gen-pki.sh> [bcprov.jar]
# Prova: idempotencia, arquivos 600 e pasta 700, nenhum valor no stdout/stderr, sealed = SealedSecret do namespace
# atlas com as chaves certas, e o conteudo selado (aberto com a chave de TESTE) igual aos arquivos de origem.
set -euo pipefail
umask 077
root=${1:?scratch}; pki=${2:?gen-pki.sh}; bcprov=${3:-}
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
script="$here/prepare-secrets.sh"
KUBESEAL=${KUBESEAL:-$(command -v kubeseal || echo "$HOME/.local/bin/kubeseal")}
[[ ! -e "$root" ]] || { echo "$root ja existe" >&2; exit 2; }
mkdir -p "$root"
fail() { echo "FALHOU: $*" >&2; exit 1; }
ok() { echo "ok: $*"; }

# par de chaves de sealed-secrets SO DE TESTE (o controller real nunca ve isto)
openssl req -x509 -newkey rsa:4096 -nodes -keyout "$root/test-ss.key" -out "$root/test-ss.pem" -days 1 \
  -subj "/CN=sealed-secret-teste" 2>/dev/null

d="$root/atlas-secrets"
mkdir -p "$d" && chmod 775 "$d"
printf 'github_pat_FALSO_%s\n' "$(openssl rand -hex 16)" > "$d/github.token"
cat > "$d/r2.env" <<R2
RCLONE_CONFIG_R2_ACCESS_KEY_ID=akid-falso-$(openssl rand -hex 8)
RCLONE_CONFIG_R2_SECRET_ACCESS_KEY=sak-falso-$(openssl rand -hex 16)
RCLONE_CONFIG_R2_ENDPOINT=https://contafalsa$(openssl rand -hex 4).r2.cloudflarestorage.com
R2_BUCKET=bucket-falso-$(openssl rand -hex 4)
CHAVE_EXTRA_QUE_NAO_DEVE_IR=nao-vai-$(openssl rand -hex 4)
R2
printf 'ATLAS_CLOUDFLARE_TOKEN=cf-falso-%s\nATLAS_CLOUDFLARE_ACCOUNT_ID=acc-falso-%s\n' "$(openssl rand -hex 12)" "$(openssl rand -hex 6)" > "$d/cloudflare.env"
printf 'argo-falso-%s\n' "$(openssl rand -hex 12)" > "$d/argocd.token"
chmod 600 "$d"/*

run() { KUBESEAL="$KUBESEAL" bash "$script" --dir "$d" --cert "$root/test-ss.pem" --pki-script "$pki" > "$root/out$1.txt" 2> "$root/err$1.txt" \
  || fail "rodada $1 saiu com erro: $(cat "$root/err$1.txt")"; }
snapshot() { (cd "$d" && find . -type f ! -path './.work*' -print0 | sort -z | xargs -0 sha256sum) > "$root/snap$1.txt"; }

run 1
[[ $(stat -c '%a' "$d") == 700 ]] || fail "pasta nao virou 700"; ok "pasta corrigida para 700"
bad=$(find "$d" -type f ! -perm 600 | wc -l); [[ $bad == 0 ]] || fail "$bad arquivo(s) sem 600"; ok "todos os arquivos 600"
for f in oidc-client-secret oidc-cookie-key postgres-password breakglass-user breakglass-password breakglass-bcrypt pki/ca.key pki/server.crt \
  sealed/sealedsecret-atlas-secrets.yaml sealed/sealedsecret-atlas-db.yaml sealed/sealedsecret-atlas-backup-r2.yaml sealed/sealedsecret-atlas-pki.yaml; do
  [[ -s "$d/$f" ]] || fail "faltou $f"
done; ok "tudo gerado e os 4 sealed"
for f in oidc-client-secret oidc-cookie-key postgres-password breakglass-password breakglass-bcrypt; do
  [[ $(tail -c1 "$d/$f" | od -An -c | tr -d ' ') != '\n' ]] || fail "$f com \\n no fim"
done; ok "segredos sem \\n no fim"
grep -Eq '^\$2[aby]\$12\$' "$d/breakglass-bcrypt" || fail "bcrypt sem cost 12"; ok "bcrypt cost 12"

# nenhum valor no output: todo valor gerado e todo valor de entrada, linha a linha
vals="$root/valores.txt"
: > "$vals"
for f in oidc-client-secret oidc-cookie-key postgres-password breakglass-password breakglass-bcrypt; do cat "$d/$f" >> "$vals"; echo >> "$vals"; done
sed -z 's/\n*$//' "$d/github.token" >> "$vals"; echo >> "$vals"
sed -z 's/\n*$//' "$d/argocd.token" >> "$vals"; echo >> "$vals"
cut -d= -f2- "$d/r2.env" "$d/cloudflare.env" >> "$vals"
grep -v 'PRIVATE KEY' "$d/pki/ca.key" | grep -v '^$' >> "$vals"
sealed_bits() { grep -hoE '[A-Za-z0-9+/=]{40,}' "$d"/sealed/*.yaml | head -20; }

run 2
snapshot 1
run 3
snapshot 2
cmp -s "$root/snap1.txt" "$root/snap2.txt" || fail "rodada 3 mudou arquivo"; ok "idempotente (nenhum arquivo mudou na rodada repetida)"
grep -q "sem mudanca" "$root/out3.txt" && ! grep -q "selado$" "$root/out3.txt" || fail "rodada repetida reselou"; ok "nao resela sem mudanca"
for n in 1 2 3; do
  if grep -F -f "$vals" "$root/out$n.txt" "$root/err$n.txt" > /dev/null; then fail "valor apareceu no output da rodada $n"; fi
done; ok "nenhum valor no stdout/stderr ($(grep -c . "$vals") valores conferidos)"

# sealed: estrutura e conteudo (aberto com a chave de TESTE, comparado em memoria, nada impresso)
python3 - "$d" "$root/test-ss.key" "$KUBESEAL" <<'PY'
import sys, subprocess, yaml, base64, pathlib
d, key, kubeseal = pathlib.Path(sys.argv[1]), sys.argv[2], sys.argv[3]
def rd(p): return pathlib.Path(p).read_bytes()
def strip(b): return b.rstrip(b"\n")
def envf(p, keys):
    out = {}
    for line in rd(p).decode().splitlines():
        k, _, v = line.partition("=")
        if k in keys: out[k] = v.encode()
    return out
r2 = envf(d/"r2.env", ["RCLONE_CONFIG_R2_ACCESS_KEY_ID","RCLONE_CONFIG_R2_SECRET_ACCESS_KEY","RCLONE_CONFIG_R2_ENDPOINT","R2_BUCKET"])
cf = envf(d/"cloudflare.env", ["ATLAS_CLOUDFLARE_TOKEN","ATLAS_CLOUDFLARE_ACCOUNT_ID"])
expect = {
 "atlas-db": {"POSTGRES_PASSWORD": rd(d/"postgres-password")},
 "atlas-pki": {k: rd(d/"pki"/k) for k in ["ca.crt","ca.key","server.crt","server.key"]},
 "atlas-backup-r2": r2,
 "atlas-secrets": {"QUARKUS_DATASOURCE_PASSWORD": rd(d/"postgres-password"), "QUARKUS_OIDC_CREDENTIALS_SECRET": rd(d/"oidc-client-secret"),
   "QUARKUS_OIDC_TOKEN_STATE_MANAGER_ENCRYPTION_SECRET": rd(d/"oidc-cookie-key"), "ATLAS_BREAKGLASS_USER": b"atlas-admin",
   "ATLAS_BREAKGLASS_BCRYPT": rd(d/"breakglass-bcrypt"), "ATLAS_SERVICESYML_GITHUB_TOKEN": strip(rd(d/"github.token")),
   "ATLAS_ARGOCD_TOKEN": strip(rd(d/"argocd.token")), **cf},
}
for name, want in expect.items():
    f = d/"sealed"/f"sealedsecret-{name}.yaml"
    doc = yaml.safe_load(f.read_text())
    assert doc["kind"] == "SealedSecret", name
    assert doc["metadata"]["namespace"] == "atlas" and doc["metadata"]["name"] == name, name
    ann = (doc["metadata"].get("annotations") or {})
    assert not any(k.startswith("sealedsecrets.bitnami.com/") and "wide" in k for k in ann), (name, "escopo nao e strict")
    assert set(doc["spec"]["encryptedData"]) == set(want), (name, "chaves divergentes")
    sec = yaml.safe_load(subprocess.run([kubeseal, "--recovery-unseal", "--recovery-private-key", key, "-o", "yaml"],
                                        input=f.read_bytes(), capture_output=True, check=True).stdout)
    got = {k: base64.b64decode(v) for k, v in sec["data"].items()}
    bad = [k for k in want if got.get(k) != want[k]]
    assert not bad, (name, "conteudo divergente em", bad)
print("ok: 4 SealedSecret do namespace atlas, chaves e conteudo conferidos (abertos com a chave de teste)")
PY

if [[ -n "$bcprov" ]]; then
  cat > "$root/Check.java" <<'JAVA'
public class Check { public static void main(String[] a) throws Exception {
  String h = java.nio.file.Files.readString(java.nio.file.Path.of(a[0]));
  char[] p = java.nio.file.Files.readString(java.nio.file.Path.of(a[1])).toCharArray();
  if (!org.bouncycastle.crypto.generators.OpenBSDBCrypt.checkPassword(h, p)) { System.exit(1); } } }
JAVA
  java -cp "$bcprov" "$root/Check.java" "$d/breakglass-bcrypt" "$d/breakglass-password" || fail "o hub (OpenBSDBCrypt) nao aceita o hash"
  ok "hash aceito pelo OpenBSDBCrypt (o mesmo do AtlasAuthMechanism)"
fi

# entrada faltando: sela o que esta completo, lista o que falta, sai 0, nao sela o incompleto
d2="$root/sem-r2"; mkdir -p "$d2"; chmod 700 "$d2"
cp "$d/github.token" "$d2/"
KUBESEAL="$KUBESEAL" bash "$script" --dir "$d2" --cert "$root/test-ss.pem" --pki-script "$pki" > "$root/out-falta.txt" 2>&1 || fail "faltando nao saiu 0"
[[ ! -e "$d2/sealed/sealedsecret-atlas-backup-r2.yaml" ]] || fail "selou backup sem r2.env"
[[ -s "$d2/sealed/sealedsecret-atlas-secrets.yaml" ]] || fail "nao selou atlas-secrets completo"
grep -q "FALTANDO" "$root/out-falta.txt" && grep -q "r2.env com RCLONE_CONFIG_R2_ACCESS_KEY_ID" "$root/out-falta.txt" || fail "nao listou r2.env"
ok "sem r2.env: sai 0, lista o que falta, nao sela o backup, sela o resto"
printf 'RCLONE_CONFIG_R2_ACCESS_KEY_ID=x\n' > "$d2/r2.env"; chmod 600 "$d2/r2.env"
KUBESEAL="$KUBESEAL" bash "$script" --dir "$d2" --cert "$root/test-ss.pem" --pki-script "$pki" > "$root/out-incompleto.txt" 2>&1
[[ ! -e "$d2/sealed/sealedsecret-atlas-backup-r2.yaml" ]] || fail "selou r2.env incompleto"
ok "r2.env incompleto tambem nao sela"
echo "TUDO OK"
