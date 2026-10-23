// Visao /embed (issue #19): o mapa so leitura que o Home Assistant enquadra. O hub so libera o frame-ancestors do HA
// para estes caminhos exatos (SecurityHeaders.isEmbed); a regra daqui e a mesma.
export function isEmbedPath(pathname) {
  return pathname === '/embed' || pathname === '/embed/'
}

function framed(win) {
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
  return isEmbedPath(win.location.pathname) || framed(win)
}

const THEMES = new Set(['dark', 'light'])

/** ?focus=<node_key>&theme=dark|light; tema fora da lista vira o padrao (dark). */
export function parseEmbedParams(search) {
  const q = new URLSearchParams(search)
  const theme = q.get('theme')
  return { focus: q.get('focus') || null, theme: THEMES.has(theme) ? theme : 'dark' }
}
