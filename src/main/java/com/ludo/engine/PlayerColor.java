package com.ludo.engine;

/** The four seats. Declaration order is the clockwise turn order. */
public enum PlayerColor {
    RED('R', 0), GREEN('G', 13), YELLOW('Y', 26), BLUE('B', 39);

    /** One-letter code used by the wire protocol. */
    public final char code;
    /** Index on the shared 52-square track where this colour enters. */
    public final int entry;

    PlayerColor(char code, int entry) {
        this.code = code;
        this.entry = entry;
    }

    public PlayerColor next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public String display() {
        return name().charAt(0) + name().substring(1).toLowerCase();
    }

    public static PlayerColor ofCode(char c) {
        for (PlayerColor p : values()) if (p.code == c) return p;
        throw new IllegalArgumentException("Unknown colour code: " + c);
    }
}
