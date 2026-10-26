package com.ctrlx.server;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 100% In-Process Native ViGEmBus Bridge for CTRLX.
 * Directly interfaces with ViGEmClient.dll via JNA (Java Native Access),
 * creating genuine virtual Xbox 360 controllers with zero external process overhead,
 * ultra-low latency, and support for up to 4 concurrent controller slots.
 */
public class ViGEmBridge {

    public interface ViGEmNativeLibrary extends Library {
        Pointer vigem_alloc();
        void vigem_free(Pointer vigem);

        int vigem_connect(Pointer vigem);
        void vigem_disconnect(Pointer vigem);

        Pointer vigem_target_x360_alloc();
        void vigem_target_free(Pointer target);

        int vigem_target_add(Pointer vigem, Pointer target);
        int vigem_target_remove(Pointer vigem, Pointer target);

        int vigem_target_x360_update(Pointer vigem, Pointer target, XUSB_REPORT report);
    }

    public static class XUSB_REPORT extends Structure implements Structure.ByValue {
        public short wButtons = 0;
        public byte bLeftTrigger = 0;
        public byte bRightTrigger = 0;
        public short sThumbLX = 0;
        public short sThumbLY = 0;
        public short sThumbRX = 0;
        public short sThumbRY = 0;

        public XUSB_REPORT() {
            super();
        }

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("wButtons", "bLeftTrigger", "bRightTrigger", "sThumbLX", "sThumbLY", "sThumbRX", "sThumbRY");
        }
    }

    // ViGEm error codes
    private static final int VIGEM_ERROR_NONE = 0x20000000;

    // Xbox 360 controller button bit masks (Standard XInput)
    public static final short BUTTON_DPAD_UP = 0x0001;
    public static final short BUTTON_DPAD_DOWN = 0x0002;
    public static final short BUTTON_DPAD_LEFT = 0x0004;
    public static final short BUTTON_DPAD_RIGHT = 0x0008;
    public static final short BUTTON_START = 0x0010;
    public static final short BUTTON_BACK = 0x0020;
    public static final short BUTTON_LEFT_THUMB = 0x0040;
    public static final short BUTTON_RIGHT_THUMB = 0x0080;
    public static final short BUTTON_LEFT_SHOULDER = 0x0100;
    public static final short BUTTON_RIGHT_SHOULDER = 0x0200;
    public static final short BUTTON_GUIDE = 0x0400;
    public static final short BUTTON_A = 0x1000;
    public static final short BUTTON_B = 0x2000;
    public static final short BUTTON_X = 0x4000;
    public static final short BUTTON_Y = (short) 0x8000;

    private static final Map<String, Short> BUTTON_MAP = new HashMap<>();
    static {
        BUTTON_MAP.put("A", BUTTON_A);
        BUTTON_MAP.put("B", BUTTON_B);
        BUTTON_MAP.put("X", BUTTON_X);
        BUTTON_MAP.put("Y", BUTTON_Y);
        BUTTON_MAP.put("LB", BUTTON_LEFT_SHOULDER);
        BUTTON_MAP.put("LEFTSHOULDER", BUTTON_LEFT_SHOULDER);
        BUTTON_MAP.put("RB", BUTTON_RIGHT_SHOULDER);
        BUTTON_MAP.put("RIGHTSHOULDER", BUTTON_RIGHT_SHOULDER);
        BUTTON_MAP.put("L3", BUTTON_LEFT_THUMB);
        BUTTON_MAP.put("LS", BUTTON_LEFT_THUMB);
        BUTTON_MAP.put("R3", BUTTON_RIGHT_THUMB);
        BUTTON_MAP.put("RS", BUTTON_RIGHT_THUMB);
        BUTTON_MAP.put("START", BUTTON_START);
        BUTTON_MAP.put("MENU", BUTTON_START);
        BUTTON_MAP.put("BACK", BUTTON_BACK);
        BUTTON_MAP.put("VIEW", BUTTON_BACK);
        BUTTON_MAP.put("SELECT", BUTTON_BACK);
        BUTTON_MAP.put("XBOX", BUTTON_GUIDE);
        BUTTON_MAP.put("GUIDE", BUTTON_GUIDE);
        BUTTON_MAP.put("UP", BUTTON_DPAD_UP);
        BUTTON_MAP.put("DPAD_UP", BUTTON_DPAD_UP);
        BUTTON_MAP.put("DOWN", BUTTON_DPAD_DOWN);
        BUTTON_MAP.put("DPAD_DOWN", BUTTON_DPAD_DOWN);
        BUTTON_MAP.put("LEFT", BUTTON_DPAD_LEFT);
        BUTTON_MAP.put("DPAD_LEFT", BUTTON_DPAD_LEFT);
        BUTTON_MAP.put("RIGHT", BUTTON_DPAD_RIGHT);
        BUTTON_MAP.put("DPAD_RIGHT", BUTTON_DPAD_RIGHT);
    }

    private static class ControllerSlot {
        final int slot;
        Pointer target;
        final XUSB_REPORT report = new XUSB_REPORT();

        ControllerSlot(int slot, Pointer target) {
            this.slot = slot;
            this.target = target;
        }
    }

    private ViGEmNativeLibrary nativeLib;
    private Pointer client;
    private volatile boolean ready = false;
    private final Map<Integer, ControllerSlot> slots = new ConcurrentHashMap<>();

    public ViGEmBridge() {
        // Register JVM shutdown hook to clean up virtual controllers cleanly on app close
        Runtime.getRuntime().addShutdownHook(new Thread(this::stop, "ViGEmBridgeShutdownHook"));
    }

    public synchronized boolean start() {
        if (ready && client != null) {
            return true;
        }

        stop();

        try {
            nativeLib = loadNativeLibrary();
            if (nativeLib == null) {
                System.err.println("ViGEmBridge: Native ViGEmClient.dll could not be loaded.");
                return false;
            }

            client = nativeLib.vigem_alloc();
            if (client == null) {
                System.err.println("ViGEmBridge: vigem_alloc returned null.");
                return false;
            }

            int connectResult = nativeLib.vigem_connect(client);
            if (connectResult != VIGEM_ERROR_NONE) {
                System.err.printf("ViGEmBridge: vigem_connect failed with code 0x%08X (Bus driver might not be installed).\n", connectResult);
                nativeLib.vigem_free(client);
                client = null;
                return false;
            }

            ready = true;
            System.out.println("ViGEmBridge: Native ViGEmBus client connected successfully. Direct Java in-process virtual Xbox 360 ready.");
            return true;
        } catch (Throwable t) {
            System.err.println("ViGEmBridge: Startup failed with exception: " + t.getMessage());
            stop();
            return false;
        }
    }

    private ViGEmNativeLibrary loadNativeLibrary() {
        String[] candidatePaths = {
            "bridge/ViGEmClient.dll",
            "../bridge/ViGEmClient.dll",
            "network-server/src/main/resources/win32-x86-64/ViGEmClient.dll",
            "../network-server/src/main/resources/win32-x86-64/ViGEmClient.dll"
        };
        for (String path : candidatePaths) {
            File f = new File(path);
            if (f.exists() && f.isFile()) {
                try {
                    return Native.load(f.getAbsolutePath(), ViGEmNativeLibrary.class);
                } catch (Throwable ignored) {
                }
            }
        }

        // Fall back to JNA classpath resource extraction (/win32-x86-64/ViGEmClient.dll)
        try {
            return Native.load("ViGEmClient", ViGEmNativeLibrary.class);
        } catch (Throwable t) {
            System.err.println("ViGEmBridge: JNA fallback load failed: " + t.getMessage());
            return null;
        }
    }

    public boolean isBridgeExecutableFound() {
        return isAvailable();
    }

    public boolean isAvailable() {
        if (ready) return true;
        String[] candidatePaths = {
            "bridge/ViGEmClient.dll",
            "../bridge/ViGEmClient.dll",
            "network-server/src/main/resources/win32-x86-64/ViGEmClient.dll",
            "../network-server/src/main/resources/win32-x86-64/ViGEmClient.dll"
        };
        for (String path : candidatePaths) {
            File f = new File(path);
            if (f.exists() && f.isFile()) {
                return true;
            }
        }
        return ViGEmBridge.class.getResource("/win32-x86-64/ViGEmClient.dll") != null;
    }

    public boolean isReady() {
        return ready && client != null;
    }

    private synchronized ControllerSlot getOrCreateSlot(int slot) {
        if (!isReady()) return null;
        ControllerSlot existing = slots.get(slot);
        if (existing != null) {
            return existing;
        }

        try {
            Pointer target = nativeLib.vigem_target_x360_alloc();
            if (target == null) {
                System.err.println("ViGEmBridge: Failed to allocate target for slot " + slot);
                return null;
            }

            int addRes = nativeLib.vigem_target_add(client, target);
            if (addRes != VIGEM_ERROR_NONE) {
                System.err.printf("ViGEmBridge: Failed to add target for slot %d: 0x%08X\n", slot, addRes);
                nativeLib.vigem_target_free(target);
                return null;
            }

            ControllerSlot cs = new ControllerSlot(slot, target);
            slots.put(slot, cs);
            updateSlotReport(cs);
            System.out.println("ViGEmBridge: Connected virtual Xbox 360 controller in Player Slot " + slot);
            return cs;
        } catch (Throwable t) {
            System.err.println("ViGEmBridge: Exception connecting slot " + slot + ": " + t.getMessage());
            return null;
        }
    }

    private void updateSlotReport(ControllerSlot cs) {
        if (cs == null || cs.target == null || nativeLib == null || client == null) return;
        try {
            nativeLib.vigem_target_x360_update(client, cs.target, cs.report);
        } catch (Throwable t) {
            System.err.println("ViGEmBridge: Update error on slot " + cs.slot + ": " + t.getMessage());
        }
    }

    public void connectSlot(int slot) {
        if (!isReady()) return;
        getOrCreateSlot(slot);
    }

    public synchronized void disconnectSlot(int slot) {
        if (nativeLib == null || client == null) return;
        ControllerSlot cs = slots.remove(slot);
        if (cs != null && cs.target != null) {
            try {
                resetSlot(slot);
                nativeLib.vigem_target_remove(client, cs.target);
                nativeLib.vigem_target_free(cs.target);
                System.out.println("ViGEmBridge: Disconnected virtual Xbox 360 controller in Player Slot " + slot);
            } catch (Throwable t) {
                System.err.println("ViGEmBridge: Error disconnecting slot " + slot + ": " + t.getMessage());
            }
            cs.target = null;
        }
    }

    public void sendButton(int slot, String button, boolean isDown) {
        if (!isReady() || button == null) return;
        String btn = button.toUpperCase().trim();

        if ("LT".equals(btn)) {
            sendTrigger(slot, "LT", isDown ? 1.0f : 0.0f);
            return;
        }
        if ("RT".equals(btn)) {
            sendTrigger(slot, "RT", isDown ? 1.0f : 0.0f);
            return;
        }

        Short mask = BUTTON_MAP.get(btn);
        if (mask == null) return;

        ControllerSlot cs = getOrCreateSlot(slot);
        if (cs == null) return;

        synchronized (cs) {
            if (isDown) {
                cs.report.wButtons |= mask;
            } else {
                cs.report.wButtons &= ~mask;
            }
            updateSlotReport(cs);
        }
    }

    public void sendButton(String button, boolean isDown) {
        sendButton(1, button, isDown);
    }

    public void sendAxis(int slot, String stick, float x, float y) {
        if (!isReady() || stick == null) return;
        ControllerSlot cs = getOrCreateSlot(slot);
        if (cs == null) return;

        short shortX = (short) Math.max(-32768, Math.min(32767, Math.round(x * 32767f)));
        // Invert Y so up on mobile joystick is positive in XInput standard
        short shortY = (short) Math.max(-32768, Math.min(32767, Math.round(-y * 32767f)));

        synchronized (cs) {
            String s = stick.toUpperCase().trim();
            if ("LEFT".equals(s) || "LS".equals(s)) {
                cs.report.sThumbLX = shortX;
                cs.report.sThumbLY = shortY;
            } else if ("RIGHT".equals(s) || "RS".equals(s)) {
                cs.report.sThumbRX = shortX;
                cs.report.sThumbRY = shortY;
            }
            updateSlotReport(cs);
        }
    }

    public void sendAxis(String stick, float x, float y) {
        sendAxis(1, stick, x, y);
    }

    public void sendTrigger(int slot, String trigger, float value) {
        if (!isReady() || trigger == null) return;
        ControllerSlot cs = getOrCreateSlot(slot);
        if (cs == null) return;

        byte byteVal = (byte) Math.max(0, Math.min(255, Math.round(value * 255f)));

        synchronized (cs) {
            String t = trigger.toUpperCase().trim();
            if ("LT".equals(t) || "LEFT".equals(t)) {
                cs.report.bLeftTrigger = byteVal;
            } else if ("RT".equals(t) || "RIGHT".equals(t)) {
                cs.report.bRightTrigger = byteVal;
            }
            updateSlotReport(cs);
        }
    }

    public void sendTrigger(String trigger, float value) {
        sendTrigger(1, trigger, value);
    }

    public void resetSlot(int slot) {
        ControllerSlot cs = slots.get(slot);
        if (cs == null) return;
        synchronized (cs) {
            cs.report.wButtons = 0;
            cs.report.bLeftTrigger = 0;
            cs.report.bRightTrigger = 0;
            cs.report.sThumbLX = 0;
            cs.report.sThumbLY = 0;
            cs.report.sThumbRX = 0;
            cs.report.sThumbRY = 0;
            updateSlotReport(cs);
        }
    }

    public void reset() {
        for (Integer slot : slots.keySet()) {
            resetSlot(slot);
        }
    }

    public synchronized void stop() {
        ready = false;
        if (nativeLib != null && client != null) {
            for (Map.Entry<Integer, ControllerSlot> entry : slots.entrySet()) {
                ControllerSlot cs = entry.getValue();
                if (cs != null && cs.target != null) {
                    try {
                        cs.report.wButtons = 0;
                        cs.report.bLeftTrigger = 0;
                        cs.report.bRightTrigger = 0;
                        cs.report.sThumbLX = 0;
                        cs.report.sThumbLY = 0;
                        cs.report.sThumbRX = 0;
                        cs.report.sThumbRY = 0;
                        nativeLib.vigem_target_x360_update(client, cs.target, cs.report);
                        nativeLib.vigem_target_remove(client, cs.target);
                        nativeLib.vigem_target_free(cs.target);
                    } catch (Throwable ignored) {
                    }
                    cs.target = null;
                }
            }
            slots.clear();

            try {
                nativeLib.vigem_disconnect(client);
            } catch (Throwable ignored) {
            }
            try {
                nativeLib.vigem_free(client);
            } catch (Throwable ignored) {
            }
            client = null;
        }
    }
}
