#!/usr/bin/env bash
# Enrollment do atlas edge que roda no k3s da maquina (deploy/kustomize/edge). A PESSOA roda, na propria maquina,
# depois que o Argo criou o Deployment atlas-edge:
#
#   deploy/kustomize/edge/enroll.sh --machine vps --ca <ca.crt> --kubectl "sudo k3s kubectl"
#   deploy/kustomize/edge/enroll.sh --machine host --ca <ca.crt> --kubectl kubectl
#
# Passos: (0) confere que o Deployment existe e que a SA do edge NAO apaga pod nem le secret; (1) pede o login
# break-glass (usuario e senha, a senha com read -s) e pede ao hub, na porta 30843, o token de uso unico;
# (2) cria o Secret atlas-edge-enroll pelo stdin do kubectl e reinicia o pod; (3) espera o hub mostrar a fonte
# edge:<maquina>/docker fresca em /api/topology; (4) apaga o Secret, deu certo ou nao.
#
# Credencial e token nunca vao para argv, nunca sao impressos nem logados: a senha fica num arquivo de config
# do curl (-K) 0600 numa pasta 0700 temporaria, o token so passa de arquivo para stdin. A pasta some no fim.
#
# Idempotente: com o edge ja conectado, recusa (saida 3) sem --reenroll. --reenroll pede token novo ao hub
# (re-enroll se o edge ja existe la), apaga o PVC de estado (a credencial antiga) e reinicia o pod; o Argo
# recria o PVC.
set -euo pipefail
umask 077

machine=""; hub="https://10.99.0.1:30843"; ca=""; kubectl_cmd="kubectl"; ns="atlas-edge"; timeout=180; reenroll=0
poll=${ATLAS_ENROLL_POLL:-5}

usage() { sed -n '5,6p' "$0" | sed 's/^# \{0,1\}//' >&2; echo "opcoes: --hub URL (padrao $hub) --namespace NS --timeout SEG --reenroll" >&2; exit 2; }
die() { echo "ERRO: $*" >&2; exit "${2:-1}"; }
while [[ $# -gt 0 ]]; do
  case "$1" in
    --machine) machine=${2:-}; shift 2 ;;
    --hub) hub=${2:-}; shift 2 ;;
    --ca) ca=${2:-}; shift 2 ;;
    --kubectl) kubectl_cmd=${2:-}; shift 2 ;;
    --namespace) ns=${2:-}; shift 2 ;;
    --timeout) timeout=${2:-}; shift 2 ;;
    --reenroll) reenroll=1; shift ;;
    -h|--help) usage ;;
    *) echo "argumento desconhecido: $1" >&2; usage ;;
  esac
done
[[ "$machine" =~ ^[a-z0-9][a-z0-9-]{0,30}$ ]] || die "--machine invalido (vps, host...)" 2
[[ "$hub" =~ ^https://[^/[:space:]]+$ ]] || die "--hub tem de ser https://host:porta, sem caminho" 2
[[ -n "$ca" && -s "$ca" ]] || die "--ca: informe o ca.crt da CA do atlas (o mesmo do overlay)" 2
[[ "$timeout" =~ ^[0-9]+$ ]] || die "--timeout em segundos" 2
for c in curl jq; do command -v "$c" > /dev/null || die "precisa de $c"; done
read -r -a KUBECTL <<< "$kubectl_cmd"
k() { "${KUBECTL[@]}" "$@"; }

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
chmod 700 "$tmp"

# (0) Deployment do Argo e prova de que a SA do edge e so leitura
k -n "$ns" get deployment atlas-edge > /dev/null 2>&1 \
  || die "Deployment $ns/atlas-edge nao existe: espere o Argo sincronizar a app <cluster>-atlas-edge"
sa="system:serviceaccount:$ns:atlas-edge"
for check in "delete pods" "get secrets"; do
  # shellcheck disable=SC2086 # verbo e recurso separados de proposito
  ans=$(k auth can-i $check -A --as="$sa" 2>/dev/null || true)
  [[ "$ans" == "no" ]] || die "a SA atlas-edge deveria responder 'no' para '$check -A' e respondeu '${ans:-erro}': RBAC errado, abortando"
done
echo "ok: Deployment $ns/atlas-edge existe; SA so leitura (delete pods: no, get secrets: no)"

# (1) login break-glass num arquivo 0600, nunca em argv
read -r -p "usuario break-glass do atlas: " bg_user
read -r -s -p "senha break-glass (nao aparece): " bg_pass; echo
[[ -n "$bg_user" && -n "$bg_pass" ]] || die "usuario e senha obrigatorios"
esc() { local s=${1//\\/\\\\}; printf '%s' "${s//\"/\\\"}"; }
cfg="$tmp/curl.cfg"
: > "$cfg"; chmod 600 "$cfg"
printf 'user = "%s:%s"\n' "$(esc "$bg_user")" "$(esc "$bg_pass")" > "$cfg"
unset bg_pass

api() { # api METODO CAMINHO SAIDA [corpo] -> imprime o status HTTP
  local args=(-sS -K "$cfg" --cacert "$ca" --max-time 15 -o "$3" -w '%{http_code}' -X "$1")
  [[ -n "${4:-}" ]] && args+=(-H 'Content-Type: application/json' --data-binary "@$4")
  # sem conexao o curl ja imprime 000 no -w
  curl "${args[@]}" "$hub$2" || true
}
source_id="edge:$machine/docker"
fresh_count() { # quantos nos tem a fonte do edge fresca
  local code; code=$(api GET /api/topology "$tmp/topo.json")
  case "$code" in
    200) jq --arg s "$source_id" '[.nodes[]?.status.sources[]? | select(.source == $s and .fresh == true)] | length' "$tmp/topo.json" ;;
    401) die "hub recusou o login break-glass (401)" ;;
    *) die "GET /api/topology respondeu $code" ;;
  esac
}

already=$(fresh_count)
if (( already > 0 )) && (( ! reenroll )); then
  die "o edge $machine ja esta conectado ($already nos com $source_id fresca); use --reenroll para trocar a credencial" 3
fi

printf '{"machine":"%s"}' "$machine" > "$tmp/body.json"
code=$(api POST /api/edges "$tmp/resp.json" "$tmp/body.json")
if [[ "$code" == 400 ]] && (( reenroll )); then
  code=$(api POST "/api/edges/edge-$machine/re-enroll" "$tmp/resp.json")
fi
case "$code" in
  201) ;;
  400) die "o hub ja tem o edge-$machine ($(jq -r '.error // "sem detalhe"' "$tmp/resp.json" 2>/dev/null)); use --reenroll" ;;
  401) die "hub recusou o login break-glass (401)" ;;
  *) die "o hub respondeu $code ao pedir o token" ;;
