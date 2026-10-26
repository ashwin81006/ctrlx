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
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    // Soft Keyboard, Media & Power Views
    private EditText hiddenKeyInput;
    private Button touchpadKeyboardBtn;
    private Button touchpadPowerBtn;
    private Button btnMediaPrev;
    private Button btnMediaPlayPause;
    private Button btnMediaNext;
    private Button btnMediaVolDown;
    private Button btnMediaVolUp;
    private Button btnMediaMute;
    private boolean isUpdatingKeyInput = false;

    // Gamepad Views
    private TextView gamepadPcBadge;
    private Button gamepadProfileBtn;
    private Button gamepadRotateBtn;
    private Button gamepadBackBtn;
    private Button gamepadEditBtn;
    private LinearLayout topButtonsBar;
    private LinearLayout editModeOverlay;
    private TextView editorTargetLabel;
    private TextView editorScaleText;
    private Button btnScaleDown;
    private Button btnScaleUp;
    private Button btnEditorReset;
    private Button btnEditorDone;

    private LinearLayout centerCluster;
    private RelativeLayout dpadCluster;
    private RelativeLayout abxyCluster;

    private JoystickView leftStick;
    private JoystickView rightStick;
    private Button btnLT, btnLB, btnRB, btnRT;
    private Button btnView, btnXboxGuide, btnMenu;
    private Button btnDpadUp, btnDpadLeft, btnDpadRight, btnDpadDown, btnL3;
    private Button btnY, btnX, btnB, btnA, btnR3;

    private boolean isLandscape = false;
    private boolean isLayoutEditMode = false;
    private View selectedControlGroup = null;
    private String selectedControlName = "";
    private float editorTouchStartX = 0f;
    private float editorTouchStartY = 0f;
    private float editorViewStartX = 0f;
    private float editorViewStartY = 0f;
    private static final String KEY_CUSTOM_LAYOUT = "key_custom_gamepad_layout_v2";

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

    // Coalescing state for ultra-low-latency mouse movement
    private final java.util.concurrent.atomic.AtomicInteger pendingDx = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicInteger pendingDy = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicBoolean mouseDrainScheduled = new java.util.concurrent.atomic.AtomicBoolean(false);

    // Coalescing state for ultra-low-latency gamepad sticks
    private volatile float latestLeftX = 0f;
    private volatile float latestLeftY = 0f;
    private final java.util.concurrent.atomic.AtomicBoolean leftStickDirty = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean leftStickDrainScheduled = new java.util.concurrent.atomic.AtomicBoolean(false);

    private volatile float latestRightX = 0f;
    private volatile float latestRightY = 0f;
    private final java.util.concurrent.atomic.AtomicBoolean rightStickDirty = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final java.util.concurrent.atomic.AtomicBoolean rightStickDrainScheduled = new java.util.concurrent.atomic.AtomicBoolean(false);

    // Touchpad gesture tracking
    private float lastX, lastY;
    private float downX, downY;
    private long downTime;
    private boolean isTwoFingerGesture;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        hideSystemUI();

        bindViews();
        setupNavigation();
        setupEventListeners();

        if (layoutGamepad != null) {
            layoutGamepad.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                int w = right - left;
                int h = bottom - top;
                int oldW = oldRight - oldLeft;
                int oldH = oldBottom - oldTop;
                if (w > 0 && h > 0 && (w != oldW || h != oldH) && !isLayoutEditMode && currentTab == Tab.GAMEPAD) {
                    applyLayoutConfiguration();
                }
            });
        }

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        // Open on Connect tab (scan and show available PCs, do NOT auto-connect)
        switchTab(Tab.CONNECT);
        startLanScan();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUI();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    private void hideSystemUI() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = getWindow().getInsetsController();
                if (controller != null) {
                    controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            }
            View decorView = getWindow().getDecorView();
            int flags = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN;
            decorView.setSystemUiVisibility(flags);
        } catch (Exception ignored) {
        }
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

        // Soft Keyboard, Media & Power Views
        hiddenKeyInput = findViewById(R.id.hiddenKeyInput);
        touchpadKeyboardBtn = findViewById(R.id.touchpadKeyboardBtn);
        touchpadPowerBtn = findViewById(R.id.touchpadPowerBtn);
        btnMediaPrev = findViewById(R.id.btnMediaPrev);
        btnMediaPlayPause = findViewById(R.id.btnMediaPlayPause);
        btnMediaNext = findViewById(R.id.btnMediaNext);
        btnMediaVolDown = findViewById(R.id.btnMediaVolDown);
        btnMediaVolUp = findViewById(R.id.btnMediaVolUp);
        btnMediaMute = findViewById(R.id.btnMediaMute);

        // Gamepad Views
        gamepadPcBadge = findViewById(R.id.gamepadPcBadge);
        gamepadProfileBtn = findViewById(R.id.gamepadProfileBtn);
        gamepadRotateBtn = findViewById(R.id.gamepadRotateBtn);
        gamepadBackBtn = findViewById(R.id.gamepadBackBtn);
        leftStick = findViewById(R.id.leftStick);
        rightStick = findViewById(R.id.rightStick);
        if (leftStick != null)
            leftStick.setLabel("LS");
        if (rightStick != null)
            rightStick.setLabel("RS");

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

        // Gamepad Clusters & Layout Editor Views
        topButtonsBar = findViewById(R.id.topButtonsBar);
        gamepadEditBtn = findViewById(R.id.gamepadEditBtn);
        editModeOverlay = findViewById(R.id.editModeOverlay);
        editorTargetLabel = findViewById(R.id.editorTargetLabel);
        editorScaleText = findViewById(R.id.editorScaleText);
        btnScaleDown = findViewById(R.id.btnScaleDown);
        btnScaleUp = findViewById(R.id.btnScaleUp);
        btnEditorReset = findViewById(R.id.btnEditorReset);
        btnEditorDone = findViewById(R.id.btnEditorDone);

        centerCluster = findViewById(R.id.centerCluster);
        dpadCluster = findViewById(R.id.dpadCluster);
        abxyCluster = findViewById(R.id.abxyCluster);
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
        if (authenticated && out != null) {
            String modeStr = (tab == Tab.GAMEPAD) ? "GAMEPAD" : (tab == Tab.TOUCHPAD ? "TOUCHPAD" : "CONNECT");
            sendModeChange(modeStr);
        }
        runOnUiThread(() -> {
            boolean isGamepad = (tab == Tab.GAMEPAD);

            layoutConnect.setVisibility(tab == Tab.CONNECT ? View.VISIBLE : View.GONE);
            layoutTouchpad.setVisibility(tab == Tab.TOUCHPAD ? View.VISIBLE : View.GONE);
            layoutGamepad.setVisibility(isGamepad ? View.VISIBLE : View.GONE);

            // Hide bottom nav bar in horizontal gamepad mode for full-screen console
            // immersion
            if (bottomNavBar != null) {
                bottomNavBar.setVisibility(isGamepad ? View.GONE : View.VISIBLE);
            }

            // Update bottom nav bar highlight
            navLabelConnect
                    .setTextColor(tab == Tab.CONNECT ? Color.parseColor("#38BDF8") : Color.parseColor("#64748B"));
            navLabelTouchpad
                    .setTextColor(tab == Tab.TOUCHPAD ? Color.parseColor("#38BDF8") : Color.parseColor("#64748B"));
            navLabelGamepad.setTextColor(isGamepad ? Color.parseColor("#38BDF8") : Color.parseColor("#64748B"));

            if (isGamepad) {
                isLandscape = true;
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                if (targetPcName != null) {
                    gamepadPcBadge.setText("🎮 " + targetPcName);
                } else {
                    gamepadPcBadge.setText("🎮 XBOX GAMEPAD");
                }
                layoutGamepad.post(this::applyLayoutConfiguration);
            } else {
                if (isLayoutEditMode) {
                    saveAndExitLayoutEditMode();
                }
                isLandscape = false;
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            }
            hideSystemUI();
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

        // Soft Keyboard setup
        if (hiddenKeyInput != null) {
            isUpdatingKeyInput = true;
            hiddenKeyInput.setText(" ");
            hiddenKeyInput.setSelection(1);
            isUpdatingKeyInput = false;

            hiddenKeyInput.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    if (isUpdatingKeyInput) return;
                    if (count == 0 && before > 0) {
                        triggerHaptic();
                        sendKeyPress("BACKSPACE");
                    } else if (count > 0) {
                        CharSequence added = s.subSequence(start, start + count);
                        String text = added.toString();
                        if ("\n".equals(text)) {
                            triggerHaptic();
                            sendKeyPress("ENTER");
                        } else if (!text.isEmpty()) {
                            sendKeyText(text);
                        }
                    }
                }

                @Override
                public void afterTextChanged(Editable s) {
                    if (isUpdatingKeyInput) return;
                    isUpdatingKeyInput = true;
                    hiddenKeyInput.setText(" ");
                    hiddenKeyInput.setSelection(1);
                    isUpdatingKeyInput = false;
                }
            });

            hiddenKeyInput.setOnKeyListener((v, keyCode, event) -> {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (keyCode == KeyEvent.KEYCODE_DEL) {
                        triggerHaptic();
                        sendKeyPress("BACKSPACE");
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_ENTER) {
                        triggerHaptic();
                        sendKeyPress("ENTER");
                        return true;
                    }
                }
                return false;
            });

            hiddenKeyInput.setOnEditorActionListener((v, actionId, event) -> {
                triggerHaptic();
                sendKeyPress("ENTER");
                return true;
            });
        }

        if (touchpadKeyboardBtn != null) {
            touchpadKeyboardBtn.setOnClickListener(v -> {
                triggerHaptic();
                toggleSoftKeyboard();
            });
        }

        if (touchpadPowerBtn != null) {
            touchpadPowerBtn.setOnClickListener(v -> {
                triggerHaptic();
                showPowerActionsDialog();
            });
        }

        // Media Controls listeners
        if (btnMediaPrev != null) {
            btnMediaPrev.setOnClickListener(v -> sendMediaCommand("PREV_TRACK"));
        }
        if (btnMediaPlayPause != null) {
            btnMediaPlayPause.setOnClickListener(v -> sendMediaCommand("PLAY_PAUSE"));
        }
        if (btnMediaNext != null) {
            btnMediaNext.setOnClickListener(v -> sendMediaCommand("NEXT_TRACK"));
        }
        if (btnMediaVolDown != null) {
            btnMediaVolDown.setOnClickListener(v -> sendMediaCommand("VOL_DOWN"));
        }
        if (btnMediaVolUp != null) {
            btnMediaVolUp.setOnClickListener(v -> sendMediaCommand("VOL_UP"));
        }
        if (btnMediaMute != null) {
            btnMediaMute.setOnClickListener(v -> sendMediaCommand("MUTE"));
        }

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


        // Back to Touchpad / Connect button
        if (gamepadBackBtn != null) {
            gamepadBackBtn.setOnClickListener(v -> {
                triggerHaptic();
                switchTab(authenticated ? Tab.TOUCHPAD : Tab.CONNECT);
            });
        }

        // Layout Editor Listeners & Draggable Groups
        setupDraggableGroup(leftStick, "Left Stick (LS)");
        setupDraggableGroup(dpadCluster, "D-Pad");
        setupDraggableGroup(centerCluster, "Center Buttons");
        setupDraggableGroup(rightStick, "Right Stick (RS)");
        setupDraggableGroup(abxyCluster, "ABXY Buttons");

        if (gamepadEditBtn != null) {
            gamepadEditBtn.setOnClickListener(v -> {
                triggerHaptic();
                toggleLayoutEditMode();
            });
        }

        if (btnEditorDone != null) {
            btnEditorDone.setOnClickListener(v -> {
                triggerHaptic();
                saveAndExitLayoutEditMode();
            });
        }

        if (btnEditorReset != null) {
            btnEditorReset.setOnClickListener(v -> {
                triggerHaptic();
                resetLayoutToDefault();
            });
        }

        if (btnScaleDown != null) {
            btnScaleDown.setOnClickListener(v -> {
                triggerHaptic();
                adjustSelectedControlScale(-0.05f);
            });
        }

        if (btnScaleUp != null) {
            btnScaleUp.setOnClickListener(v -> {
                triggerHaptic();
                adjustSelectedControlScale(0.05f);
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
            if (discoveredPcList == null)
                return;
            discoveredPcList.removeAllViews();
            LayoutInflater inflater = LayoutInflater.from(this);

            if (discoveredServers.isEmpty()) {
                scanStatusText.setText("No PC found. Open CTRLX on your PC.");
                scanStatusText.setTextColor(Color.parseColor("#94A3B8"));
                return;
            }

            scanStatusText.setText("Ready to connect. Tap your PC.");
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
                    cardPcStatus.setText("Connected • Choose Touchpad or Gamepad below");
                    cardPcStatus.setTextColor(Color.parseColor("#34D399"));
                    cardConnectBtn.setText("DISCONNECT");
                    cardConnectBtn.setTextColor(Color.parseColor("#EF4444"));
                    card.setOnClickListener(v -> disconnectFromPC());
                } else if (isThisConnecting) {
                    cardPcStatus.setText("Connecting...");
                    cardPcStatus.setTextColor(Color.parseColor("#F59E0B"));
                    cardConnectBtn.setText("CONNECTING");
                    cardConnectBtn.setTextColor(Color.parseColor("#F59E0B"));
                    card.setOnClickListener(null);
                } else {
                    cardPcStatus.setText("Ready to connect");
                    cardPcStatus.setTextColor(Color.parseColor("#94A3B8"));
                    cardConnectBtn.setText("CONNECT");
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

        scanProgress.setVisibility(View.VISIBLE);
        scanStatusText.setText("Searching for your PC...");
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

                // Gather discovery targets: Subnet broadcasts + Global broadcast + Hotspot Host + Gateway + Last Known + mDNS
                List<InetAddress> targets = new ArrayList<>(getBroadcastAddresses());
                try {
                    targets.add(InetAddress.getByName("255.255.255.255"));
                } catch (Exception ignored) {
                }
                try {
                    targets.add(InetAddress.getByName("192.168.137.1"));
                } catch (Exception ignored) {
                }
                try {
                    targets.add(InetAddress.getByName("192.168.43.1"));
                } catch (Exception ignored) {
                }

                String gwIp = null;
                if (wifi != null) {
                    try {
                        android.net.DhcpInfo dhcp = wifi.getDhcpInfo();
                        if (dhcp != null && dhcp.gateway != 0) {
                            int gw = dhcp.gateway;
                            gwIp = String.format(java.util.Locale.US, "%d.%d.%d.%d",
                                    (gw & 0xFF), ((gw >> 8) & 0xFF), ((gw >> 16) & 0xFF), ((gw >> 24) & 0xFF));
                            targets.add(InetAddress.getByName(gwIp));
                        }
                    } catch (Exception ignored) {
                    }
                }

                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
                String lastIp = prefs.getString(KEY_LAST_IP, null);
                if (lastIp != null && !lastIp.isEmpty()) {
                    try {
                        targets.add(InetAddress.getByName(lastIp));
                    } catch (Exception ignored) {
                    }
                }

                // Resolve mDNS hostnames (resolves PC's IPv4 & IPv6 addresses on local Wi-Fi/Hotspot)
                String lastPcName = prefs.getString(KEY_LAST_NAME, null);
                Set<String> mDnsQueries = new LinkedHashSet<>();
                if (lastPcName != null && !lastPcName.isEmpty()) {
                    mDnsQueries.add(lastPcName + ".local");
                }
                mDnsQueries.add("Ashy-Victus.local");
                mDnsQueries.add("windows-pc.local");

                for (String hostQuery : mDnsQueries) {
                    try {
                        InetAddress[] resolved = InetAddress.getAllByName(hostQuery);
                        for (InetAddress ra : resolved) {
                            targets.add(ra);
                        }
                    } catch (Exception ignored) {
                    }
                }
                try {
                    targets.add(InetAddress.getByName("127.0.0.1"));
                } catch (Exception ignored) {
                }

                byte[] req = "{\"type\":\"CTRLX_DISCOVER\"}".getBytes(StandardCharsets.UTF_8);

                try (DatagramSocket udpSocket = new DatagramSocket()) {
                    udpSocket.setBroadcast(true);
                    udpSocket.setSoTimeout(400);

                    // Wave 1: discovery ping
                    for (InetAddress target : targets) {
                        try {
                            DatagramPacket packet = new DatagramPacket(req, req.length, target, DISCOVERY_PORT);
                            udpSocket.send(packet);
                        } catch (Exception ignored) {
                        }
                    }

                    // Receive loop across 2500ms window
                    long deadline = System.currentTimeMillis() + 2500;
                    boolean wave2Sent = false;
                    byte[] buf = new byte[1024];

                    while (System.currentTimeMillis() < deadline) {
                        if (!wave2Sent && System.currentTimeMillis() > (deadline - 1500)) {
                            wave2Sent = true;
                            for (InetAddress target : targets) {
                                try {
                                    udpSocket.send(new DatagramPacket(req, req.length, target, DISCOVERY_PORT));
                                } catch (Exception ignored) {
                                }
                            }
                        }

                        try {
                            DatagramPacket responsePacket = new DatagramPacket(buf, buf.length);
                            udpSocket.receive(responsePacket);

                            String respStr = new String(responsePacket.getData(), 0, responsePacket.getLength(),
                                    StandardCharsets.UTF_8);
                            if (respStr.contains("CTRLX_SERVER")) {
                                String foundIp = responsePacket.getAddress().getHostAddress();
                                if (foundIp != null && foundIp.contains("%")) {
                                    foundIp = foundIp.substring(0, foundIp.indexOf("%"));
                                }
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
                                    final String fIp = foundIp;
                                    final String fName = foundName;
                                    runOnUiThread(() -> {
                                        discoveredServers.put(fIp, fName);
                                        updateDiscoveredPcCards();
                                    });
                                }
                            }
                        } catch (SocketTimeoutException ignored) {
                        }
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

                // Clear any cached token: user requested mandatory PIN verification on every
                // connect & reconnect
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
                            Toast.makeText(MainActivity.this, "Paired successfully with " + hostName,
                                    Toast.LENGTH_SHORT).show();
                            onConnectionSuccess(host, hostName);
                        });
                    } else if ("GAMEPAD_ASSIGNED".equals(type)) {
                        int player = json.optInt("player", 1);
                        runOnUiThread(() -> {
                            if (btnXboxGuide != null) {
                                btnXboxGuide.setText("P" + player);
                            }
                            Toast.makeText(MainActivity.this, "🎮 Connected as Player " + player, Toast.LENGTH_SHORT).show();
                        });
                    } else if ("ERROR".equals(type)) {
                        String code = json.optString("code");
                        if ("INVALID_PIN".equals(code)) {
                            runOnUiThread(() -> {
                                Toast.makeText(MainActivity.this, "Incorrect PIN. Please re-enter.", Toast.LENGTH_LONG)
                                        .show();
                                promptForPin(hostName);
                            });
                        } else if ("TOO_MANY_ATTEMPTS".equals(code) || "PIN_EXPIRED".equals(code)) {
                            runOnUiThread(() -> {
                                if (pinDialog != null && pinDialog.isShowing()) {
                                    pinDialog.dismiss();
                                }
                                Toast.makeText(MainActivity.this, "Pairing expired. Tap to retry.", Toast.LENGTH_LONG)
                                        .show();
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
        targetPcIp = host;
        scanStatusText.setText("Connected to " + hostName + "!\nSelect Touchpad or Gamepad below.");
        scanStatusText.setTextColor(Color.parseColor("#34D399"));
        touchpadPcBadge.setText("● Connected: " + hostName);
        gamepadPcBadge.setText("🎮 " + hostName);
        updateDiscoveredPcCards();

        // Keep user on the Connect hub page - user selects Touchpad or Gamepad below
        switchTab(Tab.CONNECT);
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
        pendingDx.addAndGet(dx);
        pendingDy.addAndGet(dy);
        if (mouseDrainScheduled.compareAndSet(false, true)) {
            senderExecutor.submit(this::drainMouseMove);
        }
    }

    private void drainMouseMove() {
        try {
            while (true) {
                int dx = pendingDx.getAndSet(0);
                int dy = pendingDy.getAndSet(0);
                if (dx != 0 || dy != 0) {
                    synchronized (sendLock) {
                        if (out != null) {
                            out.println("{\"type\":\"MOUSE_MOVE\",\"dx\":" + dx + ",\"dy\":" + dy + "}");
                        }
                    }
                }
                if (pendingDx.get() == 0 && pendingDy.get() == 0) {
                    mouseDrainScheduled.set(false);
                    if (pendingDx.get() != 0 || pendingDy.get() != 0) {
                        if (mouseDrainScheduled.compareAndSet(false, true)) {
                            continue;
                        }
                    }
                    break;
                }
            }
        } catch (Exception ignored) {
            mouseDrainScheduled.set(false);
        }
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

    private void toggleSoftKeyboard() {
        if (hiddenKeyInput == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            hiddenKeyInput.requestFocus();
            imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
        }
    }

    private void sendKeyText(String text) {
        if (!authenticated || out == null || text == null || text.isEmpty()) {
            return;
        }
        senderExecutor.submit(() -> {
            try {
                JSONObject obj = new JSONObject();
                obj.put("type", "KEY_TEXT");
                obj.put("text", text);
                synchronized (sendLock) {
                    if (out != null) {
                        out.println(obj.toString());
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void sendKeyPress(String key) {
        if (!authenticated || out == null || key == null || key.isEmpty()) {
            return;
        }
        senderExecutor.submit(() -> {
            try {
                JSONObject obj = new JSONObject();
                obj.put("type", "KEY_PRESS");
                obj.put("key", key);
                synchronized (sendLock) {
                    if (out != null) {
                        out.println(obj.toString());
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void sendMediaCommand(String command) {
        if (!authenticated || out == null || command == null) {
            return;
        }
        triggerHaptic();
        senderExecutor.submit(() -> {
            try {
                JSONObject obj = new JSONObject();
                obj.put("type", "MEDIA_COMMAND");
                obj.put("command", command);
                synchronized (sendLock) {
                    if (out != null) {
                        out.println(obj.toString());
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void sendPowerCommand(String action) {
        if (!authenticated || out == null || action == null) {
            return;
        }
        triggerHaptic();
        senderExecutor.submit(() -> {
            try {
                JSONObject obj = new JSONObject();
                obj.put("type", "POWER_COMMAND");
                obj.put("action", action);
                synchronized (sendLock) {
                    if (out != null) {
                        out.println(obj.toString());
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void showPowerActionsDialog() {
        String[] options = new String[] {
            "🔒  Lock Workstation",
            "🌙  Sleep (Low Power)",
            "🔄  Restart Computer...",
            "⏻  Shut Down Computer..."
        };

        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        builder.setTitle("⚡ PC Power Management");
        builder.setItems(options, (dialog, which) -> {
            switch (which) {
                case 0:
                    sendPowerCommand("LOCK");
                    Toast.makeText(this, "PC Workstation Locked", Toast.LENGTH_SHORT).show();
                    break;
                case 1:
                    sendPowerCommand("SLEEP");
                    Toast.makeText(this, "PC Put to Sleep", Toast.LENGTH_SHORT).show();
                    break;
                case 2:
                    confirmDestructivePowerAction("Restart", "RESTART");
                    break;
                case 3:
                    confirmDestructivePowerAction("Shut Down", "SHUTDOWN");
                    break;
            }
        });
        builder.setNegativeButton("Cancel", null);
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void confirmDestructivePowerAction(String actionName, String command) {
        String pcName = (targetPcName != null && !targetPcName.isEmpty()) ? targetPcName : "your PC";
        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        builder.setTitle(actionName + " Computer?");
        builder.setMessage("Are you sure you want to " + actionName.toLowerCase() + " " + pcName + "? Any unsaved work on your computer may be lost.");
        builder.setPositiveButton(actionName, (dialog, which) -> {
            sendPowerCommand(command);
            Toast.makeText(this, "Sent " + actionName + " command to " + pcName, Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("Cancel", null);
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private synchronized void stopHeartbeat() {
        if (heartbeatScheduler != null) {
            heartbeatScheduler.shutdownNow();
            heartbeatScheduler = null;
        }
    }

    private void onConnectionLost(String reason) {
        if (voluntaryDisconnect) {
            return;
        }

        voluntaryDisconnect = true;
        connected = false;
        connecting = false;
        authenticated = false;
        targetPcIp = null;

        stopHeartbeat();
        SecurityUtils.clearAuthToken(this);

        Socket current = socket;
        socket = null;
        out = null;

        if (current != null) {
            try {
                current.close();
            } catch (Exception ignored) {
            }
        }

        runOnUiThread(() -> {
            if (pinDialog != null && pinDialog.isShowing()) {
                pinDialog.dismiss();
            }

            scanStatusText.setText(reason);
            scanStatusText.setTextColor(Color.parseColor("#EF4444"));

            switchTab(Tab.CONNECT);
            updateDiscoveredPcCards();
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
        if (btn == null)
            return;
        btn.setOnTouchListener((v, event) -> {
            if (isLayoutEditMode) {
                View parent = (View) v.getParent();
                while (parent != null && parent != dpadCluster && parent != abxyCluster && parent != centerCluster) {
                    if (parent.getParent() instanceof View) {
                        parent = (View) parent.getParent();
                    } else {
                        break;
                    }
                }
                if (parent != null) {
                    String name = "Controls";
                    if (parent == dpadCluster) name = "D-Pad";
                    else if (parent == abxyCluster) name = "ABXY Buttons";
                    else if (parent == centerCluster) name = "Center Buttons";
                    return handleEditorGroupTouch(parent, name, event);
                }
                return true;
            }
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

    private void sendModeChange(String mode) {
        if (!authenticated || out == null)
            return;
        senderExecutor.submit(() -> {
            try {
                synchronized (sendLock) {
                    if (out != null) {
                        out.println("{\"type\":\"MODE_CHANGE\",\"mode\":\"" + mode + "\"}");
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void sendGamepadButton(String button, String state) {
        if (!authenticated || out == null)
            return;
        senderExecutor.submit(() -> {
            try {
                synchronized (sendLock) {
                    if (out != null) {
                        out.println("{\"type\":\"GAMEPAD_BUTTON\",\"button\":\"" + button + "\",\"state\":\"" + state
                                + "\"}");
                    }
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void sendGamepadAxis(String stick, float x, float y) {
        if (!authenticated || out == null)
            return;
        if ("LEFT".equalsIgnoreCase(stick)) {
            latestLeftX = x;
            latestLeftY = y;
            leftStickDirty.set(true);
            if (leftStickDrainScheduled.compareAndSet(false, true)) {
                senderExecutor.submit(this::drainLeftStick);
            }
        } else if ("RIGHT".equalsIgnoreCase(stick)) {
            latestRightX = x;
            latestRightY = y;
            rightStickDirty.set(true);
            if (rightStickDrainScheduled.compareAndSet(false, true)) {
                senderExecutor.submit(this::drainRightStick);
            }
        }
    }

    private void drainLeftStick() {
        try {
            while (leftStickDirty.getAndSet(false)) {
                float x = latestLeftX;
                float y = latestLeftY;
                synchronized (sendLock) {
                    if (out != null) {
                        out.println("{\"type\":\"GAMEPAD_AXIS\",\"stick\":\"LEFT\",\"x\":" + x + ",\"y\":" + y + "}");
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            leftStickDrainScheduled.set(false);
            if (leftStickDirty.get() && leftStickDrainScheduled.compareAndSet(false, true)) {
                senderExecutor.submit(this::drainLeftStick);
            }
        }
    }

    private void drainRightStick() {
        try {
            while (rightStickDirty.getAndSet(false)) {
                float x = latestRightX;
                float y = latestRightY;
                synchronized (sendLock) {
                    if (out != null) {
                        out.println("{\"type\":\"GAMEPAD_AXIS\",\"stick\":\"RIGHT\",\"x\":" + x + ",\"y\":" + y + "}");
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            rightStickDrainScheduled.set(false);
            if (rightStickDirty.get() && rightStickDrainScheduled.compareAndSet(false, true)) {
                senderExecutor.submit(this::drainRightStick);
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (isLayoutEditMode) {
            saveAndExitLayoutEditMode();
            return;
        }
        if (currentTab == Tab.TOUCHPAD || currentTab == Tab.GAMEPAD) {
            switchTab(Tab.CONNECT);
        } else {
            super.onBackPressed();
        }
    }

    // ==============================================================
    // GAMEPAD DYNAMIC RESPONSIVE LAYOUT & INTERACTIVE LAYOUT EDITOR
    // ==============================================================

    private void setupDraggableGroup(View group, String name) {
        if (group == null) return;
        group.setOnTouchListener((v, event) -> {
            if (!isLayoutEditMode) {
                return false;
            }
            return handleEditorGroupTouch(v, name, event);
        });
    }

    private boolean handleEditorGroupTouch(View group, String name, MotionEvent event) {
        if (!isLayoutEditMode || group == null) {
            return false;
        }
        int action = event.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                selectControlGroup(group, name);
                editorTouchStartX = event.getRawX();
                editorTouchStartY = event.getRawY();
                editorViewStartX = group.getX();
                editorViewStartY = group.getY();
                triggerHaptic();
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - editorTouchStartX;
                float dy = event.getRawY() - editorTouchStartY;
                float newX = editorViewStartX + dx;
                float newY = editorViewStartY + dy;

                if (layoutGamepad != null) {
                    int parentW = layoutGamepad.getWidth();
                    int parentH = layoutGamepad.getHeight();
                    float viewW = group.getWidth() * group.getScaleX();
                    float viewH = group.getHeight() * group.getScaleY();
                    int topLimit = dpToPx(48);

                    newX = Math.max(0, Math.min(parentW - viewW, newX));
                    newY = Math.max(topLimit, Math.min(parentH - viewH, newY));
                }

                group.setX(newX);
                group.setY(newY);
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                return true;
        }
        return false;
    }

    private void selectControlGroup(View group, String name) {
        if (selectedControlGroup != null && selectedControlGroup != group) {
            selectedControlGroup.setBackground(getDrawable(R.drawable.bg_editor_unselected));
        }
        selectedControlGroup = group;
        selectedControlName = name;
        if (group != null) {
            group.setBackground(getDrawable(R.drawable.bg_editor_selected));
            int scalePct = Math.round(group.getScaleX() * 100);
            if (editorTargetLabel != null) {
                editorTargetLabel.setText("Selected: " + name);
            }
            if (editorScaleText != null) {
                editorScaleText.setText(scalePct + "%");
            }
        } else {
            if (editorTargetLabel != null) {
                editorTargetLabel.setText("Tap a control to select • Drag to move");
            }
            if (editorScaleText != null) {
                editorScaleText.setText("100%");
            }
        }
    }

    private void adjustSelectedControlScale(float delta) {
        if (selectedControlGroup == null) {
            Toast.makeText(this, "Tap a control first to select and resize", Toast.LENGTH_SHORT).show();
            return;
        }
        float current = selectedControlGroup.getScaleX();
        float next = Math.max(0.60f, Math.min(1.50f, current + delta));
        selectedControlGroup.setPivotX(0f);
        selectedControlGroup.setPivotY(0f);
        selectedControlGroup.setScaleX(next);
        selectedControlGroup.setScaleY(next);
        if (editorScaleText != null) {
            editorScaleText.setText(Math.round(next * 100) + "%");
        }
    }

    private void toggleLayoutEditMode() {
        if (isLayoutEditMode) {
            saveAndExitLayoutEditMode();
        } else {
            enterLayoutEditMode();
        }
    }

    private void enterLayoutEditMode() {
        isLayoutEditMode = true;
        if (leftStick != null) leftStick.setEditable(true);
        if (rightStick != null) rightStick.setEditable(true);

        if (editModeOverlay != null) {
            editModeOverlay.setVisibility(View.VISIBLE);
        }
        if (topButtonsBar != null) {
            topButtonsBar.setVisibility(View.INVISIBLE);
        }

        View[] groups = { leftStick, dpadCluster, centerCluster, rightStick, abxyCluster };
        for (View g : groups) {
            if (g != null) {
                g.setBackground(getDrawable(R.drawable.bg_editor_unselected));
            }
        }
        selectControlGroup(leftStick, "Left Stick (LS)");
        Toast.makeText(this, "Edit Mode: Drag any control to reposition • Tap + / − to resize", Toast.LENGTH_LONG).show();
    }

    private void saveAndExitLayoutEditMode() {
        isLayoutEditMode = false;
        if (leftStick != null) leftStick.setEditable(false);
        if (rightStick != null) rightStick.setEditable(false);

        if (editModeOverlay != null) {
            editModeOverlay.setVisibility(View.GONE);
        }
        if (topButtonsBar != null) {
            topButtonsBar.setVisibility(View.VISIBLE);
        }

        View[] groups = { leftStick, dpadCluster, centerCluster, rightStick, abxyCluster };
        for (View g : groups) {
            if (g != null) {
                g.setBackground(null);
            }
        }
        selectedControlGroup = null;

        saveCurrentLayoutToPreferences();
        Toast.makeText(this, "Gamepad layout saved!", Toast.LENGTH_SHORT).show();
    }

    private void resetLayoutToDefault() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_CUSTOM_LAYOUT)
                .apply();

        int w = layoutGamepad.getWidth();
        int h = layoutGamepad.getHeight();
        if (w <= 0 || h <= 0) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            w = Math.max(dm.widthPixels, dm.heightPixels);
            h = Math.min(dm.widthPixels, dm.heightPixels);
        }
        applyResponsiveLayout(w, h);

        if (selectedControlGroup != null) {
            int scalePct = Math.round(selectedControlGroup.getScaleX() * 100);
            if (editorScaleText != null) {
                editorScaleText.setText(scalePct + "%");
            }
        }
        Toast.makeText(this, "Reset to default responsive layout", Toast.LENGTH_SHORT).show();
    }

    private void saveCurrentLayoutToPreferences() {
        if (layoutGamepad == null) return;
        int pw = layoutGamepad.getWidth();
        int ph = layoutGamepad.getHeight();
        if (pw <= 0 || ph <= 0) return;

        try {
            JSONObject root = new JSONObject();
            putViewGroupJson(root, "leftStick", leftStick, pw, ph);
            putViewGroupJson(root, "dpadCluster", dpadCluster, pw, ph);
            putViewGroupJson(root, "centerCluster", centerCluster, pw, ph);
            putViewGroupJson(root, "rightStick", rightStick, pw, ph);
            putViewGroupJson(root, "abxyCluster", abxyCluster, pw, ph);

            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_CUSTOM_LAYOUT, root.toString())
                    .apply();
        } catch (Exception ignored) {
        }
    }

    private void putViewGroupJson(JSONObject root, String key, View v, int pw, int ph) throws Exception {
        if (v == null) return;
        JSONObject obj = new JSONObject();
        obj.put("xPct", (double) (v.getX() / (float) pw));
        obj.put("yPct", (double) (v.getY() / (float) ph));
        obj.put("scale", (double) v.getScaleX());
        root.put(key, obj);
    }

    private void applyLayoutConfiguration() {
        if (layoutGamepad == null) return;
        int w = layoutGamepad.getWidth();
        int h = layoutGamepad.getHeight();
        if (w <= 0 || h <= 0) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            w = Math.max(dm.widthPixels, dm.heightPixels);
            h = Math.min(dm.widthPixels, dm.heightPixels);
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String customJson = prefs.getString(KEY_CUSTOM_LAYOUT, null);
        if (customJson != null && !customJson.trim().isEmpty()) {
            if (applyCustomLayout(customJson, w, h)) {
                return;
            }
        }
        applyResponsiveLayout(w, h);
    }

    private boolean applyCustomLayout(String jsonStr, int pw, int ph) {
        try {
            JSONObject root = new JSONObject(jsonStr);
            applyViewGroupJson(root, "leftStick", leftStick, pw, ph);
            applyViewGroupJson(root, "dpadCluster", dpadCluster, pw, ph);
            applyViewGroupJson(root, "centerCluster", centerCluster, pw, ph);
            applyViewGroupJson(root, "rightStick", rightStick, pw, ph);
            applyViewGroupJson(root, "abxyCluster", abxyCluster, pw, ph);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void applyViewGroupJson(JSONObject root, String key, View v, int pw, int ph) {
        if (v == null || !root.has(key)) return;
        try {
            JSONObject obj = root.getJSONObject(key);
            float x = (float) (obj.optDouble("xPct", 0.0) * pw);
            float y = (float) (obj.optDouble("yPct", 0.0) * ph);
            float scale = (float) obj.optDouble("scale", 1.0);

            v.setPivotX(0f);
            v.setPivotY(0f);
            v.setScaleX(scale);
            v.setScaleY(scale);
            v.setX(x);
            v.setY(y);
        } catch (Exception ignored) {
        }
    }

    private void applyResponsiveLayout(int screenW, int screenH) {
        if (layoutGamepad == null || screenW <= 0 || screenH <= 0) return;

        int topBarH = topButtonsBar != null && topButtonsBar.getHeight() > 0 
                ? topButtonsBar.getHeight() 
                : dpToPx(52);
        int availH = screenH - topBarH;

        // Baseline landscape reference: 340dp height, 760dp width
        float scaleH = (float) availH / dpToPx(320);
        float scaleW = (float) screenW / dpToPx(760);
        float baseScale = Math.min(scaleH, scaleW);
        float scale = Math.max(0.68f, Math.min(1.10f, baseScale));

        View[] groups = { leftStick, dpadCluster, centerCluster, rightStick, abxyCluster };
        for (View g : groups) {
            if (g != null) {
                g.setPivotX(0f);
                g.setPivotY(0f);
                g.setScaleX(scale);
                g.setScaleY(scale);
            }
        }

        // 1. Left Stick (Top-Left)
        if (leftStick != null) {
            float lsX = dpToPx(24) * scale;
            float lsY = topBarH + (dpToPx(10) * scale);
            leftStick.setX(lsX);
            leftStick.setY(lsY);
        }

        // 2. D-Pad (Bottom-Left, anchored safely above bottom edge to never clip)
        if (dpadCluster != null) {
            float dpadW = dpToPx(132) * scale;
            float dpadH = dpToPx(132) * scale;
            float dpadX = dpToPx(72) * scale;
            float dpadY = screenH - dpadH - (dpToPx(10) * scale);
            dpadCluster.setX(dpadX);
            dpadCluster.setY(dpadY);
        }

        // 3. ABXY Cluster (Bottom-Right, anchored safely)
        float abxyX = 0f;
        if (abxyCluster != null) {
            float abxyW = dpToPx(250) * scale;
            float abxyH = dpToPx(230) * scale;
            abxyX = screenW - abxyW - (dpToPx(14) * scale);
            float abxyY = screenH - abxyH - (dpToPx(12) * scale);
            abxyCluster.setX(abxyX);
            abxyCluster.setY(abxyY);
        }

        // 4. Right Stick (To the left of ABXY, anchored to bottom)
        if (rightStick != null) {
            float rsW = dpToPx(140) * scale;
            float rsH = dpToPx(140) * scale;
            float rsX = (abxyCluster != null ? abxyX : screenW - rsW) - rsW - (dpToPx(14) * scale);
            float rsY = screenH - rsH - (dpToPx(14) * scale);
            rightStick.setX(rsX);
            rightStick.setY(rsY);
        }

        // 5. Center Cluster (Horizontally centered, top aligned)
        if (centerCluster != null) {
            float ccW = dpToPx(150) * scale;
            float ccX = (screenW - ccW) / 2.0f;
            float ccY = topBarH + (dpToPx(12) * scale);
            centerCluster.setX(ccX);
            centerCluster.setY(ccY);
        }
    }

    private int dpToPx(float dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onDestroy() {
        stopHeartbeat();
        disconnectFromPC();
        senderExecutor.shutdown();
        super.onDestroy();
    }
}
