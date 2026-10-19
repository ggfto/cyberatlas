import { render, screen, waitFor } from '@testing-library/react'
import App from './App.jsx'

afterEach(() => {
  vi.restoreAllMocks()
})

describe('App', () => {
  it('busca /api/topology e mostra o cabecalho do ui-kit e o canvas', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue({ ok: true, json: async () => ({ nodes: [], edges: [] }) })
    render(<App />)
    expect(screen.getByText('Cyber Atlas')).toBeInTheDocument()
    expect(screen.getByTestId('infra-map')).toBeInTheDocument()
    await waitFor(() => expect(fetchMock).toHaveBeenCalledWith('/api/topology', expect.anything()))
  })

  it('erro da API aparece em vez de um mapa vazio silencioso', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue({ ok: false, status: 503, json: async () => ({}) })
    render(<App />)
    expect(await screen.findByText('Nao foi possivel carregar o mapa (HTTP 503).')).toBeInTheDocument()
  })
})
