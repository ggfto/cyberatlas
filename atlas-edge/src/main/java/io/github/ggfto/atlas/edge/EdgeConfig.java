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

    /**
     * Traduz o host dos alvos (checks locais e API do Traefik) para IP: nome de container pela listagem do Docker,
     * {@code host.docker.internal} pelo {@link #hostGateway()}, loopback pelo {@link #nodeIp()}. Ligado no k3s, onde
     * o pod nao tem o DNS do Docker; desligado no compose.
     */
    @WithDefault("false")
    boolean resolveTargets();

    /** O que {@code host.docker.internal} era no compose ({@code host-gateway}): o gateway do docker0. */
    @WithDefault("172.17.0.1")
    String hostGateway();

    /** IP do no ({@code status.hostIP} pela downward API), para loopback e containers em rede host. */
    Optional<String> nodeIp();

    @WithDefault("10s")
    Duration timeout();
}
