package com.ludo.engine;

/**
 * Static board geometry, ported 1:1 from the original script.js.
 * Coordinates are {row, col} on a 15x15 grid.
 *
 * A token's progress is a single number:
 *   0        in the yard
 *   1..51    on the shared 52-square track (relative to the colour's entry)
 *   52..57   on the colour's home stretch (57 = home)
 */
public final class BoardGeometry {
    private BoardGeometry() {}

    public static final int GRID = 15;
    public static final int TRACK_LENGTH = 52;
    public static final int HOME = 57;
    public static final int TOKENS_PER_PLAYER = 4;

    public static final int[][] PATH = {
        {6,1},{6,2},{6,3},{6,4},{6,5},
        {5,6},{4,6},{3,6},{2,6},{1,6},{0,6},
        {0,7},
        {0,8},{1,8},{2,8},{3,8},{4,8},{5,8},
        {6,9},{6,10},{6,11},{6,12},{6,13},{6,14},
        {7,14},
        {8,14},{8,13},{8,12},{8,11},{8,10},{8,9},
        {9,8},{10,8},{11,8},{12,8},{13,8},{14,8},
        {14,7},
        {14,6},{13,6},{12,6},{11,6},{10,6},{9,6},
        {8,5},{8,4},{8,3},{8,2},{8,1},{8,0},
        {7,0},
        {6,0}
    };

    private static final boolean[] SAFE = new boolean[TRACK_LENGTH];
    static {
        for (int i : new int[]{0, 8, 13, 21, 26, 34, 39, 47}) SAFE[i] = true;
    }

    /** Indexed by PlayerColor.ordinal(). */
    public static final int[][][] HOME_STRETCH = {
        {{7,1},{7,2},{7,3},{7,4},{7,5},{7,6}},        // red
        {{1,7},{2,7},{3,7},{4,7},{5,7},{6,7}},        // green
        {{7,13},{7,12},{7,11},{7,10},{7,9},{7,8}},    // yellow
        {{13,7},{12,7},{11,7},{10,7},{9,7},{8,7}}     // blue
    };

    /** Top-left corner of each colour's 6x6 yard, as {row, col}. */
    public static final int[][] YARD_ORIGIN = {{0,0},{0,9},{9,9},{9,0}};
    /** Seed slots inside a yard, as {row, col} offsets. */
    public static final int[][] SEED_SLOTS = {{1,1},{1,4},{4,4},{4,1}};

    public static boolean isSafe(int trackIndex) {
        return trackIndex >= 0 && SAFE[trackIndex];
    }

    /** Index on the shared track, or -1 when the token is in the yard / home stretch. */
    public static int trackIndex(PlayerColor c, int steps) {
        if (steps < 1 || steps > 51) return -1;
        return (c.entry + steps - 1) % TRACK_LENGTH;
    }

    /** Grid cell {row, col} for a token. Steps 0 gives the seed slot of that token. */
    public static int[] cell(PlayerColor c, int steps, int tokenIndex) {
        if (steps == 0) {
            int[] o = YARD_ORIGIN[c.ordinal()];
            int[] s = SEED_SLOTS[tokenIndex];
            return new int[]{o[0] + s[0], o[1] + s[1]};
        }
        if (steps <= 51) return PATH[trackIndex(c, steps)];
        return HOME_STRETCH[c.ordinal()][steps - 52];
    }
}
