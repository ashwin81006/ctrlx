package com.ctrlx.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class Server {
    public static final int PORT = 6060;
    public static final int DISCOVERY_PORT = 6061;
    public static final int MAX_MESSAGE_SIZE = 4096;
    public static final AtomicInteger clients = new AtomicInteger();
    public static final long startTime = System.nanoTime();
    public static final ExecutorService pool = Executors.newCachedThreadPool();
    public static final PairingManager pairingManager = new PairingManager();
    public static final MouseController mouseController = new MouseController();
    public static final Set<PrintWriter> adminWriters = ConcurrentHashMap.newKeySet();

    public static class ActiveClient {
        public final String deviceId;
        public final String deviceName;
        public final String remoteIp;
        public final long connectedAt;
        public final PrintWriter writer;
        public final Socket socket;

        public ActiveClient(String deviceId, String deviceName, String remoteIp, long connectedAt, PrintWriter writer, Socket socket) {
            this.deviceId = deviceId;
            this.deviceName = deviceName;
            this.remoteIp = remoteIp;
            this.connectedAt = connectedAt;
            this.writer = writer;
            this.socket = socket;
        }
    }

    public static final Map<String, ActiveClient> activeClients = new ConcurrentHashMap<>();

    public static void main(String[] args) throws Exception {
        pairingManager.addListener((deviceId, deviceName, pin) -> {
            System.out.printf("[CTRLX PAIRING] PIN for '%s' (%s): %s (Valid for 60s)%n",
                    deviceName, deviceId, pin);

            JsonObject notif = new JsonObject();
            notif.addProperty("type", "PAIR_PIN_NOTIFICATION");
            notif.addProperty("deviceId", deviceId);
            notif.addProperty("deviceName", deviceName);
            notif.addProperty("pin", pin);
            notif.addProperty("expiresIn", 60);
            broadcastToAdmins(notif);
        });

        startDiscoveryService(DISCOVERY_PORT);

        try (ServerSocket server = new ServerSocket(PORT)) {
            System.out.println("CTRLX server running on port " + PORT);
            System.out.println("Auto-discovery beacon listening on UDP port " + DISCOVERY_PORT);

            while (true) {
                Socket socket = server.accept();
                pool.submit(() -> handleClient(socket));
            }
        }
    }

    public static void startDiscoveryService(int port) {
        Thread t = new Thread(() -> {
            try (DatagramSocket socket = new DatagramSocket(port)) {
                socket.setBroadcast(true);
                byte[] buffer = new byte[1024];

                while (true) {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    socket.receive(packet);

                    String msg = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    if (msg.contains("CTRLX_DISCOVER")) {
                        JsonObject reply = new JsonObject();
                        reply.addProperty("type", "CTRLX_SERVER");
                        reply.addProperty("serverPort", PORT);
                        String hostName = "Windows PC";
                        try {
                            String h = InetAddress.getLocalHost().getHostName();
                            if (h != null && !h.trim().isEmpty()) {
                                hostName = h;
                            }
                        } catch (Exception ignored) {
                        }
                        reply.addProperty("serverName", hostName);

                        byte[] replyBytes = reply.toString().getBytes(StandardCharsets.UTF_8);
                        DatagramPacket replyPacket = new DatagramPacket(
                                replyBytes,
                                replyBytes.length,
                                packet.getAddress(),
                                packet.getPort()
                        );
                        socket.send(replyPacket);
                    }
                }
            } catch (Exception e) {
                System.err.println("Notice: Discovery service stopped: " + e.getMessage());
            }
        });
        t.setDaemon(true);
        t.start();
    }

    public static void broadcastToAdmins(JsonObject json) {
        for (PrintWriter w : adminWriters) {
            try {
                w.println(json);
            } catch (Exception ignored) {
            }
        }
    }

    public static void broadcastConnectedDevices() {
        JsonArray array = new JsonArray();
        for (ActiveClient c : activeClients.values()) {
            JsonObject obj = new JsonObject();
            obj.addProperty("deviceId", c.deviceId);
            obj.addProperty("deviceName", c.deviceName);
            obj.addProperty("remoteIp", c.remoteIp);
            obj.addProperty("connectedAt", c.connectedAt);
            array.add(obj);
        }
        JsonObject notif = new JsonObject();
        notif.addProperty("type", "DEVICES_UPDATE");
        notif.add("devices", array);
        broadcastToAdmins(notif);
    }

    public static void handleClient(Socket socket) {
        boolean counted = false;
        boolean authenticated = false;
        String authenticatedDeviceId = null;
        String clientDeviceName = "Android Device";
        PrintWriter clientWriter = null;

        try (
            Socket client = socket;
            BufferedReader in = new BufferedReader(
                new InputStreamReader(
                    client.getInputStream(),
                    StandardCharsets.UTF_8
                )
            );
            PrintWriter out = new PrintWriter(
                client.getOutputStream(),
                true,
                StandardCharsets.UTF_8
            )
        ) {
            clientWriter = out;
            client.setSoTimeout(0);

            String line;
            try {
                line = readBoundedLine(in, MAX_MESSAGE_SIZE);
            } catch (IllegalArgumentException e) {
                sendError(out, "PAYLOAD_TOO_LARGE", "Payload exceeds " + MAX_MESSAGE_SIZE + " bytes");
                return;
            }

            if (line == null) {
                return;
            }

            JsonObject hello;
            try {
                hello = JsonParser.parseString(line).getAsJsonObject();
            } catch (Exception e) {
                sendError(out, "INVALID_JSON", "Invalid JSON object");
                return;
            }

            if (!hello.has("type")
                || !"HELLO".equals(hello.get("type").getAsString())
                || !hello.has("version")
                || !hello.get("version").isJsonPrimitive()
                || !hello.get("version").getAsJsonPrimitive().isNumber()
                || hello.get("version").getAsInt() != 1
                || hello.get("version").getAsDouble() != 1.0) {
                sendError(out, "INVALID_HANDSHAKE", "Invalid handshake");
                return;
            }

            JsonObject welcome = new JsonObject();
            welcome.addProperty("type", "WELCOME");
            welcome.addProperty("version", 1);
            out.println(welcome);

            clients.incrementAndGet();
            counted = true;

            System.out.println("Client connected: " + client.getRemoteSocketAddress());

            while (true) {
                try {
                    line = readBoundedLine(in, MAX_MESSAGE_SIZE);
                } catch (IllegalArgumentException e) {
                    sendError(out, "PAYLOAD_TOO_LARGE", "Payload exceeds " + MAX_MESSAGE_SIZE + " bytes");
                    break;
                }

                if (line == null) {
                    break;
                }

                JsonObject message;
                try {
                    message = JsonParser.parseString(line).getAsJsonObject();
                } catch (Exception e) {
                    sendError(out, "INVALID_JSON", "Invalid JSON object");
                    continue;
                }

                if (!message.has("type") || !message.get("type").isJsonPrimitive()) {
                    sendError(out, "MISSING_TYPE", "Missing message type");
                    continue;
                }

                String type = message.get("type").getAsString();

                if ("MOUSE_MOVE".equals(type)) {
                    if (!authenticated) {
                        sendError(out, "AUTH_REQUIRED", "Authentication required");
                        continue;
                    }
                    if (message.has("dx") && message.has("dy")) {
                        try {
                            int dx = (int) Math.round(message.get("dx").getAsDouble());
                            int dy = (int) Math.round(message.get("dy").getAsDouble());
                            mouseController.moveRelative(dx, dy);
                        } catch (Exception ignored) {
                        }
                    }
                } else if ("MOUSE_CLICK".equals(type)) {
                    if (!authenticated) {
                        sendError(out, "AUTH_REQUIRED", "Authentication required");
                        continue;
                    }
                    String button = message.has("button") ? message.get("button").getAsString() : "LEFT";
                    String action = message.has("action") ? message.get("action").getAsString() : "CLICK";

                    if ("DOWN".equalsIgnoreCase(action)) {
                        mouseController.press(button);
                    } else if ("UP".equalsIgnoreCase(action)) {
                        mouseController.release(button);
                    } else {
                        mouseController.click(button);
                    }
                } else if ("MOUSE_SCROLL".equals(type)) {
                    if (!authenticated) {
                        sendError(out, "AUTH_REQUIRED", "Authentication required");
                        continue;
                    }
                    if (message.has("amount")) {
                        try {
                            int amount = (int) Math.round(message.get("amount").getAsDouble());
                            mouseController.scroll(amount);
                        } catch (Exception ignored) {
                        }
                    }
                } else if ("KEY_TEXT".equals(type)) {
                    if (!authenticated) {
                        sendError(out, "AUTH_REQUIRED", "Authentication required");
                        continue;
                    }
                    if (message.has("text")) {
                        mouseController.typeText(message.get("text").getAsString());
                    }
                } else if ("KEY_PRESS".equals(type)) {
                    if (!authenticated) {
                        sendError(out, "AUTH_REQUIRED", "Authentication required");
                        continue;
                    }
                    if (message.has("key")) {
                        mouseController.pressKey(message.get("key").getAsString());
                    }
                } else if ("PING".equals(type)) {
                    JsonObject pong = new JsonObject();
                    pong.addProperty("type", "PONG");
                    out.println(pong);
                } else if ("PAIR_REQUEST".equals(type)) {
                    if (!message.has("deviceId") || !message.get("deviceId").isJsonPrimitive()) {
                        sendError(out, "INVALID_ARGUMENTS", "Missing deviceId");
                        continue;
                    }
                    String deviceId = message.get("deviceId").getAsString();
                    clientDeviceName = message.has("deviceName") && message.get("deviceName").isJsonPrimitive()
                            ? message.get("deviceName").getAsString()
                            : "Android Device";

                    if (!PairingManager.isValidDeviceId(deviceId)) {
                        sendError(out, "INVALID_DEVICE_ID", "Invalid deviceId format");
                        continue;
                    }

                    try {
                        pairingManager.initiatePairing(deviceId, clientDeviceName);
                        JsonObject res = new JsonObject();
                        res.addProperty("type", "PAIR_PIN_REQUIRED");
                        res.addProperty("expiresIn", 60);
                        out.println(res);
                    } catch (Exception e) {
                        sendError(out, "PAIRING_FAILED", e.getMessage());
                    }
                } else if ("PAIR_VERIFY".equals(type)) {
                    if (!message.has("deviceId") || !message.has("pin")) {
                        sendError(out, "INVALID_ARGUMENTS", "deviceId and pin are required");
                        continue;
                    }
                    String deviceId = message.get("deviceId").getAsString();
                    String pin = message.get("pin").getAsString();

                    PairingManager.PinVerifyResult result = pairingManager.verifyPin(deviceId, pin);
                    switch (result.status) {
                        case SUCCESS:
                            authenticated = true;
                            authenticatedDeviceId = deviceId;
                            String ip = client.getInetAddress() != null ? client.getInetAddress().getHostAddress() : "Mobile";
                            activeClients.put(deviceId, new ActiveClient(deviceId, clientDeviceName, ip, System.currentTimeMillis(), out, client));
                            broadcastConnectedDevices();

                            JsonObject success = new JsonObject();
                            success.addProperty("type", "PAIR_SUCCESS");
                            success.addProperty("token", result.token);
                            out.println(success);

                            JsonObject clearNotif = new JsonObject();
                            clearNotif.addProperty("type", "PAIR_PIN_CLEARED");
                            clearNotif.addProperty("deviceId", deviceId);
                            broadcastToAdmins(clearNotif);
                            break;
                        case INVALID_PIN:
                            sendError(out, "INVALID_PIN", "Invalid pairing PIN");
                            break;
                        case EXPIRED:
                            sendError(out, "PIN_EXPIRED", "Pairing PIN has expired");
                            break;
                        case TOO_MANY_ATTEMPTS:
                            sendError(out, "TOO_MANY_ATTEMPTS", "Too many invalid attempts. Pairing cancelled.");
                            break;
                        case NO_PENDING_REQUEST:
                            sendError(out, "NO_PENDING_REQUEST", "No pending pairing request for this device");
                            break;
                    }
                } else if ("AUTH".equals(type)) {
                    if (!message.has("deviceId") || !message.has("token")) {
                        sendError(out, "INVALID_ARGUMENTS", "deviceId and token are required");
                        continue;
                    }
                    String deviceId = message.get("deviceId").getAsString();
                    String token = message.get("token").getAsString();
                    if (message.has("deviceName")) {
                        clientDeviceName = message.get("deviceName").getAsString();
                    }

                    if (pairingManager.authenticate(deviceId, token)) {
                        authenticated = true;
                        authenticatedDeviceId = deviceId;
                        if ("local-admin".equals(deviceId)) {
                            adminWriters.add(out);
                            broadcastConnectedDevices();
                        } else {
                            String ip = client.getInetAddress() != null ? client.getInetAddress().getHostAddress() : "Mobile";
                            activeClients.put(deviceId, new ActiveClient(deviceId, clientDeviceName, ip, System.currentTimeMillis(), out, client));
                            broadcastConnectedDevices();
                        }
                        JsonObject authOk = new JsonObject();
                        authOk.addProperty("type", "AUTH_SUCCESS");
                        out.println(authOk);
                    } else {
                        sendError(out, "AUTH_FAILED", "Authentication failed");
                        continue;
                    }
                } else if ("KICK_DEVICE".equals(type)) {
                    if (!authenticated || !"local-admin".equals(authenticatedDeviceId)) {
                        sendError(out, "AUTH_REQUIRED", "Admin authentication required");
                        continue;
                    }
                    if (message.has("deviceId")) {
                        String target = message.get("deviceId").getAsString();
                        ActiveClient victim = activeClients.remove(target);
                        if (victim != null) {
                            try {
                                victim.socket.close();
                            } catch (Exception ignored) {
                            }
                        }
                        broadcastConnectedDevices();
                    }
                } else if ("UNPAIR".equals(type)) {
                    if (!authenticated) {
                        sendError(out, "AUTH_REQUIRED", "Authentication required");
                        continue;
                    }
                    pairingManager.unpair(authenticatedDeviceId);
                    activeClients.remove(authenticatedDeviceId);
                    broadcastConnectedDevices();
                    authenticated = false;
                    authenticatedDeviceId = null;
                    JsonObject unpairOk = new JsonObject();
                    unpairOk.addProperty("type", "UNPAIR_SUCCESS");
                    out.println(unpairOk);
                } else if ("STATS".equals(type)) {
                    if (!authenticated) {
                        sendError(out, "AUTH_REQUIRED", "Authentication required");
                        continue;
                    }
                    JsonObject stats = new JsonObject();
                    stats.addProperty("type", "STATS");
                    stats.addProperty("connectedClients", activeClients.size());
                    stats.addProperty(
                        "uptimeSeconds",
                        (System.nanoTime() - startTime) / 1_000_000_000L
                    );
                    stats.addProperty("pairedClients", pairingManager.getPairedCount());
                    out.println(stats);
                    broadcastConnectedDevices();
                } else {
                    sendError(out, "UNSUPPORTED_MESSAGE", "Unsupported message");
                }

                if (out.checkError()) {
                    break;
                }
            }
        } catch (Exception e) {
            System.out.println("Client disconnected: " + e.getMessage());
        } finally {
            if (clientWriter != null) {
                adminWriters.remove(clientWriter);
            }
            if (authenticatedDeviceId != null && !"local-admin".equals(authenticatedDeviceId)) {
                pairingManager.unpair(authenticatedDeviceId);
                activeClients.remove(authenticatedDeviceId);
                broadcastConnectedDevices();
            }
            if (counted) {
                clients.decrementAndGet();
            }
        }
    }

    public static String readBoundedLine(BufferedReader in, int maxChars) throws IOException {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = in.read()) != -1) {
            if (ch == '\n') {
                break;
            }
            if (ch == '\r') {
                continue;
            }
            if (sb.length() >= maxChars) {
                while ((ch = in.read()) != -1 && ch != '\n') {}
                throw new IllegalArgumentException("Payload exceeds " + maxChars + " bytes");
            }
            sb.append((char) ch);
        }
        if (ch == -1 && sb.length() == 0) {
            return null;
        }
        return sb.toString();
    }

    public static void sendError(PrintWriter out, String code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("type", "ERROR");
        if (code != null) {
            error.addProperty("code", code);
        }
        error.addProperty("message", message);
        out.println(error);
    }
}
