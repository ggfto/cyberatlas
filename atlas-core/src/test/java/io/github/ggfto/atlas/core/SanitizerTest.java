package io.github.ggfto.atlas.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** Payload por allowlist (design §4): so os campos declarados por tipo de no saem do hub. */
class SanitizerTest {

    @Test
    void keepsOnlyAllowlistedAttributesOfTheKind() {
        Node n = new Node("service:vps/9router", NodeKind.SERVICE, "9router", "vps", Map.of(
                "backend", "http://9router:20128", "exposure", "externo", "auth", "false",
                "tunnel_id", "00000000-0000-0000-0000-00000000bbbb", "env.API_KEY", "segredo", "public_ip", "198.51.100.20"));
        assertEquals(Map.of("backend", "http://9router:20128", "exposure", "externo", "auth", "false"),
                Sanitizer.DEFAULT.sanitize(n).attributes());
    }

    @Test
    void kindWithoutAllowlistLeavesNoAttribute() {
        Node n = new Node("traefik:vps", NodeKind.TRAEFIK, "Traefik", "vps", Map.of("dashboard", "true", "version", "3"));
        assertEquals(Map.of(), Sanitizer.DEFAULT.sanitize(n).attributes());
    }

    @Test
    void ipv4LiteralsAreMaskedEvenInAllowedFields() {
        Node n = new Node("service:host/x", NodeKind.SERVICE, "x", "host", Map.of("backend", "http://192.0.2.44:8080/a"));
        assertEquals("http://<ip>:8080/a", Sanitizer.DEFAULT.sanitize(n).attributes().get("backend"));
    }

    @Test
    void namesAreNotTouchedButKeyAndKindAreKept() {
        Node n = new Node("route:ha.gf2.in", NodeKind.ROUTE, "ha.gf2.in", "vps", Map.of("fqdn", "ha.gf2.in"));
        Node out = Sanitizer.DEFAULT.sanitize(n);
        assertEquals(n.key(), out.key());
        assertEquals(n.kind(), out.kind());
        assertEquals("ha.gf2.in", out.name());
    }
}
