import { render, screen } from '@testing-library/react'
import { ReactFlowProvider } from '@xyflow/react'
import { InfraMap, InfraNode } from './InfraMap.jsx'

describe('InfraMap', () => {
  it('mantem os dois botoes de exportacao por padrao na pagina normal', () => {
    render(<InfraMap nodes={[]} edges={[]} />)
    expect(screen.getByRole('button', { name: 'exportar como PNG' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'exportar como SVG' })).toBeInTheDocument()
    expect(screen.getAllByRole('button')).toHaveLength(2)
  })
})

describe('InfraNode', () => {
  it('mostra nome, tipo e o motivo do estado no hover', () => {
    render(
      <ReactFlowProvider>
        <InfraNode data={{ label: 'logs.gf2.in', kind: 'ROUTE', state: 'degraded', reason: 'rota protegida respondeu sem login (200)' }} />
      </ReactFlowProvider>,
    )
    expect(screen.getByText('logs.gf2.in')).toBeInTheDocument()
    expect(screen.getByText('rota')).toBeInTheDocument()
    expect(screen.getByLabelText('degraded')).toHaveClass('bg-amber-500')
    expect(screen.getByTitle('rota protegida respondeu sem login (200)')).toBeInTheDocument()
  })
  it('mostra os selos de drift', () => {
    render(
      <ReactFlowProvider>
        <InfraNode data={{ label: 'argo:vps-dozzle', kind: 'ARGO', state: 'degraded', reason: 'OutOfSync/Healthy', drift: ['outofsync'] }} />
      </ReactFlowProvider>,
    )
    expect(screen.getByText('outofsync')).toBeInTheDocument()
  })
  it('servico que e processo do host diz isso no tipo', () => {
    render(
      <ReactFlowProvider>
        <InfraNode data={{ label: 'clink', kind: 'SERVICE', state: 'online', reason: '', hostProcess: true }} />
      </ReactFlowProvider>,
    )
    expect(screen.getByText('servico · processo no host')).toBeInTheDocument()
  })
})
