package io.github.ggfto.atlas.core;

/** Uma fonte de topologia (design §2.2). Coleta por poll; quem tem eventos implementa tambem {@link Watcher}. */
public interface TopologySource {

    String name();

    Fragment collect() throws Exception;
}
