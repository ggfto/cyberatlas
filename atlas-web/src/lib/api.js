// Cliente das escritas do hub. Toda escrita autenticada pelo cookie de sessao precisa do header anti-CSRF (o hub
// recusa com 403 sem ele; issue #18): formulario de outro *.gf2.in nao consegue manda-lo, e fetch cross-origin com
// ele exige preflight CORS, que o hub nao responde. O Origin o proprio browser poe.
export const CSRF_HEADER = 'X-Atlas-Csrf'

/** fetch de escrita (POST/PUT/PATCH/DELETE) com o header anti-CSRF somado aos do chamador. */
export function apiWrite(url, init = {}, fetchImpl = fetch) {
  return fetchImpl(url, { ...init, headers: { ...(init.headers || {}), [CSRF_HEADER]: '1' } })
}
