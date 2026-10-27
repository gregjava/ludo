package com.ludo.engine;

/** Edges of the turn state machine. */
public enum TurnTrigger {
    ROLLED_MOVABLE,   // die rolled, at least one legal move
    ROLLED_BLOCKED,   // die rolled, no legal move
    ROLLED_FORFEIT,   // third six in a row
    MOVED,            // a token has been moved
    WON,              // the mover finished all four tokens
    CONTINUE,         // nobody won yet
    PASS_TURN,        // hand the dice to the next seat
    KEEP_TURN,        // same seat rolls again (a six)
    RESET             // new game
}
