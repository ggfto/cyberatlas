package io.github.ggfto.atlas.adapters.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ggfto.atlas.core.Edge;
import io.github.ggfto.atlas.core.EdgeKind;
import io.github.ggfto.atlas.core.Fragment;
import io.github.ggfto.atlas.core.Node;
import io.github.ggfto.atlas.core.NodeKind;
import io.github.ggfto.atlas.core.Observation;
import io.github.ggfto.atlas.core.State;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Cloudflare com token so leitura (design §1.3): DNS da zona, tuneis e o ingress de cada tunel. Paginado; do
 * registro so sai tipo/proxied/classificacao, nunca o IP; id de tunel, zona e conta nunca viram dado de no.
 */
class CloudflareSourceTest {

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);
    static final String TOKEN = "cf-token-de-teste-nao-real";
    static final String ZONE_ID = "023e105f4ecef8ad9ca31a8372d0c353";
    static final String ACCOUNT = "01a7362d577a6c3019a474fd6f485823";
    static final String T_VPS = "11111111-2222-3333-4444-555555555555";
    static final String T_HOST = "66666666-7777-8888-9999-000000000000";

    static String page(String result, int page, int total) {
        return "{\"success\":true,\"errors\":[],\"result\":" + result + ",\"result_info\":{\"page\":" + page + ",\"per_page\":100,\"total_pages\":" + total + "}}";
    }

    static Map<String, FakeApi.Reply> api() {
        Map<String, FakeApi.Reply> m = new HashMap<>();
        m.put("/client/v4/zones?name=gf2.in", new FakeApi.Reply(200, page("[{\"id\":\"" + ZONE_ID + "\",\"name\":\"gf2.in\"}]", 1, 1)));
        m.put("/client/v4/zones/" + ZONE_ID + "/dns_records?page=1&per_page=100", new FakeApi.Reply(200, page("["
                + "{\"id\":\"r1\",\"name\":\"ha.gf2.in\",\"type\":\"CNAME\",\"content\":\"" + T_VPS + ".cfargotunnel.com\",\"proxied\":true,\"comment\":\"gerenciado por vps/services.yml\"},"
                + "{\"id\":\"r2\",\"name\":\"fx.local.gf2.in\",\"type\":\"A\",\"content\":\"192.168.68.107\",\"proxied\":false,\"comment\":\"gerenciado por host/services.yml\"}"
                + "]", 1, 2)));
        m.put("/client/v4/zones/" + ZONE_ID + "/dns_records?page=2&per_page=100", new FakeApi.Reply(200, page("["
                + "{\"id\":\"r3\",\"name\":\"srv.gf2.in\",\"type\":\"A\",\"content\":\"164.152.38.21\",\"proxied\":false,\"comment\":null},"
                + "{\"id\":\"r4\",\"name\":\"orfao.gf2.in\",\"type\":\"CNAME\",\"content\":\"" + T_HOST + ".cfargotunnel.com\",\"proxied\":true,\"comment\":\"gerenciado por host/services.yml\"},"
                + "{\"id\":\"r5\",\"name\":\"site.gf2.in\",\"type\":\"A\",\"content\":\"203.0.113.9\",\"proxied\":true}"
                + "]", 2, 2)));
        m.put("/client/v4/accounts/" + ACCOUNT + "/cfd_tunnel?is_deleted=false&page=1&per_page=100", new FakeApi.Reply(200, page(
                "[{\"id\":\"" + T_VPS + "\",\"name\":\"vps-tunel\",\"status\":\"healthy\",\"connections\":[{\"id\":\"c1\"},{\"id\":\"c2\"}]}]", 1, 2)));
        m.put("/client/v4/accounts/" + ACCOUNT + "/cfd_tunnel?is_deleted=false&page=2&per_page=100", new FakeApi.Reply(200, page(
                "[{\"id\":\"" + T_HOST + "\",\"name\":\"host-tunel\",\"status\":\"degraded\",\"connections\":[{\"id\":\"c3\"}]}]", 2, 2)));
        m.put("/client/v4/accounts/" + ACCOUNT + "/cfd_tunnel/" + T_VPS + "/configurations", new FakeApi.Reply(200,
                "{\"success\":true,\"result\":{\"config\":{\"ingress\":[{\"hostname\":\"ha.gf2.in\",\"service\":\"https://localhost:443\"},{\"service\":\"http_status:404\"}]}}}"));
        m.put("/client/v4/accounts/" + ACCOUNT + "/cfd_tunnel/" + T_HOST + "/configurations", new FakeApi.Reply(200,
                "{\"success\":true,\"result\":{\"config\":{\"ingress\":[{\"service\":\"http_status:404\"}]}}}"));
        return m;
    }

    static CloudflareSource source(FakeApi f) {
        return new CloudflareSource(f.base(), TOKEN, ACCOUNT, "gf2.in", Map.of(T_VPS, "vps", T_HOST, "host"),
                Duration.ofSeconds(2), CLOCK, Duration.ofSeconds(90));
    }

    static Map<String, Node> nodes(Fragment f) {
        return f.nodes().stream().collect(Collectors.toMap(Node::key, n -> n));
    }

    @Test
    void dnsRecordsOfAllPagesBecomeClassifiedNodes() throws Exception {
        try (FakeApi f = new FakeApi(api())) {
            Map<String, Node> n = nodes(source(f).collect());
            assertEquals(Map.of("type", "CNAME", "proxied", "true", "via", "tunel", "managed", "true"), n.get("dns:ha.gf2.in").attributes());
            assertEquals("lan", n.get("dns:fx.local.gf2.in").attributes().get("via"));
            assertEquals("direto", n.get("dns:srv.gf2.in").attributes().get("via"));
            assertEquals("false", n.get("dns:srv.gf2.in").attributes().get("managed"));
            assertEquals("proxy", n.get("dns:site.gf2.in").attributes().get("via"));
            assertTrue(n.containsKey("dns:orfao.gf2.in"), "segunda pagina lida");
            assertEquals(NodeKind.DNS, n.get("dns:ha.gf2.in").kind());
        }
    }

    @Test
    void tunnelsWithStatusLinkToTheirMachineEntryAndIngressHosts() throws Exception {
        try (FakeApi f = new FakeApi(api())) {
            Fragment frag = source(f).collect();
            Map<String, Node> n = nodes(frag);
            assertEquals(Map.of("status", "healthy", "connections", "2"), n.get("cf-tunnel:vps-tunel").attributes());
            Map<String, Observation> obs = frag.observations().stream().collect(Collectors.toMap(Observation::nodeKey, o -> o));
            assertEquals(State.ONLINE, obs.get("cf-tunnel:vps-tunel").state());
            assertEquals(State.DEGRADED, obs.get("cf-tunnel:host-tunel").state());
            assertEquals(State.ONLINE, obs.get("entry:vps/tunel").state(), "o tunel da maquina e a entrada dela");
            assertEquals("tunel vps-tunel: healthy (2 conexoes)", obs.get("entry:vps/tunel").reason());
            assertEquals(State.DEGRADED, obs.get("entry:host/tunel").state());
            assertTrue(frag.edges().contains(Edge.of("dns:ha.gf2.in", "cf-tunnel:vps-tunel", EdgeKind.ROUTES_TO)));
            assertTrue(frag.edges().contains(Edge.of("cf-tunnel:vps-tunel", "route:ha.gf2.in", EdgeKind.ROUTES_TO)));
            assertEquals("cloudflare", frag.source());
        }
    }

    @Test
    void neitherRecordContentNorIdsReachTheNodes() throws Exception {
        try (FakeApi f = new FakeApi(api())) {
            String all = source(f).collect().toString();
            for (String leak : new String[] {"192.168.68.107", "164.152.38.21", "203.0.113.9", T_VPS, T_HOST, ZONE_ID, ACCOUNT, TOKEN,
                    "cfargotunnel", "r1", "c1"}) {
                assertFalse(all.contains(leak), "vazou " + leak);
            }
        }
    }

    @Test
    void tokenGoesAsBearerOnEveryCall() throws Exception {
        try (FakeApi f = new FakeApi(api())) {
            source(f).collect();
            assertTrue(f.auth.stream().allMatch(a -> a.equals("Bearer " + TOKEN)), f.auth.toString());
            assertEquals(7, f.requested.size(), f.requested.toString());
        }
    }

    @Test
    void apiErrorFailsWithTheCloudflareCodeAndNeverTheToken() throws Exception {
        Map<String, FakeApi.Reply> m = api();
        m.put("/client/v4/zones?name=gf2.in", new FakeApi.Reply(403,
                "{\"success\":false,\"errors\":[{\"code\":9109,\"message\":\"Invalid access token\"}],\"result\":null}"));
        try (FakeApi f = new FakeApi(m)) {
            IOException e = assertThrows(IOException.class, () -> source(f).collect());
            assertEquals("cloudflare GET /client/v4/zones: HTTP 403 (9109 Invalid access token)", e.getMessage());
            assertFalse(e.getMessage().contains(TOKEN));
        }
    }

    @Test
    void successFalseWithHttp200IsAnErrorToo() throws Exception {
        Map<String, FakeApi.Reply> m = api();
        // result valido junto: so o success=false decide
        m.put("/client/v4/zones?name=gf2.in", new FakeApi.Reply(200, "{\"success\":false,\"errors\":[{\"code\":1000,\"message\":\"x\"}],"
                + "\"result\":[{\"id\":\"" + ZONE_ID + "\",\"name\":\"gf2.in\"}]}"));
        try (FakeApi f = new FakeApi(m)) {
            assertEquals("cloudflare GET /client/v4/zones: HTTP 200 (1000 x)",
                    assertThrows(IOException.class, () -> source(f).collect()).getMessage());
        }
    }

    @Test
    void errorMessageMasksZoneAndAccountIds() throws Exception {
        // a mensagem vai para log e para o lastErrors do coletor: ids de zona/conta/tunel nunca
        Map<String, FakeApi.Reply> m = api();
        m.put("/client/v4/zones/" + ZONE_ID + "/dns_records?page=1&per_page=100", new FakeApi.Reply(500,
                "{\"success\":false,\"errors\":[{\"code\":10000,\"message\":\"falha\"}]}"));
        try (FakeApi f = new FakeApi(m)) {
            IOException e = assertThrows(IOException.class, () -> source(f).collect());
            assertEquals("cloudflare GET /client/v4/zones/{id}/dns_records: HTTP 500 (10000 falha)", e.getMessage());
            assertFalse(e.getMessage().contains(ZONE_ID));
        }
    }

    @Test
    void unknownZoneFailsClosed() throws Exception {
        Map<String, FakeApi.Reply> m = api();
        m.put("/client/v4/zones?name=gf2.in", new FakeApi.Reply(200, page("[]", 1, 1)));
        try (FakeApi f = new FakeApi(m)) {
            IOException e = assertThrows(IOException.class, () -> source(f).collect());
            assertEquals("cloudflare: zona gf2.in nao encontrada para o token", e.getMessage());
        }
    }

    @Test
    void slowApiIsATimeout() throws Exception {
        Map<String, FakeApi.Reply> m = api();
        m.put("/client/v4/zones?name=gf2.in", new FakeApi.Reply(200, page("[]", 1, 1), 5000));
        try (FakeApi f = new FakeApi(m)) {
            IOException e = assertThrows(IOException.class, () -> source(f).collect());
            assertEquals("cloudflare GET /client/v4/zones: timeout", e.getMessage());
        }
    }

    @Test
    void paginationHasACeiling() throws Exception {
        Map<String, FakeApi.Reply> m = api();
        // API que diz ter 10 mil paginas nao prende o coletor
        m.put("/client/v4/zones/" + ZONE_ID + "/dns_records?page=1&per_page=100", new FakeApi.Reply(200, page("[]", 1, 10_000)));
        try (FakeApi f = new FakeApi(m)) {
            IOException e = assertThrows(IOException.class, () -> source(f).collect());
            assertEquals("cloudflare: mais de " + CloudflareSource.MAX_PAGES + " paginas em dns_records", e.getMessage());
        }
    }

    /** Zona e tuneis vem do services.yml (que pode estar no GitHub): falhar ao le-los e erro da coleta, nao do boot. */
    @Test
    void zoneAndTunnelsAreReadAtCollectTimeSoAFailingServicesYmlDoesNotBreakConstruction() throws Exception {
        try (FakeApi f = new FakeApi(Map.of())) {
            java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
            CloudflareSource src = new CloudflareSource(f.base(), TOKEN, ACCOUNT, () -> {
                reads.incrementAndGet();
                throw new java.io.UncheckedIOException(new java.io.IOException("github fora"));
            }, Map::of, Duration.ofSeconds(2), CLOCK, Duration.ofSeconds(90));
            assertEquals(0, reads.get(), "nada e lido no construtor");
            Exception e = org.junit.jupiter.api.Assertions.assertThrows(Exception.class, src::collect);
            assertTrue(e.getMessage().contains("github fora"), e.getMessage());
            assertEquals(0, f.requested.size(), "sem zona nao chama a Cloudflare");
        }
    }

    @Test
    void blankZoneIsAVisibleError() throws Exception {
        try (FakeApi f = new FakeApi(Map.of())) {
            CloudflareSource src = new CloudflareSource(f.base(), TOKEN, ACCOUNT, () -> "", Map::of, Duration.ofSeconds(2), CLOCK,
                    Duration.ofSeconds(90));
            java.io.IOException e = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class, src::collect);
            assertTrue(e.getMessage().contains("zona nao definida"), e.getMessage());
        }
    }
}
