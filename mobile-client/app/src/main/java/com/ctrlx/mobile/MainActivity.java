package com.ctrlx.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PREFS_NAME = "ctrlx_mobile_prefs";
    private static final String KEY_LAST_IP = "last_pc_ip";
    private static final String KEY_LAST_NAME = "last_pc_name";
    private static final String DEFAULT_IP = "10.232.250.170";
    private static final int DISCOVERY_PORT = 6061;
    private static final int SERVER_PORT = 6060;
    private static final float SENSITIVITY = 1.8f;

    public enum Tab {
        CONNECT,
        LAYOUTS,
        TOUCHPAD
    }

    private Tab currentTab = Tab.CONNECT;

    // Screens
    private ScrollView layoutConnect;
    private ScrollView layoutLayouts;
    private LinearLayout layoutTouchpad;

    // Bottom Navigation Bar
    private LinearLayout navTabConnect;
    private LinearLayout navTabLayouts;
    private LinearLayout navTabTouchpad;
    private TextView navLabelConnect;
    private TextView navLabelLayouts;
    private TextView navLabelTouchpad;

    // Tab 1: Connect Views
    private ProgressBar scanProgress;
    private TextView scanStatusText;
    private Button rescanButton;
    private LinearLayout discoveredPcCard;
    private TextView discoveredPcName;
    private TextView discoveredPcIp;
    private TextView connectButtonLabel;
    private TextView manualIpLink;

    // Tab 2: Layouts Views
    private TextView layoutsConnectedBadge;
    private Button layoutsDisconnectBtn;
    private LinearLayout gridToolTouchpad;
    private LinearLayout gridToolKeyboard;
    private LinearLayout gridToolMedia;
    private LinearLayout gridToolPower;

    // Tab 3: Touchpad Views
    private TextView touchpadPcBadge;
    private Button quickKeyboardBtn;
    private FrameLayout touchpadSurface;
    private Button leftClickButton;
    private Button rightClickButton;

    // Networking & State
    private volatile Socket socket;
    private volatile PrintWriter out;
    private volatile boolean connected;
    private volatile boolean connecting;
    private volatile boolean authenticated;

    private String targetPcIp;
    private String targetPcName = "Windows PC";
    private AlertDialog pinDialog;

    private final Object sendLock = new Object();
    private final ExecutorService senderExecutor = Executors.newSingleThreadExecutor();
    private Vibrator vibrator;

    // Touchpad gesture tracking
    private float lastX, lastY;
    private float downX, downY;
    private long downTime;
    private boolean isTwoFingerGesture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        bindViews();
        setupNavigation();
        setupEventListeners();

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        // Open on Connect tab (scan and show available PCs, do NOT auto-connect)
        switchTab(Tab.CONNECT);
        startLanScan();
    }

    private void bindViews() {
        // Tab Containers
        layoutConnect = findViewById(R.id.layoutConnect);
        layoutLayouts = findViewById(R.id.layoutLayouts);
        layoutTouchpad = findViewById(R.id.layoutTouchpad);

        // Bottom Navigation Bar
        navTabConnect = findViewById(R.id.navTabConnect);
        navTabLayouts = findViewById(R.id.navTabLayouts);
        navTabTouchpad = findViewById(R.id.navTabTouchpad);
        navLabelConnect = findViewById(R.id.navLabelConnect);
        navLabelLayouts = findViewById(R.id.navLabelLayouts);
        navLabelTouchpad = findViewById(R.id.navLabelTouchpad);

        // Tab 1 Views
        scanProgress = findViewById(R.id.scanProgress);
        scanStatusText = findViewById(R.id.scanStatusText);
        rescanButton = findViewById(R.id.rescanButton);
        discoveredPcCard = findViewById(R.id.discoveredPcCard);
        discoveredPcName = findViewById(R.id.discoveredPcName);
        discoveredPcIp = findViewById(R.id.discoveredPcIp);
        connectButtonLabel = findViewById(R.id.connectButtonLabel);
        manualIpLink = findViewById(R.id.manualIpLink);

        // Tab 2 Views
        layoutsConnectedBadge = findViewById(R.id.layoutsConnectedBadge);
        layoutsDisconnectBtn = findViewById(R.id.layoutsDisconnectBtn);
        gridToolTouchpad = findViewById(R.id.gridToolTouchpad);
        gridToolKeyboard = findViewById(R.id.gridToolKeyboard);
        gridToolMedia = findViewById(R.id.gridToolMedia);
        gridToolPower = findViewById(R.id.gridToolPower);

        // Tab 3 Views
        touchpadPcBadge = findViewById(R.id.touchpadPcBadge);
        quickKeyboardBtn = findViewById(R.id.quickKeyboardBtn);
        touchpadSurface = findViewById(R.id.touchpadSurface);
        leftClickButton = findViewById(R.id.leftClickButton);
        rightClickButton = findViewById(R.id.rightClickButton);
    }

    private void setupNavigation() {
        navTabConnect.setOnClickListener(v -> switchTab(Tab.CONNECT));

        navTabLayouts.setOnClickListener(v -> {
            if (!authenticated) {
                Toast.makeText(this, "Connect to a PC first", Toast.LENGTH_SHORT).show();
                return;
            }
            switchTab(Tab.LAYOUTS);
        });

        navTabTouchpad.setOnClickListener(v -> {
            if (!authenticated) {
                Toast.makeText(this, "Connect to a PC first", Toast.LENGTH_SHORT).show();
                return;
            }
            switchTab(Tab.TOUCHPAD);
        });
    }

    private void switchTab(Tab tab) {
        currentTab = tab;
        runOnUiThread(() -> {
            layoutConnect.setVisibility(tab == Tab.CONNECT ? View.VISIBLE : View.GONE);
            layoutLayouts.setVisibility(tab == Tab.LAYOUTS ? View.VISIBLE : View.GONE);
            layoutTouchpad.setVisibility(tab == Tab.TOUCHPAD ? View.VISIBLE : View.GONE);

            // Update bottom nav bar highlight
            navLabelConnect.setTextColor(tab == Tab.CONNECT ? Color.parseColor("#38BDF8") : Color.parseColor("#64748B"));
            navLabelLayouts.setTextColor(tab == Tab.LAYOUTS ? Color.parseColor("#38BDF8") : Color.parseColor("#64748B"));
            navLabelTouchpad.setTextColor(tab == Tab.TOUCHPAD ? Color.parseColor("#38BDF8") : Color.parseColor("#64748B"));
        });
    }

    private void setupEventListeners() {
        // Tab 1: Connect listeners
        rescanButton.setOnClickListener(v -> startLanScan());

        discoveredPcCard.setOnClickListener(v -> {
            if (targetPcIp != null && !targetPcIp.isEmpty()) {
                connectToPC(targetPcIp, targetPcName);
            }
        });

        manualIpLink.setOnClickListener(v -> showManualIpDialog());

        // Tab 2: Layouts listeners
        gridToolTouchpad.setOnClickListener(v -> switchTab(Tab.TOUCHPAD));
        gridToolKeyboard.setOnClickListener(v -> showKeyboardDialog());
        gridToolMedia.setOnClickListener(v -> showMediaDialog());
        gridToolPower.setOnClickListener(v -> showPowerDialog());

        layoutsDisconnectBtn.setOnClickListener(v -> {
            disconnectFromPC();
            switchTab(Tab.CONNECT);
            startLanScan();
        });

        // Tab 3: Touchpad listeners
        quickKeyboardBtn.setOnClickListener(v -> showKeyboardDialog());

        leftClickButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            triggerHaptic();
            sendMouseClick("LEFT");
        });

        rightClickButton.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            triggerHaptic();
            sendMouseClick("RIGHT");
        });

        setupTouchpadGesture();
    }

    private List<InetAddress> getBroadcastAddresses() {
        List<InetAddress> broadcastList = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.isLoopback() || !networkInterface.isUp()) {
                    continue;
                }
                for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                    InetAddress broadcast = interfaceAddress.getBroadcast();
                    if (broadcast != null) {
                        broadcastList.add(broadcast);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        if (broadcastList.isEmpty()) {
            try {
                broadcastList.add(InetAddress.getByName("255.255.255.255"));
            } catch (Exception ignored) {
            }
        }
        return broadcastList;
    }

    private void startLanScan() {
        if (connecting || connected) {
            return;
        }

        // Hide any previously displayed PC card immediately upon scanning
        discoveredPcCard.setVisibility(View.GONE);
        scanProgress.setVisibility(View.VISIBLE);
        scanStatusText.setText("Scanning local Wi-Fi for PCs...");
        scanStatusText.setTextColor(Color.parseColor("#38BDF8"));
        rescanButton.setEnabled(false);
        targetPcIp = null;

        new Thread(() -> {
            WifiManager.MulticastLock lock = null;
            String foundIp = null;
            String foundName = "Windows PC";

            try {
                WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wifi != null) {
                    lock = wifi.createMulticastLock("CTRLX_Discovery");
                    lock.setReferenceCounted(true);
                    lock.acquire();
                }

                try (DatagramSocket udpSocket = new DatagramSocket()) {
                    udpSocket.setBroadcast(true);
                    udpSocket.setSoTimeout(1200);

                    byte[] req = "{\"type\":\"CTRLX_DISCOVER\"}".getBytes(StandardCharsets.UTF_8);

                    // Send discovery packet to all subnet broadcast addresses and global broadcast
                    List<InetAddress> targets = getBroadcastAddresses();
                    for (InetAddress target : targets) {
                        try {
                            DatagramPacket packet = new DatagramPacket(req, req.length, target, DISCOVERY_PORT);
                            udpSocket.send(packet);
                        } catch (Exception ignored) {
                        }
                    }

                    // Also broadcast to 255.255.255.255 as backup
                    try {
                        DatagramPacket globalPacket = new DatagramPacket(
                                req, req.length, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT);
                        udpSocket.send(globalPacket);
                    } catch (Exception ignored) {
                    }

                    byte[] buf = new byte[1024];
                    DatagramPacket responsePacket = new DatagramPacket(buf, buf.length);
                    udpSocket.receive(responsePacket);

                    String respStr = new String(responsePacket.getData(), 0, responsePacket.getLength(), StandardCharsets.UTF_8);
                    if (respStr.contains("CTRLX_SERVER")) {
                        foundIp = responsePacket.getAddress().getHostAddress();
                        try {
                            JSONObject json = new JSONObject(respStr);
                            if (json.has("serverName")) {
                                foundName = json.getString("serverName");
                            }
                        } catch (Exception ignored) {
                        }
                    }
                }
            } catch (Exception ignored) {
                // Timeout or socket error -> server is offline
            } finally {
                if (lock != null && lock.isHeld()) {
                    try {
                        lock.release();
                    } catch (Exception ignored) {
                    }
                }
            }

            final String resIp = foundIp;
            final String resName = foundName;

            runOnUiThread(() -> {
                scanProgress.setVisibility(View.GONE);
                rescanButton.setEnabled(true);

                if (resIp != null && !resIp.isEmpty()) {
                    targetPcIp = resIp;
                    targetPcName = resName;
                    discoveredPcName.setText(resName);
                    discoveredPcIp.setText(resIp + " : 6060 • Online");
                    discoveredPcCard.setVisibility(View.VISIBLE);
                    scanStatusText.setText("1 Receiver found on your Wi-Fi");
                    scanStatusText.setTextColor(Color.parseColor("#4ADE80"));
                } else {
                    // When PC is offline, it is NOT shown on the phone
                    targetPcIp = null;
                    discoveredPcCard.setVisibility(View.GONE);
                    scanStatusText.setText("No PC receiver found on your Wi-Fi.\nEnsure CTRLX is open on your PC.");
                    scanStatusText.setTextColor(Color.parseColor("#94A3B8"));
                }
            });
        }).start();
    }

    private void showManualIpDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Connect to Custom IP");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 10);

        final EditText ipInput = new EditText(this);
        ipInput.setHint("e.g. 10.232.250.170");
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        ipInput.setText(prefs.getString(KEY_LAST_IP, DEFAULT_IP));
        layout.addView(ipInput);

        builder.setView(layout);

        builder.setPositiveButton("Connect", (dialog, which) -> {
            String ip = ipInput.getText().toString().trim();
            if (!ip.isEmpty()) {
                connectToPC(ip, "Windows PC");
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    void connectToPC(String host, String hostName) {
        if (connected || connecting) {
            return;
        }

        connecting = true;
        scanStatusText.setText("Connecting to " + hostName + " (" + host + ")...");
        scanStatusText.setTextColor(Color.parseColor("#F59E0B"));

        new Thread(() -> {
            Socket newSocket = new Socket();

            try {
                newSocket.connect(new InetSocketAddress(host, SERVER_PORT), 4000);
                newSocket.setSoTimeout(0);
                newSocket.setKeepAlive(true);
                newSocket.setTcpNoDelay(true);

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
                    throw new Exception("Handshake failed.");
                }

                socket = newSocket;
                out = writer;
                connected = true;

                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                prefs.edit().putString(KEY_LAST_IP, host).putString(KEY_LAST_NAME, hostName).apply();

                String savedToken = SecurityUtils.getAuthToken(MainActivity.this);
                String deviceId = SecurityUtils.getDeviceId(MainActivity.this);
                String myDeviceName = Build.MODEL != null ? Build.MODEL : "Android Phone";

                if (savedToken != null && !savedToken.isEmpty()) {
                    runOnUiThread(() -> scanStatusText.setText("Authenticating with " + hostName + "..."));
                    JSONObject authMsg = new JSONObject();
                    authMsg.put("type", "AUTH");
                    authMsg.put("deviceId", deviceId);
                    authMsg.put("deviceName", myDeviceName);
                    authMsg.put("token", savedToken);
                    writer.println(authMsg.toString());
                } else {
                    runOnUiThread(() -> scanStatusText.setText("Requesting pairing PIN from PC..."));
                    JSONObject pairMsg = new JSONObject();
                    pairMsg.put("type", "PAIR_REQUEST");
                    pairMsg.put("deviceId", deviceId);
                    pairMsg.put("deviceName", myDeviceName);
                    writer.println(pairMsg.toString());
                }

                String message;
                while ((message = reader.readLine()) != null) {
                    JSONObject json = new JSONObject(message);
                    String type = json.optString("type");

                    if ("AUTH_SUCCESS".equals(type)) {
                        authenticated = true;
                        connecting = false;
                        runOnUiThread(() -> onConnectionSuccess(host, hostName));
                    } else if ("PAIR_PIN_REQUIRED".equals(type)) {
                        runOnUiThread(() -> promptForPin(hostName));
                    } else if ("PAIR_SUCCESS".equals(type)) {
                        String token = json.optString("token");
                        if (token != null && !token.isEmpty()) {
                            SecurityUtils.saveAuthToken(MainActivity.this, token);
                        }
                        authenticated = true;
                        connecting = false;
                        runOnUiThread(() -> {
                            if (pinDialog != null && pinDialog.isShowing()) {
                                pinDialog.dismiss();
                            }
                            Toast.makeText(MainActivity.this, "Paired successfully with " + hostName, Toast.LENGTH_SHORT).show();
                            onConnectionSuccess(host, hostName);
                        });
                    } else if ("ERROR".equals(type)) {
                        String code = json.optString("code");
                        if ("AUTH_FAILED".equals(code)) {
                            SecurityUtils.clearAuthToken(MainActivity.this);
                            JSONObject pairMsg = new JSONObject();
                            pairMsg.put("type", "PAIR_REQUEST");
                            pairMsg.put("deviceId", deviceId);
                            pairMsg.put("deviceName", myDeviceName);
                            writer.println(pairMsg.toString());
                        } else if ("INVALID_PIN".equals(code)) {
                            runOnUiThread(() -> {
                                Toast.makeText(MainActivity.this, "Incorrect PIN. Please re-enter.", Toast.LENGTH_LONG).show();
                                promptForPin(hostName);
                            });
                        } else if ("TOO_MANY_ATTEMPTS".equals(code) || "PIN_EXPIRED".equals(code)) {
                            runOnUiThread(() -> {
                                if (pinDialog != null && pinDialog.isShowing()) {
                                    pinDialog.dismiss();
                                }
                                Toast.makeText(MainActivity.this, "Pairing expired. Tap to retry.", Toast.LENGTH_LONG).show();
                            });
                            break;
                        }
                    }
                }

                if (connected && socket == newSocket) {
                    throw new Exception("Connection closed.");
                }

            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (pinDialog != null && pinDialog.isShowing()) {
                        pinDialog.dismiss();
                    }
                    if (currentTab != Tab.CONNECT) {
                        Toast.makeText(MainActivity.this, "Disconnected from PC", Toast.LENGTH_SHORT).show();
                        switchTab(Tab.CONNECT);
                    }
                    scanStatusText.setText("Connection failed. Check Wi-Fi & PC receiver.");
                    scanStatusText.setTextColor(Color.parseColor("#EF4444"));
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
                    authenticated = false;
                }
                connecting = false;
            }
        }).start();
    }

    private void onConnectionSuccess(String host, String hostName) {
        layoutsConnectedBadge.setText("● Connected: " + hostName);
        touchpadPcBadge.setText("● " + hostName);

        // Monect Flow: jump directly to Touchpad controller upon connection!
        switchTab(Tab.TOUCHPAD);
    }

    private void promptForPin(String hostName) {
        if (isFinishing() || !connected) {
            return;
        }

        if (pinDialog != null && pinDialog.isShowing()) {
            pinDialog.dismiss();
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("PC Pairing Verification");
        builder.setMessage("Enter the 6-digit PIN displayed on your PC screen (" + hostName + "):");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 10);

        final EditText pinInput = new EditText(this);
        pinInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        pinInput.setHint("6-digit PIN");
        pinInput.setTextSize(22);
        pinInput.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        layout.addView(pinInput);

        builder.setView(layout);

        builder.setPositiveButton("Pair & Connect", (dialog, which) -> {
            String pin = pinInput.getText().toString().trim();
            if (pin.matches("^[0-9]{6}$")) {
                sendPinVerification(pin);
            } else {
                Toast.makeText(MainActivity.this, "PIN must be exactly 6 digits", Toast.LENGTH_SHORT).show();
                promptForPin(hostName);
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> {
            dialog.dismiss();
            disconnectFromPC();
            switchTab(Tab.CONNECT);
        });

        builder.setCancelable(false);
        pinDialog = builder.create();
        pinDialog.show();
    }

    private void sendPinVerification(String pin) {
        senderExecutor.submit(() -> {
            try {
                synchronized (sendLock) {
                    if (out != null) {
                        JSONObject verifyMsg = new JSONObject();
                        verifyMsg.put("type", "PAIR_VERIFY");
                        verifyMsg.put("deviceId", SecurityUtils.getDeviceId(MainActivity.this));
                        verifyMsg.put("pin", pin);
                        out.println(verifyMsg.toString());
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void showKeyboardDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Keyboard & Hotkeys");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 10);

        final EditText textInput = new EditText(this);
        textInput.setHint("Type text to send to PC...");
        layout.addView(textInput);

        // Quick keys row 1: Enter, Backspace, Space, Esc
        LinearLayout keysRow = new LinearLayout(this);
        keysRow.setOrientation(LinearLayout.HORIZONTAL);
        keysRow.setPadding(0, 20, 0, 0);

        String[] keys = {"Enter", "Backspace", "Space", "Esc"};
        for (String k : keys) {
            Button btn = new Button(this);
            btn.setText(k);
            btn.setTextSize(11);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            p.setMargins(4, 0, 4, 0);
            btn.setLayoutParams(p);
            btn.setOnClickListener(v -> sendKeyPress(k.toUpperCase()));
            keysRow.addView(btn);
        }
        layout.addView(keysRow);

        builder.setView(layout);

        builder.setPositiveButton("Send Text", (dialog, which) -> {
            String text = textInput.getText().toString();
            if (!text.isEmpty()) {
                sendKeyText(text);
            }
        });

        builder.setNegativeButton("Close", (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    private void showMediaDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Media Controller");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 20, 40, 20);

        String[][] mediaButtons = {
                {"Play / Pause", "SPACE"},
                {"Volume Up", "UP"},
                {"Volume Down", "DOWN"},
                {"Mute / Esc", "ESCAPE"}
        };

        for (String[] item : mediaButtons) {
            Button btn = new Button(this);
            btn.setText(item[0]);
            btn.setTextSize(14);
            btn.setPadding(0, 16, 0, 16);
            btn.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                sendKeyPress(item[1]);
            });
            layout.addView(btn);
        }

        builder.setView(layout);
        builder.setNegativeButton("Close", (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    private void showPowerDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Power Controls");
        builder.setMessage("Select a power action for your PC:");

        builder.setPositiveButton("Lock PC (Win+L)", (dialog, which) -> sendKeyPress("WIN"));
        builder.setNeutralButton("Sleep / Esc", (dialog, which) -> sendKeyPress("ESCAPE"));
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    private void setupTouchpadGesture() {
        touchpadSurface.setOnTouchListener((v, event) -> {
            if (!authenticated) {
                return false;
            }

            int action = event.getActionMasked();
            int pointerCount = event.getPointerCount();

            if (pointerCount == 2) {
                isTwoFingerGesture = true;
                if (action == MotionEvent.ACTION_MOVE) {
                    float currentY = (event.getY(0) + event.getY(1)) / 2f;
                    float deltaY = currentY - lastY;
                    if (Math.abs(deltaY) > 8) {
                        int amount = (int) (-deltaY / 8f);
                        sendScroll(amount);
                        lastY = currentY;
                    }
                } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
                    lastY = (event.getY(0) + event.getY(1)) / 2f;
                }
                return true;
            }

            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    isTwoFingerGesture = false;
                    lastX = event.getX();
                    lastY = event.getY();
                    downX = lastX;
                    downY = lastY;
                    downTime = System.currentTimeMillis();
                    return true;

                case MotionEvent.ACTION_MOVE:
                    if (isTwoFingerGesture) {
                        return true;
                    }

                    float currentX = event.getX();
                    float currentY = event.getY();

                    // Scroll strip on right edge
                    int viewWidth = v.getWidth();
                    if (downX > viewWidth - 100) {
                        float deltaY = currentY - lastY;
                        if (Math.abs(deltaY) > 6) {
                            sendScroll((int) (-deltaY / 6f));
                            lastY = currentY;
                        }
                        return true;
                    }

                    float dx = (currentX - lastX) * SENSITIVITY;
                    float dy = (currentY - lastY) * SENSITIVITY;

                    if (Math.abs(dx) >= 0.5f || Math.abs(dy) >= 0.5f) {
                        sendMouseMove(Math.round(dx), Math.round(dy));
                        lastX = currentX;
                        lastY = currentY;
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    if (!isTwoFingerGesture) {
                        long duration = System.currentTimeMillis() - downTime;
                        float distance = (float) Math.hypot(event.getX() - downX, event.getY() - downY);

                        if (duration < 220 && distance < 15) {
                            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                            triggerHaptic();
                            sendMouseClick("LEFT");
                        }
                    }
                    return true;
            }

            return false;
        });
    }

    private void sendMouseMove(int dx, int dy) {
        if (!authenticated || out == null) {
            return;
        }
        senderExecutor.submit(() -> {
            try {
                synchronized (sendLock) {
                    if (out != null) {
                        out.println("{\"type\":\"MOUSE_MOVE\",\"dx\":" + dx + ",\"dy\":" + dy + "}");
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void sendMouseClick(String button) {
        if (!authenticated || out == null) {
            return;
        }
        senderExecutor.submit(() -> {
            try {
                synchronized (sendLock) {
                    if (out != null) {
                        out.println("{\"type\":\"MOUSE_CLICK\",\"button\":\"" + button + "\",\"action\":\"CLICK\"}");
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void sendScroll(int amount) {
        if (!authenticated || out == null) {
            return;
        }
        senderExecutor.submit(() -> {
            try {
                synchronized (sendLock) {
                    if (out != null) {
                        out.println("{\"type\":\"MOUSE_SCROLL\",\"amount\":" + amount + "}");
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void sendKeyText(String text) {
        if (!authenticated || out == null) {
            return;
        }
        senderExecutor.submit(() -> {
            try {
                synchronized (sendLock) {
                    if (out != null) {
                        JSONObject msg = new JSONObject();
                        msg.put("type", "KEY_TEXT");
                        msg.put("text", text);
                        out.println(msg.toString());
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void sendKeyPress(String key) {
        if (!authenticated || out == null) {
            return;
        }
        senderExecutor.submit(() -> {
            try {
                synchronized (sendLock) {
                    if (out != null) {
                        JSONObject msg = new JSONObject();
                        msg.put("type", "KEY_PRESS");
                        msg.put("key", key);
                        out.println(msg.toString());
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void disconnectFromPC() {
        connecting = false;
        connected = false;
        authenticated = false;

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

    private void triggerHaptic() {
        try {
            if (vibrator != null && vibrator.hasVibrator()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator.vibrate(VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    vibrator.vibrate(25);
                }
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onBackPressed() {
        if (currentTab == Tab.TOUCHPAD) {
            switchTab(Tab.LAYOUTS);
        } else if (currentTab == Tab.LAYOUTS) {
            switchTab(Tab.CONNECT);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        disconnectFromPC();
        senderExecutor.shutdown();
        super.onDestroy();
    }
}
