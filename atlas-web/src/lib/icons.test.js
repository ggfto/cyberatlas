import { pickIcon, SERVICE_ICONS } from './icons.js'

describe('pickIcon', () => {
  it('casa pelo nome do servico quando bate com um slug vendorizado', () => {
    expect(pickIcon({ name: 'traefik', kind: 'TRAEFIK' })).toEqual({ source: 'service', slug: 'traefik' })
    expect(pickIcon({ name: 'portainer', kind: 'SERVICE' })).toEqual({ source: 'service', slug: 'portainer' })
  })

  it('e insensivel a maiusculas, espacos e caracteres fora do slug', () => {
    expect(pickIcon({ name: 'Home Assistant', kind: 'SERVICE' })).toEqual({ source: 'service', slug: 'home-assistant' })
    expect(pickIcon({ name: 'ARGO-CD', kind: 'ARGO' })).toEqual({ source: 'service', slug: 'argo-cd' })
    expect(pickIcon({ name: 'pi.hole', kind: 'SERVICE' })).toEqual({ source: 'service', slug: 'pi-hole' })
  })

  it('reconhece aliases comuns (homeassistant, pihole, argocd, postgresql)', () => {
    expect(pickIcon({ name: 'homeassistant', kind: 'SERVICE' })).toEqual({ source: 'service', slug: 'home-assistant' })
    expect(pickIcon({ name: 'pihole', kind: 'SERVICE' })).toEqual({ source: 'service', slug: 'pi-hole' })
    expect(pickIcon({ name: 'argocd', kind: 'ARGO' })).toEqual({ source: 'service', slug: 'argo-cd' })
    expect(pickIcon({ name: 'postgresql', kind: 'SERVICE' })).toEqual({ source: 'service', slug: 'postgres' })
  })

  it('cai no nome da imagem (ghcr.io/home-assistant/...) quando o nome do servico nao bate', () => {
    expect(pickIcon({ name: 'ha-prod', image: 'ghcr.io/home-assistant/home-assistant:2024.10', kind: 'CONTAINER' }))
      .toEqual({ source: 'service', slug: 'home-assistant' })
    expect(pickIcon({ name: 'db', image: 'postgres:16', kind: 'CONTAINER' }))
      .toEqual({ source: 'service', slug: 'postgres' })
  })

  it('override vindo do services.yml (atributo icon) vence tudo o resto, mesmo com nome generico', () => {
    expect(pickIcon({ name: 'db', icon: 'redis', kind: 'SERVICE' })).toEqual({ source: 'service', slug: 'redis' })
    // override com slug desconhecido nao inventa: cai no fallback por NodeKind
    const r = pickIcon({ name: 'homeassistant', icon: 'nao-existe', kind: 'SERVICE' })
    expect(r.source).toBe('lucide')
  })

  it('fallback por NodeKind quando nao ha mapeamento', () => {
    expect(pickIcon({ name: 'coisa-nova', kind: 'SERVICE' })).toEqual({ source: 'lucide', name: 'box' })
    expect(pickIcon({ name: 'host-a', kind: 'MACHINE' })).toEqual({ source: 'lucide', name: 'server' })
    expect(pickIcon({ name: 'tun', kind: 'TUNNEL' })).toEqual({ source: 'lucide', name: 'cable' })
    expect(pickIcon({ name: 'ns', kind: 'DNS' })).toEqual({ source: 'lucide', name: 'globe' })
    expect(pickIcon({ name: 'rota', kind: 'ROUTE' })).toEqual({ source: 'lucide', name: 'route' })
    expect(pickIcon({ name: 'authgate', kind: 'AUTHGATE' })).toEqual({ source: 'lucide', name: 'shield-check' })
    expect(pickIcon({ name: 'cluster', kind: 'CLUSTER' })).toEqual({ source: 'lucide', name: 'boxes' })
    expect(pickIcon({ name: 'entrada', kind: 'ENTRY' })).toEqual({ source: 'lucide', name: 'door-open' })
  })

  it('kind desconhecido sem casar vai para o lucide generico (box)', () => {
    expect(pickIcon({ name: 'x', kind: 'NAO_EXISTE' })).toEqual({ source: 'lucide', name: 'box' })
    expect(pickIcon({})).toEqual({ source: 'lucide', name: 'box' })
  })

  it('todo slug declarado em SERVICE_ICONS tem SVG vendorizado disponivel', async () => {
    for (const slug of SERVICE_ICONS) {
      const mod = await import(`../icons/vendor/dashboard-icons/${slug}.svg?url`)
      expect(typeof mod.default).toBe('string')
      expect(mod.default.length).toBeGreaterThan(0)
    }
  })
})
