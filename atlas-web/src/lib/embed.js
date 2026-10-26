// Visao /embed (issue #19): o mapa so leitura que o Home Assistant enquadra. O hub so libera o frame-ancestors do HA
// para estes caminhos exatos (SecurityHeaders.isEmbed); a regra daqui e a mesma.
export function isEmbedPath(pathname) {
  return pathname === '/embed' || pathname === '/embed/'
}

/** window.top !== window.self, sem estourar quando o top e de outra origem. */
export function isFramed(win) {
  try {
    return win.self !== win.top
  } catch {
    return true // top de outra origem que nem deixa comparar: esta enquadrado
  }
}

/**
 * Monta o Embed no /embed e tambem em QUALQUER pagina enquadrada. O caminho que o hub ve pode nao ser o do
 * `location.pathname` (o Traefik normaliza o path: `//embed` chega ao hub como `/embed` e ganha o frame-ancestors
 * do HA); sem esta regra, o HA enquadraria o App inteiro, com as acoes de admin, por um desses caminhos.
 */
export function shouldRenderEmbed(win) {
  return isEmbedPath(win.location.pathname) || isFramed(win)
}

const THEMES = new Set(['dark', 'light'])

/** ?focus=<node_key>&theme=dark|light; tema fora da lista vira o padrao (dark). */
export function parseEmbedParams(search) {
  const q = new URLSearchParams(search)
  const theme = q.get('theme')
  return { focus: q.get('focus') || null, theme: THEMES.has(theme) ? theme : 'dark' }
}

/**
 * Login do /embed (issue #20): pagina aberta top-level num popup. O hub manda para o Authentik como qualquer pagina
 * sem sessao e volta para ca (restore-path-after-redirect); o front so fecha a janela (LoginPopup).
 */
export const LOGIN_POPUP_PATH = '/login-popup'

export function isLoginPopupPath(pathname) {
  return pathname === LOGIN_POPUP_PATH
}

export const POPUP_POLL_MS = 500

/**
 * Abre o login num popup top-level (o Authentik nao abre em moldura, e nao deve) e chama `onClosed` uma vez quando
 * ele fecha. Sem `noopener`: e a referencia ao popup que deixa ver o `closed`. Devolve o cancelamento, ou null se
 * o browser bloqueou o popup.
 */
export function openLoginPopup(win, onClosed) {
  const popup = win.open(LOGIN_POPUP_PATH, 'atlas-login', 'popup,width=520,height=720')
  if (!popup) return null
  const timer = win.setInterval(() => {
    if (popup.closed) {
      win.clearInterval(timer)
      onClosed()
    }
  }, POPUP_POLL_MS)
  return () => win.clearInterval(timer)
}
