package com.ludo.engine;

/**
 * States of the turn state machine.
 * AWAITING_ROLL, AWAITING_MOVE and GAME_OVER are "stable" (the engine waits for input);
 * RESOLVING and ADVANCING are transient and are pumped through automatically.
 */
public enum TurnPhase {
    AWAITING_ROLL,
    AWAITING_MOVE,
    RESOLVING,
    ADVANCING,
    GAME_OVER;

    public boolean isStable() {
        return this == AWAITING_ROLL || this == AWAITING_MOVE || this == GAME_OVER;
    }
}
