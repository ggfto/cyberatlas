import { render, screen } from '@testing-library/react'
import LoginPopup from './LoginPopup.jsx'

afterEach(() => vi.restoreAllMocks())

describe('LoginPopup (/login-popup, fim do login do /embed)', () => {
  it('fecha a janela e, se o browser nao deixar, avisa que pode fechar', () => {
    const close = vi.spyOn(window, 'close').mockImplementation(() => {})
    render(<LoginPopup />)
    expect(close).toHaveBeenCalled()
    expect(screen.getByText('Login feito. Pode fechar esta janela.')).toBeInTheDocument()
  })
})
