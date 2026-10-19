package io.github.ggfto.atlas.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** De onde vem o services.yml de cada maquina: arquivo local ou GitHub, nunca os dois. */
class ServicesYmlPlanTest {

    static Supplier<String> gh(String repo, String path) {
        return () -> "github:" + repo + ":" + path;
    }

    @Test
    void filesAndGithubCanBeMixedPerMachine() {
        Map<String, Supplier<String>> plan = TopologyService.servicesYml(Map.of("host", "/x/host.yml"),
                Map.of("vps", "vps/services.yml"), Optional.of("ggfto/infra"), ServicesYmlPlanTest::gh, f -> () -> "file:" + f);
        assertEquals("file:/x/host.yml", plan.get("host").get());
        assertEquals("github:ggfto/infra:vps/services.yml", plan.get("vps").get());
        assertEquals(java.util.List.of("host", "vps"), java.util.List.copyOf(plan.keySet()));
    }

    @Test
    void sameMachineInBothSourcesFailsTheBoot() {
        var e = assertThrows(IllegalStateException.class, () -> TopologyService.servicesYml(Map.of("vps", "/x.yml"),
                Map.of("vps", "vps/services.yml"), Optional.of("ggfto/infra"), ServicesYmlPlanTest::gh, f -> () -> f));
        assertTrue(e.getMessage().contains("vps"), e.getMessage());
    }

    @Test
    void githubPathsWithoutRepoFailTheBoot() {
        assertThrows(IllegalStateException.class, () -> TopologyService.servicesYml(Map.of(), Map.of("vps", "vps/services.yml"),
                Optional.empty(), ServicesYmlPlanTest::gh, f -> () -> f));
    }
}
