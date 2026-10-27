package com.ludo.session;

import com.ludo.engine.GameEvent;
import com.ludo.engine.GameSnapshot;
import com.ludo.engine.PlayerColor;
import com.ludo.net.Protocol;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Client side of a network game: sends ROLL/MOVE requests, renders whatever the host says. */
public final class RemoteSession implements GameSession {
    private final Socket socket;
    private final BufferedReader in;
    private final PrintWriter out;
    private final PlayerColor mySeat;
    private final List<SessionListener> listeners = new CopyOnWriteArrayList<>();
    private volatile GameSnapshot snapshot;
    private volatile Map<PlayerColor, String> names = new EnumMap<>(PlayerColor.class);
    private volatile Consumer<String> lobbyHandler = s -> {};
    private volatile Runnable startHandler = () -> {};
    private volatile boolean closed;

    private RemoteSession(Socket socket, BufferedReader in, PrintWriter out, PlayerColor seat) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.mySeat = seat;
    }

    /** Connects, says hello and waits for the host to assign a seat. */
    public static RemoteSession open(String host, int port, String name) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true, StandardCharsets.UTF_8);
        out.println("HELLO|" + Protocol.sanitize(name));
        String reply = in.readLine();
        if (reply == null) {
            socket.close();
            throw new IOException("Host closed the connection");
        }
        if (reply.startsWith("REJECT|")) {
            socket.close();
            throw new IOException(reply.substring(7));
        }
        if (!reply.startsWith("WELCOME|")) {
            socket.close();
            throw new IOException("Unexpected reply from host: " + reply);
        }
        return new RemoteSession(socket, in, out, PlayerColor.ofCode(reply.charAt(8)));
    }

    public PlayerColor seat() { return mySeat; }

    /** Set before {@link #startReading()}. */
    public void setLobbyHandler(Consumer<String> h) { this.lobbyHandler = h; }
    public void setStartHandler(Runnable r) { this.startHandler = r; }

    public void startReading() {
        Thread t = new Thread(this::readLoop, "ludo-remote");
        t.setDaemon(true);
        t.start();
    }

    private void readLoop() {
        String reason = "Connection lost";
        try {
            String line;
            while ((line = in.readLine()) != null) {
                String[] f = line.split("\\|", -1);
                switch (f[0]) {
                    case "WAIT" -> lobbyHandler.accept("Waiting for players: " + f[1] + " / " + f[2]);
                    case "START" -> {
                        names = Protocol.decodeRoster(f[1]);
                        snapshot = Protocol.decodeSnapshot(f[2]);
                        startHandler.run();
                    }
                    case "UPDATE" -> {
                        List<GameEvent> events = Protocol.decodeEvents(f[1]);
                        GameSnapshot snap = Protocol.decodeSnapshot(f[2]);
                        snapshot = snap;
                        for (SessionListener l : listeners) l.onUpdate(events, snap);
                    }
                    case "CLOSED" -> {
                        reason = f.length > 1 ? f[1] : "Host closed the game";
                        closed = true;
                    }
                    default -> { /* ignore unknown */ }
                }
                if (closed) break;
            }
        } catch (IOException | RuntimeException e) {
            // fall through to notify
        }
        boolean wasClosed = closed;
        closed = true;
        for (SessionListener l : listeners) l.onClosed(wasClosed ? reason : "Connection lost");
    }

    @Override public void addListener(SessionListener l) { listeners.add(l); }
    @Override public GameSnapshot snapshot() { return snapshot; }
    @Override public Set<PlayerColor> localSeats() { return EnumSet.of(mySeat); }
    @Override public Map<PlayerColor, String> seatNames() { return Collections.unmodifiableMap(names); }
    @Override public void roll(PlayerColor seat) { if (seat == mySeat) out.println("ROLL"); }
    @Override public void move(PlayerColor seat, int token) { if (seat == mySeat) out.println("MOVE|" + token); }
    @Override public boolean canRestart() { return false; }
    @Override public void newGame() { /* only the host can restart */ }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            out.println("BYE");
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closed
        }
    }
}
