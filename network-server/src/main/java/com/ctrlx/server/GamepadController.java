package com.ctrlx.server;

import java.awt.AWTException;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Xbox Gamepad Controller translating mobile gamepad inputs into native
 * keyboard, mouse aim, trigger, and button operations for PC games.
 */
public class GamepadController {
    public enum Profile {
        FPS_ACTION("FPS / Action"),
        RACING("Racing / Driving"),
        RETRO_PLATFORMER("Retro / Platformer");

        public final String displayName;
        Profile(String displayName) {
            this.displayName = displayName;
        }
    }

    private final Robot robot;
    private final Dimension screenSize;
    private final boolean available;

    private volatile Profile currentProfile = Profile.FPS_ACTION;
    private final Set<Integer> heldKeyCodes = ConcurrentHashMap.newKeySet();
    private final Set<Integer> heldMouseMasks = ConcurrentHashMap.newKeySet();

    // Analog stick states (-1.0 to 1.0)
    private volatile float leftStickX = 0f;
    private volatile float leftStickY = 0f;
    private volatile float rightStickX = 0f;
    private volatile float rightStickY = 0f;

    // Trigger states (0.0 to 1.0)
    private volatile float leftTrigger = 0f;
    private volatile float rightTrigger = 0f;

    // Currently active WASD direction keys from left stick
    private int currentLeftStickHorizontalKey = 0;
    private int currentLeftStickVerticalKey = 0;

