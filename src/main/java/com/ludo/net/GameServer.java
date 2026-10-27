package com.ludo.net;

import com.ludo.engine.GameEvent;
import com.ludo.engine.GameSnapshot;
import com.ludo.engine.PlayerColor;
import com.ludo.session.LocalSession;
import com.ludo.session.SeatType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Host side of a network game. Clients are assigned the NETWORK seats in colour order.
 * When every NETWORK seat is filled the game starts. The host's {@link LocalSession} stays
 * authoritative: clients only send ROLL / MOVE requests, which are validated by the engine.
 */
public final class GameServer implements AutoCloseable {
    private final LocalSession session;
    private final Deque<PlayerColor> freeSeats = new ArrayDeque<>();
    private final Map<PlayerColor, Client> clients = new EnumMap<>(PlayerColor.class);
    private final int needed;
    private final Consumer<String> lobbyStatus;
    private final Runnable onAllJoined;
    private ServerSocket server;
    private boolean started;
    private volatile boolean closed;

    public GameServer(LocalSession session, Consumer<String> lobbyStatus, Runnable onAllJoined) {
        this.session = session;
        this.lobbyStatus = lobbyStatus;
        this.onAllJoined = onAllJoined;
        session.seatTypes().forEach((c, t) -> { if (t == SeatType.NETWORK) freeSeats.add(c); });
        this.needed = freeSeats.size();
        session.setBroadcaster(this::broadcastUpdate);
    }

    public int port() { return server.getLocalPort(); }

    public void start(int port) throws IOException {
        server = new ServerSocket(port);
        Thread t = new Thread(this::acceptLoop, "ludo-accept");
        t.setDaemon(true);
        t.start();
        lobbyStatus.accept("Waiting for players: 0 / " + needed);
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket s = server.accept();
                Thread t = new Thread(() -> handle(s), "ludo-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket socket) {
        Client client = null;
        PlayerColor seat = null;
        try (socket;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8)) {

            String hello = in.readLine();
            if (hello == null || !hello.startsWith("HELLO")) return;
            String name = Protocol.sanitize(hello.length() > 6 ? hello.substring(6) : "");
            client = new Client(out);

            boolean fullNow = false;
            synchronized (this) {
                if (started || freeSeats.isEmpty()) {
                    client.send("REJECT|Game is full or already started");
                    return;
                }
                seat = freeSeats.poll();
                clients.put(seat, client);
                session.setName(seat, name);
                client.send("WELCOME|" + seat.code);
                fullNow = freeSeats.isEmpty();
                if (fullNow) started = true;
                broadcastLobby();
                if (fullNow) {
                    String start = "START|" + Protocol.encodeRoster(session.seatNames()) + "|"
                        + Protocol.encodeSnapshot(session.snapshot());
                    for (Client c : clients.values()) c.send(start);
                }
            }
            if (fullNow) onAllJoined.run();

            String line;
            while (!closed && (line = in.readLine()) != null) {
                if (line.equals("ROLL")) {
                    session.roll(seat);
                } else if (line.startsWith("MOVE|")) {
                    try {
                        session.move(seat, Integer.parseInt(line.substring(5).trim()));
                    } catch (NumberFormatException ignored) {
                        // malformed request, ignore
                    }
                } else if (line.equals("BYE")) {
                    break;
                }
            }
        } catch (IOException ignored) {
            // connection lost
        } finally {
            if (seat != null) onLeft(seat, client);
        }
    }

    private synchronized void onLeft(PlayerColor seat, Client who) {
        if (clients.get(seat) != who) return;
        clients.remove(seat);
        if (closed) return;
        if (started) {
            session.playerDropped(seat);
        } else {
            freeSeats.addFirst(seat);
            broadcastLobby();
        }
    }

    private void broadcastLobby() {
        int joined = needed - freeSeats.size();
        lobbyStatus.accept("Waiting for players: " + joined + " / " + needed);
        for (Client c : new ArrayList<>(clients.values())) c.send("WAIT|" + joined + "|" + needed);
    }

    private void broadcastUpdate(List<GameEvent> events, GameSnapshot snap) {
        String msg = "UPDATE|" + Protocol.encodeEvents(events) + "|" + Protocol.encodeSnapshot(snap);
        List<Client> copy;
        synchronized (this) { copy = new ArrayList<>(clients.values()); }
        for (Client c : copy) c.send(msg);
    }

    @Override
    public void close() {
        closed = true;
        List<Client> copy;
        synchronized (this) { copy = new ArrayList<>(clients.values()); }
        for (Client c : copy) c.send("CLOSED|Host closed the game");
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    private static final class Client {
        private final PrintWriter out;
        Client(PrintWriter out) { this.out = out; }
        synchronized void send(String line) { out.println(line); }
    }
}
