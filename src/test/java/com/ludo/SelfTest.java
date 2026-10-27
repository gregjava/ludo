package com.ludo;

import com.ludo.engine.*;
import com.ludo.engine.GameEvent.*;
import com.ludo.net.GameServer;
import com.ludo.net.Protocol;
import com.ludo.session.LocalSession;
import com.ludo.session.RemoteSession;
import com.ludo.session.SeatType;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Dependency-free tests: run with  java -cp out:test-out com.ludo.SelfTest */
public final class SelfTest {
    static int failures = 0;
    static int checks = 0;

    static void check(boolean ok, String msg) {
        checks++;
        if (!ok) {
            failures++;
            System.out.println("  FAIL: " + msg);
        }
    }

    public static void main(String[] args) throws Exception {
        geometry();
        rules();
        randomGames();
        protocolRoundTrip();
        network();
        System.out.println(checks + " checks, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ geometry

    static void geometry() {
        System.out.println("geometry");
        check(BoardGeometry.PATH.length == BoardGeometry.TRACK_LENGTH, "path has 52 squares");
        Set<String> seen = new HashSet<>();
        for (int[] p : BoardGeometry.PATH) {
            check(p[0] >= 0 && p[0] < 15 && p[1] >= 0 && p[1] < 15, "path cell in grid");
            check(seen.add(p[0] + "," + p[1]), "path cell unique " + p[0] + "," + p[1]);
        }
        for (PlayerColor c : PlayerColor.values()) {
            check(BoardGeometry.HOME_STRETCH[c.ordinal()].length == 6, c + " stretch has 6 cells");
            for (int[] p : BoardGeometry.HOME_STRETCH[c.ordinal()]) {
                check(seen.add(p[0] + "," + p[1]), "stretch cell does not overlap track " + c);
            }
            check(BoardGeometry.isSafe(c.entry), c + " entry square is safe");
            // consecutive cells along the whole journey must be adjacent (or the 0->1 yard jump)
            for (int s = 1; s < BoardGeometry.HOME; s++) {
                int[] a = BoardGeometry.cell(c, s, 0);
                int[] b = BoardGeometry.cell(c, s + 1, 0);
                int d = Math.abs(a[0] - b[0]) + Math.abs(a[1] - b[1]);
                // the track turns corners via diagonal-looking steps on the 15x15 cross, so allow <= 2
                check(d >= 1 && d <= 2, c + " step " + s + "->" + (s + 1) + " distance " + d);
            }
        }
    }

    // ------------------------------------------------------------------ scripted rules

    static void setTokens(GameEngine e, PlayerColor c, int... steps) throws Exception {
        Field f = GameEngine.class.getDeclaredField("tokens");
        f.setAccessible(true);
        int[] t = (int[]) f.get(e);
        for (int i = 0; i < steps.length; i++) t[c.ordinal() * 4 + i] = steps[i];
    }

    static IntScript script(int... values) {
        return new IntScript(values);
    }

    static final class IntScript implements java.util.function.IntSupplier {
        final int[] v;
        int i;
        IntScript(int[] v) { this.v = v; }
        public int getAsInt() { return v[i++ % v.length]; }
    }

    static void rules() throws Exception {
        System.out.println("rules");
        EnumSet<PlayerColor> rg = EnumSet.of(PlayerColor.RED, PlayerColor.GREEN);

        // need a 6 to leave the yard; non-6 passes the turn
        GameEngine e = new GameEngine(rg, script(3));
        e.newGame();
        var ev = e.roll(PlayerColor.RED);
        check(ev.stream().anyMatch(x -> x instanceof NoLegalMove), "3 with all tokens in yard has no move");
        check(e.current() == PlayerColor.GREEN && e.phase() == TurnPhase.AWAITING_ROLL, "turn passed to green");

        // 6 leaves yard, grants another roll
        e = new GameEngine(rg, script(6, 2));
        e.newGame();
        e.roll(PlayerColor.RED);
        check(e.phase() == TurnPhase.AWAITING_MOVE, "6 -> awaiting move");
        check(e.snapshot().movable().equals(List.of(0, 1, 2, 3)), "all four yard tokens movable on 6");
        e.move(PlayerColor.RED, 2);
        check(e.snapshot().steps(PlayerColor.RED, 2) == 1, "token entered at step 1");
        check(e.current() == PlayerColor.RED && e.phase() == TurnPhase.AWAITING_ROLL, "six keeps the turn");

        // illegal actions are rejected
        GameEngine fe = e;
        boolean threw = false;
        try { fe.roll(PlayerColor.GREEN); } catch (IllegalStateException ex) { threw = true; }
        check(threw, "rolling out of turn throws");
        threw = false;
        try { fe.move(PlayerColor.RED, 0); } catch (IllegalStateException ex) { threw = true; }
        check(threw, "moving while awaiting roll throws");

        // three sixes forfeit
        e = new GameEngine(rg, script(6, 6, 6));
        e.newGame();
        e.roll(PlayerColor.RED); e.move(PlayerColor.RED, 0);
        e.roll(PlayerColor.RED); e.move(PlayerColor.RED, 1);
        ev = e.roll(PlayerColor.RED);
        check(ev.stream().anyMatch(x -> x instanceof Forfeited), "third six forfeits");
        check(e.current() == PlayerColor.GREEN, "forfeit passes turn");
        check(e.snapshot().sixStreak() == 0, "streak reset on pass");

        // capture on a normal square: red at 6 (track 5) +4 -> 10 (track 9); green at 49 is track 9
        e = new GameEngine(rg, script(4));
        setTokens(e, PlayerColor.RED, 6, 0, 0, 0);
        setTokens(e, PlayerColor.GREEN, 49, 0, 0, 0);
        e.roll(PlayerColor.RED);
        ev = e.move(PlayerColor.RED, 0);
        check(ev.stream().anyMatch(x -> x instanceof TokenCaptured c && c.victim() == PlayerColor.GREEN && c.token() == 0), "capture event");
        check(e.snapshot().steps(PlayerColor.GREEN, 0) == 0, "captured token back in yard");

        // no capture on a safe square: red 5 +4 -> 9 (track 8, safe); green 47 is track 8
        e = new GameEngine(rg, script(4));
        setTokens(e, PlayerColor.RED, 5, 0, 0, 0);
        setTokens(e, PlayerColor.GREEN, 47, 0, 0, 0);
        e.roll(PlayerColor.RED);
        ev = e.move(PlayerColor.RED, 0);
        check(ev.stream().noneMatch(x -> x instanceof TokenCaptured), "no capture on safe square");
        check(e.snapshot().steps(PlayerColor.GREEN, 0) == 47, "green untouched on safe square");

        // exact roll needed to finish
        check(GameEngine.legalMoves(new int[]{55, 0, 0, 0}, 3).isEmpty(), "55+3 overshoots home");
        check(GameEngine.legalMoves(new int[]{55, 0, 0, 0}, 2).equals(List.of(0)), "55+2 reaches home");
        check(GameEngine.legalMoves(new int[]{57, 0, 0, 0}, 1).isEmpty(), "finished token cannot move");

        // an unplayable six still earns another roll
        e = new GameEngine(rg, script(6));
        setTokens(e, PlayerColor.RED, 57, 57, 57, 56);
        e.newGame(); // resets, so set again
        setTokens(e, PlayerColor.RED, 57, 57, 57, 56);
        ev = e.roll(PlayerColor.RED);
        check(ev.stream().anyMatch(x -> x instanceof NoLegalMove), "blocked six reported");
        check(e.current() == PlayerColor.RED && e.phase() == TurnPhase.AWAITING_ROLL, "blocked six keeps turn");

        // win
        e = new GameEngine(rg, script(1));
        setTokens(e, PlayerColor.RED, 57, 57, 57, 56);
        e.roll(PlayerColor.RED);
        ev = e.move(PlayerColor.RED, 3);
        check(ev.stream().anyMatch(x -> x instanceof GameWon w && w.color() == PlayerColor.RED), "win event");
        check(e.phase() == TurnPhase.GAME_OVER && e.snapshot().winner() == PlayerColor.RED, "game over");
        e.newGame();
        check(e.phase() == TurnPhase.AWAITING_ROLL && e.snapshot().winner() == null, "restart works");
    }

    // ------------------------------------------------------------------ random full games

    static void randomGames() {
        System.out.println("random games");
        Random rnd = new Random(42);
        int[] wins = new int[4];
        for (int g = 0; g < 2000; g++) {
            Set<PlayerColor> players = (g % 3 == 0) ? EnumSet.allOf(PlayerColor.class)
                : (g % 3 == 1) ? EnumSet.of(PlayerColor.RED, PlayerColor.YELLOW)
                : EnumSet.of(PlayerColor.GREEN, PlayerColor.BLUE, PlayerColor.YELLOW);
            GameEngine e = new GameEngine(players, () -> 1 + rnd.nextInt(6));
            e.newGame();
            int guard = 0;
            while (e.phase() != TurnPhase.GAME_OVER && guard++ < 200_000) {
                GameSnapshot s = e.snapshot();
                check(players.contains(s.current()), "current is an active seat");
                if (s.phase() == TurnPhase.AWAITING_ROLL) {
                    e.roll(s.current());
                } else {
                    List<Integer> m = s.movable();
                    check(!m.isEmpty(), "awaiting move implies a legal move");
                    e.move(s.current(), m.get(rnd.nextInt(m.size())));
                }
                for (int v : e.snapshot().rawTokens()) {
                    if (v < 0 || v > 57) { check(false, "token out of range " + v); break; }
                }
            }
            check(guard < 200_000, "game terminates");
            GameSnapshot end = e.snapshot();
            check(end.winner() != null && end.finishedCount(end.winner()) == 4, "winner has 4 home");
            if (end.winner() != null) wins[end.winner().ordinal()]++;
        }
        System.out.println("  wins R/G/Y/B = " + Arrays.toString(wins));
    }

    // ------------------------------------------------------------------ protocol

    static void protocolRoundTrip() {
        System.out.println("protocol");
        List<GameEvent> events = List.of(
            new GameStarted(PlayerColor.RED), new DiceRolled(PlayerColor.GREEN, 6), new NoLegalMove(PlayerColor.BLUE, 2),
            new Forfeited(PlayerColor.YELLOW), new TokenMoved(PlayerColor.RED, 3, 12, 18),
            new TokenCaptured(PlayerColor.RED, PlayerColor.BLUE, 1), new TokenFinished(PlayerColor.GREEN, 0),
            new TurnChanged(PlayerColor.YELLOW, true), new GameWon(PlayerColor.RED), new PlayerDropped(PlayerColor.BLUE));
        check(Protocol.decodeEvents(Protocol.encodeEvents(events)).equals(events), "events round-trip");
        check(Protocol.decodeEvents("").isEmpty(), "empty events");

        int[] t = new int[16];
        for (int i = 0; i < 16; i++) t[i] = i * 3 % 58;
        GameSnapshot s = new GameSnapshot(PlayerColor.YELLOW, 4, 2, TurnPhase.AWAITING_MOVE, null,
            EnumSet.of(PlayerColor.RED, PlayerColor.YELLOW), t);
        GameSnapshot r = Protocol.decodeSnapshot(Protocol.encodeSnapshot(s));
        check(r.current() == s.current() && r.dice() == 4 && r.sixStreak() == 2 && r.phase() == s.phase()
            && r.winner() == null && r.active().equals(s.active()) && Arrays.equals(r.rawTokens(), t), "snapshot round-trip");

        Map<PlayerColor, String> names = new EnumMap<>(PlayerColor.class);
        names.put(PlayerColor.RED, "Ada;|=,:x");
        names.put(PlayerColor.BLUE, "Bob");
        Map<PlayerColor, String> back = Protocol.decodeRoster(Protocol.encodeRoster(names));
        check(back.get(PlayerColor.BLUE).equals("Bob") && !back.get(PlayerColor.RED).contains("|"), "roster sanitised");
    }

    // ------------------------------------------------------------------ real sockets

    static boolean waitFor(BooleanSupplier c, long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (c.getAsBoolean()) return true;
            Thread.sleep(20);
        }
        return c.getAsBoolean();
    }

    static void network() throws Exception {
        System.out.println("network");
        Map<PlayerColor, SeatType> seats = new EnumMap<>(PlayerColor.class);
        seats.put(PlayerColor.RED, SeatType.HUMAN);
        seats.put(PlayerColor.YELLOW, SeatType.NETWORK);
        Map<PlayerColor, String> names = new EnumMap<>(PlayerColor.class);
        names.put(PlayerColor.RED, "Host");

        // red rolls 3 (blocked, passes to yellow); yellow rolls 6, then 2
        LocalSession session = new LocalSession(seats, names, script(3, 6, 2, 2, 2));
        session.setAiDelays(10, 10);
        CountDownLatch full = new CountDownLatch(1);
        List<String> lobby = Collections.synchronizedList(new ArrayList<>());
        GameServer server = new GameServer(session, lobby::add, full::countDown);
        server.start(0);

        RemoteSession client = RemoteSession.open("127.0.0.1", server.port(), "Guest");
        check(client.seat() == PlayerColor.YELLOW, "client got the network seat");
        CountDownLatch started = new CountDownLatch(1);
        List<GameEvent> clientEvents = Collections.synchronizedList(new ArrayList<>());
        client.addListener(new com.ludo.session.SessionListener() {
            public void onUpdate(List<GameEvent> events, GameSnapshot snapshot) { clientEvents.addAll(events); }
        });
        client.setStartHandler(started::countDown);
        client.startReading();

        check(full.await(3, TimeUnit.SECONDS), "server reports all seats filled");
        check(started.await(3, TimeUnit.SECONDS), "client received START");
        check(client.snapshot() != null && client.seatNames().get(PlayerColor.YELLOW).equals("Guest"), "roster has guest name");
        session.begin();
        check(waitFor(() -> clientEvents.stream().anyMatch(x -> x instanceof GameStarted), 3000), "client saw GameStarted");

        // client tries to roll out of turn: ignored
        client.roll(PlayerColor.YELLOW);
        Thread.sleep(200);
        check(session.snapshot().current() == PlayerColor.RED && session.snapshot().dice() == 0, "out-of-turn roll ignored");

        session.roll(PlayerColor.RED);
        check(waitFor(() -> client.snapshot().current() == PlayerColor.YELLOW, 3000), "turn reached yellow on client");
        check(clientEvents.stream().anyMatch(x -> x instanceof DiceRolled d && d.by() == PlayerColor.RED && d.value() == 3), "client saw red's roll");

        client.roll(PlayerColor.YELLOW);
        check(waitFor(() -> client.snapshot().phase() == TurnPhase.AWAITING_MOVE, 3000), "client rolled a 6 and may move");
        client.move(PlayerColor.YELLOW, 1);
        check(waitFor(() -> client.snapshot().steps(PlayerColor.YELLOW, 1) == 1, 3000), "client token entered the board");

        // invalid move request (token 1 already out, 6 consumed): host ignores, state unchanged
        client.move(PlayerColor.YELLOW, 3);
        Thread.sleep(200);
        check(session.snapshot().steps(PlayerColor.YELLOW, 3) == 0, "invalid move ignored");

        // disconnect mid-game: seat is taken over by the AI
        client.close();
        check(waitFor(() -> session.seatTypes().get(PlayerColor.YELLOW) == SeatType.AI, 3000), "dropped player replaced by AI");
        session.close();
        server.close();
    }
}
