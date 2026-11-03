package com.ctrlx.desktop;

/**
 * Standard JavaFX bootstrap entry point.
 * Calling Dashboard.main() from a class that does NOT extend
 * javafx.application.Application allows JavaFX runtime to start cleanly
 * from non-modular classpath and bundled jpackage executable.
 */
public class Launcher {
    public static void main(String[] args) {
        Dashboard.main(args);
    }
}
