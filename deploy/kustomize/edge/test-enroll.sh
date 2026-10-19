#!/usr/bin/env bash
# Teste OFFLINE do enroll.sh com curl e kubectl FALSOS (nada sai da maquina, nenhum cluster e tocado).
# Uso: test-enroll.sh <scratch-novo>
# Prova: senha e token nunca no stdout/stderr nem no argv de processo nenhum; config do curl 0600 e umask 077;
# Secret criado pelo stdin e APAGADO no fim (sucesso e timeout); can-i "yes" aborta antes de pedir token;
# edge ja conectado recusa sem --reenroll; --reenroll usa o re-enroll do hub e apaga o PVC; 401 aborta.
set -euo pipefail
umask 077
root=${1:?scratch}
here=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
script="$here/enroll.sh"
[[ ! -e "$root" ]] || { echo "$root ja existe" >&2; exit 2; }
mkdir -p "$root/bin" "$root/tmp"
fail() { echo "FALHOU: $*" >&2; exit 1; }
ok() { echo "ok: $*"; }

# shellcheck disable=SC2089,SC2090 # aspas e barra LITERAIS de proposito: provam o escape do -K
PASS="s3nha\"com'aspas\\e-barra-$(openssl rand -hex 6)"
TOKEN="tok-FALSO-$(openssl rand -hex 16)"
TOKEN_B64=$(printf '%s' "$TOKEN" | base64 -w0)
# shellcheck disable=SC2090
export PASS TOKEN

# curl falso: confere o -K, responde pelo cenario e pelo estado em $ST
cat > "$root/bin/curl" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$ST/argv.log"
cfg=""; out=""; method=GET; url=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    -K) cfg=$2; shift 2 ;; -o) out=$2; shift 2 ;; -X) method=$2; shift 2 ;;
    -w|--cacert|--max-time|-H|--data-binary) shift 2 ;;
    -*) shift ;; *) url=$1; shift ;;
  esac
done
echo "$(stat -c %a "$cfg") $(stat -c %a "$(dirname "$cfg")") $(umask)" >> "$ST/modes.log"
want=$(python3 -c 'import os;p=os.environ["PASS"].replace("\\","\\\\").replace("\"","\\\"");print("user = \"admin:%s\"" % p)')
if [[ "$(cat "$cfg")" != "$want" || -n "${FAKE_401:-}" ]]; then echo '{}' > "$out"; printf 401; exit 0; fi
path=${url#https://*/}
case "$method /$path" in
  "GET /api/topology")
    if [[ -e "$ST/connected" ]]; then
      echo '{"nodes":[{"node":{"key":"docker:vps/x"},"status":{"sources":[{"source":"edge:vps/docker","fresh":true}]}}]}' > "$out"
    else echo '{"nodes":[{"status":{"sources":[{"source":"edge:vps/docker","fresh":false}]}}]}' > "$out"; fi
    printf 200 ;;
  "POST /api/edges")
    echo create >> "$ST/hub.log"
    if [[ -n "${FAKE_EXISTS:-}" ]]; then echo '{"error":"ja existe edge para a maquina vps (use re-enroll)"}' > "$out"; printf 400
    else printf '{"edgeId":"edge-vps","token":"%s","expiresAt":"x"}' "$TOKEN" > "$out"; printf 201; fi ;;
  "POST /api/edges/edge-vps/re-enroll")
    echo re-enroll >> "$ST/hub.log"
    printf '{"edgeId":"edge-vps","token":"%s","expiresAt":"x"}' "$TOKEN" > "$out"; printf 201 ;;
  *) echo '{}' > "$out"; printf 404 ;;
esac
FAKE

# kubectl falso: guarda argv e o stdin do apply; "delete pod" com o Secret presente = edge conecta
cat > "$root/bin/kubectl" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$ST/argv.log"
a=" $* "
case "$a" in
  *" get deployment atlas-edge "*) [[ -z "${FAKE_NO_DEPLOY:-}" ]] ;;
  *" auth can-i "*) if [[ -n "${FAKE_CANI_YES:-}" && "$a" == *" get secrets "* ]]; then echo yes; else echo no; fi ;;
  *" apply -f - "*) cat > "$ST/applied.yaml"; touch "$ST/secret" ;;
  *" delete pod "*) if [[ -e "$ST/secret" && -z "${FAKE_NEVER:-}" ]]; then touch "$ST/connected"; fi ;;
  *" delete secret atlas-edge-enroll "*) rm -f "$ST/secret"; touch "$ST/secret-deleted" ;;
  *" delete pvc atlas-edge-state "*) touch "$ST/pvc-deleted" ;;
  *" logs "*) echo "edge nao iniciou: linha falsa de log" ;;
  *) echo "kubectl falso: inesperado: $*" >&2; exit 9 ;;
esac
FAKE
chmod +x "$root/bin/curl" "$root/bin/kubectl"
printf -- '-----BEGIN CERTIFICATE-----\nFALSO\n-----END CERTIFICATE-----\n' > "$root/ca.crt"

