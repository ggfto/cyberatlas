/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// CSP estrita so no bundle de producao (o HMR do dev precisa de inline/eval). Tudo same-origin: o hub serve
// o front (Quinoa) e a API; nada de CDN.
function securityHeaders() {
  const csp = [
    "default-src 'self'",
    "script-src 'self'",
    "style-src 'self' 'unsafe-inline'",
    "img-src 'self' data:",
    "font-src 'self' data:",
    "connect-src 'self'",
    "frame-ancestors 'none'",
    "base-uri 'self'",
    "object-src 'none'",
    "form-action 'self'",
  ].join('; ')
  return {
    name: 'atlas-security-headers',
    apply: 'build',
    transformIndexHtml(html) {
      return html.replace('</head>', `  <meta http-equiv="Content-Security-Policy" content="${csp}" />\n  </head>`)
    },
  }
}

export default defineConfig({
  plugins: [react(), securityHeaders()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.js'],
    css: false,
  },
})
