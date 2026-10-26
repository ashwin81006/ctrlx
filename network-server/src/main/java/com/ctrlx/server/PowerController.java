package com.ctrlx.server;

import com.sun.jna.Library;
import com.sun.jna.Native;

/**
 * Windows Power & System Action Executor supporting:
 * - Lock Workstation
 * - Low-power Sleep
 * - System Restart
 * - System Shutdown
 */
public class PowerController {

    public interface User32Library extends Library {
        User32Library INSTANCE = Native.load("user32", User32Library.class);
        boolean LockWorkStation();
    }

    private boolean user32Available = true;

    public PowerController() {
        try {
            User32Library lib = User32Library.INSTANCE;
        } catch (Throwable t) {
            user32Available = false;
        }
    }

    public synchronized void executeAction(String action) {
        if (action == null) return;
        String cmd = action.toUpperCase().trim();
        System.out.println("[PowerController] Executing power action: " + cmd);
        switch (cmd) {
            case "LOCK":
                lock();
                break;
            case "SLEEP":
                sleep();
                break;
            case "RESTART":
                restart();
                break;
            case "SHUTDOWN":
                shutdown();
                break;
            default:
                System.err.println("[PowerController] Unknown power action: " + action);
                break;
        }
    }

    public void lock() {
        if (user32Available) {
            try {
                if (User32Library.INSTANCE.LockWorkStation()) {
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        executeProcess("rundll32.exe", "user32.dll,LockWorkStation");
    }

    public void sleep() {
        executeProcess("rundll32.exe", "powrprof.dll,SetSuspendState", "0,1,0");
    }

    public void restart() {
        executeProcess("shutdown.exe", "/r", "/t", "0");
    }

    public void shutdown() {
        executeProcess("shutdown.exe", "/s", "/t", "0");
    }

    private void executeProcess(String... command) {
        try {
            new ProcessBuilder(command).start();
        } catch (Exception e) {
            System.err.println("Failed to execute power command: " + String.join(" ", command) + " -> " + e.getMessage());
        }
    }
}
