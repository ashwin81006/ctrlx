package com.ctrlx.server;

import java.awt.AWTException;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.InputEvent;

/**
 * Windows PC Mouse Controller translating authenticated remote network commands
 * into native cursor movement, clicks, and scrolling using java.awt.Robot.
 */
public class MouseController {
    private final Robot robot;
    private final Dimension screenSize;
    private final boolean available;

    public MouseController() {
        Robot r = null;
        Dimension d = null;
        boolean ok = false;
        try {
            if (!GraphicsEnvironment.isHeadless()) {
                r = new Robot();
                r.setAutoWaitForIdle(false);
                d = Toolkit.getDefaultToolkit().getScreenSize();
                ok = true;
            }
        } catch (AWTException | RuntimeException e) {
            System.err.println("Notice: Native desktop mouse controller unavailable: " + e.getMessage());
        }
        this.robot = r;
        this.screenSize = d != null ? d : new Dimension(1920, 1080);
        this.available = ok;
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * Moves pointer relatively by dx, dy with boundary clamping.
     */
    public synchronized void moveRelative(int dx, int dy) {
        if (!available || robot == null) {
            return;
        }
        // Clamp relative delta within reasonable range to prevent jumps
        dx = Math.max(-500, Math.min(500, dx));
        dy = Math.max(-500, Math.min(500, dy));

        try {
            Point current = MouseInfo.getPointerInfo().getLocation();
            int newX = Math.max(0, Math.min(screenSize.width - 1, current.x + dx));
            int newY = Math.max(0, Math.min(screenSize.height - 1, current.y + dy));
            robot.mouseMove(newX, newY);
        } catch (Exception ignored) {
        }
    }

    /**
     * Performs a single mouse click (press and release).
     */
    public synchronized void click(String button) {
        if (!available || robot == null) {
            return;
        }
        int mask = getButtonMask(button);
        try {
            robot.mousePress(mask);
            robot.mouseRelease(mask);
        } catch (Exception ignored) {
        }
    }

    /**
     * Presses mouse button down (for drag and drop).
     */
    public synchronized void press(String button) {
        if (!available || robot == null) {
            return;
        }
        try {
            robot.mousePress(getButtonMask(button));
        } catch (Exception ignored) {
        }
    }

    /**
     * Releases mouse button.
     */
    public synchronized void release(String button) {
        if (!available || robot == null) {
            return;
        }
        try {
            robot.mouseRelease(getButtonMask(button));
        } catch (Exception ignored) {
        }
    }

    /**
     * Scrolls vertically (negative = scroll up, positive = scroll down).
     */
    public synchronized void scroll(int amount) {
        if (!available || robot == null) {
            return;
        }
        amount = Math.max(-100, Math.min(100, amount));
        try {
            robot.mouseWheel(amount);
        } catch (Exception ignored) {
        }
    }

    private int getButtonMask(String button) {
        if ("RIGHT".equalsIgnoreCase(button)) {
            return InputEvent.BUTTON3_DOWN_MASK;
        }
        return InputEvent.BUTTON1_DOWN_MASK;
    }

    /**
     * Types text by injecting it via the clipboard and Ctrl+V for full Unicode support.
     */
    public synchronized void typeText(String text) {
        if (!available || robot == null || text == null || text.isEmpty()) {
            return;
        }
        try {
            java.awt.datatransfer.StringSelection sel = new java.awt.datatransfer.StringSelection(text);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, sel);
            robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL);
            robot.keyPress(java.awt.event.KeyEvent.VK_V);
            robot.keyRelease(java.awt.event.KeyEvent.VK_V);
            robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL);
        } catch (Exception ignored) {
        }
    }

    /**
     * Presses and releases a keyboard key by name.
     */
    public synchronized void pressKey(String key) {
        if (!available || robot == null || key == null) {
            return;
        }
        int code = getKeyCode(key.toUpperCase());
        if (code != 0) {
            try {
                robot.keyPress(code);
                robot.keyRelease(code);
            } catch (Exception ignored) {
            }
        }
    }

    private int getKeyCode(String key) {
        switch (key) {
            case "ENTER": return java.awt.event.KeyEvent.VK_ENTER;
            case "BACKSPACE": return java.awt.event.KeyEvent.VK_BACK_SPACE;
            case "TAB": return java.awt.event.KeyEvent.VK_TAB;
            case "SPACE": return java.awt.event.KeyEvent.VK_SPACE;
            case "ESCAPE": case "ESC": return java.awt.event.KeyEvent.VK_ESCAPE;
            case "UP": return java.awt.event.KeyEvent.VK_UP;
            case "DOWN": return java.awt.event.KeyEvent.VK_DOWN;
            case "LEFT": return java.awt.event.KeyEvent.VK_LEFT;
            case "RIGHT": return java.awt.event.KeyEvent.VK_RIGHT;
            case "DELETE": return java.awt.event.KeyEvent.VK_DELETE;
            case "HOME": return java.awt.event.KeyEvent.VK_HOME;
            case "END": return java.awt.event.KeyEvent.VK_END;
            case "WIN": case "WINDOWS": return java.awt.event.KeyEvent.VK_WINDOWS;
            default: return 0;
        }
    }
}
