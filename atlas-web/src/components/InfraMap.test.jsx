import { render, screen } from '@testing-library/react'
import { ReactFlowProvider } from '@xyflow/react'
import { InfraNode } from './InfraMap.jsx'

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
})
