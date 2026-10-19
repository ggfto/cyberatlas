package io.github.ggfto.atlas.core;

/** Tipos de no do mapa (design §3). */
public enum NodeKind {
    ENTRY, TUNNEL, DNS, TRAEFIK, ROUTER, AUTHGATE, ROUTE, SERVICE, WORKLOAD, POD, CONTAINER, MACHINE, CLUSTER, ARGO
}
