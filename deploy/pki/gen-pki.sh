#!/usr/bin/env bash
# Gera a PKI do atlas (design §1.2): a CA propria (assina os certificados dos edges no enrollment) e o
# certificado de servidor da porta 8443 (WS mTLS dos edges + break-glass), assinado por ela.
#
# Uso:  deploy/pki/gen-pki.sh <dir-novo> <SAN>...
#   SAN no formato do openssl: DNS:nome ou IP:endereco, ex.:
#   deploy/pki/gen-pki.sh ./atlas-pki DNS:atlas.atlas.svc IP:10.99.0.1 IP:172.17.0.1
#
# Rode numa maquina de confianca (nao no cluster). O script:
#   - nunca sobrescreve: se <dir> ja existe, para (trocar a CA invalida todos os edges);
#   - grava as chaves com permissao 600 e nunca as imprime;
#   - so imprime o proximo passo (selar com kubeseal); quem sela e guarda a CA no cofre e a pessoa.
set -euo pipefail
umask 077

out=${1:-}
shift || true
[[ -n "$out" ]] || { echo "uso: $0 <dir-novo> DNS:nome|IP:endereco ..." >&2; exit 2; }
(( $# > 0 )) || { echo "informe ao menos um nome do servidor (DNS:... ou IP:...)" >&2; exit 2; }
for san in "$@"; do
  [[ "$san" =~ ^(DNS:[A-Za-z0-9.-]+|IP:[0-9a-fA-F.:]+)$ ]] || { echo "SAN invalido: $san" >&2; exit 2; }
done
[[ ! -e "$out" ]] || { echo "$out ja existe: nao sobrescrevo a PKI (apague a mao se for mesmo trocar a CA)" >&2; exit 1; }
command -v openssl >/dev/null || { echo "precisa de openssl" >&2; exit 1; }

mkdir -p "$out"
tmp=$(mktemp -d "$out/.tmp.XXXXXX")
trap 'rm -rf "$tmp"' EXIT

# CA: EC P-256 (a mesma curva das chaves que o edge gera), 10 anos, so assina certificado.
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$out/ca.key" 2>/dev/null
openssl req -x509 -new -key "$out/ca.key" -sha256 -days 3650 -subj "/CN=atlas-ca" \
  -addext "basicConstraints=critical,CA:TRUE,pathlen:0" \
  -addext "keyUsage=critical,keyCertSign,cRLSign" \
  -addext "subjectKeyIdentifier=hash" \
  -out "$out/ca.crt"

# Servidor da 8443: 825 dias (renovar com a mesma CA nao mexe nos edges).
openssl genpkey -algorithm EC -pkeyopt ec_paramgen_curve:P-256 -out "$out/server.key" 2>/dev/null
openssl req -new -key "$out/server.key" -subj "/CN=atlas-hub" -out "$tmp/server.csr"
san=$(IFS=,; echo "$*")
cat > "$tmp/ext.cnf" <<EXT
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature
extendedKeyUsage=serverAuth
subjectAltName=$san
authorityKeyIdentifier=keyid
EXT
openssl x509 -req -in "$tmp/server.csr" -CA "$out/ca.crt" -CAkey "$out/ca.key" -CAcreateserial -CAserial "$tmp/ca.srl" \
  -days 825 -sha256 -extfile "$tmp/ext.cnf" -out "$out/server.crt" 2>/dev/null

chmod 600 "$out/ca.key" "$out/server.key"
chmod 644 "$out/ca.crt" "$out/server.crt"

cat <<NEXT
PKI gerada em $out (chaves com permissao 600; nada foi impresso nem enviado).
Proximo passo (a pessoa), selar para o namespace atlas do cluster da VPS:
  kubectl create secret generic atlas-pki -n atlas \\
    --from-file=ca.crt=$out/ca.crt --from-file=ca.key=$out/ca.key \\
    --from-file=server.crt=$out/server.crt --from-file=server.key=$out/server.key \\
    --dry-run=client -o yaml \\
  | kubeseal --cert k8s/_certs/sealed-secrets-vps.pem --format yaml > k8s/vps/_atlas/sealedsecret-pki.yaml
Guarde $out/ca.key no cofre e apague a copia local. Os edges recebem so o ca.crt.
NEXT
