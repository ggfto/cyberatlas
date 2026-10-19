package io.github.ggfto.atlas.adapters.edge;

import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.apps.DaemonSet;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.ReplicaSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.github.ggfto.atlas.core.Edge;
import io.github.ggfto.atlas.core.EdgeKind;
import io.github.ggfto.atlas.core.Fragment;
import io.github.ggfto.atlas.core.Node;
import io.github.ggfto.atlas.core.NodeKind;
import io.github.ggfto.atlas.core.Observation;
import io.github.ggfto.atlas.core.State;
import io.github.ggfto.atlas.core.TopologySource;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Pods e workloads do k3s da maquina (design §1.2). Le so status, nome e imagem: Env, args, annotations e
 * volumes nunca. RBAC esperado: get/list/watch em pods, replicasets e workloads (sem secrets/configmaps).
 */
public final class KubernetesSource implements TopologySource {

    private final String machine;
    private final KubernetesClient client;
    private final Clock clock;
    private final Duration ttl;

    public KubernetesSource(String machine, KubernetesClient client, Clock clock, Duration ttl) {
        this.machine = machine;
        this.client = client;
        this.clock = clock;
        this.ttl = ttl;
    }

    @Override
    public String name() {
        return "kubernetes";
    }

    @Override
    public Fragment collect() {
        Instant now = clock.instant();
        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        List<Observation> obs = new ArrayList<>();
        Map<String, String> rsOwner = new HashMap<>();
        for (ReplicaSet rs : client.apps().replicaSets().inAnyNamespace().list().getItems()) {
            ownerOf(rs.getMetadata().getOwnerReferences(), "Deployment")
                    .ifPresent(o -> rsOwner.put(rs.getMetadata().getNamespace() + "/" + rs.getMetadata().getName(), o));
        }
        for (Deployment d : client.apps().deployments().inAnyNamespace().list().getItems()) {
            Integer desired = d.getSpec() == null ? null : d.getSpec().getReplicas();
            Integer ready = d.getStatus() == null ? null : d.getStatus().getReadyReplicas();
            workload(nodes, obs, d.getMetadata().getNamespace(), "deployment", d.getMetadata().getName(), desired, ready, now);
        }
        for (StatefulSet s : client.apps().statefulSets().inAnyNamespace().list().getItems()) {
            Integer desired = s.getSpec() == null ? null : s.getSpec().getReplicas();
            Integer ready = s.getStatus() == null ? null : s.getStatus().getReadyReplicas();
            workload(nodes, obs, s.getMetadata().getNamespace(), "statefulset", s.getMetadata().getName(), desired, ready, now);
        }
        for (DaemonSet s : client.apps().daemonSets().inAnyNamespace().list().getItems()) {
            Integer desired = s.getStatus() == null ? null : s.getStatus().getDesiredNumberScheduled();
            Integer ready = s.getStatus() == null ? null : s.getStatus().getNumberReady();
            workload(nodes, obs, s.getMetadata().getNamespace(), "daemonset", s.getMetadata().getName(), desired, ready, now);
        }
        for (Pod p : client.pods().inAnyNamespace().list().getItems()) {
            String ns = p.getMetadata().getNamespace();
            String key = "k8s:" + machine + "/" + ns + "/pod/" + p.getMetadata().getName();
            List<ContainerStatus> cs = p.getStatus() == null || p.getStatus().getContainerStatuses() == null
                    ? List.of() : p.getStatus().getContainerStatuses();
            int ready = (int) cs.stream().filter(c -> Boolean.TRUE.equals(c.getReady())).count();
            int restarts = cs.stream().mapToInt(c -> c.getRestartCount() == null ? 0 : c.getRestartCount()).sum();
            String phase = p.getStatus() == null ? "Unknown" : String.valueOf(p.getStatus().getPhase());
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("namespace", ns);
            attrs.put("phase", phase);
            attrs.put("ready", ready + "/" + cs.size());
            attrs.put("restarts", String.valueOf(restarts));
            Optional<String> owner = owner(p, rsOwner);
            owner.ifPresent(o -> attrs.put("owner", o));
            if (p.getSpec() != null && !p.getSpec().getContainers().isEmpty()) {
                attrs.put("image", p.getSpec().getContainers().get(0).getImage());
            }
            nodes.add(new Node(key, NodeKind.POD, p.getMetadata().getName(), machine, attrs));
            owner.filter(o -> o.startsWith("Deployment/"))
                    .ifPresent(o -> edges.add(Edge.of("k8s:" + machine + "/" + ns + "/deployment/" + o.substring("Deployment/".length()),
                            key, EdgeKind.RUNS_ON)));
            obs.add(podState(key, phase, cs, ready, restarts, now));
        }
        return new Fragment(name(), now, ttl, nodes, edges, obs);
    }

