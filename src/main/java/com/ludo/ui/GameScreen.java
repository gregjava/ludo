package com.ludo.ui;

import com.ludo.engine.GameEvent;
import com.ludo.engine.GameEvent.*;
import com.ludo.engine.GameSnapshot;
import com.ludo.engine.PlayerColor;
import com.ludo.engine.TurnPhase;
import com.ludo.session.GameSession;
import com.ludo.session.SessionListener;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** In-game screen: board, dice, turn status, player list, log. Talks only to a {@link GameSession}. */
public final class GameScreen extends BorderPane {
    private static final long REQUEST_GRACE_NS = 1_500_000_000L;

    private final GameSession session;
    private final Runnable onExit;
    private final BoardView board = new BoardView();
    private final DiceView dice = new DiceView(84);
    private final Button rollButton = new Button("Roll dice");
    private final Label status = new Label();
    private final Label turnLabel = new Label();
    private final Circle turnDot = new Circle(9);
    private final ListView<String> log = new ListView<>();
    private final Map<PlayerColor, HBox> rows = new EnumMap<>(PlayerColor.class);
    private final Map<PlayerColor, Label> homeLabels = new EnumMap<>(PlayerColor.class);
    private final VBox overlay = new VBox(14);
    private final Label overlayTitle = new Label();
    private final AnimationTimer loop;

    private GameSnapshot snap;
    private long requestBlockedUntil;
    private boolean disposed;

    public GameScreen(GameSession session, Runnable onExit) {
        this.session = session;
        this.onExit = onExit;
        this.snap = session.snapshot();
        getStyleClass().add("game-root");

        board.setSnapshot(snap);
        board.setOnTokenClicked(this::onTokenClicked);

        StackPane boardHolder = new StackPane(board, buildOverlay());
        boardHolder.setPadding(new Insets(16));
        setCenter(boardHolder);
        setRight(buildSidePanel());

        dice.setOnMouseClicked(e -> onRollClicked());
        rollButton.setOnAction(e -> onRollClicked());

        session.addListener(new SessionListener() {
            @Override public void onUpdate(List<GameEvent> events, GameSnapshot s) {
                Platform.runLater(() -> handleUpdate(events, s));
            }

            @Override public void onClosed(String reason) {
                Platform.runLater(() -> handleClosed(reason));
            }
        });

        refreshPlayers();
        refreshStatus();

        loop = new javafx.animation.AnimationTimer() {
            long last;

            @Override public void handle(long now) {
                if (last == 0) last = now;
                double dt = Math.min(0.05, (now - last) / 1e9);
                last = now;
                board.render(dt);
                refreshInteraction();
            }
        };
        loop.start();
    }

    // ------------------------------------------------------------------ layout

    private Region buildOverlay() {
        overlay.setAlignment(Pos.CENTER);
        overlay.getStyleClass().add("overlay");
        overlay.setMaxSize(360, 200);
        overlayTitle.getStyleClass().add("overlay-title");
        Button again = new Button("Play again");
        again.getStyleClass().add("primary");
        again.setOnAction(e -> session.newGame());
        again.setVisible(session.canRestart());
        again.setManaged(session.canRestart());
        Button menu = new Button("Back to menu");
        menu.setOnAction(e -> exit());
        overlay.getChildren().addAll(overlayTitle, again, menu);
        overlay.setVisible(false);
        return overlay;
    }

    private Region buildSidePanel() {
        VBox side = new VBox(14);
        side.getStyleClass().add("side");
        side.setPrefWidth(290);
        side.setPadding(new Insets(16));

        turnDot.setStroke(Color.WHITE);
        HBox turnBox = new HBox(10, turnDot, turnLabel);
        turnBox.setAlignment(Pos.CENTER_LEFT);
        turnLabel.getStyleClass().add("turn-label");

        dice.setCursor(javafx.scene.Cursor.HAND);
        rollButton.getStyleClass().add("primary");
        rollButton.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(rollButton, Priority.ALWAYS);
        VBox rollBox = new VBox(10, dice, rollButton);
        rollBox.setAlignment(Pos.CENTER);

        status.setWrapText(true);
        status.getStyleClass().add("status");

        VBox players = new VBox(6);
        for (PlayerColor c : PlayerColor.values()) {
            if (!snap.isActive(c)) continue;
            Circle dot = new Circle(8, Palette.base(c));
            dot.setStroke(Palette.dark(c));
            String name = session.seatNames().getOrDefault(c, c.display());
            Label n = new Label(name + (session.localSeats().contains(c) ? "  (you)" : ""));
            n.getStyleClass().add("player-name");
            Label home = new Label();
            home.getStyleClass().add("player-home");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox row = new HBox(10, dot, n, spacer, home);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(6, 10, 6, 10));
            row.getStyleClass().add("player-row");
            rows.put(c, row);
            homeLabels.put(c, home);
            players.getChildren().add(row);
        }

        log.getStyleClass().add("log");
        log.setFocusTraversable(false);
        log.setMouseTransparent(true);
        VBox.setVgrow(log, Priority.ALWAYS);

        Button menu = new Button("Quit to menu");
        menu.setMaxWidth(Double.MAX_VALUE);
        menu.setOnAction(e -> exit());

