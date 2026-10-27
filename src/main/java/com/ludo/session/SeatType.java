package com.ludo.session;

public enum SeatType {
    HUMAN("Human (this device)"),
    AI("Computer"),
    NETWORK("Network player"),
    OFF("Not playing");

    private final String label;
    SeatType(String label) { this.label = label; }
    @Override public String toString() { return label; }
}
