import { pickIcon } from '../lib/icons.js'
import * as LucideIcons from 'lucide-react'

// Vite inlina cada SVG como URL no bundle (data: ou /assets same-origin); nada e buscado em runtime.
// A CSP img-src 'self' data: continua. Os arquivos vivem em src/icons/vendor/dashboard-icons
// (dashboard-icons do homarr-labs, Apache-2.0 — veja NOTICE).
const SERVICE_SVGS = import.meta.glob('../icons/vendor/dashboard-icons/*.svg', {
  eager: true,
  query: '?url',
  import: 'default',
})

const URL_BY_SLUG = Object.fromEntries(
  Object.entries(SERVICE_SVGS).map(([path, url]) => {
    const slug = path.split('/').pop().replace(/\.svg$/, '')
    return [slug, url]
  }),
)

/** `shield-check` -> `ShieldCheck` para encontrar o export do lucide-react. */
function toPascal(name) {
  return String(name)
    .split('-')
    .filter(Boolean)
    .map((p) => p.charAt(0).toUpperCase() + p.slice(1))
    .join('')
}

/**
 * Icone do no do mapa. Resolve por {name, image, kind, icon} e renderiza em 16px:
 *   - fonte "service": <img src=".../slug.svg"> (vendorizado, sem CDN)
 *   - fonte "lucide":  componente do lucide-react em currentColor
 * Fallback do fallback: se o nome lucide nao existir no pacote, nao renderiza nada.
 */
export function ServiceIcon({ name, image, kind, icon, className = 'h-4 w-4 shrink-0', ariaLabel }) {
  const pick = pickIcon({ name, image, kind, icon })
  if (pick.source === 'service') {
    const url = URL_BY_SLUG[pick.slug]
    if (url) {
      return <img src={url} alt={ariaLabel || pick.slug} className={className} draggable={false} />
    }
  }
  const Lucide = LucideIcons[toPascal(pick.source === 'lucide' ? pick.name : 'box')]
  return Lucide ? <Lucide className={`${className} text-muted-foreground`} aria-label={ariaLabel || 'icone'} /> : null
}
