# Cyber Atlas

Mapa vivo do homelab: rotas (Cloudflare → Traefik → Authentik → serviço), containers e pods das
máquinas, estado real de cada peça e logs ao vivo, tudo a partir das APIs da própria infra, só leitura.

Um `atlas hub` central junta o que um `atlas edge` por máquina coleta localmente (Docker, Kubernetes,
Traefik) com as fontes globais (Cloudflare, Argo CD, `services.yml`).

Estado: esqueleto em construção. O desenho está em [`docs/design.md`](docs/design.md).

Licença: [MIT](LICENSE).

## Estrutura

| módulo | o quê |
|---|---|
| `atlas-core` | modelo, SPI `TopologySource`/`LogSource`, merge puro (só JDK) |
| `atlas-protocol` | frames edge ⇄ hub |
| `atlas-adapters-edge` | Docker, Kubernetes, Traefik, checks locais, WireGuard |
| `atlas-adapters-hub` | `services.yml`, Cloudflare, Argo CD, checks públicos |
| `atlas-edge` | app Quarkus do edge |
| `atlas-hub` | app Quarkus do hub (API, SSE, WebSocket dos edges); serve o `atlas-web` via Quinoa |
| `atlas-web` | Vite + React + `@xyflow/react` + `@mriqbox/ui-kit` |

## Build

Requisitos: JDK 21 e Docker (para o nativo). Node e npm são baixados pelo próprio build (Quinoa).

```sh
mvn verify -DskipITs=false   # unitários, Vitest do front e testes de integração nos jars
mvn verify -Pnative          # binários nativos (native-image em container) + testes de integração neles
```

Créditos de ideias de outros projetos em [`NOTICE`](NOTICE).
