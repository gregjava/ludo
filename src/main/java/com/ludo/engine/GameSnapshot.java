package com.ludo.engine;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Immutable view of the game, safe to hand to the UI or serialise. */
public final class GameSnapshot {
    private final PlayerColor current;
    private final int dice;
    private final int sixStreak;
    private final TurnPhase phase;
    private final PlayerColor winner;
    private final Set<PlayerColor> active;
    private final int[] tokens; // [color*4 + index]

    public GameSnapshot(PlayerColor current, int dice, int sixStreak, TurnPhase phase,
                        PlayerColor winner, Set<PlayerColor> active, int[] tokens) {
        this.current = current;
        this.dice = dice;
        this.sixStreak = sixStreak;
        this.phase = phase;
        this.winner = winner;
        this.active = EnumSet.copyOf(active);
        this.tokens = tokens.clone();
    }

    public PlayerColor current() { return current; }
    public int dice() { return dice; }
    public int sixStreak() { return sixStreak; }
    public TurnPhase phase() { return phase; }
    public PlayerColor winner() { return winner; }
    public Set<PlayerColor> active() { return EnumSet.copyOf(active); }
    public boolean isActive(PlayerColor c) { return active.contains(c); }
    public int[] rawTokens() { return tokens.clone(); }

    public int steps(PlayerColor c, int index) {
        return tokens[c.ordinal() * BoardGeometry.TOKENS_PER_PLAYER + index];
    }

    public int[] stepsOf(PlayerColor c) {
        int[] out = new int[BoardGeometry.TOKENS_PER_PLAYER];
        for (int i = 0; i < out.length; i++) out[i] = steps(c, i);
        return out;
    }

    public int finishedCount(PlayerColor c) {
        int n = 0;
        for (int i = 0; i < BoardGeometry.TOKENS_PER_PLAYER; i++) {
            if (steps(c, i) == BoardGeometry.HOME) n++;
        }
        return n;
    }

    /** Token indices the current player may move now (empty unless AWAITING_MOVE). */
    public List<Integer> movable() {
        if (phase != TurnPhase.AWAITING_MOVE) return List.of();
        return GameEngine.legalMoves(stepsOf(current), dice);
    }

    public List<Integer> movableFor(PlayerColor c) {
        return c == current ? movable() : new ArrayList<>();
    }
}
