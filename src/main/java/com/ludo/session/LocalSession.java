package com.ludo.session;

import com.ludo.engine.GameEngine;
import com.ludo.engine.GameEvent;
import com.ludo.engine.GameSnapshot;
import com.ludo.engine.PlayerColor;
import com.ludo.engine.TurnPhase;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.IntSupplier;

/**
 * Authoritative session: owns the engine, runs the AI seats, and (optionally) forwards
 * every update to a network server. All engine access happens on one worker thread.
 */
public final class LocalSession implements GameSession {
    public static final long AI_ROLL_DELAY_MS = 1100;
    public static final long AI_MOVE_DELAY_MS = 900;

    private final Map<PlayerColor, SeatType> seats = new EnumMap<>(PlayerColor.class);
    private final Map<PlayerColor, String> names = new EnumMap<>(PlayerColor.class);
    private final GameEngine engine;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ludo-engine");
        t.setDaemon(true);
        return t;
    });
    private final List<SessionListener> listeners = new CopyOnWriteArrayList<>();
    private volatile BiConsumer<List<GameEvent>, GameSnapshot> broadcaster = (e, s) -> {};
    private long aiTicket;
    private volatile boolean closed;
    private volatile long aiRollDelay = AI_ROLL_DELAY_MS;
    private volatile long aiMoveDelay = AI_MOVE_DELAY_MS;

    public LocalSession(Map<PlayerColor, SeatType> seatTypes, Map<PlayerColor, String> seatNames) {
        this(seatTypes, seatNames, null);
    }

    /** {@code die} may be null for a real random die (tests pass a scripted one). */
    public LocalSession(Map<PlayerColor, SeatType> seatTypes, Map<PlayerColor, String> seatNames, IntSupplier die) {
        Set<PlayerColor> active = EnumSet.noneOf(PlayerColor.class);
        for (PlayerColor c : PlayerColor.values()) {
            SeatType t = seatTypes.getOrDefault(c, SeatType.OFF);
            if (t != SeatType.OFF) {
                active.add(c);
                seats.put(c, t);
                names.put(c, seatNames.getOrDefault(c, defaultName(t)));
            }
        }
        this.engine = die == null ? new GameEngine(active) : new GameEngine(active, die);
    }

    private static String defaultName(SeatType t) {
        return switch (t) {
            case AI -> "Computer";
            case NETWORK -> "Waiting...";
            default -> "Player";
        };
    }

    /** Shortens AI think time (used by tests). */
    public void setAiDelays(long rollMs, long moveMs) {
        this.aiRollDelay = rollMs;
        this.aiMoveDelay = moveMs;
    }

    // ------------------------------------------------------------ wiring

    @Override public void addListener(SessionListener l) { listeners.add(l); }

    public void setBroadcaster(BiConsumer<List<GameEvent>, GameSnapshot> b) { this.broadcaster = b; }

    public synchronized Map<PlayerColor, SeatType> seatTypes() {
        return Collections.unmodifiableMap(new EnumMap<>(seats));
    }

    @Override public synchronized Map<PlayerColor, String> seatNames() {
        return Collections.unmodifiableMap(new EnumMap<>(names));
    }

    public synchronized void setName(PlayerColor c, String name) { names.put(c, name); }

    @Override public GameSnapshot snapshot() { return engine.snapshot(); }

    @Override public synchronized Set<PlayerColor> localSeats() {
        Set<PlayerColor> r = EnumSet.noneOf(PlayerColor.class);
        seats.forEach((c, t) -> { if (t == SeatType.HUMAN) r.add(c); });
        return r;
    }

    // ------------------------------------------------------------ commands

    /** Starts the first game and kicks the AI if it moves first. */
    public void begin() {
        exec.execute(() -> publish(engine.newGame()));
    }

    @Override public boolean canRestart() { return true; }

    @Override public void newGame() { begin(); }

    /** Called by the UI (HUMAN seats) or the server (NETWORK seats). AI seats are driven internally. */
    @Override public void roll(PlayerColor seat) {
        exec.execute(() -> {
            if (!humanControlled(seat) || !engine.canRoll(seat)) return;
            publish(engine.roll(seat));
        });
    }

    @Override public void move(PlayerColor seat, int token) {
        exec.execute(() -> {
            if (!humanControlled(seat) || !engine.canMove(seat, token)) return;
            publish(engine.move(seat, token));
        });
    }

    /** A networked player left mid-game: the AI takes the seat over. */
    public void playerDropped(PlayerColor seat) {
        exec.execute(() -> {
            synchronized (this) {
                if (seats.get(seat) != SeatType.NETWORK) return;
                seats.put(seat, SeatType.AI);
                names.put(seat, "Computer");
            }
            publish(new ArrayList<>(List.of(new GameEvent.PlayerDropped(seat))));
        });
    }

    private synchronized boolean humanControlled(PlayerColor seat) {
        SeatType t = seats.get(seat);
        return t == SeatType.HUMAN || t == SeatType.NETWORK;
    }

    private synchronized SeatType seatOf(PlayerColor c) { return seats.get(c); }

    // ------------------------------------------------------------ internals

    private void publish(List<GameEvent> events) {
        if (closed) return;
        GameSnapshot snap = engine.snapshot();
        for (SessionListener l : listeners) l.onUpdate(events, snap);
        broadcaster.accept(events, snap);
        scheduleAi(snap);
    }

    private void scheduleAi(GameSnapshot snap) {
        if (snap.phase() == TurnPhase.GAME_OVER || seatOf(snap.current()) != SeatType.AI) return;
        long ticket = ++aiTicket;
        long delay = snap.phase() == TurnPhase.AWAITING_ROLL ? aiRollDelay : aiMoveDelay;
        exec.schedule(() -> {
            if (ticket != aiTicket || closed) return;
            GameSnapshot s = engine.snapshot();
            PlayerColor c = s.current();
            if (seatOf(c) != SeatType.AI) return;
            if (s.phase() == TurnPhase.AWAITING_ROLL) {
                publish(engine.roll(c));
            } else if (s.phase() == TurnPhase.AWAITING_MOVE) {
                publish(engine.move(c, AiPlayer.choose(s)));
            }
        }, delay, TimeUnit.MILLISECONDS);
    }

    @Override public void close() {
        closed = true;
        exec.shutdownNow();
        for (SessionListener l : listeners) l.onClosed("Game closed");
    }
}
