package io.github.ggfto.atlas.edge;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.Optional;

@ConfigMapping(prefix = "atlas.edge")
public interface EdgeConfig {

    /** Nome da maquina (o hub confere com o certificado). Sem ele e sem o hub, o edge fica ocioso. */
    Optional<String> machine();

    /** Porta de edge do hub, ex.: https://IP-do-WG:8443 (nunca pela Cloudflare: o tunel termina o TLS). */
    Optional<String> hub();

    /** Token de uso unico do enrollment; so usado se ainda nao houver credencial no stateDir. */
    Optional<String> token();

    /** CA do atlas recebida na instalacao (PEM), para confiar no hub ja no enrollment. */
    Optional<String> caFile();

    @WithDefault("/var/lib/atlas-edge")
    String stateDir();

    /** unix:///var/run/docker.sock ou tcp://socket-proxy:2375; vazio desliga o Docker. */
    Optional<String> docker();

    /** API interna do Traefik da maquina, ex.: http://traefik:8080; vazio desliga. */
    Optional<String> traefik();

    @WithDefault("false")
    boolean kubernetes();

    @WithDefault("10s")
    Duration timeout();
}
