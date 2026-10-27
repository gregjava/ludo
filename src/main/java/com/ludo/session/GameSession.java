package com.ludo.session;

import com.ludo.engine.GameSnapshot;
import com.ludo.engine.PlayerColor;

import java.util.Map;
import java.util.Set;

/** What the UI talks to. Implemented by a local (host) session and a remote (client) session. */
public interface GameSession extends AutoCloseable {
    void addListener(SessionListener listener);

    GameSnapshot snapshot();

    /** Seats controlled from this device. */
    Set<PlayerColor> localSeats();

    /** Display names per active seat. */
    Map<PlayerColor, String> seatNames();

    void roll(PlayerColor seat);

    void move(PlayerColor seat, int token);

    boolean canRestart();

    void newGame();

    @Override
    void close();
}
