import { render, screen } from '@testing-library/react'
import App from './App.jsx'

describe('App (F0)', () => {
  it('mostra o cabecalho do ui-kit e o canvas do mapa', () => {
    render(<App />)
    expect(screen.getByText('Cyber Atlas')).toBeInTheDocument()
    expect(screen.getByTestId('infra-map')).toBeInTheDocument()
  })
})
