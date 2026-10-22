package io.github.ggfto.atlas.adapters.hub;

import io.github.ggfto.atlas.core.Expectation;
import io.github.ggfto.atlas.core.Edge;
import io.github.ggfto.atlas.core.EdgeKind;
import io.github.ggfto.atlas.core.Fragment;
import io.github.ggfto.atlas.core.Node;
import io.github.ggfto.atlas.core.NodeKind;
import io.github.ggfto.atlas.core.TopologySource;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Le o {@code services.yml} de uma maquina (a mesma fonte do apply-exposure.py do infra) e declara entrada ->
 * Traefik -> [Authentik] -> rota -> servico. So declara: o estado vem dos checks e do edge. Do topo do arquivo
 * so a {@code zone} e usada; {@code lan_ip}, {@code public_ip} e {@code tunnel_id} nunca viram dado de no.
 */
public final class ServicesYmlSource implements TopologySource {

    static final String AUTHGATE = "authgate:authentik";

    private enum Exposure {
        LOCAL, EXTERNO, AMBOS, DIRETO
    }

    private record Service(String name, String subdomain, String subdomainExterno, String backend, Exposure exposure,
                           boolean auth, String icon) {
    }

    private record Route(String fqdn, String entry, String exposure, boolean publicCheck) {
    }

    private final String machine;
    private final Supplier<String> yaml;
    private final Clock clock;

    public ServicesYmlSource(String machine, Supplier<String> yaml, Clock clock) {
        this.machine = machine;
        this.yaml = yaml;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "servicesyml:" + machine;
    }

    @Override
    public Fragment collect() {
        Parsed p = parse();
        Map<String, Node> nodes = new LinkedHashMap<>();
        List<Edge> edges = new ArrayList<>();
        String traefik = "traefik:" + machine;
        nodes.put("machine:" + machine, new Node("machine:" + machine, NodeKind.MACHINE, machine, machine, Map.of()));
        nodes.put(traefik, new Node(traefik, NodeKind.TRAEFIK, "Traefik", machine, Map.of()));
        for (Service s : p.services()) {
            String service = "service:" + machine + "/" + s.name();
            // o icon so entra no mapa quando declarado: assim o atlas-web ouve "override" e o Map.of continua
            // o mesmo para quem nao usa icone (sem rebocar os testes antigos de atributos exatos).
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("backend", s.backend());
            attrs.put("exposure", lower(s.exposure()));
            attrs.put("auth", String.valueOf(s.auth()));
            if (s.icon() != null && !s.icon().isBlank()) {
                attrs.put("icon", s.icon().trim());
            }
            nodes.put(service, new Node(service, NodeKind.SERVICE, s.name(), machine, Map.copyOf(attrs)));
            for (Route r : routes(s, p.zone())) {
                String entry = "entry:" + machine + "/" + r.entry();
                nodes.putIfAbsent(entry, new Node(entry, NodeKind.ENTRY, entryName(r.entry()), machine, Map.of("via", r.entry())));
                String route = "route:" + r.fqdn();
                nodes.put(route, new Node(route, NodeKind.ROUTE, r.fqdn(), machine, Map.of(
                        "fqdn", r.fqdn(), "exposure", r.exposure(), "auth", String.valueOf(s.auth()), "url", url(r.fqdn()))));
                edges.add(Edge.of(entry, traefik, EdgeKind.ROUTES_TO));
                edges.add(Edge.of(traefik, route, EdgeKind.ROUTES_TO));
                if (s.auth()) {
                    nodes.putIfAbsent(AUTHGATE, new Node(AUTHGATE, NodeKind.AUTHGATE, "Authentik", null, Map.of("provider", "authentik")));
                    edges.add(Edge.of(route, AUTHGATE, EdgeKind.AUTH_VIA));
                }
                edges.add(Edge.of(route, service, EdgeKind.ROUTES_TO));
            }
        }
        return new Fragment(name(), clock.instant(), Duration.ofMinutes(10), List.copyOf(nodes.values()),
                edges.stream().distinct().toList(), List.of());
    }

