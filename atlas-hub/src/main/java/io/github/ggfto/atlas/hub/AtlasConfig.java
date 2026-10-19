package io.github.ggfto.atlas.hub;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.Map;

@ConfigMapping(prefix = "atlas")
public interface AtlasConfig {

    ServicesYml servicesyml();

    Checks checks();

    Collect collect();

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
