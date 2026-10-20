package io.github.ggfto.atlas.adapters.edge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/**
 * Traduz o host de um alvo de check do jeito que o compose resolvia, para o edge que roda num pod do k3s (onde nem o
 * DNS do Docker nem o {@code host-gateway} existem):
 * <ul>
 * <li>nome de container (ou servico compose, quando so um container o tem) -> IP do container, da listagem
 * {@code GET /containers/json} que o {@link DockerSource} ja usa ({@code NetworkSettings.Networks.*.IPAddress}).
 * Nunca o inspect: a allowlist GET continua a mesma. Container em {@code network_mode: host} -> IP do no;</li>
 * <li>{@code host.docker.internal} -> gateway do docker0 ({@code 172.17.0.1}), o mesmo que o {@code host-gateway}
 * do compose dava: processo do host preso ao docker0 (ex.: hermes em 172.17.0.1:9119) so responde ali;</li>
 * <li>{@code localhost}/{@code 127.0.0.1} -> IP do no ({@code status.hostIP} pela downward API): o loopback do pod
 * e o proprio pod;</li>
 * <li>qualquer outro host (FQDN, IP, nome desconhecido) fica como esta. Nome desconhecido falha no DNS e o check
 * diz "nao resolve".</li>
 * </ul>
 * Com o host trocado por IP, o {@code Host} da requisicao vira o IP: serve para backend HTTP interno; backend HTTPS
 * por nome falharia na validacao do certificado.
 */
public final class TargetResolver {

    static final String HOST_ALIAS = "host.docker.internal";
    static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final DockerClient docker;
    private final String hostGateway;
    private final String nodeIp;

    /**
     * @param docker      a listagem do Docker da maquina; nulo = sem traducao de nome de container
     * @param hostGateway IP para {@code host.docker.internal} (o gateway do docker0)
     * @param nodeIp      IP do no para loopback e containers em rede host; nulo = loopback fica como esta
     */
    public TargetResolver(DockerClient docker, String hostGateway, String nodeIp) {
        this.docker = docker;
        this.hostGateway = blankToNull(hostGateway);
        this.nodeIp = blankToNull(nodeIp);
    }

    /** Um tradutor com a foto atual dos containers (uma listagem por rodada de checks). */
    public UnaryOperator<URI> snapshot() throws IOException {
        Map<String, String> ips = docker == null ? Map.of() : containerIps(JSON.readTree(docker.get("/containers/json")), nodeIp);
        return u -> resolve(u, ips, hostGateway, nodeIp);
    }

    static URI resolve(URI url, Map<String, String> containerIps, String hostGateway, String nodeIp) {
        String host = url.getHost();
        if (host == null) {
            return url;
        }
        String h = host.toLowerCase(Locale.ROOT);
        String to;
        if (HOST_ALIAS.equals(h)) {
            to = hostGateway;
        } else if (LOOPBACK.contains(h)) {
            to = nodeIp;
        } else {
            to = containerIps.get(h);
        }
        return to == null ? url : withHost(url, to);
    }

    /**
     * Nome -> IP. Por nome de container sempre; por servico compose so quando um unico container o tem (dois
     * projetos com servico "web" nao viram chute). Containers sem IP (parados) ficam de fora.
     */
    static Map<String, String> containerIps(JsonNode list, String nodeIp) {
        Map<String, String> byName = new HashMap<>();
        Map<String, List<String>> byService = new HashMap<>();
        for (JsonNode c : list) {
            String name = c.path("Names").path(0).asText("").replaceFirst("^/", "").toLowerCase(Locale.ROOT);
            String ip = ipOf(c, nodeIp);
            if (name.isEmpty() || ip == null) {
                continue;
            }
            byName.put(name, ip);
            JsonNode svc = c.path("Labels").path("com.docker.compose.service");
            if (svc.isTextual()) {
                byService.computeIfAbsent(svc.asText().toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(ip);
            }
        }
        Map<String, String> out = new HashMap<>();
        byService.forEach((svc, ips) -> {
            if (ips.size() == 1) {
                out.put(svc, ips.get(0));
            }
        });
        out.putAll(byName);
        return out;
    }

    /** Primeira rede (por nome, para ser deterministico) com IP; rede host = IP do no. */
    private static String ipOf(JsonNode c, String nodeIp) {
        if ("host".equals(c.path("HostConfig").path("NetworkMode").asText())) {
            return nodeIp;
        }
        Map<String, String> byNetwork = new TreeMap<>();
        c.path("NetworkSettings").path("Networks").fields().forEachRemaining(e -> {
            String ip = e.getValue().path("IPAddress").asText("");
            if (!ip.isEmpty()) {
                byNetwork.put(e.getKey(), ip);
            }
        });
        return byNetwork.isEmpty() ? null : byNetwork.values().iterator().next();
    }

    /** Troca so o host; caminho e query seguem crus (sem recodificar {@code %xx}). */
    private static URI withHost(URI u, String host) {
        StringBuilder b = new StringBuilder(u.getScheme()).append("://");
        if (u.getRawUserInfo() != null) {
            b.append(u.getRawUserInfo()).append('@');
        }
        b.append(host.contains(":") ? "[" + host + "]" : host);
        if (u.getPort() >= 0) {
            b.append(':').append(u.getPort());
        }
        b.append(u.getRawPath() == null ? "" : u.getRawPath());
        if (u.getRawQuery() != null) {
            b.append('?').append(u.getRawQuery());
        }
        if (u.getRawFragment() != null) {
            b.append('#').append(u.getRawFragment());
        }
        return URI.create(b.toString());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
