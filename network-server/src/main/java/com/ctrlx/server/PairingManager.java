package com.ctrlx.server;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages device pairing, PIN verification, persistent credentials, and session authentication.
 */
public class PairingManager {
    private static final String PAIRED_DEVICES_FILE = "paired_devices.json";
    private static final String ADMIN_TOKEN_FILE = "server.token";
    private static final int PIN_EXPIRY_MS = 60_000;
    private static final int MAX_FAILED_ATTEMPTS = 3;

    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, PendingPairing> pendingPairings = new ConcurrentHashMap<>();
    private final Map<String, PairedDevice> pairedDevices = new ConcurrentHashMap<>();
    private final List<PairingListener> listeners = new CopyOnWriteArrayList<>();
    private final List<PairingClearListener> clearListeners = new CopyOnWriteArrayList<>();
    private String adminTokenHash;

    public interface PairingListener {
        void onPinGenerated(String deviceId, String deviceName, String pin);
    }

    public interface PairingClearListener {
        void onPinCleared(String deviceId);
    }

    public static class PairedDevice {
        public String deviceId;
        public String deviceName;
        public String tokenHash;
        public long pairedAt;

        public PairedDevice(String deviceId, String deviceName, String tokenHash, long pairedAt) {
            this.deviceId = deviceId;
            this.deviceName = deviceName;
            this.tokenHash = tokenHash;
            this.pairedAt = pairedAt;
        }
    }

    private static class PendingPairing {
        final String deviceName;
        final String pin;
        final long expiresAt;
        final AtomicInteger failedAttempts = new AtomicInteger(0);

        PendingPairing(String deviceName, String pin, long expiresAt) {
            this.deviceName = deviceName;
            this.pin = pin;
            this.expiresAt = expiresAt;
        }
    }

    public enum PinVerifyStatus {
        SUCCESS,
        INVALID_PIN,
        EXPIRED,
        TOO_MANY_ATTEMPTS,
        NO_PENDING_REQUEST
    }

    public static class PinVerifyResult {
        public final PinVerifyStatus status;
        public final String token; // only non-null on SUCCESS

        public PinVerifyResult(PinVerifyStatus status, String token) {
            this.status = status;
            this.token = token;
        }
    }

    public PairingManager() {
        // Pairing is ephemeral per server run: clean up any legacy file and start fresh
        try {
            File file = new File(PAIRED_DEVICES_FILE);
            if (file.exists()) {
                file.delete();
            }
        } catch (Exception ignored) {
        }
        resetActivePin();
        initializeAdminToken();
    }

    public void addListener(PairingListener listener) {
        if (listener != null) {
            this.listeners.add(listener);
        }
    }

    public void addClearListener(PairingClearListener listener) {
        if (listener != null) {
            this.clearListeners.add(listener);
        }
    }

    public void notifyPinCleared(String deviceId) {
        for (PairingClearListener l : clearListeners) {
            try {
                l.onPinCleared(deviceId);
            } catch (Exception ignored) {
            }
        }
    }

    public String getPendingPinForTesting(String deviceId) {
        PendingPairing pending = pendingPairings.get(deviceId);
        return pending != null ? pending.pin : null;
    }

    private String activeGlobalPin = null;

    public synchronized String getOrCreateActivePin() {
        if (activeGlobalPin == null) {
            int pinNum = secureRandom.nextInt(900_000) + 100_000;
            activeGlobalPin = String.valueOf(pinNum);
        }
        return activeGlobalPin;
    }

    public synchronized void resetActivePin() {
        activeGlobalPin = null;
        getOrCreateActivePin();
    }

    /**
     * Initializes or loads the local administration token for loopback/dashboard access.
     */
    private synchronized void initializeAdminToken() {
        File tokenFile = new File(ADMIN_TOKEN_FILE);
        File userHomeCtrlx = new File(System.getProperty("user.home"), ".ctrlx");
        if (!userHomeCtrlx.exists()) {
            userHomeCtrlx.mkdirs();
        }
        File globalTokenFile = new File(userHomeCtrlx, "server.token");

        // Try reading existing token from local or user home
        String token = readTokenFromFile(tokenFile);
        if (token == null) {
            token = readTokenFromFile(globalTokenFile);
        }

        if (token != null) {
            this.adminTokenHash = sha256Hex(token);
            writeTokenToFile(tokenFile, token);
            writeTokenToFile(globalTokenFile, token);
            return;
        }

        // Generate new local admin token
        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        this.adminTokenHash = sha256Hex(token);

        writeTokenToFile(tokenFile, token);
        writeTokenToFile(globalTokenFile, token);
    }

