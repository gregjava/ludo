package com.ludo.ui;

import com.ludo.session.GameSession;
import com.ludo.session.LocalSession;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import java.util.Objects;

/** Application shell: swaps between the menu and the game screen. */
public final class LudoApp extends Application {
    private final StackPane root = new StackPane();
    private GameScreen game;
    private AutoCloseable hostServer;

    @Override
    public void start(Stage stage) {
        Scene scene = new Scene(root, 1040, 740);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/ludo.css")).toExternalForm());
        stage.setTitle("Ludo");
        stage.setMinWidth(820);
        stage.setMinHeight(600);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> teardown());
        showMenu();
        stage.show();
    }

    @Override
    public void stop() {
        teardown();
    }

    void showMenu() {
        teardown();
        root.getChildren().setAll(new MenuScreen(this));
    }

    /** Shows the game. {@code server} (nullable) is closed when the game screen is left. */
    void showGame(GameSession session, AutoCloseable server) {
        teardown();
        this.hostServer = server;
        game = new GameScreen(session, this::showMenu);
        root.getChildren().setAll(game);
        if (session instanceof LocalSession local) local.begin();
    }

    private void teardown() {
        if (game != null) {
            game.dispose();
            game = null;
        }
        if (hostServer != null) {
            try {
                hostServer.close();
            } catch (Exception ignored) {
                // best effort
            }
            hostServer = null;
        }
    }
}
