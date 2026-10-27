package com.ludo;

import com.ludo.ui.LudoApp;
import javafx.application.Application;

/** Separate launcher class so the app also starts from a plain (non-modular) fat jar. */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        Application.launch(LudoApp.class, args);
    }
}
