package com.ludo.ui;

import com.ludo.engine.PlayerColor;
import com.ludo.net.GameServer;
import com.ludo.net.Protocol;
import com.ludo.session.LocalSession;
import com.ludo.session.RemoteSession;
import com.ludo.session.SeatType;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;

import java.io.IOException;
import java.net.InetAddress;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Seat setup plus local / host / join entry points. */
final class MenuScreen extends VBox {
    private final LudoApp app;
    private final Map<PlayerColor, ComboBox<SeatType>> seatBoxes = new EnumMap<>(PlayerColor.class);
    private final TextField nameField = new TextField(System.getProperty("user.name", "Player"));
    private final TextField hostPort = new TextField(String.valueOf(Protocol.DEFAULT_PORT));
    private final TextField joinHost = new TextField("localhost");
    private final TextField joinPort = new TextField(String.valueOf(Protocol.DEFAULT_PORT));
    private final Label error = new Label();

    MenuScreen(LudoApp app) {
        this.app = app;
        setAlignment(Pos.CENTER);
        setSpacing(18);
        setPadding(new Insets(24));
        getStyleClass().add("menu-root");

        Label title = new Label("LUDO");
        title.getStyleClass().add("title");
        Label sub = new Label("Pick who sits where, then play on this device or over the network.");
        sub.getStyleClass().add("subtitle");

        VBox seats = new VBox(8);
        seats.getStyleClass().add("card");
        seats.setMaxWidth(520);
        for (PlayerColor c : PlayerColor.values()) {
            Circle dot = new Circle(11, Palette.base(c));
            dot.setStroke(Palette.dark(c));
            Label name = new Label(c.display());
            name.setMinWidth(70);
            ComboBox<SeatType> box = new ComboBox<>();
            box.getItems().addAll(SeatType.values());
            box.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(box, Priority.ALWAYS);
            seatBoxes.put(c, box);
            HBox row = new HBox(12, dot, name, box);
            row.setAlignment(Pos.CENTER_LEFT);
            seats.getChildren().add(row);
        }
        preset(SeatType.HUMAN, SeatType.AI, SeatType.AI, SeatType.AI);

        HBox presets = new HBox(8,
            presetButton("You vs 1 computer", SeatType.HUMAN, SeatType.OFF, SeatType.AI, SeatType.OFF),
            presetButton("2 local players", SeatType.HUMAN, SeatType.OFF, SeatType.HUMAN, SeatType.OFF),
            presetButton("You vs 3 computers", SeatType.HUMAN, SeatType.AI, SeatType.AI, SeatType.AI),
            presetButton("4 local players", SeatType.HUMAN, SeatType.HUMAN, SeatType.HUMAN, SeatType.HUMAN));
        presets.setAlignment(Pos.CENTER);

        HBox nameRow = new HBox(10, new Label("Your name"), nameField);
        nameRow.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(nameField, Priority.ALWAYS);

        Button play = new Button("Play on this device");
        play.getStyleClass().add("primary");
        play.setOnAction(e -> playLocal());

        hostPort.setPrefWidth(90);
        Button host = new Button("Host network game");
        host.setOnAction(e -> hostGame());
        HBox hostRow = new HBox(10, host, new Label("port"), hostPort);
        hostRow.setAlignment(Pos.CENTER_LEFT);

        joinHost.setPrefWidth(170);
        joinPort.setPrefWidth(90);
        Button join = new Button("Join");
        join.setOnAction(e -> joinGame());
        HBox joinRow = new HBox(10, join, new Label("host"), joinHost, new Label("port"), joinPort);
        joinRow.setAlignment(Pos.CENTER_LEFT);

        Label hint = new Label("To host: set a seat to \"Network player\" for each remote friend. "
            + "The game starts when they have all joined.");
        hint.setWrapText(true);
        hint.getStyleClass().add("subtitle");

        VBox actions = new VBox(10, nameRow, play, hostRow, hint, new Region(), joinRow);
        actions.getStyleClass().add("card");
        actions.setMaxWidth(520);

        error.getStyleClass().add("error");
        error.setWrapText(true);
        error.setMaxWidth(520);

        getChildren().addAll(title, sub, seats, presets, actions, error);
    }

    private Button presetButton(String text, SeatType r, SeatType g, SeatType y, SeatType b) {
        Button btn = new Button(text);
        btn.setOnAction(e -> preset(r, g, y, b));
        return btn;
    }

    private void preset(SeatType r, SeatType g, SeatType y, SeatType b) {
        seatBoxes.get(PlayerColor.RED).setValue(r);
        seatBoxes.get(PlayerColor.GREEN).setValue(g);
        seatBoxes.get(PlayerColor.YELLOW).setValue(y);
        seatBoxes.get(PlayerColor.BLUE).setValue(b);
    }

    // ------------------------------------------------------------------ setup

    private Map<PlayerColor, SeatType> seatTypes() {
        Map<PlayerColor, SeatType> m = new EnumMap<>(PlayerColor.class);
        seatBoxes.forEach((c, b) -> m.put(c, b.getValue()));
        return m;
    }

