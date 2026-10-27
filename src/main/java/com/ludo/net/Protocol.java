package com.ludo.net;

import com.ludo.engine.GameEvent;
import com.ludo.engine.GameEvent.*;
import com.ludo.engine.GameSnapshot;
import com.ludo.engine.PlayerColor;
import com.ludo.engine.TurnPhase;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Line-based text protocol (UTF-8, one message per line, fields separated by '|').
 *
 * Client -> server:  HELLO|name   ROLL   MOVE|token   BYE
 * Server -> client:  WELCOME|seat   REJECT|reason   WAIT|joined|needed
 *                    START|roster|snapshot   UPDATE|events|snapshot   CLOSED|reason
 *
 * events   = event(;event)*     event = tag,field,field...
 * snapshot = current:dice:sixStreak:phase:winner|-:activeLetters:t0,t1,...,t15
 * roster   = R=name;G=name;...
 */
public final class Protocol {
    private Protocol() {}

    public static final int DEFAULT_PORT = 5959;

    // ------------------------------------------------------------ events

    public static String encodeEvents(List<GameEvent> events) {
        StringBuilder sb = new StringBuilder();
        for (GameEvent e : events) {
            if (sb.length() > 0) sb.append(';');
            sb.append(encode(e));
        }
        return sb.toString();
    }

    public static List<GameEvent> decodeEvents(String s) {
        List<GameEvent> list = new ArrayList<>();
        if (s.isEmpty()) return list;
        for (String part : s.split(";")) list.add(decode(part));
        return list;
    }

    private static String encode(GameEvent e) {
        return switch (e) {
            case GameStarted g -> "G," + g.first().code;
            case DiceRolled d -> "D," + d.by().code + "," + d.value();
            case NoLegalMove n -> "N," + n.by().code + "," + n.value();
            case Forfeited f -> "F," + f.by().code;
            case TokenMoved m -> "M," + m.color().code + "," + m.token() + "," + m.from() + "," + m.to();
            case TokenCaptured c -> "C," + c.by().code + "," + c.victim().code + "," + c.token();
            case TokenFinished h -> "H," + h.color().code + "," + h.token();
            case TurnChanged t -> "T," + t.color().code + "," + (t.extraTurn() ? 1 : 0);
            case GameWon w -> "W," + w.color().code;
            case PlayerDropped p -> "P," + p.color().code;
        };
    }

    private static GameEvent decode(String s) {
        String[] f = s.split(",");
        return switch (f[0]) {
            case "G" -> new GameStarted(col(f[1]));
            case "D" -> new DiceRolled(col(f[1]), Integer.parseInt(f[2]));
            case "N" -> new NoLegalMove(col(f[1]), Integer.parseInt(f[2]));
            case "F" -> new Forfeited(col(f[1]));
            case "M" -> new TokenMoved(col(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]), Integer.parseInt(f[4]));
            case "C" -> new TokenCaptured(col(f[1]), col(f[2]), Integer.parseInt(f[3]));
            case "H" -> new TokenFinished(col(f[1]), Integer.parseInt(f[2]));
            case "T" -> new TurnChanged(col(f[1]), f[2].equals("1"));
            case "W" -> new GameWon(col(f[1]));
            case "P" -> new PlayerDropped(col(f[1]));
            default -> throw new IllegalArgumentException("Unknown event: " + s);
        };
    }

    private static PlayerColor col(String s) {
        return PlayerColor.ofCode(s.charAt(0));
    }

    // ------------------------------------------------------------ snapshot

    public static String encodeSnapshot(GameSnapshot s) {
        StringBuilder active = new StringBuilder();
        for (PlayerColor c : PlayerColor.values()) if (s.isActive(c)) active.append(c.code);
        StringBuilder toks = new StringBuilder();
        int[] raw = s.rawTokens();
        for (int i = 0; i < raw.length; i++) {
            if (i > 0) toks.append(',');
            toks.append(raw[i]);
        }
        return s.current().code + ":" + s.dice() + ":" + s.sixStreak() + ":" + s.phase().name() + ":"
            + (s.winner() == null ? "-" : String.valueOf(s.winner().code)) + ":" + active + ":" + toks;
    }

    public static GameSnapshot decodeSnapshot(String str) {
        String[] f = str.split(":");
        Set<PlayerColor> active = EnumSet.noneOf(PlayerColor.class);
        for (char c : f[5].toCharArray()) active.add(PlayerColor.ofCode(c));
        String[] t = f[6].split(",");
        int[] tokens = new int[t.length];
        for (int i = 0; i < t.length; i++) tokens[i] = Integer.parseInt(t[i]);
        return new GameSnapshot(col(f[0]), Integer.parseInt(f[1]), Integer.parseInt(f[2]),
            TurnPhase.valueOf(f[3]), f[4].equals("-") ? null : col(f[4]), active, tokens);
    }

    // ------------------------------------------------------------ roster

    public static String sanitize(String name) {
        String n = name == null ? "" : name.replaceAll("[|;=,:\\r\\n]", " ").trim();
        if (n.isEmpty()) n = "Player";
        return n.length() > 20 ? n.substring(0, 20) : n;
    }

    public static String encodeRoster(Map<PlayerColor, String> names) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<PlayerColor, String> e : names.entrySet()) {
            if (sb.length() > 0) sb.append(';');
            sb.append(e.getKey().code).append('=').append(sanitize(e.getValue()));
        }
        return sb.toString();
    }

    public static Map<PlayerColor, String> decodeRoster(String s) {
        Map<PlayerColor, String> m = new EnumMap<>(PlayerColor.class);
        if (s.isEmpty()) return m;
        for (String part : s.split(";")) {
            int eq = part.indexOf('=');
            m.put(col(part.substring(0, eq)), part.substring(eq + 1));
        }
        return m;
    }
}
