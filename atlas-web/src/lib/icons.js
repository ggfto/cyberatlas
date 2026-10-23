/**
 * Mapeamento imagem/nome do servico -> icone, puro (sem React, sem Vite). O InfraNode usa isso
 * para escolher entre um SVG vendorizado do dashboard-icons (homarr-labs, Apache-2.0) e um icone
 * do lucide por NodeKind. A CSP img-src 'self' data: continua: tudo e servido same-origin.
 *
 * Precedencia:
 *   1. override `icon:` do services.yml (so se o slug for conhecido);
 *   2. nome do servico normalizado (traefik, homeassistant -> home-assistant, pihole -> pi-hole...);
 *   3. nome da imagem (ghcr.io/home-assistant/home-assistant:... -> home-assistant);
 *   4. fallback por NodeKind no lucide.
 */

/** Icones de servico vendorizados em src/icons/vendor/dashboard-icons. Mantenha so os que usamos. */
export const SERVICE_ICONS = Object.freeze([
  'argo-cd',
  'authentik',
  'cloudflare',
  'dozzle',
  'home-assistant',
  'pi-hole',
  'portainer',
  'postgres',
  'redis',
  'traefik',
])

const SERVICE_SET = new Set(SERVICE_ICONS)

/** Aliases: nome escrito pelo humano -> slug canonico. Resolvido depois da normalizacao do nome. */
const ALIASES = Object.freeze({
  homeassistant: 'home-assistant',
  'home-assistant-core': 'home-assistant',
  pihole: 'pi-hole',
  argocd: 'argo-cd',
  argo: 'argo-cd',
  postgresql: 'postgres',
  psql: 'postgres',
  // nomes como "traefik", "authentik", "portainer", "dozzle", "redis", "cloudflare" ja batem direto
})

/** Fallback por NodeKind: nome de icone do lucide-react (ja importado via @xyflow). */
const LUCIDE_BY_KIND = Object.freeze({
  ENTRY: 'door-open',
  TUNNEL: 'cable',
  DNS: 'globe',
  TRAEFIK: 'router',
  ROUTER: 'router',
  AUTHGATE: 'shield-check',
  ROUTE: 'route',
  SERVICE: 'box',
  WORKLOAD: 'package',
  POD: 'container',
  CONTAINER: 'container',
  MACHINE: 'server',
  CLUSTER: 'boxes',
  ARGO: 'git-branch',
})

const LUCIDE_DEFAULT = 'box'

/** `Home Assistant` -> `home-assistant`; tudo que nao for a-z/0-9 vira hifen, hifens sobrando somem. */
function slugify(input) {
  return String(input ?? '')
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
}

/** Resolve um slug (ja normalizado) para um slug vendorizado, respeitando aliases. */
function resolveSlug(slug) {
  if (!slug) return null
  if (SERVICE_SET.has(slug)) return slug
  const aliased = ALIASES[slug]
  if (aliased && SERVICE_SET.has(aliased)) return aliased
  return null
}

/** `ghcr.io/home-assistant/home-assistant:2024.10` -> `home-assistant`. */
function slugFromImage(image) {
  if (!image) return null
  // tira registry/namespace e tag/digest, pega o ultimo segmento do caminho
  const noTag = String(image).split('@')[0].split(':')[0]
  const parts = noTag.split('/').filter(Boolean)
  if (parts.length === 0) return null
  const last = slugify(parts[parts.length - 1])
  return resolveSlug(last)
}

/**
 * Decide o icone de um no. Entrada: {name, image, kind, icon}. Saida:
 *   {source: 'service', slug}  — SVG vendorizado (dashboard-icons)
 *   {source: 'lucide', name}   — fallback no lucide
 */
export function pickIcon({ name, image, kind, icon } = {}) {
  // 1. override explicito do services.yml: se declarado, ele manda — slug desconhecido NAO cai
  // na deducao por nome/imagem (silenciaria o typo); cai direto no fallback por NodeKind.
  if (icon) {
    const s = resolveSlug(slugify(icon))
    return s ? { source: 'service', slug: s } : { source: 'lucide', name: LUCIDE_BY_KIND[kind] || LUCIDE_DEFAULT }
  }
  // 2. nome do servico
  const byName = resolveSlug(slugify(name))
  if (byName) return { source: 'service', slug: byName }
  // 3. nome da imagem
  const byImage = slugFromImage(image)
  if (byImage) return { source: 'service', slug: byImage }
  // 4. fallback por NodeKind
  return { source: 'lucide', name: LUCIDE_BY_KIND[kind] || LUCIDE_DEFAULT }
}
