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
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

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
    private Label statusBadge;
    private Label pcNameLabel;
    private Label activePinLabel;

    private VBox devicesContainer;
    private Label noDevicesLabel;


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

        // PC Info & Security Cards (Abstract & clean)
        String hostName = getHostComputerName();
        String initialPin = Server.pairingManager.getOrCreateActivePin();

        // Card 1: PC Identity
        Label pcTitle = new Label("THIS COMPUTER");
        pcTitle.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #94a3b8; -fx-letter-spacing: 1px;");
        pcNameLabel = new Label("🖥️  " + hostName);
        pcNameLabel.setStyle("-fx-font-size: 18px; -fx-font-weight: bold; -fx-text-fill: #f8fafc;");
        Label pcSubtitle = new Label("Ready for connection");
        pcSubtitle.setStyle("-fx-font-size: 12px; -fx-text-fill: #34d399;");
        VBox pcCard = new VBox(6, pcTitle, pcNameLabel, pcSubtitle);
        styleCard(pcCard);

        // Card 2: Security PIN
        Label pinTitle = new Label("PAIRING PIN CODE");
        pinTitle.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: #94a3b8; -fx-letter-spacing: 1px;");
        activePinLabel = new Label(initialPin);
        activePinLabel.setStyle("-fx-font-size: 22px; -fx-font-weight: bold; -fx-text-fill: #a78bfa; -fx-letter-spacing: 3px;");
        Label pinSubtitle = new Label("Enter this code on your phone");
        pinSubtitle.setStyle("-fx-font-size: 12px; -fx-text-fill: #94a3b8;");
        VBox pinCard = new VBox(6, pinTitle, activePinLabel, pinSubtitle);
        styleCard(pinCard);

        HBox infoCardsRow = new HBox(14, pcCard, pinCard);
        HBox.setHgrow(pcCard, Priority.ALWAYS);
        HBox.setHgrow(pinCard, Priority.ALWAYS);

        // Connected Devices Section
        Label devSectionTitle = new Label("CONNECTED DEVICES");
        devSectionTitle.setStyle("-fx-font-size: 12px; -fx-font-weight: bold; -fx-text-fill: #64748b; -fx-letter-spacing: 1px;");

        devicesContainer = new VBox(8);
        noDevicesLabel = new Label("No devices connected. Open the CTRLX app on your phone and tap this PC.");
        noDevicesLabel.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 13px; -fx-padding: 12;");
        devicesContainer.getChildren().add(noDevicesLabel);

        VBox devicesSection = new VBox(10, devSectionTitle, devicesContainer);
        devicesSection.setPadding(new Insets(16));
        styleCard(devicesSection);

        VBox root = new VBox(
                18,
                header,
                infoCardsRow,
                devicesSection);

        root.setPadding(new Insets(24));
        root.setStyle("-fx-background-color: #0b0f19;");

        Scene scene = new Scene(root, 620, 420);
        stage.setTitle("CTRLX");
        stage.setScene(scene);
        stage.setMinWidth(520);
        stage.setMinHeight(360);
        stage.show();

        stage.setOnCloseRequest(e -> shutdownAndExit());

        // Connect automatically to local server
        new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
            Platform.runLater(this::connectToServer);
        }).start();

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
                });

                String message;
                while ((message = reader.readLine()) != null) {
                    String received = message;

                    if (received.contains("\"type\":\"PAIR_PIN_NOTIFICATION\"")) {
                        Pattern pinPattern = Pattern.compile("\"pin\"\\s*:\\s*\"([0-9]{6})\"");
                        Matcher pinM = pinPattern.matcher(received);
                        String pin = pinM.find() ? pinM.group(1) : "------";
                        Platform.runLater(() -> activePinLabel.setText(pin));
                    } else if (received.contains("\"type\":\"DEVICES_UPDATE\"")) {
                        updateDevicesList(received);
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

                    int player = d.has("player") ? d.get("player").getAsInt() : 0;
                    Label icon = new Label(player > 0 ? "🎮" : "📱");
                    icon.setStyle("-fx-font-size: 20px;");

                    Label nameLbl = new Label(devName + (player > 0 ? " [Player " + player + "]" : ""));
                    nameLbl.setStyle("-fx-font-size: 14px; -fx-font-weight: bold; -fx-text-fill: #f8fafc;");

                    Label statusLbl = new Label(player > 0 ? "● Gamepad Active (Controller " + player + ")" : "Connected & Active");
                    statusLbl.setStyle("-fx-font-size: 12px; -fx-text-fill: " + (player > 0 ? "#38bdf8;" : "#34d399;"));

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
            if (connected && out != null) {
                out.println("{\"type\":\"KICK_DEVICE\",\"deviceId\":\"" + deviceId + "\"}");
            }
        }).start();
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
        shutdownAndExit();
    }

    private void shutdownAndExit() {
        try {
            disconnect();
            Server.stop();
        } catch (Exception ignored) {
        } finally {
            Platform.exit();
            System.exit(0);
        }
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
