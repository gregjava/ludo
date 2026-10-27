package com.ludo.engine;

/** Things that happened, in order. Used for the log, sounds, and the network protocol. */
public sealed interface GameEvent {
    record GameStarted(PlayerColor first) implements GameEvent {}
    record DiceRolled(PlayerColor by, int value) implements GameEvent {}
    record NoLegalMove(PlayerColor by, int value) implements GameEvent {}
    record Forfeited(PlayerColor by) implements GameEvent {}
    record TokenMoved(PlayerColor color, int token, int from, int to) implements GameEvent {}
    record TokenCaptured(PlayerColor by, PlayerColor victim, int token) implements GameEvent {}
    record TokenFinished(PlayerColor color, int token) implements GameEvent {}
    record TurnChanged(PlayerColor color, boolean extraTurn) implements GameEvent {}
    record GameWon(PlayerColor color) implements GameEvent {}
    /** Session-level: a networked player left and the seat was given to the AI. */
    record PlayerDropped(PlayerColor color) implements GameEvent {}
}
