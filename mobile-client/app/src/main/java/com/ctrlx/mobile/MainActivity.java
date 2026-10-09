package com.ctrlx.mobile;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    EditText ipAddress;
    Button connectButton;
    Button pingButton;
    Button disconnectButton;
    TextView statusText;

    volatile Socket socket;
    volatile PrintWriter out;
    volatile boolean connected;
    volatile boolean connecting;

    final Object sendLock = new Object();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ipAddress = findViewById(R.id.ipAddress);
        connectButton = findViewById(R.id.connectButton);
        pingButton = findViewById(R.id.pingButton);
        disconnectButton = findViewById(R.id.disconnectButton);
        statusText = findViewById(R.id.statusText);

        connectButton.setOnClickListener(v -> connectToPC());
        pingButton.setOnClickListener(v -> sendPing());
        disconnectButton.setOnClickListener(v -> disconnectFromPC());

        updateButtons();
    }

    void connectToPC() {
        if (connected || connecting) {
            return;
        }

        String host = ipAddress.getText().toString().trim();

        if (host.isEmpty()) {
            statusText.setText("Enter the PC IP address.");
            return;
        }

        connecting = true;
        statusText.setText("Connecting to " + host + ":6060...");
        updateButtons();

        new Thread(() -> {
            Socket newSocket = new Socket();

            try {
                newSocket.connect(
                        new InetSocketAddress(host, 6060),
                        5000);
                newSocket.setSoTimeout(0);
                newSocket.setKeepAlive(true);

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

                if (response == null) {
                    throw new Exception("Server closed the connection.");
                }

                JSONObject welcome = new JSONObject(response);

                if (!"WELCOME".equals(welcome.optString("type"))) {
                    throw new Exception(
                            welcome.optString("message", "Handshake rejected."));
                }

                socket = newSocket;
                out = writer;
                connected = true;

                runOnUiThread(() -> {
                    connecting = false;
                    statusText.setText(
                            "Status: Connected\n" + host + ":6060\nReady to ping.");
                    updateButtons();
                });

                String message;

                while ((message = reader.readLine()) != null) {
                    JSONObject json = new JSONObject(message);
                    String type = json.optString("type");

                    if ("PONG".equals(type)) {
                        runOnUiThread(() -> statusText.setText(
                                "Status: Connected\nPONG received from PC."));
                    } else if ("ERROR".equals(type)) {
                        String error = json.optString(
                                "message", "Unknown server error.");

                        runOnUiThread(() -> statusText.setText(
                                "Status: Connected\nServer error: " + error));
                    }
                }

                if (connected && socket == newSocket) {
                    throw new Exception("Server closed the connection.");
                }

            } catch (Exception e) {
                if (connecting || connected) {
                    String reason = e.getMessage();

                    if (reason == null || reason.isEmpty()) {
                        reason = "Connection ended.";
                    }

                    String finalReason = reason;

                    runOnUiThread(() -> statusText.setText("Disconnected\n" + finalReason));
                }
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

                connecting = false;

                runOnUiThread(this::updateButtons);
            }
        }).start();
    }

    void sendPing() {
        if (!connected || out == null) {
            statusText.setText("Not connected to the PC.");
            return;
        }

        pingButton.setEnabled(false);
        statusText.setText("Sending PING...");

        new Thread(() -> {
            String result;

            try {
                synchronized (sendLock) {
                    if (!connected || out == null) {
                        throw new Exception("Connection is no longer active.");
                    }

                    out.println("{\"type\":\"PING\"}");

                    if (out.checkError()) {
                        throw new Exception("Failed to send PING.");
                    }
                }

                result = "PING sent. Waiting for PONG...";
            } catch (Exception e) {
                result = "Ping failed: " + e.getMessage();
            }

            String finalResult = result;

            runOnUiThread(() -> {
                statusText.setText(finalResult);
                pingButton.setEnabled(connected);
            });
        }).start();
    }

    void disconnectFromPC() {
        connecting = false;
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

        statusText.setText("Status: Disconnected");
        updateButtons();
    }

    void updateButtons() {
        connectButton.setEnabled(!connected && !connecting);
        pingButton.setEnabled(connected);
        disconnectButton.setEnabled(connected || connecting);
        ipAddress.setEnabled(!connected && !connecting);
    }

    @Override
    protected void onDestroy() {
        disconnectFromPC();
        super.onDestroy();
    }
}
