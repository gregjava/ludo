package com.ludo.engine;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.IntSupplier;

import static com.ludo.engine.BoardGeometry.HOME;
import static com.ludo.engine.BoardGeometry.TOKENS_PER_PLAYER;
import static com.ludo.engine.TurnPhase.*;
import static com.ludo.engine.TurnTrigger.*;

/**
 * Headless, authoritative Ludo rules engine. No UI or network code in here.
 *
 * Turn flow (see the transition table in the constructor):
 *
 *   AWAITING_ROLL --ROLLED_MOVABLE--> AWAITING_MOVE --MOVED--> RESOLVING --CONTINUE--> ADVANCING
 *   AWAITING_ROLL --ROLLED_BLOCKED--> ADVANCING                 RESOLVING --WON-----> GAME_OVER
 *   AWAITING_ROLL --ROLLED_FORFEIT--> ADVANCING                 ADVANCING --PASS/KEEP--> AWAITING_ROLL
 *
 * RESOLVING and ADVANCING are transient; {@link #pump()} drives them so callers only
 * ever observe AWAITING_ROLL, AWAITING_MOVE or GAME_OVER.
 *
 * Rules are identical to the original web version: a 6 is needed to leave the yard,
 * exact roll to finish, captures on non-safe track squares, a 6 earns another roll
 * (also when it cannot be played), and three 6s in a row forfeit the turn.
 */
public final class GameEngine {
    private final Set<PlayerColor> active;
    private final IntSupplier die;
    private final int[] tokens = new int[PlayerColor.values().length * TOKENS_PER_PLAYER];
    private final StateMachine<TurnPhase, TurnTrigger> fsm = new StateMachine<>(AWAITING_ROLL);

    private PlayerColor current;
    private int dice;
    private int sixStreak;
    private PlayerColor winner;
    private boolean keepTurn;
    private List<GameEvent> out = new ArrayList<>();

    public GameEngine(Set<PlayerColor> active) {
        this(active, () -> 1 + (int) (Math.random() * 6));
    }

    /** The die source is injectable so tests can script rolls. */
    public GameEngine(Set<PlayerColor> active, IntSupplier die) {
        if (active.size() < 2) throw new IllegalArgumentException("Need at least two players");
        this.active = EnumSet.copyOf(active);
        this.die = die;
        this.current = firstActive();
        buildTransitions();
    }

    private void buildTransitions() {
        fsm.allow(AWAITING_ROLL, ROLLED_MOVABLE, AWAITING_MOVE)
           .allow(AWAITING_ROLL, ROLLED_BLOCKED, ADVANCING)
           .allow(AWAITING_ROLL, ROLLED_FORFEIT, ADVANCING)
           .allow(AWAITING_MOVE, MOVED, RESOLVING)
           .allow(RESOLVING, WON, GAME_OVER)
           .allow(RESOLVING, CONTINUE, ADVANCING)
           .allow(ADVANCING, PASS_TURN, AWAITING_ROLL, this::passTurn)
           .allow(ADVANCING, KEEP_TURN, AWAITING_ROLL, this::keepSameTurn);
        for (TurnPhase p : TurnPhase.values()) {
            fsm.allow(p, RESET, AWAITING_ROLL, this::resetBoard);
        }
    }

    // ------------------------------------------------------------------ queries

    public synchronized TurnPhase phase() { return fsm.state(); }
    public synchronized PlayerColor current() { return current; }

    public synchronized boolean canRoll(PlayerColor who) {
        return fsm.state() == AWAITING_ROLL && who == current;
    }

    public synchronized boolean canMove(PlayerColor who, int token) {
        return fsm.state() == AWAITING_MOVE && who == current
            && legalMoves(stepsOf(who), dice).contains(token);
    }

    public synchronized GameSnapshot snapshot() {
        return new GameSnapshot(current, dice, sixStreak, fsm.state(), winner, active, tokens);
    }

    // ------------------------------------------------------------------ commands

    /** Starts (or restarts) a game. */
    public synchronized List<GameEvent> newGame() {
        out = new ArrayList<>();
        fsm.fire(RESET);
        out.add(new GameEvent.GameStarted(current));
        return drain();
    }

