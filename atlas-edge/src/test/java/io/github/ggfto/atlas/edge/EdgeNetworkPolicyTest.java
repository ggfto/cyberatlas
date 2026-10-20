package io.github.ggfto.atlas.edge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyEgressRule;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeer;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPort;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * A saida do pod do edge (deploy/kustomize/edge): os checks locais alcancam bridges do Docker, NodePorts (pod depois
 * do DNAT) e o IP do no, so por TCP e so em faixa privada. Nada de internet.
 */
class EdgeNetworkPolicyTest {

    static final Path DIR = Path.of("../deploy/kustomize/edge");
    static final KubernetesSerialization K8S = new KubernetesSerialization();

    static Map<String, NetworkPolicy> policies() throws Exception {
        return Arrays.stream(Files.readString(DIR.resolve("networkpolicy.yaml")).split("(?m)^---\\s*$"))
                .filter(doc -> doc.contains("kind: NetworkPolicy"))
                .map(doc -> K8S.unmarshal(doc, NetworkPolicy.class))
                .collect(Collectors.toMap(p -> p.getMetadata().getName(), Function.identity()));
    }

    static List<String> cidrs(NetworkPolicy p) {
        return p.getSpec().getEgress().stream().flatMap(r -> r.getTo().stream()).map(NetworkPolicyPeer::getIpBlock)
                .filter(Objects::nonNull).map(b -> b.getCidr()).toList();
    }

    @Test
    void checksSoTcpParaBridgesDoDockerEPodsDoCluster() throws Exception {
        NetworkPolicy checks = policies().get("atlas-edge-checks");
        assertEquals(List.of("Egress"), checks.getSpec().getPolicyTypes());
        assertEquals(Map.of("app", "atlas-edge"), checks.getSpec().getPodSelector().getMatchLabels());
        assertEquals(List.of("172.16.0.0/12", "10.42.0.0/16"), cidrs(checks));
        for (NetworkPolicyEgressRule r : checks.getSpec().getEgress()) {
            assertTrue(!r.getPorts().isEmpty() && r.getPorts().stream().allMatch(p -> "TCP".equals(p.getProtocol())), "so TCP");
        }
    }

    @Test
    void ipDoNoEPorOverlayESemPatchNaoLiberaNada() throws Exception {
        NetworkPolicy no = policies().get("atlas-edge-no");
        assertEquals(List.of("192.0.2.1/32"), cidrs(no), "TEST-NET-1 ate o overlay trocar (host 192.168.68.107/32, VPS 10.0.0.66/32)");
        assertEquals(1, no.getSpec().getEgress().size(), "o patch do overlay troca /spec/egress/0/to/0/ipBlock/cidr");
        assertEquals(List.of("TCP"), no.getSpec().getEgress().get(0).getPorts().stream().map(NetworkPolicyPort::getProtocol).toList());
    }

    @Test
    void nenhumaRegraAbreInternetEAsAntigasContinuam() throws Exception {
        Map<String, NetworkPolicy> all = policies();
        assertEquals(Set.of("atlas-edge", "atlas-edge-checks", "atlas-edge-no"), all.keySet());
        for (NetworkPolicy p : all.values()) {
            for (String cidr : cidrs(p)) {
                assertTrue(Set.of("10.43.0.1/32", "172.16.0.0/12", "10.42.0.0/16", "192.0.2.1/32").contains(cidr), p.getMetadata().getName() + ": " + cidr);
            }
            for (NetworkPolicyEgressRule r : p.getSpec().getEgress()) {
                assertTrue(r.getTo() != null && !r.getTo().isEmpty(), "regra sem destino = qualquer destino");
            }
        }
        NetworkPolicy base = all.get("atlas-edge");
        assertEquals(List.of("10.43.0.1/32"), cidrs(base), "apiserver");
        assertTrue(base.getSpec().getEgress().get(0).getPorts().stream().anyMatch(p -> p.getPort().getIntVal() == 53), "DNS");
        assertTrue(Files.readString(DIR.resolve("kustomization.yaml")).contains("networkpolicy.yaml"));
    }

    @Test
    void deploymentLigaATraducaoEPassaOIpDoNo() throws Exception {
        Deployment d = K8S.unmarshal(Files.readString(DIR.resolve("deployment.yaml")), Deployment.class);
        Map<String, EnvVar> env = d.getSpec().getTemplate().getSpec().getContainers().get(0).getEnv().stream()
                .collect(Collectors.toMap(EnvVar::getName, Function.identity()));
        assertEquals("true", env.get("ATLAS_EDGE_RESOLVE_TARGETS").getValue());
        assertEquals("status.hostIP", env.get("ATLAS_EDGE_NODE_IP").getValueFrom().getFieldRef().getFieldPath());
    }
}
