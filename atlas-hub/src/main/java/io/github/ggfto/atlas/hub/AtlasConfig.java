package io.github.ggfto.atlas.hub;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

@ConfigMapping(prefix = "atlas")
public interface AtlasConfig {

    ServicesYml servicesyml();

    Checks checks();

    Collect collect();

    Edges edges();

    Cloudflare cloudflare();

    Argocd argocd();

    Stream stream();

    interface Cloudflare {
        @WithDefault("https://api.cloudflare.com")
        String api();

        /** Token so leitura (Zone:Read, DNS:Read, Cloudflare Tunnel:Read), de Secret. Sem ele, sem Cloudflare. */
        Optional<String> token();

        Optional<String> accountId();

        /** Zona; padrao = a do primeiro services.yml. */
        Optional<String> zone();
    }

    interface Argocd {
        /** URL da API do Argo CD, ex.: http://argocd-server.argocd.svc. Sem ela (e o token), sem Argo. */
        Optional<String> url();

        /** Token da conta local so leitura do Argo, de Secret. */
        Optional<String> token();

        /** destination.name ou destination.server de uma app -> maquina. */
        Map<String, String> clusterMachines();
    }

    interface Stream {
        /** De quanto em quanto tempo o stream confere se o mapa mudou (inclui estados que venceram pelo TTL). */
        @WithDefault("5s")
        Duration every();

        /** Quantas versoes ficam guardadas para a retomada por Last-Event-ID. */
        @WithDefault("64")
        int history();
    }

    interface Edges {
        /** CA do atlas (PEM). Sem as duas, nao ha enrollment nem edge conectado. */
        Optional<String> caCert();

        Optional<String> caKey();

        /** Porta (do container) que so escuta no WG/cluster: TLS, WS mTLS dos edges e enrollment. */
        @WithDefault("8443")
        int port();

        /** Criar/revogar edge por API. Desligado ate a F5 trazer login (OIDC + papel atlas-admins). */
        @WithDefault("false")
        boolean adminEnabled();
    }

    interface Collect {
        /** Intervalo de coleta das fontes (lido pelo @Scheduled como {atlas.collect.every}). */
        @WithDefault("30s")
        Duration every();
    }

    interface ServicesYml {
        /** maquina -> caminho do services.yml. Na F1 so arquivo local (fixture); GitHub contents:read entra com o token. */
        Map<String, String> files();
    }

    interface Checks {
        /** Checks publicos pela internet. Desligado por padrao: so liga onde a rota de saida e a pretendida. */
        @WithDefault("false")
        boolean enabled();

        @WithDefault("10s")
        Duration timeout();

        /** Validade de um resultado: 3 ciclos de coleta perdidos e o no vira unknown. */
        @WithDefault("90s")
        Duration ttl();
    }
}