        side.getChildren().addAll(turnBox, rollBox, status, players, log, menu);
        return side;
    }

    // ------------------------------------------------------------------ input

    private boolean idle() {
        return !dice.isRolling() && !board.isAnimating();
    }

    private boolean myTurn() {
        return snap != null && snap.phase() != TurnPhase.GAME_OVER && session.localSeats().contains(snap.current());
    }

    private boolean canAct() {
        return idle() && myTurn() && System.nanoTime() >= requestBlockedUntil;
    }

    private void onRollClicked() {
        if (!canAct() || snap.phase() != TurnPhase.AWAITING_ROLL) return;
        requestBlockedUntil = System.nanoTime() + REQUEST_GRACE_NS;
        session.roll(snap.current());
    }

    private void onTokenClicked(int token) {
        if (!canAct() || snap.phase() != TurnPhase.AWAITING_MOVE) return;
        requestBlockedUntil = System.nanoTime() + REQUEST_GRACE_NS;
        session.move(snap.current(), token);
    }

    private void refreshInteraction() {
        boolean act = canAct();
        rollButton.setDisable(!(act && snap.phase() == TurnPhase.AWAITING_ROLL));
        if (act && snap.phase() == TurnPhase.AWAITING_MOVE) {
            board.setHighlight(snap.current(), new HashSet<>(snap.movable()));
        } else {
            board.setHighlight(snap.current(), Set.of());
        }
        boolean over = snap.phase() == TurnPhase.GAME_OVER && idle();
        if (over != overlay.isVisible()) {
            if (over) overlayTitle.setText(snap.winner().display().toUpperCase() + " WINS!");
            overlay.setVisible(over);
        }
        refreshStatus();
    }

    // ------------------------------------------------------------------ updates

    private void handleUpdate(List<GameEvent> events, GameSnapshot s) {
        if (disposed) return;
        requestBlockedUntil = 0;
        for (GameEvent e : events) describe(e);
        snap = s;
        board.setSnapshot(s);
        refreshPlayers();
    }

    private void describe(GameEvent e) {
        switch (e) {
            case GameStarted g -> {
                log.getItems().clear();
                addLog("New game. " + g.first().display() + " starts.");
                dice.setValue(0);
            }
            case DiceRolled d -> {
                dice.setTint(Palette.base(d.by()));
                dice.roll(d.value(), null);
                addLog(d.by().display() + " rolled a " + d.value());
            }
            case NoLegalMove n -> addLog(n.by().display() + " has no legal move");
            case Forfeited f -> addLog("Three sixes in a row: " + f.by().display() + " loses the turn");
            case TokenMoved m -> { /* the board animates this from the snapshot */ }
            case TokenCaptured c -> addLog(c.by().display() + " captured " + c.victim().display() + "'s token #" + (c.token() + 1));
            case TokenFinished h -> addLog(h.color().display() + " token #" + (h.token() + 1) + " reached home");
            case TurnChanged t -> { if (t.extraTurn()) addLog(t.color().display() + " rolls again"); }
            case GameWon w -> addLog(w.color().display() + " wins the game!");
            case PlayerDropped p -> addLog(p.color().display() + " left; the computer takes over");
        }
    }

    private void addLog(String text) {
        log.getItems().add(text);
        if (log.getItems().size() > 200) log.getItems().remove(0);
        log.scrollTo(log.getItems().size() - 1);
    }

    private void refreshPlayers() {
        for (PlayerColor c : rows.keySet()) {
            homeLabels.get(c).setText(snap.finishedCount(c) + " / 4 home");
            boolean turn = snap.phase() != TurnPhase.GAME_OVER && c == snap.current();
            rows.get(c).setStyle(turn ? "-fx-background-color: rgba(255,255,255,0.16); -fx-background-radius: 8;" : "");
        }
        turnDot.setFill(Palette.base(snap.current()));
    }

    private void refreshStatus() {
        String turnText;
        String msg;
        PlayerColor cur = snap.current();
        if (snap.phase() == TurnPhase.GAME_OVER) {
            turnText = snap.winner().display() + " won";
            msg = "Game over.";
        } else {
            boolean mine = session.localSeats().contains(cur);
            turnText = cur.display() + "'s turn";
            if (mine) {
                msg = snap.phase() == TurnPhase.AWAITING_ROLL ? "Roll the dice."
                    : "Click a glowing token to move it " + snap.dice() + ".";
            } else {
                String who = session.seatNames().getOrDefault(cur, cur.display());
                msg = "Waiting for " + who + "...";
            }
        }
        if (!turnLabel.getText().equals(turnText)) turnLabel.setText(turnText);
        if (!status.getText().equals(msg)) status.setText(msg);
    }

    private void handleClosed(String reason) {
        if (disposed) return;
        disposed = true;
        loop.stop();
        Alert a = new Alert(Alert.AlertType.INFORMATION, reason);
        a.setHeaderText("Game ended");
        a.showAndWait();
        onExit.run();
    }

    private void exit() {
        dispose();
        onExit.run();
    }

    /** Stops animation and closes the session. Safe to call more than once. */
    public void dispose() {
        disposed = true;
        loop.stop();
        session.close();
    }
}