    private void workload(List<Node> nodes, List<Observation> obs, String ns, String kind, String name, Integer desiredOrNull,
                          Integer readyOrNull, Instant now) {
        int desired = desiredOrNull == null ? 1 : desiredOrNull;
        int ready = readyOrNull == null ? 0 : readyOrNull;
        String key = "k8s:" + machine + "/" + ns + "/" + kind + "/" + name;
        nodes.add(new Node(key, NodeKind.WORKLOAD, name, machine, Map.of("namespace", ns, "workload", kind,
                "ready", String.valueOf(ready), "desired", String.valueOf(desired))));
        State s = ready >= desired ? State.ONLINE : ready == 0 ? State.OFFLINE : State.DEGRADED;
        obs.add(new Observation(key, name(), s, ready + "/" + desired + " prontos", now, ttl));
    }

    private Observation podState(String key, String phase, List<ContainerStatus> cs, int ready, int restarts, Instant now) {
        String waiting = cs.stream()
                .map(c -> c.getState() == null || c.getState().getWaiting() == null ? null : c.getState().getWaiting().getReason())
                .filter(r -> r != null && !r.isBlank()).findFirst().orElse(null);
        return switch (phase) {
            case "Running" -> waiting != null
                    ? new Observation(key, name(), State.DEGRADED, waiting + " (" + restarts + " restarts)", now, ttl)
                    : ready == cs.size()
                            ? new Observation(key, name(), State.ONLINE, "Running", now, ttl)
                            : new Observation(key, name(), State.DEGRADED, "nao pronto (" + ready + "/" + cs.size() + ")", now, ttl);
            case "Pending" -> new Observation(key, name(), State.DEGRADED, waiting != null ? "Pending: " + waiting : "Pending", now, ttl);
            case "Failed" -> new Observation(key, name(), State.OFFLINE, "Failed", now, ttl);
            case "Succeeded" -> new Observation(key, name(), State.ONLINE, "Succeeded", now, ttl);
            default -> new Observation(key, name(), State.UNKNOWN, "phase " + phase, now, ttl);
        };
    }

    private static Optional<String> owner(Pod p, Map<String, String> rsOwner) {
        List<OwnerReference> refs = p.getMetadata().getOwnerReferences();
        if (refs == null || refs.isEmpty()) {
            return Optional.empty();
        }
        OwnerReference r = refs.get(0);
        if ("ReplicaSet".equals(r.getKind())) {
            String dep = rsOwner.get(p.getMetadata().getNamespace() + "/" + r.getName());
            return Optional.of(dep != null ? "Deployment/" + dep : "ReplicaSet/" + r.getName());
        }
        return Optional.of(r.getKind() + "/" + r.getName());
    }

    private static Optional<String> ownerOf(List<OwnerReference> refs, String kind) {
        return refs == null ? Optional.empty()
                : refs.stream().filter(r -> kind.equals(r.getKind())).map(OwnerReference::getName).findFirst();
    }
}
