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

    Logs logs();

    Breakglass breakglass();

    /**
     * Login local para quando o Authentik cair (design §4): usuario unico, hash bcrypt do cofre via Secret. So vale
     * na porta TLS de edge (8443, so WG/cluster); sem os dois, nao existe.
     */
    interface Breakglass {
        Optional<String> user();

        Optional<String> bcrypt();
    }

    interface Logs {
        /** Chave de desligar o log ao vivo (so atlas-admins abre log; viewer leva 403). */
        @WithDefault("true")
        boolean enabled();

        @WithDefault("4")
        int perUser();

        @WithDefault("20")
        int global();

        /** Linhas guardadas por assinante; acima disso o lento perde as mais antigas e recebe dropped. */
        @WithDefault("2000")
        int subscriberBuffer();

        /** Follow sem assinante fecha no edge depois disso. */
        @WithDefault("10s")
        Duration idleClose();

        @WithDefault("30m")
        Duration maxDuration();

        /** Lotes que o edge pode mandar antes do primeiro credito de volta. */
        @WithDefault("8")
        int initialCredit();
    }

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

        /** Chave de desligar o admin de edges por API (so atlas-admins chega nele). */
        @WithDefault("true")
        boolean adminEnabled();
    }

    interface Collect {
        /** Intervalo de coleta das fontes (lido pelo @Scheduled como {atlas.collect.every}). */
        @WithDefault("30s")
        Duration every();
    }

    /** De onde vem o services.yml de cada maquina: arquivo local ou GitHub (nunca os dois para a mesma maquina). */
    interface ServicesYml {
        /** maquina -> arquivo local (fixture, dev). */
        Map<String, String> files();

        Github github();

        /** GitHub contents:read (design §1.3): o services.yml versionado no ggfto/infra, sem copia no deploy. */
        interface Github {
            @WithDefault("https://api.github.com")
            String api();

            /** dono/nome, ex.: ggfto/infra. Obrigatorio se houver paths. */
            Optional<String> repo();

            /** Branch, tag ou commit lido; stable = o que o Argo e o config-sync aplicam. */
            @WithDefault("stable")
            String ref();

            /** Token fine-grained so contents:read do repo (Secret); vazio = repo publico. */
            Optional<String> token();

            /** maquina -> caminho no repo, ex.: vps=vps/services.yml. */
            Map<String, String> paths();

            @WithDefault("10s")
            Duration timeout();
        }
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
