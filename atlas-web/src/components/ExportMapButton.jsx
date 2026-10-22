import { useCallback, useState } from 'react'
import { Panel, useReactFlow, getNodesBounds, getViewportForBounds } from '@xyflow/react'
import { toPng, toSvg } from 'html-to-image'
import { Download } from 'lucide-react'

// A exportacao gera um data URL (PNG/SVG) e aciona o download por <a href="data:...">: data: ja esta liberado em
// img-src pela CSP do hub (SecurityHeaders.java), e nada e requisitado via rede. skipFonts evita fetch de @font-face
// externo (nao existe no atlas, mas blinda contra uma fonte do bundle apontar para CDN).
const EXPORT_PADDING = 0.1
const EXPORT_SIZE = { width: 1920, height: 1080 }
const EXPORT_BG = '#ffffff'

/** O elemento que o React Flow desenha com as transformacoes atuais (zoom/pan). Fora dele vem o chrome do canvas. */
export const VIEWPORT_SELECTOR = '.react-flow__viewport'

/** Calcula o viewport necessario para enquadrar todos os nos na imagem final (molde do exemplo oficial do RF). */
export function computeExportViewport(nodes, size = EXPORT_SIZE, padding = EXPORT_PADDING) {
  const bounds = getNodesBounds(nodes)
  return getViewportForBounds(bounds, size.width, size.height, 0.5, 2, padding)
}

/** Dispara o download no browser: usa data URL para nao violar connect-src e nao criar blob: na CSP. */
export function triggerDownload(dataUrl, filename) {
  const a = document.createElement('a')
  a.href = dataUrl
  a.download = filename
  a.rel = 'noopener'
  document.body.appendChild(a)
  a.click()
  a.remove()
}

/** Opcoes passadas ao html-to-image. skipFonts: sem fetch externo. backgroundColor: mapa fica legivel fora do app. */
export function buildExportOptions(nodes) {
  const viewport = computeExportViewport(nodes)
  return {
    backgroundColor: EXPORT_BG,
    width: EXPORT_SIZE.width,
    height: EXPORT_SIZE.height,
    skipFonts: true,
    cacheBust: true,
    style: {
      width: `${EXPORT_SIZE.width}px`,
      height: `${EXPORT_SIZE.height}px`,
      transform: `translate(${viewport.x}px, ${viewport.y}px) scale(${viewport.zoom})`,
    },
  }
}

const EXPORTERS = { png: toPng, svg: toSvg }
const EXT = { png: 'png', svg: 'svg' }

/**
 * Botao isolado para nao poluir o InfraMap: fica numa <Panel> dentro do <ReactFlow>. Exporta a visao atual
 * do mapa (nos com as cores de status preservadas, porque o DOM e capturado como esta) em PNG ou SVG.
 * `exporters` e injetado nos testes para evitar canvas no jsdom.
 */
export function ExportMapButton({ exporters = EXPORTERS, download = triggerDownload, filename = 'cyberatlas' }) {
  const { getNodes } = useReactFlow()
  const [busy, setBusy] = useState(null)
  const onExport = useCallback(
    async (format) => {
      const toImage = exporters[format]
      if (!toImage) return
      const viewportEl = document.querySelector(VIEWPORT_SELECTOR)
      if (!viewportEl) return
      setBusy(format)
      try {
        const dataUrl = await toImage(viewportEl, buildExportOptions(getNodes()))
        const stamp = new Date().toISOString().replace(/[:.]/g, '-')
        download(dataUrl, `${filename}-${stamp}.${EXT[format]}`)
      } finally {
        setBusy(null)
      }
    },
    [exporters, download, filename, getNodes],
  )
  return (
    <Panel position="top-right">
      <div className="flex gap-1 rounded-lg border border-border bg-card p-1 shadow-sm" data-testid="export-map">
        <button
          type="button"
          className="flex items-center gap-1 rounded px-2 py-1 text-xs hover:bg-muted disabled:opacity-50"
          onClick={() => onExport('png')}
          disabled={busy !== null}
          aria-label="exportar como PNG"
        >
          <Download className="h-3 w-3" aria-hidden="true" />
          PNG
        </button>
        <button
          type="button"
          className="flex items-center gap-1 rounded px-2 py-1 text-xs hover:bg-muted disabled:opacity-50"
          onClick={() => onExport('svg')}
          disabled={busy !== null}
          aria-label="exportar como SVG"
        >
          <Download className="h-3 w-3" aria-hidden="true" />
          SVG
        </button>
      </div>
    </Panel>
  )
}
