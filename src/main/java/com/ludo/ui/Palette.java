package com.ludo.ui;

import com.ludo.engine.PlayerColor;
import javafx.scene.paint.Color;

final class Palette {
    private Palette() {}

    static Color base(PlayerColor c) {
        return switch (c) {
            case RED -> Color.web("#e74c3c");
            case GREEN -> Color.web("#2ecc71");
            case YELLOW -> Color.web("#f1c40f");
            case BLUE -> Color.web("#3498db");
        };
    }

    static Color dark(PlayerColor c) { return base(c).darker().darker(); }

    static Color light(PlayerColor c) { return base(c).interpolate(Color.WHITE, 0.55); }

    static Color pale(PlayerColor c) { return base(c).interpolate(Color.WHITE, 0.78); }

    static String hex(Color c) {
        return String.format("#%02x%02x%02x", (int) Math.round(c.getRed() * 255),
            (int) Math.round(c.getGreen() * 255), (int) Math.round(c.getBlue() * 255));
    }
}