    private Map<PlayerColor, String> names(Map<PlayerColor, SeatType> seats) {
        Map<PlayerColor, String> names = new EnumMap<>(PlayerColor.class);
        String me = Protocol.sanitize(nameField.getText());
        int humans = 0;
        for (PlayerColor c : PlayerColor.values()) {
            switch (seats.get(c)) {
                case HUMAN -> names.put(c, humans++ == 0 ? me : "Player " + humans);
                case AI -> names.put(c, "Computer");
                default -> { }
            }
        }
        return names;
    }

    private String validate(Map<PlayerColor, SeatType> seats, boolean allowNetwork, boolean needNetwork) {
        long active = seats.values().stream().filter(t -> t != SeatType.OFF).count();
        long humans = seats.values().stream().filter(t -> t == SeatType.HUMAN).count();
        long network = seats.values().stream().filter(t -> t == SeatType.NETWORK).count();
        if (active < 2) return "At least two seats must be playing.";
        if (humans < 1) return "At least one seat must be Human (this device).";
        if (!allowNetwork && network > 0) return "Network seats need \"Host network game\".";
        if (needNetwork && network < 1) return "Set at least one seat to \"Network player\" to host.";
        return null;
    }

    private void playLocal() {
        Map<PlayerColor, SeatType> seats = seatTypes();
        String err = validate(seats, false, false);
        if (err != null) { error.setText(err); return; }
        error.setText("");
        app.showGame(new LocalSession(seats, names(seats)), null);
    }

    // ------------------------------------------------------------------ host

    private void hostGame() {
        Map<PlayerColor, SeatType> seats = seatTypes();
        String err = validate(seats, true, true);
        if (err != null) { error.setText(err); return; }
        int port;
        try {
            port = Integer.parseInt(hostPort.getText().trim());
        } catch (NumberFormatException ex) {
            error.setText("Port must be a number.");
            return;
        }
        error.setText("");

        LocalSession session = new LocalSession(seats, names(seats));
        Label status = new Label("Starting...");
        AtomicBoolean started = new AtomicBoolean();
        Dialog<Void> dialog = lobbyDialog("Hosting game", status, addressHint(port));

        AtomicReference<GameServer> ref = new AtomicReference<>();
        GameServer server = new GameServer(session,
            text -> Platform.runLater(() -> status.setText(text)),
            () -> Platform.runLater(() -> {
                started.set(true);
                dialog.close();
                app.showGame(session, ref.get());
            }));
        ref.set(server);
        try {
            server.start(port);
        } catch (IOException ex) {
            session.close();
            error.setText("Could not open port " + port + ": " + ex.getMessage());
            return;
        }
        dialog.setOnHidden(e -> {
            if (!started.get()) {
                server.close();
                session.close();
            }
        });
        dialog.show();
    }

    private static String addressHint(int port) {
        String ip = "your IP address";
        try {
            ip = InetAddress.getLocalHost().getHostAddress();
        } catch (IOException ignored) {
            // keep generic text
        }
        return "Friends connect to " + ip + " : " + port;
    }

    // ------------------------------------------------------------------ join

    private void joinGame() {
        int port;
        try {
            port = Integer.parseInt(joinPort.getText().trim());
        } catch (NumberFormatException ex) {
            error.setText("Port must be a number.");
            return;
        }
        String host = joinHost.getText().trim();
        String name = nameField.getText();
        error.setText("Connecting...");

        Thread t = new Thread(() -> {
            try {
                RemoteSession session = RemoteSession.open(host, port, name);
                Platform.runLater(() -> onJoined(session));
            } catch (IOException ex) {
                Platform.runLater(() -> error.setText("Could not join: " + ex.getMessage()));
            }
        }, "ludo-connect");
        t.setDaemon(true);
        t.start();
    }

    private void onJoined(RemoteSession session) {
        error.setText("");
        Label status = new Label("Connected as " + session.seat().display() + ". Waiting for other players...");
        AtomicBoolean started = new AtomicBoolean();
        Dialog<Void> dialog = lobbyDialog("Joined game", status, "The host starts the game when every seat is filled.");
        session.setLobbyHandler(text -> Platform.runLater(() -> status.setText(text)));
        session.setStartHandler(() -> Platform.runLater(() -> {
            started.set(true);
            dialog.close();
            app.showGame(session, null);
        }));
        session.addListener(new com.ludo.session.SessionListener() {
            @Override public void onUpdate(java.util.List<com.ludo.engine.GameEvent> e, com.ludo.engine.GameSnapshot s) { }

            @Override public void onClosed(String reason) {
                if (started.get()) return;
                Platform.runLater(() -> {
                    dialog.close();
                    error.setText("Disconnected: " + reason);
                });
            }
        });
        dialog.setOnHidden(e -> {
            if (!started.get()) session.close();
        });
        dialog.show();
        session.startReading();
    }

    private Dialog<Void> lobbyDialog(String title, Label status, String hint) {
        Dialog<Void> d = new Dialog<>();
        d.setTitle(title);
        d.setHeaderText(title);
        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setPrefSize(36, 36);
        Label h = new Label(hint);
        h.setWrapText(true);
        VBox box = new VBox(12, new HBox(12, spinner, status), h);
        box.setPadding(new Insets(10));
        box.setPrefWidth(360);
        d.getDialogPane().setContent(box);
        d.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        return d;
    }

}
