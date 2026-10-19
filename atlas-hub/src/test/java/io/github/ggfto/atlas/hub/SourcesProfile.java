package io.github.ggfto.atlas.hub;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.HashMap;
import java.util.Map;

/**
 * Hub com Cloudflare e Argo apontando para APIs falsas (subidas aqui, antes do boot) e os services.yml sanitizados.
 * Tokens ficticios: provam que vao no header e nunca no payload.
 */
public class SourcesProfile implements QuarkusTestProfile {

    static final String CF_TOKEN = "cf-token-ficticio-do-teste";
    static final String ARGO_TOKEN = "argo-token-ficticio-do-teste";
    static final String ZONE_ID = "023e105f4ecef8ad9ca31a8372d0c353";
    static final String ACCOUNT = "01a7362d577a6c3019a474fd6f485823";
    // os tunnel_id dos fixtures sanitizados (host = ...aaaa, vps = ...bbbb)
    static final String T_HOST = "00000000-0000-0000-0000-00000000aaaa";
    static final String T_VPS = "00000000-0000-0000-0000-00000000bbbb";
    static FakeApi api;

    static String page(String result) {
        return "{\"success\":true,\"errors\":[],\"result\":" + result + ",\"result_info\":{\"page\":1,\"per_page\":100,\"total_pages\":1}}";
    }

    static synchronized FakeApi api() throws Exception {
        if (api == null) {
            Map<String, FakeApi.Reply> m = new HashMap<>();
            m.put("/client/v4/zones?name=gf2.in", new FakeApi.Reply(200, page("[{\"id\":\"" + ZONE_ID + "\",\"name\":\"gf2.in\"}]")));
            m.put("/client/v4/zones/" + ZONE_ID + "/dns_records?page=1&per_page=100", new FakeApi.Reply(200, page("["
                    + "{\"name\":\"ha.gf2.in\",\"type\":\"CNAME\",\"content\":\"" + T_VPS + ".cfargotunnel.com\",\"proxied\":true,\"comment\":\"gerenciado por vps/services.yml\"},"
                    + "{\"name\":\"orfao.gf2.in\",\"type\":\"A\",\"content\":\"198.51.100.77\",\"proxied\":false,\"comment\":\"gerenciado por vps/services.yml\"}]")));
            m.put("/client/v4/accounts/" + ACCOUNT + "/cfd_tunnel?is_deleted=false&page=1&per_page=100", new FakeApi.Reply(200, page(
                    "[{\"id\":\"" + T_VPS + "\",\"name\":\"vps-tunel\",\"status\":\"healthy\",\"connections\":[{},{}]},"
                    + "{\"id\":\"" + T_HOST + "\",\"name\":\"host-tunel\",\"status\":\"down\",\"connections\":[]}]")));
            m.put("/client/v4/accounts/" + ACCOUNT + "/cfd_tunnel/" + T_VPS + "/configurations", new FakeApi.Reply(200,
                    "{\"success\":true,\"result\":{\"config\":{\"ingress\":[{\"hostname\":\"ha.gf2.in\",\"service\":\"https://localhost\"}]}}}"));
            m.put("/client/v4/accounts/" + ACCOUNT + "/cfd_tunnel/" + T_HOST + "/configurations", new FakeApi.Reply(200,
                    "{\"success\":true,\"result\":{\"config\":{\"ingress\":[]}}}"));
            m.put("/api/v1/applications", new FakeApi.Reply(200, "{\"items\":[{\"metadata\":{\"name\":\"vps-dozzle\"},"
                    + "\"spec\":{\"destination\":{\"server\":\"https://kubernetes.default.svc\",\"namespace\":\"dozzle\"}},"
                    + "\"status\":{\"sync\":{\"status\":\"OutOfSync\"},\"health\":{\"status\":\"Healthy\"},"
                    + "\"resources\":[{\"kind\":\"Deployment\",\"namespace\":\"dozzle\",\"name\":\"dozzle\"}]}}]}"));
            api = new FakeApi(m);
        }
        return api;
    }

    @Override
    public Map<String, String> getConfigOverrides() {
        try {
            Map<String, String> c = new HashMap<>(new FixtureProfile().getConfigOverrides());
            c.put("atlas.cloudflare.api", api().base().toString());
            c.put("atlas.cloudflare.token", CF_TOKEN);
            c.put("atlas.cloudflare.account-id", ACCOUNT);
            c.put("atlas.argocd.url", api().base().toString());
            c.put("atlas.argocd.token", ARGO_TOKEN);
            return c;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