    /** Rotas que se checa pela internet; as {@code *.local} ficam para o edge da maquina. */
    public List<CheckTarget> checkTargets() {
        Parsed p = parse();
        String authHost = "auth." + p.zone();
        List<CheckTarget> out = new ArrayList<>();
        for (Service s : p.services()) {
            for (Route r : routes(s, p.zone())) {
                if (r.publicCheck()) {
                    Expectation e = s.auth() ? new Expectation.Protected(authHost) : new Expectation.Open(authHost);
                    out.add(new CheckTarget("route:" + r.fqdn(), URI.create(url(r.fqdn())), e));
                }
            }
        }
        return out;
    }

    /**
     * O que so o edge desta maquina alcanca: o backend interno de cada servico (status do upstream) e as rotas
     * {@code *.local}. Vai no Welcome do edge (design §1.2).
     */
    public List<CheckTarget> localTargets() {
        Parsed p = parse();
        String authHost = "auth." + p.zone();
        List<CheckTarget> out = new ArrayList<>();
        for (Service s : p.services()) {
            URI backend = backendUri(s.backend());
            if (backend != null) {
                out.add(new CheckTarget("service:" + machine + "/" + s.name(), backend, new Expectation.Open(authHost)));
            }
            for (Route r : routes(s, p.zone())) {
                if (!r.publicCheck()) {
                    Expectation e = s.auth() ? new Expectation.Protected(authHost) : new Expectation.Open(authHost);
                    out.add(new CheckTarget("route:" + r.fqdn(), URI.create(url(r.fqdn())), e));
                }
            }
        }
        return out;
    }

    private static URI backendUri(String backend) {
        if (backend == null) {
            return null;
        }
        try {
            URI u = URI.create(backend.trim());
            if (!"http".equals(u.getScheme()) && !"https".equals(u.getScheme())) {
                return null;
            }
            return u.getRawPath() == null || u.getRawPath().isEmpty() ? URI.create(u + "/") : u;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * O {@code tunnel_id} do arquivo: so para o hub ligar o tunel da Cloudflare a entrada desta maquina. Nunca vira
     * atributo de no.
     */
    public java.util.Optional<String> tunnelId() {
        Map<String, Object> doc = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml.get());
        Object id = doc.get("tunnel_id");
        return id == null ? java.util.Optional.empty() : java.util.Optional.of(String.valueOf(id));
    }

    /** A zona do arquivo (ex.: gf2.in). */
    public String zone() {
        return parse().zone();
    }

    private record Parsed(String zone, List<Service> services) {
    }

    private Parsed parse() {
        Map<String, Object> doc = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml.get());
        String zone = String.valueOf(doc.get("zone"));
        Object raw = doc.getOrDefault("services", Map.of());
        List<Service> services = new ArrayList<>();
        for (Map.Entry<?, ?> e : ((Map<?, ?>) raw).entrySet()) {
            String name = String.valueOf(e.getKey());
            Map<?, ?> s = (Map<?, ?>) e.getValue();
            services.add(new Service(name, str(s.get("subdomain")), str(s.get("subdomain_externo")), str(s.get("backend")),
                    exposure(name, s.get("exposure")), Boolean.TRUE.equals(s.get("auth")), str(s.get("icon"))));
        }
        return new Parsed(zone, services);
    }

    private Exposure exposure(String service, Object value) {
        try {
            return Exposure.valueOf(String.valueOf(value).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("services.yml (" + machine + "): exposure invalida em " + service + ": " + value);
        }
    }

    private static List<Route> routes(Service s, String zone) {
        String externo = (s.subdomainExterno() != null ? s.subdomainExterno() : s.subdomain()) + "." + zone;
        String local = s.subdomain() + ".local." + zone;
        return switch (s.exposure()) {
            case LOCAL -> List.of(new Route(local, "lan", "local", false));
            case EXTERNO -> List.of(new Route(externo, "tunel", "externo", true));
            case AMBOS -> List.of(new Route(local, "lan", "local", false), new Route(externo, "tunel", "externo", true));
            case DIRETO -> List.of(new Route(s.subdomain() + "." + zone, "direto", "direto", true));
        };
    }

    private static String entryName(String via) {
        return switch (via) {
            case "lan" -> "LAN (*.local)";
            case "tunel" -> "Tunel Cloudflare";
            default -> "DNS direto";
        };
    }

    private static String url(String fqdn) {
        return "https://" + fqdn + "/";
    }

    private static String lower(Exposure e) {
        return e.name().toLowerCase(Locale.ROOT);
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
