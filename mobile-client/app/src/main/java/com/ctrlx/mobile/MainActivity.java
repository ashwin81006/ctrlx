package com.ctrlx.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Color;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
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
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private static final String PREFS_NAME = "ctrlx_mobile_prefs";
    private static final String KEY_LAST_IP = "last_pc_ip";
    private static final String KEY_LAST_NAME = "last_pc_name";
    private static final int DISCOVERY_PORT = 6061;
    private static final int SERVER_PORT = 6060;
    private static final float SENSITIVITY = 1.8f;

    public enum Tab {
        CONNECT,
        TOUCHPAD,
        GAMEPAD
    }

    private Tab currentTab = Tab.CONNECT;

    // Screens
    private ScrollView layoutConnect;
    private LinearLayout layoutTouchpad;
    private LinearLayout layoutGamepad;

    // Bottom Navigation Bar
    private LinearLayout bottomNavBar;
    private LinearLayout navTabConnect;
    private LinearLayout navTabTouchpad;
    private LinearLayout navTabGamepad;
    private TextView navLabelConnect;
    private TextView navLabelTouchpad;
    private TextView navLabelGamepad;

    // Tab 1: Connect Views
    private ProgressBar scanProgress;
    private TextView scanStatusText;
    private Button rescanButton;
    private LinearLayout discoveredPcList;

    // Touchpad Views
    private TextView touchpadPcBadge;
    private Button touchpadDisconnectBtn;
    private FrameLayout touchpadSurface;
    private Button leftClickButton;
    private Button rightClickButton;

    // Gamepad Views
    private TextView gamepadPcBadge;
    private Button gamepadProfileBtn;
    private Button gamepadRotateBtn;
    private Button gamepadBackBtn;
    private JoystickView leftStick;
    private JoystickView rightStick;
    private Button btnLT, btnLB, btnRB, btnRT;
    private Button btnView, btnXboxGuide, btnMenu;
    private Button btnDpadUp, btnDpadLeft, btnDpadRight, btnDpadDown, btnL3;
    private Button btnY, btnX, btnB, btnA, btnR3;

    // Gamepad Profiles & State
    private int currentProfileIndex = 0;
    private final String[] profileNames = {"FPS / Action ▾", "Racing / Driving ▾", "Retro / 2D ▾"};
    private final String[] profileCodes = {"FPS", "RACING", "RETRO"};
    private boolean isLandscape = false;

    // Networking & State
    private volatile Socket socket;
    private volatile PrintWriter out;
    private volatile boolean connected;
    private volatile boolean connecting;
    private volatile boolean authenticated;
    private volatile boolean voluntaryDisconnect;

    private String targetPcIp;
    private String targetPcName = "Windows PC";
    private final Map<String, String> discoveredServers = new LinkedHashMap<>();
    private AlertDialog pinDialog;

    private final Object sendLock = new Object();
    private final ExecutorService senderExecutor = Executors.newSingleThreadExecutor();
    private ScheduledExecutorService heartbeatScheduler;
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
        layoutTouchpad = findViewById(R.id.layoutTouchpad);
        layoutGamepad = findViewById(R.id.layoutGamepad);

        // Bottom Navigation Bar
        bottomNavBar = findViewById(R.id.bottomNavBar);
        navTabConnect = findViewById(R.id.navTabConnect);
        navTabTouchpad = findViewById(R.id.navTabTouchpad);
        navTabGamepad = findViewById(R.id.navTabGamepad);
        navLabelConnect = findViewById(R.id.navLabelConnect);
        navLabelTouchpad = findViewById(R.id.navLabelTouchpad);
        navLabelGamepad = findViewById(R.id.navLabelGamepad);

        // Tab 1 Views
        scanProgress = findViewById(R.id.scanProgress);
        scanStatusText = findViewById(R.id.scanStatusText);
        rescanButton = findViewById(R.id.rescanButton);
        discoveredPcList = findViewById(R.id.discoveredPcList);

        // Touchpad Views
        touchpadPcBadge = findViewById(R.id.touchpadPcBadge);
        touchpadDisconnectBtn = findViewById(R.id.touchpadDisconnectBtn);
        touchpadSurface = findViewById(R.id.touchpadSurface);
        leftClickButton = findViewById(R.id.leftClickButton);
        rightClickButton = findViewById(R.id.rightClickButton);

        // Gamepad Views
        gamepadPcBadge = findViewById(R.id.gamepadPcBadge);
        gamepadProfileBtn = findViewById(R.id.gamepadProfileBtn);
        gamepadRotateBtn = findViewById(R.id.gamepadRotateBtn);
        gamepadBackBtn = findViewById(R.id.gamepadBackBtn);
        leftStick = findViewById(R.id.leftStick);
        rightStick = findViewById(R.id.rightStick);
        if (leftStick != null) leftStick.setLabel("LS");
        if (rightStick != null) rightStick.setLabel("RS");

        btnLT = findViewById(R.id.btnLT);
        btnLB = findViewById(R.id.btnLB);
        btnRB = findViewById(R.id.btnRB);
        btnRT = findViewById(R.id.btnRT);

        btnView = findViewById(R.id.btnView);
        btnXboxGuide = findViewById(R.id.btnXboxGuide);
        btnMenu = findViewById(R.id.btnMenu);

        btnDpadUp = findViewById(R.id.btnDpadUp);
        btnDpadLeft = findViewById(R.id.btnDpadLeft);
        btnDpadRight = findViewById(R.id.btnDpadRight);
        btnDpadDown = findViewById(R.id.btnDpadDown);
        btnL3 = findViewById(R.id.btnL3);

        btnY = findViewById(R.id.btnY);
        btnX = findViewById(R.id.btnX);
        btnB = findViewById(R.id.btnB);
        btnA = findViewById(R.id.btnA);
        btnR3 = findViewById(R.id.btnR3);
    }

    private void setupNavigation() {
        navTabConnect.setOnClickListener(v -> {
            switchTab(Tab.CONNECT);
            if (!connected && !connecting) {
                startLanScan();
            }
        });

        navTabTouchpad.setOnClickListener(v -> {
            if (!authenticated) {
                Toast.makeText(this, "Connect to a PC first for live input", Toast.LENGTH_SHORT).show();
            }
            switchTab(Tab.TOUCHPAD);
        });

        navTabGamepad.setOnClickListener(v -> {
            if (!authenticated) {
                Toast.makeText(this, "Connect to a PC first for live input", Toast.LENGTH_SHORT).show();
            }
            switchTab(Tab.GAMEPAD);
        });
    }

    private void switchTab(Tab tab) {
        currentTab = tab;
        runOnUiThread(() -> {
            boolean isGamepad = (tab == Tab.GAMEPAD);

            layoutConnect.setVisibility(tab == Tab.CONNECT ? View.VISIBLE : View.GONE);
            layoutTouchpad.setVisibility(tab == Tab.TOUCHPAD ? View.VISIBLE : View.GONE);
            layoutGamepad.setVisibility(isGamepad ? View.VISIBLE : View.GONE);

            // Hide bottom nav bar in horizontal gamepad mode for full-screen console immersion
            if (bottomNavBar != null) {
                bottomNavBar.setVisibility(isGamepad ? View.GONE : View.VISIBLE);
            }

            // Update bottom nav bar highlight
            navLabelConnect.setTextColor(tab == Tab.CONNECT ? Color.parseColor("#38BDF8") : Color.parseColor("#64748B"));
            navLabelTouchpad.setTextColor(tab == Tab.TOUCHPAD ? Color.parseColor("#38BDF8") : Color.parseColor("#64748B"));
            navLabelGamepad.setTextColor(isGamepad ? Color.parseColor("#38BDF8") : Color.parseColor("#64748B"));

            if (isGamepad) {
                isLandscape = true;
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                if (targetPcName != null) {
                    gamepadPcBadge.setText("🎮 " + targetPcName);
                } else {
                    gamepadPcBadge.setText("🎮 XBOX GAMEPAD");
                }
            } else {
                isLandscape = false;
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            }
        });
    }

    private void setupEventListeners() {
        // Tab 1: Connect listeners
        rescanButton.setOnClickListener(v -> startLanScan());

        // Touchpad listeners
        touchpadDisconnectBtn.setOnClickListener(v -> {
            disconnectFromPC();
            switchTab(Tab.CONNECT);
            startLanScan();
        });

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

        // Gamepad Action Buttons (Tactile multi-touch hold & release)
        bindGamepadButton(btnA, "A");
        bindGamepadButton(btnB, "B");
        bindGamepadButton(btnX, "X");
        bindGamepadButton(btnY, "Y");

        // Gamepad Shoulder Buttons & Triggers
        bindGamepadButton(btnLT, "LT");
        bindGamepadButton(btnLB, "LB");
        bindGamepadButton(btnRT, "RT");
        bindGamepadButton(btnRB, "RB");

        // Gamepad D-Pad Directions & Stick Clicks
        bindGamepadButton(btnDpadUp, "DPAD_UP");
        bindGamepadButton(btnDpadDown, "DPAD_DOWN");
        bindGamepadButton(btnDpadLeft, "DPAD_LEFT");
        bindGamepadButton(btnDpadRight, "DPAD_RIGHT");
        bindGamepadButton(btnL3, "L3");
        bindGamepadButton(btnR3, "R3");

        // System & Xbox Guide Buttons
        bindGamepadButton(btnView, "BACK");
        bindGamepadButton(btnMenu, "START");
        bindGamepadButton(btnXboxGuide, "XBOX");

        // Virtual Analog Joysticks
        if (leftStick != null) {
            leftStick.setOnJoystickMoveListener((x, y) -> sendGamepadAxis("LEFT", x, y));
        }
        if (rightStick != null) {
            rightStick.setOnJoystickMoveListener((x, y) -> sendGamepadAxis("RIGHT", x, y));
        }

        // Profile switcher button (FPS / Racing / Retro)
        if (gamepadProfileBtn != null) {
            gamepadProfileBtn.setOnClickListener(v -> {
                currentProfileIndex = (currentProfileIndex + 1) % profileNames.length;
                gamepadProfileBtn.setText(profileNames[currentProfileIndex]);
                sendGamepadProfile(profileCodes[currentProfileIndex]);
                triggerHaptic();
                Toast.makeText(this, "Profile: " + profileNames[currentProfileIndex].replace(" ▾", ""), Toast.LENGTH_SHORT).show();
            });
        }

        // Back to Touchpad / Connect button
        if (gamepadBackBtn != null) {
            gamepadBackBtn.setOnClickListener(v -> {
                triggerHaptic();
                switchTab(authenticated ? Tab.TOUCHPAD : Tab.CONNECT);
            });
        }

        // Screen orientation toggle (Landscape / Portrait)
        if (gamepadRotateBtn != null) {
            gamepadRotateBtn.setOnClickListener(v -> {
                isLandscape = !isLandscape;
                setRequestedOrientation(isLandscape
                        ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        : ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                gamepadRotateBtn.setText(isLandscape ? "⛶ Rotate" : "⛶ Landscape");
                triggerHaptic();
            });
        }
    }

    private void updateDiscoveredPcCards() {
        runOnUiThread(() -> {
            if (discoveredPcList == null) return;
            discoveredPcList.removeAllViews();
            LayoutInflater inflater = LayoutInflater.from(this);

            if (discoveredServers.isEmpty()) {
                scanStatusText.setText("No PC receiver found on your Wi-Fi.\nEnsure CTRLX is open on your PC.");
                scanStatusText.setTextColor(Color.parseColor("#94A3B8"));
                return;
            }

            int count = discoveredServers.size();
            scanStatusText.setText(count + (count == 1 ? " PC receiver found on your Wi-Fi" : " PC receivers found on your Wi-Fi"));
            scanStatusText.setTextColor(Color.parseColor("#4ADE80"));

            for (Map.Entry<String, String> entry : discoveredServers.entrySet()) {
                String ip = entry.getKey();
                String name = entry.getValue();

                View card = inflater.inflate(R.layout.item_pc_card, discoveredPcList, false);
                TextView cardPcName = card.findViewById(R.id.cardPcName);
                TextView cardPcStatus = card.findViewById(R.id.cardPcStatus);
                TextView cardConnectBtn = card.findViewById(R.id.cardConnectBtn);

                cardPcName.setText(name);

                boolean isThisConnected = connected && authenticated && ip.equals(targetPcIp);
                boolean isThisConnecting = connecting && ip.equals(targetPcIp);

                if (isThisConnected) {
                    cardPcStatus.setText(ip + " • Connected (Active)");
                    cardPcStatus.setTextColor(Color.parseColor("#34D399"));
                    cardConnectBtn.setText("OPEN ›");
                    cardConnectBtn.setTextColor(Color.parseColor("#34D399"));
                    card.setOnClickListener(v -> switchTab(Tab.TOUCHPAD));
                } else if (isThisConnecting) {
                    cardPcStatus.setText(ip + " • Connecting & Pairing...");
                    cardPcStatus.setTextColor(Color.parseColor("#F59E0B"));
                    cardConnectBtn.setText("PAIRING");
                    cardConnectBtn.setTextColor(Color.parseColor("#F59E0B"));
                    card.setOnClickListener(null);
                } else {
                    cardPcStatus.setText(ip + " • Tap to Enter PIN & Connect");
                    cardPcStatus.setTextColor(Color.parseColor("#94A3B8"));
                    cardConnectBtn.setText("CONNECT ›");
                    cardConnectBtn.setTextColor(Color.parseColor("#38BDF8"));
                    card.setOnClickListener(v -> {
                        if (connected || connecting) {
                            disconnectFromPC();
                        }
                        connectToPC(ip, name);
                    });
                }

                discoveredPcList.addView(card);
            }
        });
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
        if (connecting) {
            return;
        }

        // Immediately clear previous list so stale/offline PCs never linger
        discoveredServers.clear();
        updateDiscoveredPcCards();

        scanProgress.setVisibility(View.VISIBLE);
        scanStatusText.setText("Scanning local Wi-Fi for PCs...");
        scanStatusText.setTextColor(Color.parseColor("#38BDF8"));
        rescanButton.setEnabled(false);

        new Thread(() -> {
            WifiManager.MulticastLock lock = null;
            Map<String, String> foundServers = new LinkedHashMap<>();

            try {
                WifiManager wifi = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
                if (wifi != null) {
                    lock = wifi.createMulticastLock("CTRLX_Discovery");
                    lock.setReferenceCounted(true);
                    lock.acquire();
                }

                try (DatagramSocket udpSocket = new DatagramSocket()) {
                    udpSocket.setBroadcast(true);
                    udpSocket.setSoTimeout(300);

                    byte[] req = "{\"type\":\"CTRLX_DISCOVER\"}".getBytes(StandardCharsets.UTF_8);

                    // Send discovery packet to all subnet broadcast addresses
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

                    // Receive loop across all laptops on the network over a 1500ms window
                    long deadline = System.currentTimeMillis() + 1500;
                    byte[] buf = new byte[1024];

                    while (System.currentTimeMillis() < deadline) {
                        try {
                            DatagramPacket responsePacket = new DatagramPacket(buf, buf.length);
                            udpSocket.receive(responsePacket);

                            String respStr = new String(responsePacket.getData(), 0, responsePacket.getLength(), StandardCharsets.UTF_8);
                            if (respStr.contains("CTRLX_SERVER")) {
                                String foundIp = responsePacket.getAddress().getHostAddress();
                                String foundName = "Windows PC";
                                try {
                                    JSONObject json = new JSONObject(respStr);
                                    if (json.has("serverName")) {
                                        foundName = json.getString("serverName");
                                    }
                                } catch (Exception ignored) {
                                }
                                if (foundIp != null && !foundIp.isEmpty()) {
                                    foundServers.put(foundIp, foundName);
                                }
                            }
                        } catch (SocketTimeoutException ignored) {
                            // Timeout for this receive slice, continue until deadline
                        }
                    }

                    // Check if localhost (127.0.0.1) is forwarded over USB (via adb reverse)
                    try (Socket usbSocket = new Socket()) {
                        usbSocket.connect(new InetSocketAddress("127.0.0.1", SERVER_PORT), 250);
                        foundServers.put("127.0.0.1", "Windows PC (USB)");
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (lock != null && lock.isHeld()) {
                    try {
                        lock.release();
                    } catch (Exception ignored) {
                    }
                }
            }

            runOnUiThread(() -> {
                scanProgress.setVisibility(View.GONE);
                rescanButton.setEnabled(true);
                discoveredServers.clear();
                discoveredServers.putAll(foundServers);
                updateDiscoveredPcCards();
            });
        }).start();
    }

    void connectToPC(String host, String hostName) {
        if (connected || connecting) {
            return;
        }

        targetPcIp = host;
        targetPcName = hostName;
        voluntaryDisconnect = false;
        connecting = true;
        scanStatusText.setText("Connecting to " + hostName + "...");
        scanStatusText.setTextColor(Color.parseColor("#F59E0B"));
        updateDiscoveredPcCards();

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

                // Clear any cached token: user requested mandatory PIN verification on every connect & reconnect
                SecurityUtils.clearAuthToken(MainActivity.this);

                String deviceId = SecurityUtils.getDeviceId(MainActivity.this);
                String myDeviceName = Build.MODEL != null ? Build.MODEL : "Android Phone";

                runOnUiThread(() -> {
                    scanStatusText.setText("Requesting pairing PIN from " + hostName + "...");
                    scanStatusText.setTextColor(Color.parseColor("#F59E0B"));
                });

                JSONObject pairMsg = new JSONObject();
                pairMsg.put("type", "PAIR_REQUEST");
                pairMsg.put("deviceId", deviceId);
                pairMsg.put("deviceName", myDeviceName);
                writer.println(pairMsg.toString());

                String message;
                while ((message = reader.readLine()) != null) {
                    JSONObject json = new JSONObject(message);
                    String type = json.optString("type");

                    if ("PAIR_PIN_REQUIRED".equals(type)) {
                        runOnUiThread(() -> promptForPin(hostName));
                    } else if ("PAIR_SUCCESS".equals(type)) {
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
                        if ("INVALID_PIN".equals(code)) {
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
                                scanStatusText.setText("Pairing expired. Tap PC to retry.");
                                scanStatusText.setTextColor(Color.parseColor("#EF4444"));
                                updateDiscoveredPcCards();
                            });
                            break;
                        }
                    }
                }

                // If loop finished because readLine() returned null, server closed socket
                if (socket == newSocket && !voluntaryDisconnect) {
                    onConnectionLost("Connection closed by " + hostName);
                }

            } catch (Exception e) {
                if (!voluntaryDisconnect) {
                    onConnectionLost("Could not connect to " + hostName);
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
                    authenticated = false;
                    targetPcIp = null;
                    stopHeartbeat();
                }
                connecting = false;
            }
        }).start();
    }

    private void onConnectionSuccess(String host, String hostName) {
        scanStatusText.setText("Connected to " + hostName);
        scanStatusText.setTextColor(Color.parseColor("#34D399"));
        touchpadPcBadge.setText("● Connected: " + hostName);
        gamepadPcBadge.setText("🎮 " + hostName);
        updateDiscoveredPcCards();

        // Jump directly to Gamepad controller upon connection!
        switchTab(Tab.GAMEPAD);
    }

    private void promptForPin(String hostName) {
        if (isFinishing() || !connected) {
            return;
        }

        scanStatusText.setText("Enter PIN displayed on " + hostName);
        scanStatusText.setTextColor(Color.parseColor("#38BDF8"));

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
            voluntaryDisconnect = true;
            disconnectFromPC();
            switchTab(Tab.CONNECT);
            startLanScan();
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
                        if (out.checkError()) {
                            onConnectionLost("Connection to PC lost");
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        });
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
                        if (out.checkError()) {
                            onConnectionLost("Connection to PC lost");
                        }
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
                        if (out.checkError()) {
                            onConnectionLost("Connection to PC lost");
                        }
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
                        if (out.checkError()) {
                            onConnectionLost("Connection to PC lost");
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void disconnectFromPC() {
        voluntaryDisconnect = true;
        connecting = false;
        connected = false;
        authenticated = false;
        targetPcIp = null;
        stopHeartbeat();
        SecurityUtils.clearAuthToken(this);

        if (pinDialog != null && pinDialog.isShowing()) {
            try {
                pinDialog.dismiss();
            } catch (Exception ignored) {
            }
        }

        Socket current = socket;
        socket = null;
        out = null;

        if (current != null) {
            new Thread(() -> {
                try {
                    current.close();
                } catch (Exception ignored) {
                }
            }).start();
        }
        runOnUiThread(() -> {
            discoveredServers.clear();
            updateDiscoveredPcCards();
        });
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

    private void bindGamepadButton(Button btn, String buttonCode) {
        if (btn == null) return;
        btn.setOnTouchListener((v, event) -> {
            if (!authenticated) {
                return false;
            }
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                triggerHaptic();
                sendGamepadButton(buttonCode, "DOWN");
                v.setPressed(true);
                return true;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                sendGamepadButton(buttonCode, "UP");
                v.setPressed(false);
                return true;
            }
            return false;
        });
    }

    private void sendGamepadButton(String button, String state) {
        if (!authenticated || out == null) return;
        senderExecutor.submit(() -> {
            try {
                synchronized (sendLock) {
                    if (out != null) {
                        out.println("{\"type\":\"GAMEPAD_BUTTON\",\"button\":\"" + button + "\",\"state\":\"" + state + "\"}");
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void sendGamepadAxis(String stick, float x, float y) {
        if (!authenticated || out == null) return;
        senderExecutor.submit(() -> {
            try {
                synchronized (sendLock) {
                    if (out != null) {
                        out.println("{\"type\":\"GAMEPAD_AXIS\",\"stick\":\"" + stick + "\",\"x\":" + x + ",\"y\":" + y + "}");
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void sendGamepadProfile(String profile) {
        if (!authenticated || out == null) return;
        senderExecutor.submit(() -> {
            try {
                synchronized (sendLock) {
                    if (out != null) {
                        out.println("{\"type\":\"GAMEPAD_PROFILE\",\"profile\":\"" + profile + "\"}");
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    @Override
    public void onBackPressed() {
        if (currentTab == Tab.TOUCHPAD || currentTab == Tab.GAMEPAD) {
            switchTab(Tab.CONNECT);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        stopHeartbeat();
        disconnectFromPC();
        senderExecutor.shutdown();
        super.onDestroy();
    }
}
