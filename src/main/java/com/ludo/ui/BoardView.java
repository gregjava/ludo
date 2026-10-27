package com.ludo.ui;

import com.ludo.engine.BoardGeometry;
import com.ludo.engine.GameSnapshot;
import com.ludo.engine.PlayerColor;
import com.ludo.engine.TurnPhase;
import javafx.scene.Cursor;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;
import javafx.scene.shape.StrokeLineCap;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntConsumer;

import static com.ludo.engine.BoardGeometry.*;

/**
 * Custom Canvas renderer for the board. It owns a "shown" position per token and walks it
 * toward the authoritative snapshot one square at a time, so local, AI and network moves
 * are all animated the same way.
 */
public final class BoardView extends Region {
    private static final double HOP_SEC = 0.13;
    private static final double RETURN_SEC = 0.45;
    private static final int N = PlayerColor.values().length;

    private final Canvas canvas = new Canvas();
    private final int[][] shown = new int[N][TOKENS_PER_PLAYER];
    private final double[][] hop = new double[N][TOKENS_PER_PLAYER];
    private final double[][] ret = new double[N][TOKENS_PER_PLAYER];
    private final double[][][] retFrom = new double[N][TOKENS_PER_PLAYER][2];
    private final double[][][] drawn = new double[N][TOKENS_PER_PLAYER][3]; // x, y, radius in pixels

    private GameSnapshot snap;
    private PlayerColor highlightColor;
    private Set<Integer> highlight = Set.of();
    private int hover = -1;
    private double pulse;
    private IntConsumer onTokenClicked;

    private double ox, oy, cell;

    public BoardView() {
        getChildren().add(canvas);
        setPrefSize(660, 660);
        setMinSize(320, 320);
        for (double[] row : ret) java.util.Arrays.fill(row, 1);
        setOnMouseMoved(e -> {
            hover = hitTest(e.getX(), e.getY());
            setCursor(hover >= 0 ? Cursor.HAND : Cursor.DEFAULT);
        });
        setOnMouseExited(e -> {
            hover = -1;
            setCursor(Cursor.DEFAULT);
        });
        setOnMouseClicked(e -> {
            int t = hitTest(e.getX(), e.getY());
            if (t >= 0 && onTokenClicked != null) onTokenClicked.accept(t);
        });
    }

    @Override
    protected void layoutChildren() {
        canvas.setWidth(getWidth());
        canvas.setHeight(getHeight());
    }

    // ------------------------------------------------------------------ state

    public void setOnTokenClicked(IntConsumer c) { this.onTokenClicked = c; }

    /** Sets the target state. The very first snapshot is shown without animation. */
    public void setSnapshot(GameSnapshot s) {
        boolean first = snap == null;
        snap = s;
        if (first) {
            for (PlayerColor c : PlayerColor.values()) {
                for (int i = 0; i < TOKENS_PER_PLAYER; i++) shown[c.ordinal()][i] = s.steps(c, i);
            }
        }
    }

    /** Which tokens of which colour are clickable right now. */
    public void setHighlight(PlayerColor color, Set<Integer> tokens) {
        this.highlightColor = color;
        this.highlight = tokens;
        if (tokens.isEmpty()) hover = -1;
    }

    public boolean isAnimating() {
        if (snap == null) return false;
        for (PlayerColor c : PlayerColor.values()) {
            for (int i = 0; i < TOKENS_PER_PLAYER; i++) {
                if (shown[c.ordinal()][i] != snap.steps(c, i) || ret[c.ordinal()][i] < 1) return true;
            }
        }
        return false;
    }

    public void render(double dt) {
        tick(dt);
        draw();
    }

    // ------------------------------------------------------------------ animation

    private void tick(double dt) {
        pulse += dt * 4.5;
        if (snap == null) return;

        boolean anyForward = false;
        for (PlayerColor c : PlayerColor.values()) {
            for (int i = 0; i < TOKENS_PER_PLAYER; i++) {
                if (snap.steps(c, i) > shown[c.ordinal()][i]) anyForward = true;
            }
        }

        for (PlayerColor c : PlayerColor.values()) {
            int ci = c.ordinal();
            for (int i = 0; i < TOKENS_PER_PLAYER; i++) {
                if (ret[ci][i] < 1) ret[ci][i] = Math.min(1, ret[ci][i] + dt / RETURN_SEC);
                int target = snap.steps(c, i);
                if (shown[ci][i] == target) {
                    hop[ci][i] = 0;
                } else if (target > shown[ci][i]) {
                    hop[ci][i] += dt / HOP_SEC;
                    while (hop[ci][i] >= 1 && shown[ci][i] < target) {
                        hop[ci][i] -= 1;
                        shown[ci][i]++;
                    }
                    if (shown[ci][i] == target) hop[ci][i] = 0;
                } else if (!anyForward) {
                    // captured or restarted: fly back to the yard once the capturer has landed
                    double[] p = tokenPos(c, i);
                    retFrom[ci][i][0] = p[0];
                    retFrom[ci][i][1] = p[1];
                    ret[ci][i] = 0;
                    shown[ci][i] = target;
                    hop[ci][i] = 0;
                }
            }
        }
    }