esac
jq -e '.token | type == "string" and length > 0' "$tmp/resp.json" > /dev/null || die "resposta do hub sem token"
edge_id=$(jq -r '.edgeId' "$tmp/resp.json")
echo "ok: token de uso unico emitido para $edge_id (vale 1 h; nao e mostrado)"

# (2) Secret pelo stdin (o token sai do arquivo direto para o pipe), depois o pod sobe com ele
{
  printf 'apiVersion: v1\nkind: Secret\nmetadata:\n  name: atlas-edge-enroll\n  namespace: %s\ntype: Opaque\ndata:\n  ATLAS_EDGE_TOKEN: ' "$ns"
  jq -r '.token | @base64' "$tmp/resp.json"
} | k apply -f - > /dev/null || die "kubectl nao criou o Secret atlas-edge-enroll"
rm -f "$tmp/resp.json"
echo "ok: Secret $ns/atlas-edge-enroll criado"

delete_secret() {
  if k -n "$ns" delete secret atlas-edge-enroll --ignore-not-found > /dev/null; then
    echo "ok: Secret $ns/atlas-edge-enroll apagado"
  else
    echo "ATENCAO: nao consegui apagar o Secret; rode: ${KUBECTL[*]} -n $ns delete secret atlas-edge-enroll" >&2
  fi
}

if (( reenroll )); then
  # credencial antiga fora: sem isto o edge carrega o PVC e ignora o token novo
  k -n "$ns" delete pvc atlas-edge-state --wait=false > /dev/null || true
  echo "ok: PVC atlas-edge-state marcado para apagar (o Argo recria)"
fi
k -n "$ns" delete pod -l app=atlas-edge --wait=false > /dev/null || die "nao consegui reiniciar o pod do edge"
echo "ok: pod do edge reiniciado; esperando $source_id fresca no hub (ate ${timeout}s)"

# (3) sinal deterministico: o hub so marca a fonte edge:<m>/docker depois do handshake mTLS e de um snapshot
deadline=$(( SECONDS + timeout )); n=0
while (( SECONDS < deadline )); do
  n=$(fresh_count)
  (( n > 0 )) && break
  sleep "$poll"
done

# (4) o Secret sai em qualquer caso: token de uso unico, ja consumido ou inutil
delete_secret

if (( n == 0 )); then
  echo "FALHOU: $source_id nao ficou fresca em ${timeout}s. Ultimas linhas do edge:" >&2
  k -n "$ns" logs deployment/atlas-edge -c edge --tail=30 >&2 || true
  echo "dicas: 'hub inacessivel' = rede/NetworkPolicy; 'hub recusou' ou 403 = credencial antiga no PVC (--reenroll)" >&2
  exit 4
fi
echo "resumo: $edge_id conectado; $n nos com $source_id fresca; Secret de enrollment apagado"