    public synchronized List<GameEvent> roll(PlayerColor who) {
        if (!canRoll(who)) throw new IllegalStateException(who + " cannot roll in " + fsm.state());
        out = new ArrayList<>();

        int value = die.getAsInt();
        dice = value;
        sixStreak = (value == 6) ? sixStreak + 1 : 0;
        out.add(new GameEvent.DiceRolled(who, value));

        if (sixStreak == 3) {
            out.add(new GameEvent.Forfeited(who));
            keepTurn = false;
            fsm.fire(ROLLED_FORFEIT);
        } else if (legalMoves(stepsOf(who), value).isEmpty()) {
            out.add(new GameEvent.NoLegalMove(who, value));
            keepTurn = (value == 6); // original behaviour: an unplayable 6 still earns a re-roll
            fsm.fire(ROLLED_BLOCKED);
        } else {
            fsm.fire(ROLLED_MOVABLE);
        }
        pump();
        return drain();
    }

    public synchronized List<GameEvent> move(PlayerColor who, int token) {
        if (!canMove(who, token)) {
            throw new IllegalStateException(who + " cannot move token " + token + " in " + fsm.state());
        }
        out = new ArrayList<>();

        int base = who.ordinal() * TOKENS_PER_PLAYER;
        int from = tokens[base + token];
        int to = (from == 0) ? 1 : from + dice;
        tokens[base + token] = to;
        out.add(new GameEvent.TokenMoved(who, token, from, to));

        captureAt(who, to);
        if (to == HOME) out.add(new GameEvent.TokenFinished(who, token));

        keepTurn = (dice == 6);
        boolean won = true;
        for (int i = 0; i < TOKENS_PER_PLAYER; i++) won &= tokens[base + i] == HOME;
        if (won) {
            winner = who;
            dice = 0;
            out.add(new GameEvent.GameWon(who));
        }
        fsm.fire(MOVED);
        pump();
        return drain();
    }

    // ------------------------------------------------------------------ internals

    /** Sends every opposing token on the landing square home, unless it is a safe square. */
    private void captureAt(PlayerColor mover, int steps) {
        int track = BoardGeometry.trackIndex(mover, steps);
        if (track < 0 || BoardGeometry.isSafe(track)) return;
        for (PlayerColor other : PlayerColor.values()) {
            if (other == mover) continue;
            for (int j = 0; j < TOKENS_PER_PLAYER; j++) {
                int idx = other.ordinal() * TOKENS_PER_PLAYER + j;
                if (BoardGeometry.trackIndex(other, tokens[idx]) == track) {
                    tokens[idx] = 0;
                    out.add(new GameEvent.TokenCaptured(mover, other, j));
                }
            }
        }
    }

    /** Drives the transient states until the machine is waiting for input again. */
    private void pump() {
        while (!fsm.state().isStable()) {
            switch (fsm.state()) {
                case RESOLVING -> fsm.fire(winner != null ? WON : CONTINUE);
                case ADVANCING -> fsm.fire(keepTurn ? KEEP_TURN : PASS_TURN);
                default -> throw new IllegalStateException(fsm.state().name());
            }
        }
    }

    private void passTurn() {
        PlayerColor next = current.next();
        while (!active.contains(next)) next = next.next();
        current = next;
        dice = 0;
        sixStreak = 0;
        out.add(new GameEvent.TurnChanged(current, false));
    }

    private void keepSameTurn() {
        dice = 0;
        out.add(new GameEvent.TurnChanged(current, true));
    }

    private void resetBoard() {
        java.util.Arrays.fill(tokens, 0);
        current = firstActive();
        dice = 0;
        sixStreak = 0;
        winner = null;
        keepTurn = false;
    }

    private PlayerColor firstActive() {
        for (PlayerColor c : PlayerColor.values()) if (active.contains(c)) return c;
        throw new IllegalStateException();
    }

    private int[] stepsOf(PlayerColor c) {
        int[] s = new int[TOKENS_PER_PLAYER];
        System.arraycopy(tokens, c.ordinal() * TOKENS_PER_PLAYER, s, 0, TOKENS_PER_PLAYER);
        return s;
    }

    private List<GameEvent> drain() {
        List<GameEvent> r = out;
        out = new ArrayList<>();
        return r;
    }

    /** Token indices that can legally move with this die value (pure function, no engine state). */
    public static List<Integer> legalMoves(int[] steps, int die) {
        List<Integer> list = new ArrayList<>();
        for (int i = 0; i < steps.length; i++) {
            int s = steps[i];
            if (s == 0) {
                if (die == 6) list.add(i);
            } else if (s < HOME && s + die <= HOME) {
                list.add(i);
            }
        }
        return list;
    }
}
