/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// A CSP (com frame-ancestors 'none') e os demais cabecalhos de seguranca saem do hub como header HTTP
// (SecurityHeaders.java, issue #18): em <meta>, frame-ancestors e ignorado pelo browser. Tudo same-origin: o hub
// serve o front (Quinoa) e a API; nada de CDN.
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.js'],
    css: false,
  },
})
