package com.ctrlx.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * High-performance native bridge communicating with the CTRLX ViGEm Xbox 360
 * controller driver over stdin/stdout. Provides genuine Xbox 360 XInput gamepad
 * emulation to Windows and PC games.
 */
public class ViGEmBridge {
    private Process process;
    private BufferedWriter writer;
    private volatile boolean ready = false;
    private final Object writeLock = new Object();

    public synchronized boolean start() {
        if (ready && process != null && process.isAlive()) {
            return true;
        }

        stop();

        File exeFile = findBridgeExecutable();
        if (exeFile == null || !exeFile.exists()) {
            return false;
        }

        try {
            ProcessBuilder pb = new ProcessBuilder(exeFile.getAbsolutePath());
            if (exeFile.getParentFile() != null) {
                pb.directory(exeFile.getParentFile());
            }
            pb.redirectErrorStream(true);
            process = pb.start();

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));

            // Read the initial line via future with 2000ms timeout
            java.util.concurrent.CompletableFuture<String> future = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    return reader.readLine();
                } catch (Exception e) {
                    return null;
                }
            });

            String line = null;
            try {
                line = future.get(2000, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                future.cancel(true);
            }

            if (line != null && line.contains("READY")) {
                ready = true;
                System.out.println("ViGEmBridge: Virtual Xbox 360 controller connected (" + line.trim() + ")");

                // Drain output in background daemon thread
                Thread drainThread = new Thread(() -> {
                    try {
                        while (reader.readLine() != null) {
                        }
                    } catch (Exception ignored) {
                    }
                }, "ViGEmBridgeDrain");
                drainThread.setDaemon(true);
                drainThread.start();

                return true;
            } else {
                System.err.println("ViGEmBridge: Startup failed or timed out. Got output: " + line);
                stop();
                return false;
            }
        } catch (Exception e) {
            System.err.println("ViGEmBridge: Startup exception: " + e.getMessage());
            stop();
            return false;
        }
    }

    private File findBridgeExecutable() {
        String[] candidates = new String[] {
            "bridge/gamepad-bridge.exe",
            "../bridge/gamepad-bridge.exe",
            "gamepad-bridge/bin/publish/gamepad-bridge.exe",
            "../gamepad-bridge/bin/publish/gamepad-bridge.exe",
            "../../gamepad-bridge/bin/publish/gamepad-bridge.exe"
        };
        for (String path : candidates) {
            File f = new File(path);
            if (f.exists() && f.isFile()) {
                return f.getAbsoluteFile();
            }
        }
        return null;
    }

    public boolean isBridgeExecutableFound() {
        return findBridgeExecutable() != null;
    }

    public boolean isReady() {
        return ready && process != null && process.isAlive();
    }

    public void connectSlot(int slot) {
        if (!isReady()) return;
        writeCommand("CONNECT " + slot);
    }

    public void disconnectSlot(int slot) {
        if (!isReady()) return;
        writeCommand("DISCONNECT " + slot);
    }

    public void sendButton(int slot, String button, boolean isDown) {
        if (!isReady()) return;
        writeCommand("BTN " + slot + " " + button + " " + (isDown ? "1" : "0"));
    }

    public void sendButton(String button, boolean isDown) {
        sendButton(1, button, isDown);
    }

    public void sendAxis(int slot, String stick, float x, float y) {
        if (!isReady()) return;
        writeCommand(String.format(Locale.US, "AXIS %d %s %.4f %.4f", slot, stick, x, y));
    }

    public void sendAxis(String stick, float x, float y) {
        sendAxis(1, stick, x, y);
    }

    public void sendTrigger(int slot, String trigger, float value) {
        if (!isReady()) return;
        writeCommand(String.format(Locale.US, "TRIGGER %d %s %.4f", slot, trigger, value));
    }

    public void sendTrigger(String trigger, float value) {
        sendTrigger(1, trigger, value);
    }

    public void resetSlot(int slot) {
        if (!isReady()) return;
        writeCommand("RESET " + slot);
    }

    public void reset() {
        if (!isReady()) return;
        writeCommand("RESET ALL");
    }

    private void writeCommand(String cmd) {
        synchronized (writeLock) {
            if (writer != null) {
                try {
                    writer.write(cmd);
                    writer.newLine();
                    writer.flush();
                } catch (Exception e) {
                    ready = false;
                }
            }
        }
    }

    public synchronized void stop() {
        ready = false;
        if (writer != null) {
            try {
                writer.write("EXIT");
                writer.newLine();
                writer.flush();
            } catch (Exception ignored) {
            }
            try {
                writer.close();
            } catch (Exception ignored) {
            }
            writer = null;
        }
        if (process != null) {
            try {
                if (!process.waitFor(600, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
            } catch (Exception ignored) {
                process.destroy();
            }
            process = null;
        }
    }
}
