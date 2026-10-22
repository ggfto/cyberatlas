import { render } from '@testing-library/react'
import { ServiceIcon } from './ServiceIcon.jsx'

describe('ServiceIcon', () => {
  // Vite serve o SVG como data:image/svg+xml (quando pequeno) ou /assets/x.svg (quando grande); as
  // duas formas sao same-origin para a CSP img-src 'self' data:. Nunca aparece http(s) externo.
  const isLocal = (src) => /^data:image\/svg\+xml[,;]/.test(src) || /\.svg(\?.*)?$/.test(src)

  it('renderiza <img> same-origin (data: ou /assets/*.svg) para servico vendorizado', () => {
    const { container } = render(<ServiceIcon name="traefik" kind="TRAEFIK" />)
    const img = container.querySelector('img')
    expect(img).not.toBeNull()
    const src = img.getAttribute('src')
    expect(isLocal(src)).toBe(true)
    expect(src).not.toMatch(/^https?:\/\//)
    // identidade do icone: o conteudo (data:) ou o nome do arquivo (/assets) carrega "traefik"
    expect(src).toMatch(/traefik|%23/i)
  })

  it('override do services.yml troca o icone sem olhar o nome do servico', () => {
    const { container: a } = render(<ServiceIcon name="qualquer" icon="redis" kind="SERVICE" />)
    const { container: b } = render(<ServiceIcon name="qualquer" icon="traefik" kind="SERVICE" />)
    const srcA = a.querySelector('img').getAttribute('src')
    const srcB = b.querySelector('img').getAttribute('src')
    expect(isLocal(srcA)).toBe(true)
    expect(isLocal(srcB)).toBe(true)
    expect(srcA).not.toBe(srcB)
  })

  it('fallback por NodeKind renderiza um svg do lucide (nao img externo)', () => {
    const { container } = render(<ServiceIcon name="desconhecido" kind="MACHINE" />)
    expect(container.querySelector('img')).toBeNull()
    expect(container.querySelector('svg')).not.toBeNull()
  })
})
