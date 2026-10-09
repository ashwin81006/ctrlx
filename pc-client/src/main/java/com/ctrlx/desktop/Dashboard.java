package com.ctrlx.desktop;

import com.ctrlx.server.Server;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Dashboard extends Application {
    private Socket socket;
    private BufferedReader in;
    private PrintWriter out;
    private volatile boolean connected;
    private final Object sendLock = new Object();

    private Label statusBadge;
    private Label pcNameLabel;
    private Label ipValueLabel;
    private Label activePinLabel;

    private VBox devicesContainer;
    private Label noDevicesLabel;
    private Label clientsValue;
    private Label uptimeValue;
    private Label pairedValue;

    private VBox pairingAlertCard;
    private Label pairingDeviceLabel;
    private Label pairingPinAlertLabel;

    @Override
    public void start(Stage stage) {
        startEmbeddedServerIfNeeded();

        // Title Header
        Label title = new Label("CTRLX");
        title.setStyle("-fx-font-size: 28px; -fx-font-weight: bold; -fx-text-fill: white;");

        Label subtitle = new Label("PC REMOTE RECEIVER");
        subtitle.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #64748b; -fx-letter-spacing: 1px;");

        statusBadge = new Label("● READY TO CONNECT");
        statusBadge.setStyle("-fx-background-color: rgba(16, 185, 129, 0.15); -fx-text-fill: #34d399; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 6 12; -fx-background-radius: 20; -fx-border-color: #059669; -fx-border-radius: 20; -fx-border-width: 1;");

        HBox header = new HBox(
                new VBox(2, title, subtitle),
                createSpacer(),
                statusBadge);
        header.setAlignment(Pos.CENTER_LEFT);

        // PC Info & Security Cards (Monect-style grid)
        String hostName = getHostComputerName();
        String wifiIp = getLocalWifiIp();
        String initialPin = Server.pairingManager.getOrCreateActivePin();

        // Card 1: PC Identity
        Label pcTitle = new Label("COMPUTER NAME");
        pcTitle.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: #94a3b8;");
        pcNameLabel = new Label(hostName);
        pcNameLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #f8fafc;");
        VBox pcCard = new VBox(4, pcTitle, pcNameLabel);
        styleCard(pcCard);

        // Card 2: Wi-Fi IP Address
        Label ipTitle = new Label("WI-FI IP ADDRESS");
        ipTitle.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: #94a3b8;");
        ipValueLabel = new Label(wifiIp + " : 6060");
        ipValueLabel.setStyle("-fx-font-size: 16px; -fx-font-weight: bold; -fx-text-fill: #38bdf8;");
        VBox ipCard = new VBox(4, ipTitle, ipValueLabel);
        styleCard(ipCard);

        // Card 3: Security PIN
        Label pinTitle = new Label("PAIRING PIN CODE");
        pinTitle.setStyle("-fx-font-size: 10px; -fx-font-weight: bold; -fx-text-fill: #94a3b8;");
        activePinLabel = new Label(initialPin);
        activePinLabel.setStyle("-fx-font-size: 20px; -fx-font-weight: bold; -fx-text-fill: #a78bfa; -fx-letter-spacing: 2px;");

        Button refreshPinBtn = new Button("↻");
        refreshPinBtn.setStyle("-fx-background-color: transparent; -fx-text-fill: #94a3b8; -fx-font-size: 14px; -fx-cursor: hand;");
        refreshPinBtn.setOnAction(e -> {
            Server.pairingManager.resetActivePin();
            activePinLabel.setText(Server.pairingManager.getOrCreateActivePin());
        });

        HBox pinRow = new HBox(8, activePinLabel, refreshPinBtn);
        pinRow.setAlignment(Pos.CENTER_LEFT);
        VBox pinCard = new VBox(4, pinTitle, pinRow);
        styleCard(pinCard);

        HBox infoCardsRow = new HBox(12, pcCard, ipCard, pinCard);
        HBox.setHgrow(pcCard, Priority.ALWAYS);
        HBox.setHgrow(ipCard, Priority.ALWAYS);
        HBox.setHgrow(pinCard, Priority.ALWAYS);

        // Prominent Pairing Alert Card (glowing when pairing is initiated)
        Label alertHeading = new Label("NEW DEVICE PAIRING REQUEST");
        alertHeading.setStyle("-fx-font-size: 13px; -fx-font-weight: bold; -fx-text-fill: #38bdf8;");

        pairingDeviceLabel = new Label("Device: Android Phone");
        pairingDeviceLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #e2e8f0;");

        pairingPinAlertLabel = new Label("------");
        pairingPinAlertLabel.setStyle("-fx-font-size: 40px; -fx-font-weight: bold; -fx-text-fill: #38bdf8; -fx-letter-spacing: 4px;");

        Label alertInstruct = new Label("Enter this 6-digit PIN on your phone to pair. Expires in 60s.");
        alertInstruct.setStyle("-fx-font-size: 12px; -fx-text-fill: #94a3b8;");

        pairingAlertCard = new VBox(6, alertHeading, pairingDeviceLabel, pairingPinAlertLabel, alertInstruct);
        pairingAlertCard.setPadding(new Insets(16));
        pairingAlertCard.setStyle("-fx-background-color: #1e1b4b; -fx-border-color: #6366f1; -fx-border-width: 2px; -fx-border-radius: 12; -fx-background-radius: 12;");
        pairingAlertCard.setVisible(false);
        pairingAlertCard.setManaged(false);

        // Connected Devices Section (Monect style)
        Label devSectionTitle = new Label("CONNECTED DEVICES");
        devSectionTitle.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #64748b; -fx-letter-spacing: 1px;");

        devicesContainer = new VBox(8);
        noDevicesLabel = new Label("No devices connected. Open the CTRLX app on your phone and tap this PC.");
        noDevicesLabel.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 13px; -fx-padding: 12;");
        devicesContainer.getChildren().add(noDevicesLabel);

        VBox devicesSection = new VBox(10, devSectionTitle, devicesContainer);
        devicesSection.setPadding(new Insets(16));
        styleCard(devicesSection);

        // Footer Statistics
        clientsValue = new Label("0");
        uptimeValue = new Label("00:00:00");
        pairedValue = new Label(String.valueOf(Server.pairingManager.getPairedCount()));

        clientsValue.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: white;");
        uptimeValue.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: white;");
        pairedValue.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: white;");

        HBox statsFooter = new HBox(
                32,
                new VBox(2, new Label("Clients Connected") {{ setStyle("-fx-font-size: 11px; -fx-text-fill: #64748b;"); }}, clientsValue),
                new VBox(2, new Label("Paired Devices") {{ setStyle("-fx-font-size: 11px; -fx-text-fill: #64748b;"); }}, pairedValue),
                new VBox(2, new Label("Server Uptime") {{ setStyle("-fx-font-size: 11px; -fx-text-fill: #64748b;"); }}, uptimeValue));

        VBox root = new VBox(
                16,
                header,
                infoCardsRow,
                pairingAlertCard,
                devicesSection,
                statsFooter);

        root.setPadding(new Insets(24));
        root.setStyle("-fx-background-color: #0b0f19;");

        Scene scene = new Scene(root, 650, 520);
        stage.setTitle("CTRLX - PC Remote Receiver");
        stage.setScene(scene);
        stage.setMinWidth(580);
        stage.setMinHeight(480);
        stage.show();

        stage.setOnCloseRequest(e -> disconnect());

        // Connect automatically to local server
        new Thread(() -> {
            try {
                Thread.sleep(600);
            } catch (InterruptedException ignored) {
            }
            Platform.runLater(this::connectToServer);
        }).start();
    }

    private void styleCard(VBox box) {
        box.setPadding(new Insets(14));
        box.setStyle("-fx-background-color: #131d31; -fx-background-radius: 10; -fx-border-color: #1e293b; -fx-border-radius: 10; -fx-border-width: 1;");
    }

    private javafx.scene.Node createSpacer() {
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    private void startEmbeddedServerIfNeeded() {
        if (isPortFree(6060)) {
            Thread t = new Thread(() -> {
                try {
                    Server.main(new String[0]);
                } catch (Exception e) {
                    System.err.println("Notice: Embedded server error: " + e.getMessage());
                }
            });
            t.setDaemon(true);
            t.start();
        }

        try {
            Server.pairingManager.addListener((deviceId, deviceName, pin) -> {
                Platform.runLater(() -> {
                    activePinLabel.setText(pin);
                    pairingDeviceLabel.setText("Device: " + deviceName);
                    pairingPinAlertLabel.setText(pin);
                    pairingAlertCard.setVisible(true);
                    pairingAlertCard.setManaged(true);
                });
            });

            Server.pairingManager.addClearListener(deviceId -> {
                Platform.runLater(() -> {
                    pairingAlertCard.setVisible(false);
                    pairingAlertCard.setManaged(false);
                    requestStats();
                });
            });
        } catch (Throwable ignored) {
        }
    }

    private boolean isPortFree(int port) {
        try (ServerSocket s = new ServerSocket(port)) {
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String getHostComputerName() {
        try {
            String name = System.getenv("COMPUTERNAME");
            if (name != null && !name.trim().isEmpty()) {
                return name;
            }
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception ignored) {
            return "Windows PC";
        }
    }

    private String getLocalWifiIp() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface n = interfaces.nextElement();
                if (!n.isUp() || n.isLoopback() || n.isVirtual()) continue;
                Enumeration<InetAddress> addresses = n.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        if (ip.startsWith("192.168.") || ip.startsWith("10.")) {
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    private void requestStats() {
        if (!connected || out == null) {
            connectToServer();
            return;
        }

        new Thread(() -> {
            synchronized (sendLock) {
                if (connected && out != null) {
                    out.println("{\"type\":\"STATS\"}");
                }
            }
        }).start();
    }

    void connectToServer() {
        new Thread(() -> {
            Socket newSocket = new Socket();

            try {
                newSocket.connect(new InetSocketAddress("127.0.0.1", 6060), 4000);

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(
                                newSocket.getInputStream(),
                                StandardCharsets.UTF_8));

                PrintWriter writer = new PrintWriter(
                        newSocket.getOutputStream(),
                        true,
                        StandardCharsets.UTF_8);

                writer.println("{\"type\":\"HELLO\",\"version\":1}");

                String response = reader.readLine();
                if (response == null || !response.contains("\"WELCOME\"")) {
                    throw new Exception("Server handshake failed.");
                }

                socket = newSocket;
                in = reader;
                out = writer;
                connected = true;

                String adminToken = findAdminToken();
                if (adminToken != null) {
                    writer.println("{\"type\":\"AUTH\",\"deviceId\":\"local-admin\",\"token\":\"" + adminToken + "\"}");
                }

                Platform.runLater(() -> {
                    statusBadge.setText("● READY TO CONNECT");
                    statusBadge.setStyle("-fx-background-color: rgba(16, 185, 129, 0.15); -fx-text-fill: #34d399; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 6 12; -fx-background-radius: 20; -fx-border-color: #059669; -fx-border-radius: 20; -fx-border-width: 1;");
                    requestStats();
                });

                String message;
                while ((message = reader.readLine()) != null) {
                    String received = message;

                    if (received.contains("\"type\":\"PAIR_PIN_NOTIFICATION\"")) {
                        Pattern pinPattern = Pattern.compile("\"pin\"\\s*:\\s*\"([0-9]{6})\"");
                        Pattern devPattern = Pattern.compile("\"deviceName\"\\s*:\\s*\"([^\"]+)\"");
                        Matcher pinM = pinPattern.matcher(received);
                        Matcher devM = devPattern.matcher(received);
                        String pin = pinM.find() ? pinM.group(1) : "------";
                        String dev = devM.find() ? devM.group(1) : "Android Phone";

                        Platform.runLater(() -> {
                            activePinLabel.setText(pin);
                            pairingDeviceLabel.setText("Device: " + dev);
                            pairingPinAlertLabel.setText(pin);
                            pairingAlertCard.setVisible(true);
                            pairingAlertCard.setManaged(true);
                        });
                    } else if (received.contains("\"type\":\"PAIR_PIN_CLEARED\"")) {
                        Platform.runLater(() -> {
                            pairingAlertCard.setVisible(false);
                            pairingAlertCard.setManaged(false);
                            requestStats();
                        });
                    } else if (received.contains("\"type\":\"DEVICES_UPDATE\"")) {
                        updateDevicesList(received);
                    } else if (received.contains("\"type\":\"STATS\"")) {
                        updateStats(received);
                    }
                }

            } catch (Exception e) {
                Platform.runLater(() -> {
                    statusBadge.setText("● OFFLINE");
                    statusBadge.setStyle("-fx-background-color: rgba(239, 68, 68, 0.15); -fx-text-fill: #f87171; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 6 12; -fx-background-radius: 20;");
                });
            } finally {
                try {
                    newSocket.close();
                } catch (Exception ignored) {
                }

                if (socket == newSocket) {
                    socket = null;
                    in = null;
                    out = null;
                    connected = false;
                }
            }
        }).start();
    }

    private void updateDevicesList(String json) {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            JsonArray devices = root.getAsJsonArray("devices");

            Platform.runLater(() -> {
                devicesContainer.getChildren().clear();

                if (devices == null || devices.size() == 0) {
                    devicesContainer.getChildren().add(noDevicesLabel);
                    statusBadge.setText("● READY TO CONNECT");
                    statusBadge.setStyle("-fx-background-color: rgba(16, 185, 129, 0.15); -fx-text-fill: #34d399; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 6 12; -fx-background-radius: 20; -fx-border-color: #059669; -fx-border-radius: 20; -fx-border-width: 1;");
                    return;
                }

                statusBadge.setText("● CONNECTED (" + devices.size() + " DEVICE" + (devices.size() > 1 ? "S" : "") + ")");
                statusBadge.setStyle("-fx-background-color: rgba(56, 189, 248, 0.15); -fx-text-fill: #38bdf8; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 6 12; -fx-background-radius: 20; -fx-border-color: #0284c7; -fx-border-radius: 20; -fx-border-width: 1;");

                for (JsonElement el : devices) {
                    JsonObject d = el.getAsJsonObject();
                    String devId = d.get("deviceId").getAsString();
                    String devName = d.has("deviceName") ? d.get("deviceName").getAsString() : "Mobile Phone";
                    String devIp = d.has("remoteIp") ? d.get("remoteIp").getAsString() : "LAN";

                    Label icon = new Label("📱");
                    icon.setStyle("-fx-font-size: 20px;");

                    Label nameLbl = new Label(devName);
                    nameLbl.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #f8fafc;");

                    Label ipLbl = new Label(devIp + " • Active");
                    ipLbl.setStyle("-fx-font-size: 12px; -fx-text-fill: #10b981;");

                    VBox info = new VBox(2, nameLbl, ipLbl);

                    Button kickBtn = new Button("Disconnect");
                    kickBtn.setStyle("-fx-background-color: #334155; -fx-text-fill: #f87171; -fx-font-size: 12px; -fx-font-weight: bold; -fx-cursor: hand;");
                    kickBtn.setOnAction(e -> kickDevice(devId));

                    HBox item = new HBox(12, icon, info, createSpacer(), kickBtn);
                    item.setAlignment(Pos.CENTER_LEFT);
                    item.setPadding(new Insets(10, 14, 10, 14));
                    item.setStyle("-fx-background-color: #1e293b; -fx-background-radius: 8;");

                    devicesContainer.getChildren().add(item);
                }
            });
        } catch (Exception ignored) {
        }
    }

    private void kickDevice(String deviceId) {
        new Thread(() -> {
            synchronized (sendLock) {
                if (connected && out != null) {
                    out.println("{\"type\":\"KICK_DEVICE\",\"deviceId\":\"" + deviceId + "\"}");
                }
            }
        }).start();
    }

    private void updateStats(String json) {
        Pattern clientsPattern = Pattern.compile("\"connectedClients\"\\s*:\\s*(\\d+)");
        Pattern uptimePattern = Pattern.compile("\"uptimeSeconds\"\\s*:\\s*(\\d+)");
        Pattern pairedPattern = Pattern.compile("\"pairedClients\"\\s*:\\s*(\\d+)");

        Matcher clientsMatcher = clientsPattern.matcher(json);
        Matcher uptimeMatcher = uptimePattern.matcher(json);
        Matcher pairedMatcher = pairedPattern.matcher(json);

        if (!clientsMatcher.find() || !uptimeMatcher.find()) {
            return;
        }

        int clients = Integer.parseInt(clientsMatcher.group(1));
        long uptime = Long.parseLong(uptimeMatcher.group(1));
        int paired = pairedMatcher.find() ? Integer.parseInt(pairedMatcher.group(1)) : 0;

        long hours = uptime / 3600;
        long minutes = (uptime % 3600) / 60;
        long seconds = uptime % 60;

        String formattedUptime = String.format("%02d:%02d:%02d", hours, minutes, seconds);

        Platform.runLater(() -> {
            clientsValue.setText(String.valueOf(clients));
            pairedValue.setText(String.valueOf(paired));
            uptimeValue.setText(formattedUptime);
        });
    }

    void disconnect() {
        connected = false;
        Socket current = socket;
        socket = null;
        out = null;
        in = null;
        if (current != null) {
            try {
                current.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void stop() {
        disconnect();
    }

    private String findAdminToken() {
        File[] candidates = new File[] {
            new File("server.token"),
            new File("../network-server/server.token"),
            new File(System.getProperty("user.home"), ".ctrlx/server.token")
        };
        for (File f : candidates) {
            if (f.exists()) {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        new java.io.FileInputStream(f), StandardCharsets.UTF_8))) {
                    String line = r.readLine();
                    if (line != null && !line.trim().isEmpty()) {
                        return line.trim();
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
