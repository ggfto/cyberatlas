import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { ReactFlow, ReactFlowProvider, Background } from '@xyflow/react'
import { InfraNode } from './InfraMap.jsx'
import { ExportMapButton, buildExportOptions, VIEWPORT_SELECTOR } from './ExportMapButton.jsx'

// Mapa minimo com dois nos de estados diferentes (online e offline): o teste checa que o DOM capturado
// mantem as cores de status (criterio da #25) e que o download sai como data URL (so coisa que a CSP img-src
// 'self' data: aceita; sem blob:/sem fetch externo).
function MapHarness({ exporters, download }) {
  const nodes = [
    { id: 'a', type: 'infra', position: { x: 0, y: 0 }, data: { label: 'edge', kind: 'SERVICE', state: 'online', reason: 'ok' } },
    { id: 'b', type: 'infra', position: { x: 200, y: 0 }, data: { label: 'traefik', kind: 'TRAEFIK', state: 'offline', reason: 'down' } },
  ]
  return (
    <ReactFlowProvider>
      <div style={{ width: 800, height: 600 }}>
        <ReactFlow nodes={nodes} edges={[]} nodeTypes={{ infra: InfraNode }} fitView>
          <Background />
          <ExportMapButton exporters={exporters} download={download} />
        </ReactFlow>
      </div>
    </ReactFlowProvider>
  )
}

describe('ExportMapButton', () => {
  it('exporta PNG com data URL e aciona download', async () => {
    const png = 'data:image/png;base64,iVBOR'
    const toPng = vi.fn(async () => png)
    const download = vi.fn()
    render(<MapHarness exporters={{ png: toPng, svg: vi.fn() }} download={download} />)

    fireEvent.click(screen.getByRole('button', { name: 'exportar como PNG' }))

    await waitFor(() => expect(toPng).toHaveBeenCalled())
    const [el] = toPng.mock.calls[0]
    expect(el.matches(VIEWPORT_SELECTOR)).toBe(true)
    expect(download).toHaveBeenCalledTimes(1)
    const [url, name] = download.mock.calls[0]
    expect(url.startsWith('data:')).toBe(true)
    expect(name).toMatch(/cyberatlas-.*\.png$/)
  })

  it('exporta SVG com data URL e aciona download', async () => {
    const svg = 'data:image/svg+xml;utf8,<svg/>'
    const toSvg = vi.fn(async () => svg)
    const download = vi.fn()
    render(<MapHarness exporters={{ png: vi.fn(), svg: toSvg }} download={download} />)

    fireEvent.click(screen.getByRole('button', { name: 'exportar como SVG' }))

    await waitFor(() => expect(toSvg).toHaveBeenCalled())
    expect(download).toHaveBeenCalledWith(expect.stringMatching(/^data:/), expect.stringMatching(/\.svg$/))
  })

  it('o DOM capturado mantem as cores de status dos nos', async () => {
    const toPng = vi.fn(async (el) => {
      // guarda o html exato que seria renderizado para a imagem; o html-to-image olha o mesmo DOM.
      toPng.capturedHtml = el.outerHTML
      return 'data:image/png;base64,ok'
    })
    render(<MapHarness exporters={{ png: toPng, svg: vi.fn() }} download={vi.fn()} />)

    fireEvent.click(screen.getByRole('button', { name: 'exportar como PNG' }))

    await waitFor(() => expect(toPng).toHaveBeenCalled())
    expect(toPng.capturedHtml).toContain('bg-green-500') // no online
    expect(toPng.capturedHtml).toContain('bg-red-500') // no offline
  })

  it('buildExportOptions nao faz fetch externo (skipFonts) e pinta fundo', () => {
    const opts = buildExportOptions([{ id: 'a', position: { x: 0, y: 0 }, width: 100, height: 50 }])
    expect(opts.skipFonts).toBe(true)
    expect(opts.backgroundColor).toBe('#ffffff')
    expect(opts.style.transform).toMatch(/scale\(/)
  })
})
