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

    @Test
    void containerKeepsOnlyRuntimeFactsNeverEnvOrLabels() {
        Node n = new Node("docker:vps/9router", NodeKind.CONTAINER, "9router", "vps", Map.of(
                "image", "ghcr.io/ggfto/9router:sha-1", "state", "running", "status", "Up 2 hours (healthy)", "health", "healthy",
                "compose_project", "9router", "compose_service", "9router",
                "env", "API_KEY=x", "label.traefik.http.middlewares.auth.basicauth.users", "admin:$apr1$hash", "command", "node server.js"));
        assertEquals(Map.of("image", "ghcr.io/ggfto/9router:sha-1", "state", "running", "status", "Up 2 hours (healthy)",
                "health", "healthy", "compose_project", "9router", "compose_service", "9router"),
                Sanitizer.DEFAULT.sanitize(n).attributes());
    }

    @Test
    void serviceKeepsIconOverrideButKeepsDroppingAttributesOutOfTheAllowlist() {
        // garante que o override "icon" do services.yml (issue #24) sobrevive ao sanitizer e que a
        // allowlist continua apertada: um atributo nao declarado (env.*) e descartado no mesmo no.
        Node n = new Node("service:vps/portainer", NodeKind.SERVICE, "portainer", "vps", Map.of(
                "backend", "http://portainer:9000", "exposure", "externo", "auth", "true",
                "icon", "portainer", "env.ADMIN_PASSWORD", "segredo"));
        assertEquals(Map.of("backend", "http://portainer:9000", "exposure", "externo", "auth", "true",
                "icon", "portainer"), Sanitizer.DEFAULT.sanitize(n).attributes());
    }

    @Test
    void routerPodAndWorkloadHaveTheirOwnLists() {
        assertEquals(Map.of("rule", "Host(`ha.gf2.in`)", "service", "ha@file", "status", "enabled"),
                Sanitizer.DEFAULT.sanitize(new Node("router:vps/ha", NodeKind.ROUTER, "ha", "vps", Map.of(
                        "rule", "Host(`ha.gf2.in`)", "service", "ha@file", "status", "enabled", "middlewares", "auth@file"))).attributes());
        assertEquals(Map.of("namespace", "dozzle", "phase", "Running", "ready", "1/1", "restarts", "0"),
                Sanitizer.DEFAULT.sanitize(new Node("k8s:vps/dozzle/pod/d-1", NodeKind.POD, "d-1", "vps", Map.of(
                        "namespace", "dozzle", "phase", "Running", "ready", "1/1", "restarts", "0", "env", "x"))).attributes());
        assertEquals(Map.of("namespace", "dozzle", "ready", "1", "desired", "1"),
                Sanitizer.DEFAULT.sanitize(new Node("k8s:vps/dozzle/deployment/d", NodeKind.WORKLOAD, "d", "vps", Map.of(
                        "namespace", "dozzle", "ready", "1", "desired", "1", "annotations", "x"))).attributes());
    }
}