n=0
run() { # run <nome> <saida-esperada> [VAR=valor...] [-- args extras do enroll.sh]
  local name=$1 want=$2; shift 2
  local envs=(); while [[ $# -gt 0 && $1 != -- ]]; do envs+=("$1"); shift; done; [[ ${1:-} == -- ]] && shift
  n=$((n + 1)); ST="$root/st-$name"; mkdir -p "$ST"; export ST
  [[ " ${envs[*]} " != *" PRE_CONNECTED=1 "* ]] || touch "$ST/connected"
  local rc=0
  printf 'admin\n%s\n' "$PASS" | env PATH="$root/bin:$PATH" TMPDIR="$root/tmp" ATLAS_ENROLL_POLL=0.05 "${envs[@]}" \
    bash "$script" --machine vps --ca "$root/ca.crt" --kubectl kubectl --timeout 1 "$@" \
    > "$ST/out.txt" 2> "$ST/err.txt" || rc=$?
  [[ $rc == "$want" ]] || fail "$name: saiu $rc, esperado $want: $(cat "$ST/err.txt")"
  # nenhum segredo em stdout, stderr nem no argv de qualquer processo falso
  for f in out.txt err.txt argv.log; do
    [[ -e "$ST/$f" ]] || continue
    if grep -qF -e "$PASS" -e "$TOKEN" -e "$TOKEN_B64" "$ST/$f"; then fail "$name: segredo em $f"; fi
  done
  if [[ -e "$ST/modes.log" ]] && grep -qv '^600 700 0077$' "$ST/modes.log"; then fail "$name: modos errados: $(sort -u "$ST/modes.log")"; fi
  [[ -z "$(ls -A "$root/tmp")" ]] || fail "$name: pasta temporaria (com a senha) ficou para tras"
}

run sucesso 0
S="$root/st-sucesso"
[[ -e "$S/secret-deleted" && ! -e "$S/secret" ]] || fail "sucesso: Secret de enrollment nao foi apagado"; ok "Secret apagado depois do sucesso"
{ grep -q "ATLAS_EDGE_TOKEN: $TOKEN_B64" "$S/applied.yaml" && grep -q 'namespace: atlas-edge' "$S/applied.yaml"; } \
  || fail "sucesso: Secret aplicado sem o token ou fora do namespace"; ok "Secret chegou ao kubectl so pelo stdin, com o token"
grep -q '^600 700 0077$' "$S/modes.log" || fail "sucesso: modos nao conferidos"; ok "config do curl 0600, pasta 0700, umask 077, pasta apagada no fim"
grep -q 'conectado' "$S/out.txt" || fail "sucesso: sem resumo"; ok "resumo impresso"
ok "senha e token fora de stdout/stderr/argv (conferido em todas as rodadas)"

run cani 1 FAKE_CANI_YES=1
[[ ! -e "$root/st-cani/hub.log" && ! -e "$root/st-cani/applied.yaml" ]] || fail "cani: pediu token mesmo com can-i yes"
grep -q "get secrets" "$root/st-cani/err.txt" || fail "cani: erro sem motivo"; ok "can-i 'yes' aborta antes do hub"

run sem-deploy 1 FAKE_NO_DEPLOY=1
grep -q 'espere o Argo' "$root/st-sem-deploy/err.txt" || fail "sem-deploy: mensagem"; ok "sem Deployment: aborta e manda esperar o Argo"

run ja-conectado 3 PRE_CONNECTED=1
[[ ! -e "$root/st-ja-conectado/hub.log" ]] || fail "ja-conectado: pediu token"; ok "edge ja conectado: recusa sem --reenroll"

run existe 1 FAKE_EXISTS=1
[[ ! -e "$root/st-existe/applied.yaml" ]] || fail "existe: criou Secret"; grep -q -- '--reenroll' "$root/st-existe/err.txt" || fail "existe: sem dica"
ok "edge ja existe no hub: aborta e sugere --reenroll"

run reenroll 0 PRE_CONNECTED=1 FAKE_EXISTS=1 -- --reenroll
R="$root/st-reenroll"
[[ "$(tr '\n' ' ' < "$R/hub.log")" == "create re-enroll " ]] || fail "reenroll: chamadas ao hub $(cat "$R/hub.log")"
[[ -e "$R/pvc-deleted" && -e "$R/secret-deleted" ]] || fail "reenroll: PVC ou Secret nao apagados"; ok "--reenroll: re-enroll no hub, PVC e Secret apagados"

run timeout 4 FAKE_NEVER=1
T="$root/st-timeout"
[[ -e "$T/secret-deleted" ]] || fail "timeout: Secret ficou"; grep -q 'linha falsa de log' "$T/err.txt" || fail "timeout: sem log do edge"
ok "timeout: falha visivel (saida 4, log do edge) e Secret apagado mesmo assim"

run login-errado 1 FAKE_401=1
[[ ! -e "$root/st-login-errado/applied.yaml" ]] || fail "401: criou Secret"; ok "401 do hub aborta"

echo "TODOS OS $n CENARIOS PASSARAM"
