package io.github.ggfto.atlas.core;

import java.util.function.Consumer;

/** Opcional: fonte com eventos (informers do k8s, eventos do Docker) empurra fragmentos sem esperar o poll. */
public interface Watcher {

    void watch(Consumer<Fragment> out) throws Exception;
}
