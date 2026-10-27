package com.ludo.engine;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/** Tiny table-driven finite state machine. Invalid transitions throw. */
public final class StateMachine<S extends Enum<S>, T extends Enum<T>> {
    private record Key<S, T>(S from, T trigger) {}
    private record Edge<S>(S to, Runnable action) {}

    private final Map<Key<S, T>, Edge<S>> table = new HashMap<>();
    private final List<BiConsumer<S, S>> observers = new CopyOnWriteArrayList<>();
    private S state;

    public StateMachine(S initial) {
        this.state = initial;
    }

    public StateMachine<S, T> allow(S from, T trigger, S to) {
        return allow(from, trigger, to, null);
    }

    public StateMachine<S, T> allow(S from, T trigger, S to, Runnable action) {
        table.put(new Key<>(from, trigger), new Edge<>(to, action));
        return this;
    }

    public S state() {
        return state;
    }

    public boolean can(T trigger) {
        return table.containsKey(new Key<>(state, trigger));
    }

    public void onTransition(BiConsumer<S, S> observer) {
        observers.add(observer);
    }

    /** The state is updated first, then the edge action runs, then observers are told. */
    public void fire(T trigger) {
        Edge<S> edge = table.get(new Key<>(state, trigger));
        if (edge == null) {
            throw new IllegalStateException("No transition from " + state + " on " + trigger);
        }
        S old = state;
        state = edge.to();
        if (edge.action() != null) edge.action().run();
        for (BiConsumer<S, S> o : observers) o.accept(old, state);
    }
}