    private String readTokenFromFile(File file) {
        if (!file.exists()) {
            return null;
        }
        try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
            char[] buf = new char[128];
            int read = reader.read(buf);
            if (read > 0) {
                return new String(buf, 0, read).trim();
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    private void writeTokenToFile(File file, String token) {
        try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
            writer.write(token);
        } catch (IOException e) {
            System.err.println("Notice: Could not write token file " + file.getPath() + ": " + e.getMessage());
        }
    }

    /**
     * Initiates a pairing request. Returns the generated PIN.
     */
    public synchronized String initiatePairing(String deviceId, String deviceName) {
        if (!isValidDeviceId(deviceId)) {
            throw new IllegalArgumentException("Invalid deviceId format");
        }
        if (deviceName == null || deviceName.trim().isEmpty() || deviceName.length() > 64) {
            deviceName = "Unknown Device";
        }

        String pin = getOrCreateActivePin();
        long expiresAt = System.currentTimeMillis() + PIN_EXPIRY_MS;

        PendingPairing pending = new PendingPairing(deviceName, pin, expiresAt);
        pendingPairings.put(deviceId, pending);

        for (PairingListener l : listeners) {
            try {
                l.onPinGenerated(deviceId, deviceName, pin);
            } catch (Exception ignored) {
            }
        }

        return pin;
    }

    /**
     * Verifies the PIN entered by the client.
     */
    public PinVerifyResult verifyPin(String deviceId, String enteredPin) {
        if (!isValidDeviceId(deviceId) || enteredPin == null || !enteredPin.matches("^[0-9]{6}$")) {
            return new PinVerifyResult(PinVerifyStatus.INVALID_PIN, null);
        }

        PendingPairing pending = pendingPairings.get(deviceId);
        if (pending == null) {
            return new PinVerifyResult(PinVerifyStatus.NO_PENDING_REQUEST, null);
        }

        if (System.currentTimeMillis() > pending.expiresAt) {
            pendingPairings.remove(deviceId);
            return new PinVerifyResult(PinVerifyStatus.EXPIRED, null);
        }

        boolean match = MessageDigest.isEqual(
                enteredPin.getBytes(StandardCharsets.UTF_8),
                pending.pin.getBytes(StandardCharsets.UTF_8)
        );

        if (!match) {
            int attempts = pending.failedAttempts.incrementAndGet();
            if (attempts >= MAX_FAILED_ATTEMPTS) {
                pendingPairings.remove(deviceId);
                return new PinVerifyResult(PinVerifyStatus.TOO_MANY_ATTEMPTS, null);
            }
            return new PinVerifyResult(PinVerifyStatus.INVALID_PIN, null);
        }

        // Successfully verified PIN: generate 256-bit authentication token
        pendingPairings.remove(deviceId);
        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        String tokenHash = sha256Hex(token);

        PairedDevice device = new PairedDevice(
                deviceId,
                pending.deviceName,
                tokenHash,
                System.currentTimeMillis()
        );
        pairedDevices.put(deviceId, device);
        notifyPinCleared(deviceId);

        return new PinVerifyResult(PinVerifyStatus.SUCCESS, token);
    }

    /**
     * Authenticates an already-paired device using its issued token.
     */
    public boolean authenticate(String deviceId, String token) {
        if (token == null || token.trim().isEmpty() || token.length() > 128) {
            return false;
        }

        // Check local admin token
        if ("local-admin".equals(deviceId)) {
            return adminTokenHash != null && MessageDigest.isEqual(
                    sha256Hex(token).getBytes(StandardCharsets.UTF_8),
                    adminTokenHash.getBytes(StandardCharsets.UTF_8)
            );
        }

        if (!isValidDeviceId(deviceId)) {
            return false;
        }

        PairedDevice device = pairedDevices.get(deviceId);
        if (device == null || device.tokenHash == null) {
            return false;
        }

        String computedHash = sha256Hex(token);
        return MessageDigest.isEqual(
                computedHash.getBytes(StandardCharsets.UTF_8),
                device.tokenHash.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Unpairs / revokes a device.
     */
    public synchronized boolean unpair(String deviceId) {
        if (deviceId == null) {
            return false;
        }
        PairedDevice removed = pairedDevices.remove(deviceId);
        return removed != null;
    }

    public boolean isPaired(String deviceId) {
        return deviceId != null && pairedDevices.containsKey(deviceId);
    }

    public int getPairedCount() {
        return pairedDevices.size();
    }

    public static boolean isValidDeviceId(String deviceId) {
        return deviceId != null && deviceId.matches("^[a-zA-Z0-9_-]{1,64}$");
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) {
                    hex.append('0');
                }
                hex.append(h);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not supported", e);
        }
    }
}
