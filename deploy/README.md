# deploy/ (F6, preparacao: nada aqui e aplicado sozinho)

| pasta | o que e | quem usa |
|---|---|---|
| `kustomize/base/` | hub: Deployment (nao-root, FS so leitura, sem capabilities, sem token de SA), Service, NetworkPolicy | overlay `k8s/vps/_atlas` no `ggfto/infra`, por commit |
| `compose/edge/` | edge + socket-proxy (allowlist por regex, rede interna sem saida, sem porta publicada) | host e VPS, como stack Docker |
| `pki/gen-pki.sh` | CA do atlas + certificado da 8443, com chaves 600, sem sobrescrever | a pessoa, numa maquina de confianca |

## Ordem de subida (cada passo e da pessoa)
1. Imagens publicadas no GHCR por digest (hoje a CI so builda, sem push).
2. `deploy/pki/gen-pki.sh ./atlas-pki DNS:atlas-hub.atlas.svc IP:10.99.0.1 IP:172.17.0.1`, selar `atlas-pki`, CA no cofre.
3. Selar `atlas-secrets` (ver `k8s/vps/_atlas/secret.example.yaml` no infra) e `atlas-db`.
4. Authentik: blueprint `80-atlas-oidc.yaml` (grupos `atlas-admins`, `atlas-viewers`, provider `atlas`).
5. Renomear `k8s/vps/_atlas` para `k8s/vps/atlas` no infra: so ai o ApplicationSet cria a Application.
6. Rota `atlas.gf2.in` no Traefik da VPS; NodePort da 8443 ouvindo no `wg0` (k3s `node-port-addresses`).
7. Edge: `POST /api/edges` (admin) por maquina, `.env` com o token, `docker compose up -d`; apagar o token do `.env`.

## Sem segredo aqui
Nenhum arquivo deste diretorio tem valor sensivel. Segredos so como SealedSecret no infra, selados pela pessoa.
