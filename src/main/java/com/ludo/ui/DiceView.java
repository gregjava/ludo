package com.ludo.ui;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.util.Random;

/** A drawn die with a short tumbling animation. */
public final class DiceView extends Canvas {
    private static final double[][] PIP = {
        {0.25, 0.25}, {0.75, 0.25}, {0.25, 0.5}, {0.75, 0.5}, {0.25, 0.75}, {0.75, 0.75}, {0.5, 0.5}
    };
    // indices into PIP for each face
    private static final int[][] FACES = {
        {}, {6}, {0, 5}, {0, 6, 5}, {0, 1, 4, 5}, {0, 1, 6, 4, 5}, {0, 1, 2, 3, 4, 5}
    };

    private final Random random = new Random();
    private int value;
    private Color tint = Color.web("#95a5a6");
    private boolean rolling;
    private Timeline timeline;

    public DiceView(double size) {
        super(size, size);
        draw();
    }

    public boolean isRolling() { return rolling; }

    public void setTint(Color c) {
        this.tint = c;
        draw();
    }

    public void setValue(int v) {
        this.value = v;
        draw();
    }

    /** Tumbles through random faces, then lands on {@code finalValue}. */
    public void roll(int finalValue, Runnable done) {
        if (timeline != null) timeline.stop();
        rolling = true;
        timeline = new Timeline(new KeyFrame(Duration.millis(70), e -> {
            value = 1 + random.nextInt(6);
            setRotate((random.nextDouble() - 0.5) * 24);
            draw();
        }));
        timeline.setCycleCount(8);
        timeline.setOnFinished(e -> {
            rolling = false;
            setRotate(0);
            value = finalValue;
            draw();
            if (done != null) done.run();
        });
        timeline.play();
    }

    private void draw() {
        GraphicsContext g = getGraphicsContext2D();
        double s = getWidth();
        g.clearRect(0, 0, s, s);
        g.setFill(Color.rgb(0, 0, 0, 0.25));
        g.fillRoundRect(4, 6, s - 8, s - 8, s * 0.2, s * 0.2);
        g.setFill(Color.web("#fdfdfb"));
        g.fillRoundRect(2, 2, s - 8, s - 8, s * 0.2, s * 0.2);
        g.setStroke(tint);
        g.setLineWidth(3);
        g.strokeRoundRect(2, 2, s - 8, s - 8, s * 0.2, s * 0.2);
        if (value < 1 || value > 6) return;
        g.setFill(Color.web("#2c3e50"));
        double inner = s - 8, r = inner * 0.085;
        for (int idx : FACES[value]) {
            double x = 2 + PIP[idx][0] * inner, y = 2 + PIP[idx][1] * inner;
            g.fillOval(x - r, y - r, 2 * r, 2 * r);
        }
    }
}
