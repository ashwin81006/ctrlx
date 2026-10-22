package com.ctrlx.desktop;

import com.ctrlx.server.Server;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Dashboard extends Application {
    private Socket socket;
    private PrintWriter out;
    private volatile boolean connected;
    private final Object sendLock = new Object();

    private Timeline uptimeTimeline;
    private Timeline statsTimeline;
    private long serverStartTimeMillis = System.currentTimeMillis();

    private Label statusBadge;
    private Label pcNameLabel;
    private Label activePinLabel;

    private VBox devicesContainer;
    private Label noDevicesLabel;
    private Label clientsValue;
    private Label uptimeValue;
    private Label pairedValue;


    @Override
    public void start(Stage stage) {
        startEmbeddedServerIfNeeded();

        // Title Header
        Label title = new Label("CTRLX");
        title.setStyle("-fx-font-size: 28px; -fx-font-weight: bold; -fx-text-fill: white;");

        Label subtitle = new Label("PC REMOTE RECEIVER");
        subtitle.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #64748b; -fx-letter-spacing: 1px;");

        statusBadge = new Label("● READY FOR CONNECTION");
        statusBadge.setStyle("-fx-background-color: rgba(16, 185, 129, 0.15); -fx-text-fill: #34d399; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 6 14; -fx-background-radius: 20; -fx-border-color: #059669; -fx-border-radius: 20; -fx-border-width: 1;");

        HBox header = new HBox(
                new VBox(2, title, subtitle),
                createSpacer(),
                statusBadge);
        header.setAlignment(Pos.CENTER_LEFT);

        // PC Info & Security Cards (Clean Monect style - No raw IP/port clutter)
        String hostName = getHostComputerName();
        String initialPin = Server.pairingManager.getOrCreateActivePin();

        // Card 1: PC Identity
        Label pcTitle = new Label("THIS COMPUTER");
        pcTitle.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #94a3b8; -fx-letter-spacing: 1px;");
        pcNameLabel = new Label("🖥️  " + hostName);
        pcNameLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #f8fafc;");
        Label pcSubtitle = new Label("Broadcasting on Wi-Fi • Automatic discovery active");
        pcSubtitle.setStyle("-fx-font-size: 12px; -fx-text-fill: #34d399;");
        VBox pcCard = new VBox(6, pcTitle, pcNameLabel, pcSubtitle);
        styleCard(pcCard);

        // Card 2: Security PIN
        Label pinTitle = new Label("PAIRING PIN CODE");
        pinTitle.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #94a3b8; -fx-letter-spacing: 1px;");
        activePinLabel = new Label(initialPin);
        activePinLabel.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #a78bfa; -fx-letter-spacing: 3px;");

        Label pinSubtitle = new Label("One-time passcode for new phones");
        pinSubtitle.setStyle("-fx-font-size: 12px; -fx-text-fill: #94a3b8;");
        VBox pinCard = new VBox(6, pinTitle, activePinLabel, pinSubtitle);
        styleCard(pinCard);

        // Card 3: Xbox Gamepad Receiver
        Label padTitle = new Label("GAMEPAD RECEIVER");
        padTitle.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #94a3b8; -fx-letter-spacing: 1px;");
        Label padProfileLabel = new Label("🎮 Xbox Pad Ready");
        padProfileLabel.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #38bdf8;");
        Label padSubtitle = new Label("WASD Movement • Mouse Aim");
        padSubtitle.setStyle("-fx-font-size: 11px; -fx-text-fill: #94a3b8;");
        VBox padCard = new VBox(6, padTitle, padProfileLabel, padSubtitle);
        styleCard(padCard);

        HBox infoCardsRow = new HBox(12, pcCard, pinCard, padCard);
        HBox.setHgrow(pcCard, Priority.ALWAYS);
        HBox.setHgrow(pinCard, Priority.ALWAYS);
        HBox.setHgrow(padCard, Priority.ALWAYS);


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
                devicesSection,
                statsFooter);

        root.setPadding(new Insets(24));
        root.setStyle("-fx-background-color: #0b0f19;");

        Scene scene = new Scene(root, 720, 530);
        stage.setTitle("CTRLX - PC Remote Receiver");
        stage.setScene(scene);
        stage.setMinWidth(580);
        stage.setMinHeight(480);
        stage.show();

        stage.setOnCloseRequest(e -> {
            if (uptimeTimeline != null) uptimeTimeline.stop();
            if (statsTimeline != null) statsTimeline.stop();
            disconnect();
        });

        // Live ticking stopwatch timer for Server Uptime (updates every 1 second)
        uptimeTimeline = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            long elapsedSecs = Math.max(0, (System.currentTimeMillis() - serverStartTimeMillis) / 1000L);
            long hours = elapsedSecs / 3600;
            long minutes = (elapsedSecs % 3600) / 60;
            long seconds = elapsedSecs % 60;
            uptimeValue.setText(String.format("%02d:%02d:%02d", hours, minutes, seconds));
        }));
        uptimeTimeline.setCycleCount(Animation.INDEFINITE);
        uptimeTimeline.play();

        // Periodic background stats synchronizer (updates every 3 seconds)
        statsTimeline = new Timeline(new KeyFrame(Duration.seconds(3), e -> requestStats()));
        statsTimeline.setCycleCount(Animation.INDEFINITE);
        statsTimeline.play();

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
                Platform.runLater(() -> activePinLabel.setText(pin));
            });

            Server.pairingManager.addClearListener(deviceId -> {
                Platform.runLater(this::requestStats);
            });
        } catch (Throwable ignored) {
        }
    }

    private boolean isPortFree(int port) {
        try (ServerSocket s = new ServerSocket(port)) {
            return s.isBound();
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
                        Matcher pinM = pinPattern.matcher(received);
                        String pin = pinM.find() ? pinM.group(1) : "------";
                        Platform.runLater(() -> activePinLabel.setText(pin));
                    } else if (received.contains("\"type\":\"PAIR_PIN_CLEARED\"")) {
                        Platform.runLater(this::requestStats);
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

                    Label icon = new Label("📱");
                    icon.setStyle("-fx-font-size: 20px;");

                    Label nameLbl = new Label(devName);
                    nameLbl.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #f8fafc;");

                    Label statusLbl = new Label("Connected & Active");
                    statusLbl.setStyle("-fx-font-size: 12px; -fx-text-fill: #34d399;");

                    VBox info = new VBox(2, nameLbl, statusLbl);

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

        // Synchronize base start time so the 1-second live ticker counts seamlessly
        serverStartTimeMillis = System.currentTimeMillis() - (uptime * 1000L);

        Platform.runLater(() -> {
            clientsValue.setText(String.valueOf(clients));
            pairedValue.setText(String.valueOf(paired));
        });
    }

    void disconnect() {
        connected = false;
        Socket current = socket;
        socket = null;
        out = null;
        if (current != null) {
            try {
                current.close();
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void stop() {
        if (uptimeTimeline != null) {
            uptimeTimeline.stop();
        }
        if (statsTimeline != null) {
            statsTimeline.stop();
        }
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
