# atlas

Mapa vivo do homelab: rotas (Cloudflare → Traefik → Authentik → serviço), containers e pods das
máquinas, estado real de cada peça e logs ao vivo, tudo a partir das APIs da própria infra, só leitura.

Um `atlas hub` central junta o que um `atlas edge` por máquina coleta localmente (Docker, Kubernetes,
Traefik) com as fontes globais (Cloudflare, Argo CD, `services.yml`).

Estado: esqueleto em construção. O desenho está em [`docs/design.md`](docs/design.md).

Licença: [MIT](LICENSE).
