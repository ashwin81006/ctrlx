package com.ctrlx.desktop;

import com.ctrlx.server.Server;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
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

    private Label connectionStatus;
    private Label responseStatus;
    private Label clientsValue;
    private Label uptimeValue;
    private Label pairedValue;
    private Label ipValue;

    private VBox pairingCard;
    private Label pairingDeviceLabel;
    private Label pairingPinLabel;

    @Override
    public void start(Stage stage) {
        // Auto-start embedded network server immediately on launch
        startEmbeddedServerIfNeeded();

        Label title = new Label("CTRLX");
        title.setStyle("-fx-font-size: 32px; -fx-font-weight: bold;");

        Label subtitle = new Label("PC REMOTE RECEIVER & DASHBOARD");
        subtitle.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 12px;");

        // PC Host & Network Card
        Label serverTitle = new Label("RECEIVER STATUS");
        serverTitle.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");

        String localIp = getLocalWifiIp();
        ipValue = new Label("Wi-Fi IP: " + localIp + " (Port 6060)");
        ipValue.setStyle("-fx-font-size: 14px; -fx-text-fill: #38bdf8; -fx-font-weight: bold;");

        connectionStatus = new Label("ONLINE");
        connectionStatus.setTextFill(Color.web("#4ade80"));
        connectionStatus.setStyle("-fx-font-weight: bold; -fx-font-size: 13px;");

        responseStatus = new Label("Server active and broadcasting discovery beacon.");
        responseStatus.setWrapText(true);
        responseStatus.setStyle("-fx-text-fill: #cbd5e1; -fx-font-size: 12px;");

        Button refreshButton = new Button("Refresh Stats");
        refreshButton.setStyle("-fx-background-color: #334155; -fx-text-fill: #f8fafc;");
        refreshButton.setOnAction(e -> requestStats());

        HBox topStatusRow = new HBox(12, connectionStatus, refreshButton);
        topStatusRow.setAlignment(Pos.CENTER_LEFT);

        VBox statusCard = new VBox(10, serverTitle, ipValue, topStatusRow, responseStatus);

        // Prominent Phone Pairing Request Card
        Label pairingTitle = new Label("PHONE PAIRING REQUEST");
        pairingTitle.setStyle("-fx-font-size: 15px; -fx-font-weight: bold; -fx-text-fill: #38bdf8;");

        pairingDeviceLabel = new Label("Device: Android Phone");
        pairingDeviceLabel.setStyle("-fx-font-size: 14px; -fx-text-fill: #f1f5f9;");

        pairingPinLabel = new Label("------");
        pairingPinLabel.setStyle("-fx-font-size: 44px; -fx-font-weight: bold; -fx-text-fill: #38bdf8; -fx-alignment: center;");

        Label pairingInstruction = new Label("Enter this 6-digit PIN on your phone to complete pairing (Expires in 60s)");
        pairingInstruction.setStyle("-fx-font-size: 12px; -fx-text-fill: #94a3b8;");
        pairingInstruction.setWrapText(true);

        pairingCard = new VBox(8, pairingTitle, pairingDeviceLabel, pairingPinLabel, pairingInstruction);
        pairingCard.setPadding(new Insets(18));
        pairingCard.setStyle(
                "-fx-background-color: #1e1b4b;" +
                "-fx-border-color: #6366f1;" +
                "-fx-border-width: 2px;" +
                "-fx-border-radius: 12;" +
                "-fx-background-radius: 12;");
        pairingCard.setVisible(false);
        pairingCard.setManaged(false);

        // Statistics Card
        clientsValue = new Label("—");
        uptimeValue = new Label("—");
        pairedValue = new Label("—");

        clientsValue.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: white;");
        uptimeValue.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: white;");
        pairedValue.setStyle("-fx-font-size: 24px; -fx-font-weight: bold; -fx-text-fill: white;");

        VBox statsCard = new VBox(
                12,
                new Label("CONNECTION STATISTICS"),
                new HBox(
                        24,
                        new VBox(6, new Label("Connected Clients"), clientsValue),
                        new VBox(6, new Label("Paired Devices"), pairedValue),
                        new VBox(6, new Label("Server Uptime"), uptimeValue)));

        for (VBox card : new VBox[] { statusCard, statsCard }) {
            card.setPadding(new Insets(18));
            card.setStyle(
                    "-fx-background-color: #1e293b;" +
                    "-fx-background-radius: 12;");
        }

        VBox root = new VBox(
                16,
                title,
                subtitle,
                statusCard,
                pairingCard,
                statsCard);

        root.setPadding(new Insets(24));
        root.setStyle("-fx-background-color: #0f172a;");

        root.lookupAll(".label").forEach(node -> {
            if (node instanceof Label label && label != title) {
                if (label.getTextFill().equals(Color.BLACK)) {
                    label.setTextFill(Color.WHITE);
                }
            }
        });

        title.setTextFill(Color.WHITE);
        subtitle.setTextFill(Color.web("#94a3b8"));
        serverTitle.setTextFill(Color.WHITE);

        Scene scene = new Scene(root, 620, 520);
        stage.setTitle("CTRLX - PC Dashboard");
        stage.setScene(scene);
        stage.setMinWidth(520);
        stage.setMinHeight(460);
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

        // Register in-process pairing listener to show PIN on UI immediately
        try {
            Server.pairingManager.addListener((deviceId, deviceName, pin) -> {
                Platform.runLater(() -> {
                    pairingDeviceLabel.setText("Device: " + deviceName);
                    pairingPinLabel.setText(pin);
                    pairingCard.setVisible(true);
                    pairingCard.setManaged(true);
                    responseStatus.setText("Pairing requested by " + deviceName + ". PIN: " + pin);
                });
            });
            Server.pairingManager.addClearListener(deviceId -> {
                Platform.runLater(() -> {
                    pairingCard.setVisible(false);
                    pairingCard.setManaged(false);
                    responseStatus.setText("Device paired successfully.");
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
                    connectionStatus.setText("ONLINE");
                    connectionStatus.setTextFill(Color.web("#4ade80"));
                    responseStatus.setText("Receiver active and listening for mobile connections.");
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
                        String dev = devM.find() ? devM.group(1) : "Android Device";

                        Platform.runLater(() -> {
                            pairingDeviceLabel.setText("Device: " + dev);
                            pairingPinLabel.setText(pin);
                            pairingCard.setVisible(true);
                            pairingCard.setManaged(true);
                        });
                    } else if (received.contains("\"type\":\"PAIR_PIN_CLEARED\"")) {
                        Platform.runLater(() -> {
                            pairingCard.setVisible(false);
                            pairingCard.setManaged(false);
                            requestStats();
                        });
                    } else if (received.contains("\"type\":\"STATS\"")) {
                        updateStats(received);
                    }
                }

            } catch (Exception e) {
                Platform.runLater(() -> {
                    connectionStatus.setText("OFFLINE");
                    connectionStatus.setTextFill(Color.web("#f87171"));
                    responseStatus.setText("Connecting to receiver...");
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
