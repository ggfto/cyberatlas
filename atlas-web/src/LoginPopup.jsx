import { useEffect } from 'react'

/**
 * Fim do login em popup do /embed (issue #20). O iframe do HA abre /login-popup top-level; o hub faz o code flow
 * normal (fora de moldura o Authentik abre) e volta para ca. Aqui so fecha a janela: quem abriu (o iframe) percebe
 * o popup fechado e recarrega, ja com a sessao. Se o browser nao deixar fechar (aba aberta na mao), fica o aviso.
 */
export default function LoginPopup() {
  useEffect(() => {
    window.close()
  }, [])

  return (
    <div className="flex h-full flex-col items-center justify-center gap-2 p-4 text-center" data-testid="login-popup">
      <p className="text-base">Login feito. Pode fechar esta janela.</p>
      <a className="text-sm underline" href="/">
        Abrir o atlas
      </a>
    </div>
  )
}
