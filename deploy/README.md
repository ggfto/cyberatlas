# deploy/ (F6, preparacao: nada aqui e aplicado sozinho)

| pasta | o que e | quem usa |
|---|---|---|
| `kustomize/base/` | hub: Deployment (nao-root, FS so leitura, sem capabilities, sem token de SA), Service, NetworkPolicy | overlay `k8s/vps/_atlas` no `ggfto/infra`, por commit |
| `kustomize/edge/` | edge no k3s da maquina: Deployment (edge + sidecar socket-proxy), SA so leitura, PVC, NetworkPolicy; `enroll.sh` | overlays `k8s/{vps,host}/atlas-edge` no `ggfto/infra`, por commit |
| `compose/edge/` | edge + socket-proxy como stack Docker (SUBSTITUIDO por `kustomize/edge/`: no compose o edge nao alcanca a 6443 do k3s, que o firewall das duas maquinas so abre para pod e WG) | ninguem; fica como referencia |
| `pki/gen-pki.sh` | CA do atlas + certificado da 8443, com chaves 600, sem sobrescrever | a pessoa, numa maquina de confianca |

## Ordem de subida (cada passo e da pessoa)
1. Imagens no GHCR (`ghcr.io/ggfto/cyberatlas-{hub,edge}`, amd64+arm64): a CI publica em push na main (`sha-<curto>`, `main`) e em tag `v*` (semver), nunca `latest`; o digest do manifest list sai no resumo do job `manifest multi-arch`. Fixar por digest.
2. `deploy/pki/gen-pki.sh ./atlas-pki DNS:atlas-hub.atlas.svc IP:10.99.0.1 IP:172.17.0.1`, selar `atlas-pki`, CA no cofre.
3. Selar `atlas-secrets` (ver `k8s/vps/_atlas/secret.example.yaml` no infra) e `atlas-db`.
4. Authentik: blueprint `80-atlas-oidc.yaml` (grupos `atlas-admins`, `atlas-viewers`, provider `atlas`).
5. Renomear `k8s/vps/_atlas` para `k8s/vps/atlas` no infra: so ai o ApplicationSet cria a Application.
6. Rota `atlas.gf2.in` no Traefik da VPS; NodePort da 8443 ouvindo no `wg0` (k3s `node-port-addresses`).
7. Edge: overlay `k8s/<cluster>/atlas-edge` no infra (o Argo cria o Deployment) e `kustomize/edge/enroll.sh` na maquina (secao abaixo).

## services.yml
O hub le o `services.yml` de cada maquina direto do `ggfto/infra` pela API de contents do GitHub
(`atlas.servicesyml.github.*`: repo, ref `stable`, paths por maquina), com um token fine-grained so
`contents:read` daquele repo (`ATLAS_SERVICESYML_GITHUB_TOKEN`, no Secret). ETag/304 a cada coleta; erro (404 de
repo privado, 401, rate limit) aparece em `GET /api/sources` e o resto do mapa segue.

## Sem segredo aqui
Nenhum arquivo deste diretorio tem valor sensivel. Segredos so como SealedSecret no infra, selados pela pessoa.

## Edge no k3s (`kustomize/edge/`)
Um pod por maquina, gerido pelo Argo (`vps-atlas-edge`, `host-atlas-edge`), namespace `atlas-edge`:
- `edge` (UID 1001): le o k3s pela SA `atlas-edge` (in-cluster, token projetado SO neste container; ClusterRole
  `atlas-edge-read`: get/list/watch em pods, pods/log e donos; nada de secrets) e o Docker por `127.0.0.1:2375`;
- `socket-proxy` (wollomatic 1.13.1, UID 65534 + GID do docker da maquina): unico com o `docker.sock` (hostPath,
  so leitura), mesma allowlist so-GET do compose (`containers/json`, `events`, `containers/<id>/logs`; sem inspect);
- PVC `atlas-edge-state` (local-path, 16Mi) com a credencial; Recreate, 1 replica;
- NetworkPolicy: nenhuma entrada; saida so DNS, apiserver (10.43.0.1:443 e `<ip-do-no>:6443`) e o hub.

Perdido em relacao ao compose (escolha registrada): o proxy divide a rede do pod com o edge (escuta so no
loopback do pod, mas a saida dele e a da NetworkPolicy, nao "nenhuma"); `supplementalGroups` vale para o pod
inteiro; hostPath pede namespace sem Pod Security baseline/restricted; k3s fora = edge fora (o mapa mostra
"edge sem conexao").

O token de enrollment NUNCA vai para o git: e de uso unico e vence em 1 h, nao sobrevive a selar-PR-merge-promote.
O Deployment le o Secret OPCIONAL `atlas-edge-enroll` (chave `ATLAS_EDGE_TOKEN`), que o `enroll.sh` cria pelo stdin
do kubectl e apaga no fim. Ele nao tem a anotacao de rastreio do Argo, entao a app nao fica OutOfSync nem o apaga.

### Passos da pessoa (VPS primeiro, depois host)
Pre-requisito: o PR do infra com `k8s/<cluster>/atlas-edge` mergeado e promovido para `stable`, e a app
`<cluster>-atlas-edge` Synced (o pod fica no ar sem credencial ate o passo abaixo; log: "edge nao iniciou: sem
credencial"). O `ca.crt` e o mesmo do overlay (`k8s/<cluster>/atlas-edge/ca.crt` no infra).

VPS (`ssh ubuntu@10.99.0.1`, num clone deste repo):
```bash
deploy/kustomize/edge/enroll.sh --machine vps --ca ~/infra/k8s/vps/atlas-edge/ca.crt --kubectl "sudo k3s kubectl"
```
Host (num clone deste repo):
```bash
deploy/kustomize/edge/enroll.sh --machine host --ca ~/infra/k8s/host/atlas-edge/ca.crt --kubectl kubectl
```
O script: (0) confere que o Deployment existe e que a SA responde "no" a `delete pods` e `get secrets`;
(1) pede usuario e senha break-glass (`read -s`; ficam num config 0600 do curl, nunca em argv) e faz
`POST /api/edges` em `https://10.99.0.1:30843`; (2) cria o Secret pelo stdin e reinicia o pod; (3) espera ate
180 s por `GET /api/topology` com a fonte `edge:<maquina>/docker` `fresh: true`; (4) apaga o Secret (sempre).
Saidas: 0 ok; 3 ja conectado (use `--reenroll`); 4 nao conectou (mostra o log do edge); 1/2 outros erros.
`--reenroll` (maquina trocada, chave perdida): token novo pelo re-enroll do hub, apaga o PVC (o Argo recria) e
reinicia o pod.

Conferir a mao, se quiser: `kubectl -n atlas-edge get secret atlas-edge-enroll` tem de dar NotFound.

Teste offline (curl e kubectl falsos): `deploy/kustomize/edge/test-enroll.sh <scratch-novo>`.
