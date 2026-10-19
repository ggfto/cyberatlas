package io.github.ggfto.atlas.core;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Mistura as observacoes de um no (design §3.3): vale o pior entre as fontes frescas que decidiram. Fonte vencida
 * nao conta; sem fonte fresca o no fica {@code UNKNOWN} com o motivo, nunca "online velho". Um edge que caiu
 * vira {@code UNKNOWN}, nao {@code OFFLINE}: a ultima coisa que ele disse ja nao vale.
 */
public final class StatusMerge {

    private StatusMerge() {
    }

    public static NodeStatus merge(List<Observation> observations, Instant now) {
        List<SourceState> sources = observations.stream()
                .map(o -> new SourceState(o.source(), o.state(), o.reason(), o.observedAt(), o.freshAt(now)))
                .toList();
        List<Observation> fresh = observations.stream().filter(o -> o.freshAt(now)).toList();
        Comparator<Observation> worstFirst = Comparator.comparingInt((Observation o) -> -o.state().severity())
                .thenComparing(Observation::source);
        var decisive = fresh.stream().filter(o -> o.state() != State.UNKNOWN).sorted(worstFirst).findFirst();
        if (decisive.isPresent()) {
            return new NodeStatus(decisive.get().state(), decisive.get().reason(), sources);
        }
        if (!fresh.isEmpty()) {
            Observation first = fresh.stream().min(Comparator.comparing(Observation::source)).orElseThrow();
            return new NodeStatus(State.UNKNOWN, first.reason(), sources);
        }
        if (observations.isEmpty()) {
            return new NodeStatus(State.UNKNOWN, "sem fonte", sources);
        }
        Observation last = observations.stream().max(Comparator.comparing(Observation::observedAt)).orElseThrow();
        long age = Duration.between(last.observedAt(), now).toSeconds();
        return new NodeStatus(State.UNKNOWN,
                "nenhuma fonte fresca (ultima: " + last.source() + " ha " + age + "s)", sources);
    }
}
