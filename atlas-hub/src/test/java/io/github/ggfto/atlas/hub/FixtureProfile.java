package io.github.ggfto.atlas.hub;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/** Hub lendo os services.yml sanitizados do adapters-hub; sem checks (nada sai para a internet no teste). */
public class FixtureProfile implements QuarkusTestProfile {

    static final String FIXTURES = "../atlas-adapters-hub/src/test/resources/fixtures/";

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                "atlas.servicesyml.files.host", FIXTURES + "services-host.yml",
                "atlas.servicesyml.files.vps", FIXTURES + "services-vps.yml",
                "atlas.checks.enabled", "false");
    }
}
