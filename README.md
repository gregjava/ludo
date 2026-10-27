# ludo
An African game played by 1, 2 or 4 players

## Ludo FX

A JavaFX rewrite of the web Ludo game: headless rules engine with an explicit turn state
machine, host-authoritative multiplayer over TCP, AI seats, and a custom Canvas board renderer.

## Run

Requires JDK 21+.

    mvn javafx:run

Or without Maven (JavaFX SDK unpacked somewhere):

    javac --release 21 -d out --module-path $JAVAFX/lib --add-modules javafx.controls $(find src/main/java -name '*.java')
    cp src/main/resources/ludo.css out/
    java -cp out --module-path $JAVAFX/lib --add-modules javafx.controls com.ludo.Main

## Self test (no dependencies)

    javac --release 21 -d out $(find src/main/java/com/ludo/engine src/main/java/com/ludo/session src/main/java/com/ludo/net -name '*.java')
    javac --release 21 -cp out -d test-out src/test/java/com/ludo/SelfTest.java
    java -cp out:test-out com.ludo.SelfTest

Covers board geometry, scripted rule cases (six to leave, capture, safe squares, exact finish,
three-sixes forfeit, unplayable six), 2000 random full games, the wire protocol, and a real
localhost host + client game including AI takeover on disconnect.

## Layout

    engine/   PlayerColor, BoardGeometry, StateMachine, TurnPhase/TurnTrigger, GameEngine,
              GameEvent, GameSnapshot            (no UI, no networking)
    session/  GameSession interface, LocalSession (authoritative + AI scheduling),
              RemoteSession (network client), AiPlayer, SeatType
    net/      Protocol (text codec), GameServer (lobby + routing)
    ui/       LudoApp, MenuScreen, GameScreen, BoardView (Canvas renderer), DiceView

## Turn state machine

    AWAITING_ROLL --ROLLED_MOVABLE--> AWAITING_MOVE --MOVED--> RESOLVING --CONTINUE--> ADVANCING
    AWAITING_ROLL --ROLLED_BLOCKED--> ADVANCING                RESOLVING --WON-----> GAME_OVER
    AWAITING_ROLL --ROLLED_FORFEIT--> ADVANCING                ADVANCING --PASS_TURN / KEEP_TURN--> AWAITING_ROLL
    any state --RESET--> AWAITING_ROLL

RESOLVING and ADVANCING are transient and pumped automatically; callers only ever see
AWAITING_ROLL, AWAITING_MOVE or GAME_OVER. Invalid transitions throw.

## Multiplayer

Set seats to Human / Computer / Network player / Not playing. "Host network game" opens a
TCP port (default 5959); each Network seat is filled by the next client to join, and the game
starts when all are filled. The host engine is authoritative: clients send only `ROLL` and
`MOVE|token`, which are validated against turn and phase. If a client disconnects mid-game
its seat is taken over by the AI.

Protocol (one UTF-8 line per message):

    C->S  HELLO|name   ROLL   MOVE|token   BYE
    S->C  WELCOME|seat   REJECT|reason   WAIT|joined|needed
          START|roster|snapshot   UPDATE|events|snapshot   CLOSED|reason

The protocol is unauthenticated plain text: use it on a trusted LAN or behind a tunnel.

## Rules (same as the original)

A 6 is needed to leave the yard; exact roll to reach home; landing on a non-safe track square
captures every opposing token there; a 6 gives another roll (even if unplayable); three 6s in
a row forfeit the turn; first to bring all four tokens home wins.