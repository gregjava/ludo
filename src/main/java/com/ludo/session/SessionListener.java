package com.ludo.session;

import com.ludo.engine.GameEvent;
import com.ludo.engine.GameSnapshot;

import java.util.List;

/** Callbacks may arrive on any thread; UI code must hop to the FX thread. */
public interface SessionListener {
    void onUpdate(List<GameEvent> events, GameSnapshot snapshot);

    default void onClosed(String reason) {}
}
