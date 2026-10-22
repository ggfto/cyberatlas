# dashboard-icons (vendorizado)

Icones SVG do [homarr-labs/dashboard-icons](https://github.com/homarr-labs/dashboard-icons),
congelados no commit `320c819da84048d5a3066e2578bfa83be9019fdf`.

Copyright (c) 2024 Bjorn Lammers, Meier Lukas, Thomas Camlong and Homarr Labs.
Licenciado sob a Apache License 2.0 — ver `LICENSE` nesta pasta e a atribuicao em `NOTICE` na raiz.

Vendorizamos (ao inves de baixar via CDN) para honrar `img-src 'self' data:` na CSP do atlas-web.

## Como adicionar/atualizar icone

1. baixe o SVG do mesmo commit (ou atualize o commit em README+NOTICE+comentario do icons.js):
   ```
   curl -sS -o src/icons/vendor/dashboard-icons/<slug>.svg \
     https://raw.githubusercontent.com/homarr-labs/dashboard-icons/<sha>/svg/<slug>.svg
   ```
2. inclua o slug em `SERVICE_ICONS` dentro de `src/lib/icons.js` (ordem alfabetica);
3. rode `npm test -- src/lib/icons.test.js` — o teste "todo slug ... tem SVG vendorizado" cobre.
