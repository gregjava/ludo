package com.ludo.session;

import com.ludo.engine.BoardGeometry;
import com.ludo.engine.GameSnapshot;
import com.ludo.engine.PlayerColor;

import java.util.List;

/** Simple greedy heuristic: finish > capture > leave yard > safe square > progress, avoid danger. */
public final class AiPlayer {
    private AiPlayer() {}

    public static int choose(GameSnapshot s) {
        PlayerColor me = s.current();
        List<Integer> options = s.movable();
        int best = options.get(0);
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int t : options) {
            double score = score(s, me, t);
            if (score > bestScore) {
                bestScore = score;
                best = t;
            }
        }
        return best;
    }

    private static double score(GameSnapshot s, PlayerColor me, int token) {
        int from = s.steps(me, token);
        int to = from == 0 ? 1 : from + s.dice();
        double score = to; // prefer advancing the furthest token a little

        if (to == BoardGeometry.HOME) score += 100;
        if (from == 0) score += 40;

        int track = BoardGeometry.trackIndex(me, to);
        if (track >= 0) {
            if (BoardGeometry.isSafe(track)) {
                score += 25;
            } else {
                if (opponentsOn(s, me, track) > 0) score += 80;
                if (threatened(s, me, track)) score -= 30;
            }
        } else if (to > 51) {
            score += 20; // home stretch is untouchable
        }
        // leaving a threatened square is good
        int fromTrack = BoardGeometry.trackIndex(me, from);
        if (fromTrack >= 0 && !BoardGeometry.isSafe(fromTrack) && threatened(s, me, fromTrack)) score += 15;
        return score;
    }

    private static int opponentsOn(GameSnapshot s, PlayerColor me, int track) {
        int n = 0;
        for (PlayerColor o : PlayerColor.values()) {
            if (o == me || !s.isActive(o)) continue;
            for (int i = 0; i < BoardGeometry.TOKENS_PER_PLAYER; i++) {
                if (BoardGeometry.trackIndex(o, s.steps(o, i)) == track) n++;
            }
        }
        return n;
    }

    /** True if an opponent token sits 1..6 squares behind this track square. */
    private static boolean threatened(GameSnapshot s, PlayerColor me, int track) {
        for (int d = 1; d <= 6; d++) {
            int behind = Math.floorMod(track - d, BoardGeometry.TRACK_LENGTH);
            if (opponentsOn(s, me, behind) > 0) return true;
        }
        return false;
    }
}