    // ------------------------------------------------------------------ positions (grid units)

    private static double[] unitPos(PlayerColor c, int i, int steps) {
        if (steps == HOME) return finishPos(c, i);
        int[] rc = BoardGeometry.cell(c, steps, i);
        return new double[]{rc[1] + 0.5, rc[0] + 0.5};
    }

    private static double[] finishPos(PlayerColor c, int i) {
        double dx = 0, dy = 0;
        switch (c) {
            case RED -> dx = -1;
            case GREEN -> dy = -1;
            case YELLOW -> dx = 1;
            case BLUE -> dy = 1;
        }
        double spread = (i - 1.5) * 0.2;
        return new double[]{7.5 + dx * 0.65 - dy * spread, 7.5 + dy * 0.65 + dx * spread};
    }

    private static double smooth(double t) { return t * t * (3 - 2 * t); }

    private double[] tokenPos(PlayerColor c, int i) {
        int ci = c.ordinal();
        int s = shown[ci][i];
        if (ret[ci][i] < 1) {
            double[] to = unitPos(c, i, 0);
            double t = smooth(ret[ci][i]);
            double[] f = retFrom[ci][i];
            return new double[]{f[0] + (to[0] - f[0]) * t, f[1] + (to[1] - f[1]) * t - Math.sin(Math.PI * t) * 0.8};
        }
        double[] a = unitPos(c, i, s);
        if (hop[ci][i] > 0 && s < HOME) {
            double[] b = unitPos(c, i, s + 1);
            double t = smooth(hop[ci][i]);
            return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t - Math.sin(Math.PI * t) * 0.3};
        }
        return a;
    }

    // ------------------------------------------------------------------ hit testing

    private int hitTest(double mx, double my) {
        if (snap == null || highlight.isEmpty() || highlightColor == null) return -1;
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int t : highlight) {
            double[] d = drawn[highlightColor.ordinal()][t];
            double dist = Math.hypot(mx - d[0], my - d[1]);
            double reach = Math.max(d[2] * 1.25, cell * 0.4);
            if (dist <= reach && dist < bestD) {
                bestD = dist;
                best = t;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ drawing

    private double px(double col) { return ox + col * cell; }
    private double py(double row) { return oy + row * cell; }

    private void draw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        double w = getWidth(), h = getHeight();
        g.clearRect(0, 0, w, h);
        double size = Math.min(w, h);
        if (size < 60) return;
        cell = size / GRID;
        ox = (w - size) / 2;
        oy = (h - size) / 2;

        drawBoardBase(g, size);
        drawYards(g);
        drawTrack(g);
        drawCenter(g);
        if (snap != null) drawTokens(g);
    }

    private void drawBoardBase(GraphicsContext g, double size) {
        g.setFill(Color.rgb(0, 0, 0, 0.3));
        g.fillRoundRect(ox + cell * 0.12, oy + cell * 0.16, size, size, cell * 0.7, cell * 0.7);
        g.setFill(Color.web("#fff8e7"));
        g.fillRoundRect(ox, oy, size, size, cell * 0.7, cell * 0.7);
    }

    private void drawYards(GraphicsContext g) {
        PlayerColor turn = (snap != null && snap.phase() != TurnPhase.GAME_OVER) ? snap.current() : null;
        for (PlayerColor c : PlayerColor.values()) {
            int[] o = YARD_ORIGIN[c.ordinal()];
            boolean active = snap == null || snap.isActive(c);
            Color base = active ? Palette.base(c) : Palette.base(c).interpolate(Color.web("#bdc3c7"), 0.75);
            g.setFill(base);
            g.fillRoundRect(px(o[1]), py(o[0]), 6 * cell, 6 * cell, cell * 0.7, cell * 0.7);
            g.setFill(Color.web("#fffdf7"));
            g.fillRoundRect(px(o[1] + 0.6), py(o[0] + 0.6), 4.8 * cell, 4.8 * cell, cell * 0.55, cell * 0.55);
            for (int[] slot : SEED_SLOTS) {
                double cx = px(o[1] + slot[1] + 0.5), cy = py(o[0] + slot[0] + 0.5), r = cell * 0.5;
                g.setFill(active ? Palette.pale(c) : Color.web("#ecf0f1"));
                g.fillOval(cx - r, cy - r, 2 * r, 2 * r);
                g.setStroke(base);
                g.setLineWidth(Math.max(1.5, cell * 0.06));
                g.strokeOval(cx - r, cy - r, 2 * r, 2 * r);
            }
            if (c == turn) {
                g.setStroke(Color.rgb(255, 255, 255, 0.55 + 0.35 * Math.sin(pulse)));
                g.setLineWidth(cell * 0.14);
                g.strokeRoundRect(px(o[1]) + cell * 0.07, py(o[0]) + cell * 0.07, 6 * cell - cell * 0.14,
                    6 * cell - cell * 0.14, cell * 0.65, cell * 0.65);
            }
        }
    }

    private void drawTrack(GraphicsContext g) {
        g.setLineWidth(Math.max(1, cell * 0.035));
        for (int[] p : PATH) cellRect(g, p, Color.web("#fffdf5"));

        for (PlayerColor c : PlayerColor.values()) {
            for (int[] p : HOME_STRETCH[c.ordinal()]) cellRect(g, p, Palette.light(c));
            cellRect(g, PATH[c.entry], Palette.base(c));
        }
        int[] safe = {0, 8, 13, 21, 26, 34, 39, 47};
        for (int idx : safe) {
            boolean entry = idx % 13 == 0;
            int[] p = PATH[idx];
            star(g, px(p[1] + 0.5), py(p[0] + 0.5), cell * 0.36, entry ? Color.WHITE : Color.web("#c9b88f"));
        }
        // small arrows on each home stretch pointing at the centre
        for (PlayerColor c : PlayerColor.values()) {
            int[][] hs = HOME_STRETCH[c.ordinal()];
            for (int k = 1; k < 5; k++) {
                double cx = px(hs[k][1] + 0.5), cy = py(hs[k][0] + 0.5);
                double dx = Integer.signum(hs[k + 1][1] - hs[k][1]), dy = Integer.signum(hs[k + 1][0] - hs[k][0]);
                g.setStroke(Palette.base(c).darker());
                g.setLineWidth(Math.max(1.5, cell * 0.07));
                g.setLineCap(StrokeLineCap.ROUND);
                double a = cell * 0.13;
                g.strokeLine(cx - dx * a - dy * a, cy - dy * a + dx * a, cx + dx * a, cy + dy * a);
                g.strokeLine(cx - dx * a + dy * a, cy - dy * a - dx * a, cx + dx * a, cy + dy * a);
            }
        }
    }

    private void cellRect(GraphicsContext g, int[] rc, Color fill) {
        g.setFill(fill);
        g.fillRect(px(rc[1]), py(rc[0]), cell, cell);
        g.setStroke(Color.web("#b8a888"));
        g.strokeRect(px(rc[1]), py(rc[0]), cell, cell);
    }

    private void drawCenter(GraphicsContext g) {
        double[][] tri = {
            {6, 6, 6, 9, 7.5, 7.5},   // red (left)
            {6, 6, 9, 6, 7.5, 7.5},   // green (top)
            {9, 6, 9, 9, 7.5, 7.5},   // yellow (right)
            {6, 9, 9, 9, 7.5, 7.5}    // blue (bottom)
        };
        for (PlayerColor c : PlayerColor.values()) {
            double[] t = tri[c.ordinal()];
            double[] xs = {px(t[0]), px(t[2]), px(t[4])};
            double[] ys = {py(t[1]), py(t[3]), py(t[5])};
            g.setFill(Palette.base(c));
            g.fillPolygon(xs, ys, 3);
            g.setStroke(Color.web("#fffdf5"));
            g.setLineWidth(Math.max(1, cell * 0.04));
            g.strokePolygon(xs, ys, 3);
        }
    }

    private void star(GraphicsContext g, double cx, double cy, double r, Color color) {
        double[] xs = new double[10], ys = new double[10];
        for (int k = 0; k < 10; k++) {
            double rad = (k % 2 == 0) ? r : r * 0.45;
            double a = -Math.PI / 2 + k * Math.PI / 5;
            xs[k] = cx + Math.cos(a) * rad;
            ys[k] = cy + Math.sin(a) * rad;
        }
        g.setFill(color);
        g.fillPolygon(xs, ys, 10);
    }

    // ------------------------------------------------------------------ tokens

    private record Item(PlayerColor color, int index, double x, double y, boolean resting, boolean finished) {}

    private void drawTokens(GraphicsContext g) {
        List<Item> items = new ArrayList<>();
        Map<String, List<Item>> groups = new HashMap<>();
        for (PlayerColor c : PlayerColor.values()) {
            if (!snap.isActive(c)) continue;
            int ci = c.ordinal();
            for (int i = 0; i < TOKENS_PER_PLAYER; i++) {
                double[] p = tokenPos(c, i);
                boolean still = hop[ci][i] == 0 && ret[ci][i] >= 1;
                int s = shown[ci][i];
                Item it = new Item(c, i, p[0], p[1], still && s > 0 && s < HOME, s == HOME && still);
                items.add(it);
                if (it.resting()) {
                    groups.computeIfAbsent((int) Math.floor(p[0]) + "," + (int) Math.floor(p[1]), k -> new ArrayList<>()).add(it);
                }
            }
        }
        // spread tokens that share a square
        Map<Item, double[]> offset = new HashMap<>();
        Map<Item, Double> scale = new HashMap<>();
        for (List<Item> grp : groups.values()) {
            int n = grp.size();
            if (n < 2) continue;
            for (int k = 0; k < n; k++) {
                double a = -Math.PI / 2 + 2 * Math.PI * k / n;
                double rad = n == 2 ? 0.22 : 0.25;
                offset.put(grp.get(k), new double[]{Math.cos(a) * rad, Math.sin(a) * rad});
                scale.put(grp.get(k), n == 2 ? 0.8 : 0.68);
            }
        }
        items.sort(Comparator.comparingDouble(Item::y));

        for (Item it : items) {
            double[] off = offset.getOrDefault(it, new double[]{0, 0});
            double sc = it.finished() ? 0.55 : scale.getOrDefault(it, 1.0);
            double x = px(it.x() + off[0]), y = py(it.y() + off[1]);
            double r = cell * 0.36 * sc;
            int ci = it.color().ordinal();
            drawn[ci][it.index()][0] = x;
            drawn[ci][it.index()][1] = y;
            drawn[ci][it.index()][2] = r;
            boolean hl = it.color() == highlightColor && highlight.contains(it.index());
            drawToken(g, x, y, r, it.color(), hl, hl && hover == it.index());
        }
    }

    private void drawToken(GraphicsContext g, double x, double y, double r, PlayerColor c, boolean hl, boolean hovered) {
        double rr = hovered ? r * 1.12 : r;
        g.setFill(Color.rgb(0, 0, 0, 0.28));
        g.fillOval(x - rr, y - rr + rr * 0.28, 2 * rr, 2 * rr);
        if (hl) {
            double ring = rr * (1.25 + 0.12 * Math.sin(pulse));
            g.setStroke(Color.rgb(0, 0, 0, 0.55));
            g.setLineWidth(rr * 0.34);
            g.strokeOval(x - ring, y - ring, 2 * ring, 2 * ring);
            g.setStroke(Color.WHITE);
            g.setLineWidth(rr * 0.2);
            g.strokeOval(x - ring, y - ring, 2 * ring, 2 * ring);
        }
        g.setFill(new RadialGradient(0, 0, x - rr * 0.3, y - rr * 0.35, rr * 1.5, false, CycleMethod.NO_CYCLE,
            new Stop(0, Palette.light(c)), new Stop(0.55, Palette.base(c)), new Stop(1, Palette.dark(c))));
        g.fillOval(x - rr, y - rr, 2 * rr, 2 * rr);
        g.setStroke(Palette.dark(c));
        g.setLineWidth(Math.max(1.5, rr * 0.12));
        g.strokeOval(x - rr, y - rr, 2 * rr, 2 * rr);
        g.setStroke(Color.rgb(255, 255, 255, 0.55));
        g.setLineWidth(Math.max(1, rr * 0.1));
        double in = rr * 0.55;
        g.strokeOval(x - in, y - in, 2 * in, 2 * in);
    }
}