    // Right stick camera loop
    private final ScheduledExecutorService aimingExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "GamepadAimingLoop");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> aimingTask;

    private float aimSensitivity = 22.0f;

    public GamepadController() {
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
            System.err.println("Notice: GamepadController native robot unavailable: " + e.getMessage());
        }
        this.robot = r;
        this.screenSize = d != null ? d : new Dimension(1920, 1080);
        this.available = ok;

        startAimingLoop();
    }

    public boolean isAvailable() {
        return available;
    }

    public Profile getCurrentProfile() {
        return currentProfile;
    }

    public void setProfile(Profile profile) {
        if (profile != null && profile != currentProfile) {
            resetAll();
            this.currentProfile = profile;
        }
    }

    public void setProfileByName(String name) {
        if (name == null) return;
        try {
            setProfile(Profile.valueOf(name.toUpperCase()));
        } catch (IllegalArgumentException ignored) {
            if ("RACING".equalsIgnoreCase(name)) setProfile(Profile.RACING);
            else if ("RETRO".equalsIgnoreCase(name) || "PLATFORMER".equalsIgnoreCase(name)) setProfile(Profile.RETRO_PLATFORMER);
            else setProfile(Profile.FPS_ACTION);
        }
    }

    public void setAimSensitivity(float sensitivity) {
        this.aimSensitivity = Math.max(5.0f, Math.min(60.0f, sensitivity));
    }

    /**
     * Handles Xbox button state changes (DOWN or UP).
     */
    public synchronized void handleButton(String button, String state) {
        if (!available || robot == null || button == null) {
            return;
        }
        boolean isDown = "DOWN".equalsIgnoreCase(state);
        String btn = button.toUpperCase().trim();

        // Special handling for triggers passed as button clicks
        if ("LT".equals(btn)) {
            handleTrigger("LT", isDown ? 1.0f : 0.0f);
            return;
        } else if ("RT".equals(btn)) {
            handleTrigger("RT", isDown ? 1.0f : 0.0f);
            return;
        }

        // Xbox Guide Button (Win + G for Xbox Game Bar)
        if ("XBOX".equals(btn) || "GUIDE".equals(btn)) {
            if (isDown) {
                try {
                    robot.keyPress(KeyEvent.VK_WINDOWS);
                    robot.keyPress(KeyEvent.VK_G);
                    robot.keyRelease(KeyEvent.VK_G);
                    robot.keyRelease(KeyEvent.VK_WINDOWS);
                } catch (Exception ignored) {
                }
            }
            return;
        }

        // Mouse-mapped buttons
        if ("R3".equals(btn) && currentProfile == Profile.FPS_ACTION) {
            // R3 as melee/middle mouse click
            setMouseButtonState(InputEvent.BUTTON2_DOWN_MASK, isDown);
            return;
        }

        int keyCode = mapButtonToKeyCode(btn, currentProfile);
        if (keyCode != 0) {
            setKeyState(keyCode, isDown);
        }
    }

    /**
     * Handles analog trigger pressure (0.0 to 1.0).
     */
    public synchronized void handleTrigger(String trigger, float value) {
        if (!available || robot == null || trigger == null) {
            return;
        }
        value = Math.max(0f, Math.min(1f, value));
        boolean pressed = value > 0.25f;

        if ("LT".equalsIgnoreCase(trigger)) {
            leftTrigger = value;
            if (currentProfile == Profile.FPS_ACTION) {
                // Right Mouse Button (Aim Down Sights)
                setMouseButtonState(InputEvent.BUTTON3_DOWN_MASK, pressed);
            } else if (currentProfile == Profile.RACING) {
                // S key (Brake / Reverse)
                setKeyState(KeyEvent.VK_S, pressed);
            } else {
                setKeyState(KeyEvent.VK_D, pressed);
            }
        } else if ("RT".equalsIgnoreCase(trigger)) {
            rightTrigger = value;
            if (currentProfile == Profile.FPS_ACTION) {
                // Left Mouse Button (Fire / Attack)
                setMouseButtonState(InputEvent.BUTTON1_DOWN_MASK, pressed);
            } else if (currentProfile == Profile.RACING) {
                // W key (Accelerate / Gas)
                setKeyState(KeyEvent.VK_W, pressed);
            } else {
                setKeyState(KeyEvent.VK_F, pressed);
            }
        }
    }

    /**
     * Handles analog stick coordinates (-1.0 to 1.0).
     */
    public synchronized void handleAxis(String stick, float x, float y) {
        if (!available || robot == null || stick == null) {
            return;
        }
        x = Math.max(-1.0f, Math.min(1.0f, x));
        y = Math.max(-1.0f, Math.min(1.0f, y));

        if ("LEFT".equalsIgnoreCase(stick)) {
            leftStickX = x;
            leftStickY = y;
            updateLeftStickKeys(x, y);
        } else if ("RIGHT".equalsIgnoreCase(stick)) {
            rightStickX = x;
            rightStickY = y;
        }
    }

    /**
     * Translates left stick tilt into directional WASD (or Arrow keys).
     */
    private void updateLeftStickKeys(float x, float y) {
        float deadzone = 0.22f;
        int targetH = 0;
        int targetV = 0;

        int upKey = currentProfile == Profile.RETRO_PLATFORMER ? KeyEvent.VK_UP : KeyEvent.VK_W;
        int downKey = currentProfile == Profile.RETRO_PLATFORMER ? KeyEvent.VK_DOWN : KeyEvent.VK_S;
        int leftKey = currentProfile == Profile.RETRO_PLATFORMER ? KeyEvent.VK_LEFT : KeyEvent.VK_A;
        int rightKey = currentProfile == Profile.RETRO_PLATFORMER ? KeyEvent.VK_RIGHT : KeyEvent.VK_D;

        if (x < -deadzone) {
            targetH = leftKey;
        } else if (x > deadzone) {
            targetH = rightKey;
        }

        if (y < -deadzone) {
            targetV = upKey;
        } else if (y > deadzone) {
            targetV = downKey;
        }

        // Apply horizontal changes
        if (targetH != currentLeftStickHorizontalKey) {
            if (currentLeftStickHorizontalKey != 0) {
                setKeyState(currentLeftStickHorizontalKey, false);
            }
            if (targetH != 0) {
                setKeyState(targetH, true);
            }
            currentLeftStickHorizontalKey = targetH;
        }

        // Apply vertical changes
        if (targetV != currentLeftStickVerticalKey) {
            if (currentLeftStickVerticalKey != 0) {
                setKeyState(currentLeftStickVerticalKey, false);
            }
            if (targetV != 0) {
                setKeyState(targetV, true);
            }
            currentLeftStickVerticalKey = targetV;
        }
    }

    /**
     * High frequency aiming loop for analog right thumbstick camera movement.
     */
    private void startAimingLoop() {
        if (!available || robot == null) {
            return;
        }
        aimingTask = aimingExecutor.scheduleAtFixedRate(() -> {
            try {
                float rx = rightStickX;
                float ry = rightStickY;
                float mag = (float) Math.hypot(rx, ry);
                float deadzone = 0.08f;

                if (mag > deadzone) {
                    // Smooth power response curve for precision aiming
                    float normX = rx / mag;
                    float normY = ry / mag;
                    float scaledMag = (mag - deadzone) / (1.0f - deadzone);
                    float response = (float) Math.pow(scaledMag, 1.45) * aimSensitivity;

                    int dx = Math.round(normX * response);
                    int dy = Math.round(normY * response);

                    if (dx != 0 || dy != 0) {
                        Point current = MouseInfo.getPointerInfo().getLocation();
                        int newX = Math.max(0, Math.min(screenSize.width - 1, current.x + dx));
                        int newY = Math.max(0, Math.min(screenSize.height - 1, current.y + dy));
                        robot.mouseMove(newX, newY);
                    }
                }
            } catch (Exception ignored) {
            }
        }, 0, 16, TimeUnit.MILLISECONDS); // ~60 Hz polling
    }

    private void setKeyState(int keyCode, boolean down) {
        if (!available || robot == null || keyCode == 0) return;
        try {
            if (down) {
                if (heldKeyCodes.add(keyCode)) {
                    robot.keyPress(keyCode);
                }
            } else {
                if (heldKeyCodes.remove(keyCode)) {
                    robot.keyRelease(keyCode);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void setMouseButtonState(int buttonMask, boolean down) {
        if (!available || robot == null || buttonMask == 0) return;
        try {
            if (down) {
                if (heldMouseMasks.add(buttonMask)) {
                    robot.mousePress(buttonMask);
                }
            } else {
                if (heldMouseMasks.remove(buttonMask)) {
                    robot.mouseRelease(buttonMask);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private int mapButtonToKeyCode(String button, Profile profile) {
        switch (profile) {
            case RACING:
                switch (button) {
                    case "A": return KeyEvent.VK_SPACE;       // Handbrake
                    case "B": return KeyEvent.VK_SHIFT;       // Nitro / Boost
                    case "X": return KeyEvent.VK_C;           // Change View / Camera
                    case "Y": return KeyEvent.VK_R;           // Reset Vehicle
                    case "LB": return KeyEvent.VK_Q;          // Look Back
                    case "RB": return KeyEvent.VK_E;          // Horn / Special
                    case "START": return KeyEvent.VK_ESCAPE;  // Pause
                    case "BACK": case "VIEW": return KeyEvent.VK_TAB; // Map / Leaderboard
                    case "DPAD_UP": return KeyEvent.VK_UP;
                    case "DPAD_DOWN": return KeyEvent.VK_DOWN;
                    case "DPAD_LEFT": return KeyEvent.VK_LEFT;
                    case "DPAD_RIGHT": return KeyEvent.VK_RIGHT;
                    case "L3": return KeyEvent.VK_SHIFT;
                    case "R3": return KeyEvent.VK_V;
                    default: return 0;
                }
            case RETRO_PLATFORMER:
                switch (button) {
                    case "A": return KeyEvent.VK_SPACE;       // Jump
                    case "B": return KeyEvent.VK_X;           // Dash / Run
                    case "X": return KeyEvent.VK_C;           // Attack
                    case "Y": return KeyEvent.VK_V;           // Special
                    case "LB": return KeyEvent.VK_A;
                    case "RB": return KeyEvent.VK_S;
                    case "START": return KeyEvent.VK_ENTER;   // Start
                    case "BACK": case "VIEW": return KeyEvent.VK_ESCAPE; // Select
                    case "DPAD_UP": return KeyEvent.VK_UP;
                    case "DPAD_DOWN": return KeyEvent.VK_DOWN;
                    case "DPAD_LEFT": return KeyEvent.VK_LEFT;
                    case "DPAD_RIGHT": return KeyEvent.VK_RIGHT;
                    case "L3": return KeyEvent.VK_Z;
                    case "R3": return KeyEvent.VK_X;
                    default: return 0;
                }
            case FPS_ACTION:
            default:
                switch (button) {
                    case "A": return KeyEvent.VK_SPACE;       // Jump / Confirm
                    case "B": return KeyEvent.VK_CONTROL;     // Crouch / Slide
                    case "X": return KeyEvent.VK_R;           // Reload / Interact
                    case "Y": return KeyEvent.VK_F;           // Use / Pick up / Weapon Switch
                    case "LB": return KeyEvent.VK_SHIFT;      // Sprint
                    case "RB": return KeyEvent.VK_E;          // Ability / Grenade
                    case "START": return KeyEvent.VK_ESCAPE;  // Menu / Pause
                    case "BACK": case "VIEW": return KeyEvent.VK_TAB; // Scoreboard / Inventory
                    case "DPAD_UP": return KeyEvent.VK_1;     // Weapon 1
                    case "DPAD_DOWN": return KeyEvent.VK_2;   // Weapon 2
                    case "DPAD_LEFT": return KeyEvent.VK_3;   // Item 3
                    case "DPAD_RIGHT": return KeyEvent.VK_4;  // Item 4
                    case "L3": return KeyEvent.VK_SHIFT;      // Sprint Toggle
                    default: return 0;
                }
        }
    }

    /**
     * Safely releases all held keys and mouse buttons.
     */
    public synchronized void resetAll() {
        if (!available || robot == null) {
            heldKeyCodes.clear();
            heldMouseMasks.clear();
            return;
        }

        leftStickX = 0f;
        leftStickY = 0f;
        rightStickX = 0f;
        rightStickY = 0f;
        leftTrigger = 0f;
        rightTrigger = 0f;
        currentLeftStickHorizontalKey = 0;
        currentLeftStickVerticalKey = 0;

        for (int code : heldKeyCodes) {
            try {
                robot.keyRelease(code);
            } catch (Exception ignored) {
            }
        }
        heldKeyCodes.clear();

        for (int mask : heldMouseMasks) {
            try {
                robot.mouseRelease(mask);
            } catch (Exception ignored) {
            }
        }
        heldMouseMasks.clear();
    }

    public void shutdown() {
        resetAll();
        if (aimingTask != null) {
            aimingTask.cancel(true);
        }
        aimingExecutor.shutdownNow();
    }
}
