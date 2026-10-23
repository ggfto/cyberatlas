package io.github.ggfto.atlas.hub.mcp;

import io.github.ggfto.atlas.hub.FixtureProfile;
import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.HashMap;
import java.util.Map;

/**
 * Fixture do mapa, mais duas coisas de producao:
 * <ul>
 *   <li>{@code quarkus.http.host=0.0.0.0}: com host local a extensao MCP liga um check proprio de Origin (403) que
 *       some em producao. Sem isto o teste de cookie passaria pela guarda errada;</li>
 *   <li>uma fonte quebrada com IP no erro, para o list_sources ter texto livre a mascarar.</li>
 * </ul>
 */
public class McpProdLikeProfile implements QuarkusTestProfile {

    static final String BROKEN_IP = "10.9.8.7";

    @Override
    public Map<String, String> getConfigOverrides() {
        Map<String, String> c = new HashMap<>(new FixtureProfile().getConfigOverrides());
        c.put("quarkus.http.host", "0.0.0.0");
        c.put("atlas.servicesyml.files.quebrada", "/nao/existe/" + BROKEN_IP + "/services.yml");
        return c;
    }
}
