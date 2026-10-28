package io.github.ggfto.atlas.hub;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.Optional;

@ConfigMapping(prefix = "atlas.ha-mqtt")
public interface HaMqttConfig {
    @WithDefault("false") boolean enabled();
    @WithDefault("localhost") String host();
    @WithDefault("1883") int port();
    @WithDefault("atlas-hub") String clientId();
    @WithDefault("false") boolean tls();
    Optional<String> username();
    Optional<String> password();
    @WithDefault("60s") Duration removalGrace();
}
