
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class Dashboard extends Application {
    Socket socket;
    BufferedReader in;
    PrintWriter out;
    volatile boolean connected;
    final Object sendLock = new Object();

    Label connectionStatus;
    Label responseStatus;
    Label clientsValue;
    Label uptimeValue;
    TextField hostField;
    TextField portField;
    Button connectButton;
    Button pingButton;
    Button disconnectButton;
    Button statsButton;

    @Override
    public void start(Stage stage) {
        Label title = new Label("CTRLX");
        title.setStyle("-fx-font-size: 32px; -fx-font-weight: bold;");

        Label subtitle = new Label("YOUR PC. YOUR CONTROL.");
        subtitle.setStyle("-fx-text-fill: #94a3b8; -fx-font-size: 12px;");

        Label serverTitle = new Label("SERVER CONNECTION");
        serverTitle.setStyle("-fx-font-size: 16px; -fx-font-weight: bold;");

        hostField = new TextField("127.0.0.1");
        hostField.setPromptText("Server IP");

        portField = new TextField("6060");
        portField.setPrefWidth(90);
        portField.setPromptText("Port");

        HBox addressRow = new HBox(10, hostField, portField);
        HBox.setHgrow(hostField, Priority.ALWAYS);

        connectButton = new Button("Connect");
        pingButton = new Button("Ping Server");
        disconnectButton = new Button("Disconnect");
        statsButton = new Button("Refresh Stats");
        pingButton.setDisable(true);
        disconnectButton.setDisable(true);
        statsButton.setDisable(true);
        connectionStatus = new Label("DISCONNECTED");
        connectionStatus.setTextFill(Color.web("#f87171"));

        responseStatus = new Label("No response received yet.");
        clientsValue = new Label("—");
        uptimeValue = new Label("—");

        clientsValue.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        uptimeValue.setStyle("-fx-font-size: 24px; -fx-font-weight: bold;");
        responseStatus.setWrapText(true);

        VBox connectionCard = new VBox(
                14,
                serverTitle,
                addressRow,
                new HBox(10, connectButton, disconnectButton),
                connectionStatus);

        VBox pingCard = new VBox(
                12,
                new Label("CONNECTION TEST"),
                new HBox(10, pingButton, statsButton),
                responseStatus);

        VBox statsCard = new VBox(
                12,
                new Label("SERVER STATISTICS"),
                new HBox(
                        10,
                        new VBox(6, new Label("Connected Clients"), clientsValue),
                        new VBox(6, new Label("Server Uptime"), uptimeValue)));

        for (VBox card : new VBox[] { connectionCard, pingCard, statsCard }) {
            card.setPadding(new Insets(20));
            card.setStyle(
                    "-fx-background-color: #1e293b;" +
                            "-fx-background-radius: 12;");
        }

        connectButton.setOnAction(e -> connectToServer());
        pingButton.setOnAction(e -> pingServer());
        disconnectButton.setOnAction(e -> disconnect());
        statsButton.setOnAction(e -> requestStats());
        VBox root = new VBox(
                20,
                title,
                subtitle,
                connectionCard,
                pingCard,
                statsCard);

        root.setPadding(new Insets(30));
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
        responseStatus.setTextFill(Color.web("#cbd5e1"));
        clientsValue.setTextFill(Color.WHITE);
        uptimeValue.setTextFill(Color.WHITE);

        Scene scene = new Scene(root, 600, 500);
        stage.setTitle("CTRLX - PC Dashboard");
        stage.setScene(scene);
        stage.setMinWidth(500);
        stage.setMinHeight(450);
        stage.show();

        stage.setOnCloseRequest(e -> disconnect());
    }

    private void requestStats() {
        if (!connected || out == null) {
            responseStatus.setText("Connect to the server first.");
            return;
        }

        Thread thread = new Thread(() -> {
            synchronized (sendLock) {
                if (connected && out != null) {
                    out.println("{\"type\":\"STATS\"}");

                    if (out.checkError()) {
                        javafx.application.Platform.runLater(
                                () -> responseStatus.setText("Failed to request stats."));
                    }
                }
            }
        });

        thread.setDaemon(true);
        thread.start();
    }

    void connectToServer() {
        String host = hostField.getText().trim();
        int port;

        try {
            port = Integer.parseInt(portField.getText().trim());

            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
        } catch (NumberFormatException e) {
            responseStatus.setText("Enter a valid port.");
            return;
        }

        connectButton.setDisable(true);
        connectionStatus.setTextFill(Color.ORANGE);
        connectionStatus.setText("CONNECTING...");

        new Thread(() -> {
            Socket newSocket = new Socket();

            try {
                newSocket.connect(new InetSocketAddress(host, port), 5000);

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

                Platform.runLater(() -> {
                    connectionStatus.setText("CONNECTED");
                    connectionStatus.setTextFill(Color.web("#4ade80"));
                    responseStatus.setText("Handshake successful: " + response);
                    pingButton.setDisable(false);
                    disconnectButton.setDisable(false);
                    hostField.setDisable(true);
                    portField.setDisable(true);
                    connectButton.setDisable(true);
                    statsButton.setDisable(false);
                });

                String message;

                while ((message = reader.readLine()) != null) {
                    String received = message;

                    if (received.contains("\"type\":\"STATS\"")
                            || received.contains("\"type\": \"STATS\"")) {
                        updateStats(received);
                    } else {
                        Platform.runLater(
                                () -> responseStatus.setText("Server response: " + received));
                    }
                }
                throw new Exception("Server closed the connection.");

            } catch (Exception e) {
                String reason = e.getMessage() == null
                        ? "Connection ended."
                        : e.getMessage();

                Platform.runLater(() -> responseStatus.setText(reason));
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

                Platform.runLater(this::updateConnectionUI);
            }
        }).start();
    }

    private void updateStats(String json) {
        Pattern clientsPattern = Pattern.compile(
                "\"connectedClients\"\\s*:\\s*(\\d+)");
        Pattern uptimePattern = Pattern.compile(
                "\"uptimeSeconds\"\\s*:\\s*(\\d+)");

        Matcher clientsMatcher = clientsPattern.matcher(json);
        Matcher uptimeMatcher = uptimePattern.matcher(json);

        if (!clientsMatcher.find() || !uptimeMatcher.find()) {
            Platform.runLater(
                    () -> responseStatus.setText("Invalid statistics response."));
            return;
        }

        int clients = Integer.parseInt(clientsMatcher.group(1));
        long uptime = Long.parseLong(uptimeMatcher.group(1));

        long hours = uptime / 3600;
        long minutes = (uptime % 3600) / 60;
        long seconds = uptime % 60;

        String formattedUptime = String.format(
                "%02d:%02d:%02d", hours, minutes, seconds);

        Platform.runLater(() -> {
            clientsValue.setText(String.valueOf(clients));
            uptimeValue.setText(formattedUptime);
            responseStatus.setText("Server statistics updated successfully.");
        });
    }

    void pingServer() {
        if (!connected || out == null) {
            return;
        }

        synchronized (sendLock) {
            out.println("{\"type\":\"PING\"}");

            if (out.checkError()) {
                responseStatus.setText("Failed to send PING.");
                return;
            }
        }

        responseStatus.setText("PING sent. Waiting for PONG...");
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

        updateConnectionUI();
    }

    void updateConnectionUI() {
        boolean active = connected;

        connectionStatus.setText(active ? "CONNECTED" : "DISCONNECTED");
        connectionStatus.setTextFill(
                active ? Color.web("#4ade80") : Color.web("#f87171"));

        connectButton.setDisable(active);
        pingButton.setDisable(!active);
        disconnectButton.setDisable(!active);
        statsButton.setDisable(!active);
        hostField.setDisable(active);
        portField.setDisable(active);
    }

    @Override
    public void stop() {
        disconnect();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
