package com.gsmrelay.cloud;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.gsmrelay.cloud.audio.AudioRelayEngine;
import com.gsmrelay.cloud.network.CloudClient;
import com.gsmrelay.cloud.protocol.ProtocolMessage;
import com.gsmrelay.cloud.service.GsmGatewayService;
import com.gsmrelay.cloud.telephony.GsmTelephonyController;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQ_CODE = 1001;
    private static final String PREFS_NAME = "GsmRelayPrefs";
    private static final String PREF_KEY_MODE = "active_mode";
    private static final String PREF_KEY_ROOM = "active_room";
    private static final String PREF_KEY_SERVER = "active_server";
    private static final String PREF_KEY_KEEP_SCREEN_ON = "pref_keep_screen_on";
    private static final String PREF_KEY_PRIVATE_RELAY = "pref_private_relay";

    // Modes
    private static final int MODE_GATEWAY = 1;
    private static final int MODE_TERMINAL = 2;
    private int currentMode = MODE_GATEWAY;

    // Core Components
    private CloudClient cloudClient;
    private AudioRelayEngine audioRelayEngine;
    private GsmTelephonyController gsmController;
    private SharedPreferences prefs;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // Hardware Locks for 100% Background Reliability
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    // Top Bar UI
    private TextView tvTopStatus;
    private TextView tvTopLatency;
    private View dotTopStatus;
    private TextView tabGateway;
    private TextView tabTerminal;
    private View layoutGateway;
    private View layoutTerminal;
    private View bannerPermissions;
    private MaterialButton btnFixPermissions;

    // Gateway UI
    private TextView tvGwVersion;
    private TextView tvGwCarrier;
    private EditText etGwRoom;
    private EditText etGwServerUrl;
    private MaterialButton btnGwToggleService;
    private SwitchCompat swKeepScreenOn;
    private SwitchCompat swAutoAnswer;
    private SwitchCompat swSpeakerRelay;
    private SwitchCompat swPrivateRelay;
    private SwitchCompat swAudioBoost;
    private SwitchCompat swBluetoothSco;
    private SwitchCompat swSmsRelay;
    private Spinner spnGwAudioSource;
    private MaterialButton btnStreamAudioTest;
    private MaterialButton btnAudioTest;
    private TextView tvGwCallStatus;
    private TextView tvGwCallNumber;
    private TextView tvGwCallEvents;
    private ProgressBar pbGwAudioTx;
    private ProgressBar pbGwAudioRx;
    private TextView tvGwLogs;
    private boolean isGatewayRunning = false;
    private boolean isGsmCallActive = false;

    // Terminal UI
    private View cardTermConnection;
    private View dotTermGwStatus;
    private TextView tvTermGwStatus;
    private TextView tvTermGwCarrier;
    private TextView tvTermMyExtensionBadge;
    private EditText etTermExtension;
    private MaterialButton btnTermSaveExtension;
    private Spinner spnTermGateways;
    private final List<GatewayItem> gatewayList = new ArrayList<>();
    private ArrayAdapter<GatewayItem> gatewayAdapter;
    private EditText etTermServerUrl;
    private EditText etTermRoom;
    private MaterialButton btnTermConnect;
    private SwitchCompat swTermKeepScreenOn;
    private View viewDialpad;
    private View viewInCall;
    private EditText etPhoneNumber;
    private ImageButton btnClearPhone;
    private ImageButton btnBackspace;
    private ImageButton btnTermDial;
    private TextView tvInCallStatus;
    private TextView tvInCallNumber;
    private TextView tvInCallTimer;
    private ProgressBar pbTermAudioRx;
    private ProgressBar pbTermAudioTx;
    private TextView tvTermCallEvents;
    private ImageButton btnTermMute;
    private TextView tvTermMuteLabel;
    private ImageButton btnTermSpeaker;
    private TextView tvTermSpeakerLabel;
    private ImageButton btnTermHangup;
    private boolean isTerminalConnected = false;
    private boolean isTerminalInCall = false;
    private boolean isTerminalSpeaker = true;

    // GatewayItem helper for spinner
    public static class GatewayItem {
        public final String id;
        public final String name;
        public final String carrier;
        public final boolean isBusy;

        public GatewayItem(String id, String name, String carrier, boolean isBusy) {
            this.id = id;
            this.name = name;
            this.carrier = carrier;
            this.isBusy = isBusy;
        }

        @NonNull
        @Override
        public String toString() {
            if (id == null || id.isEmpty()) {
                return name;
            }
            return "📱 " + name + " (" + (carrier != null && !carrier.isEmpty() ? carrier : "GSM") + ")" + (isBusy ? " [مشغول 🔴]" : " [متاح 🟢]");
        }
    }

    // Call Timer
    private int callSeconds = 0;
    private final Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isTerminalInCall) {
                callSeconds++;
                int mins = callSeconds / 60;
                int secs = callSeconds % 60;
                tvInCallTimer.setText(String.format(Locale.getDefault(), "%02d:%02d", mins, secs));
                handler.postDelayed(this, 1000);
            }
        }
    };

    // Speakerphone / Audio Lock Loop on Gateway
    private final Runnable speakerphoneEnforcerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isGsmCallActive && isGatewayRunning) {
                try {
                    AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                    if (am != null) {
                        am.setMicrophoneMute(false);
                        boolean isBluetooth = (swBluetoothSco != null && swBluetoothSco.isChecked());
                        boolean isPrivate = (swPrivateRelay != null && swPrivateRelay.isChecked());

                        if (isBluetooth) {
                            if (!am.isBluetoothScoOn()) {
                                am.startBluetoothSco();
                                am.setBluetoothScoOn(true);
                            }
                            if (am.isSpeakerphoneOn()) am.setSpeakerphoneOn(false);
                            int maxCall = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
                            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (int)(maxCall * 0.85f), 0);
                        } else if (isPrivate) {
                            if (am.isSpeakerphoneOn()) am.setSpeakerphoneOn(false);
                            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, 1, 0);
                        } else {
                            // Smart High-Gain Acoustic Bridge
                            if (am.getMode() != AudioManager.MODE_IN_COMMUNICATION) {
                                am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                            }
                            if (!am.isSpeakerphoneOn()) am.setSpeakerphoneOn(true);
                            int maxCall = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
                            int targetVol = (int) (maxCall * 0.82f);
                            am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, Math.max(1, targetVol), 0);
                        }
                    }
                } catch (Throwable ignored) {}
                handler.postDelayed(this, 1200);
            }
        }
    };

    // Incoming Call Dialog on Terminal
    private AlertDialog incomingCallDialog;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        try {
            setContentView(R.layout.activity_main);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true);
                setTurnScreenOn(true);
            } else {
                getWindow().addFlags(
                        android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                );
            }

            prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            currentMode = prefs.getInt(PREF_KEY_MODE, MODE_GATEWAY);

            boolean keepScreenOn = prefs.getBoolean(PREF_KEY_KEEP_SCREEN_ON, true);
            updateKeepScreenOn(keepScreenOn);

            acquireAppLocks();
            checkBatteryOptimizations();

            initViews();
            initComponents();
            setupDialpad();
            checkAndRequestPermissions();

            switchMode(currentMode);
        } catch (Throwable t) {
            Log.e(TAG, "Fatal startup exception in onCreate", t);
            try {
                new AlertDialog.Builder(this)
                        .setTitle("خطأ في تشغيل التطبيق")
                        .setMessage("واجه التطبيق خطأ أثناء البدء:\n" + t.getClass().getSimpleName() + ": " + t.getMessage())
                        .setPositiveButton("إعادة المحاولة", (d, w) -> recreate())
                        .setNegativeButton("إغلاق", (d, w) -> finish())
                        .setCancelable(false)
                        .show();
            } catch (Throwable ignored) {}
        }
    }

    private void initViews() {
        tvTopStatus = findViewById(R.id.tv_top_status);
        tvTopLatency = findViewById(R.id.tv_top_latency);
        dotTopStatus = findViewById(R.id.dot_top_status);
        tabGateway = findViewById(R.id.tab_gateway);
        tabTerminal = findViewById(R.id.tab_terminal);
        layoutGateway = findViewById(R.id.layout_gateway);
        layoutTerminal = findViewById(R.id.layout_terminal);

        if (tabGateway != null) tabGateway.setOnClickListener(v -> switchMode(MODE_GATEWAY));
        if (tabTerminal != null) tabTerminal.setOnClickListener(v -> switchMode(MODE_TERMINAL));

        bannerPermissions = findViewById(R.id.banner_permissions);
        btnFixPermissions = findViewById(R.id.btn_fix_permissions);
        if (btnFixPermissions != null) {
            btnFixPermissions.setOnClickListener(v -> showPermissionRationaleAndRequest());
        }

        // Gateway UI Bindings
        tvGwVersion = findViewById(R.id.tv_gw_version);
        tvGwCarrier = findViewById(R.id.tv_gw_carrier);
        etGwRoom = findViewById(R.id.et_gw_room);
        etGwServerUrl = findViewById(R.id.et_gw_server_url);
        btnGwToggleService = findViewById(R.id.btn_gw_toggle_service);
        swKeepScreenOn = findViewById(R.id.sw_keep_screen_on);
        boolean savedKeepScreenOn = (prefs != null) ? prefs.getBoolean(PREF_KEY_KEEP_SCREEN_ON, true) : true;
        if (swKeepScreenOn != null) {
            swKeepScreenOn.setChecked(savedKeepScreenOn);
            swKeepScreenOn.setOnCheckedChangeListener((btn, isChecked) -> onKeepScreenOnToggled(isChecked));
        }
        swAutoAnswer = findViewById(R.id.sw_auto_answer);
        swPrivateRelay = findViewById(R.id.sw_private_relay);
        swSpeakerRelay = findViewById(R.id.sw_speaker_relay);
        boolean savedPrivateRelay = (prefs != null) ? prefs.getBoolean(PREF_KEY_PRIVATE_RELAY, false) : false;
        if (swPrivateRelay != null) {
            swPrivateRelay.setChecked(savedPrivateRelay);
            swPrivateRelay.setOnCheckedChangeListener((btn, isChecked) -> {
                if (prefs != null) {
                    prefs.edit().putBoolean(PREF_KEY_PRIVATE_RELAY, isChecked).apply();
                }
                if (isChecked && swSpeakerRelay != null && swSpeakerRelay.isChecked()) {
                    swSpeakerRelay.setChecked(false);
                }
                GsmGatewayService service = GsmGatewayService.getInstance();
                if (service != null) {
                    service.setPrivateRelay(isChecked);
                } else if (audioRelayEngine != null) {
                    audioRelayEngine.setPrivateRelay(isChecked);
                }
                appendCallEvent(isChecked ? "🔒 تم تفعيل وضع الغرفة الصامتة بالوسيط (يتطلب سماعة بلوتوث أو سلكية)" : "🔊 تم تفعيل جسر مكبر الصوت عالي الحساسية");
            });
        }
        if (swSpeakerRelay != null) {
            swSpeakerRelay.setChecked(!savedPrivateRelay);
            swSpeakerRelay.setOnCheckedChangeListener((btn, isChecked) -> {
                if (isChecked && swPrivateRelay != null && swPrivateRelay.isChecked()) {
                    swPrivateRelay.setChecked(false);
                }
                GsmGatewayService service = GsmGatewayService.getInstance();
                if (service != null) {
                    service.setSpeakerRelay(isChecked);
                }
                appendCallEvent(isChecked ? "🔊 تم تشغيل جسر مكبر الصوت عالي الحساسية (Acoustic Relay)" : "🔇 إيقاف مكبر الصوت");
            });
        }
        swAudioBoost = findViewById(R.id.sw_audio_boost);
        swBluetoothSco = findViewById(R.id.sw_bluetooth_sco);
        swSmsRelay = findViewById(R.id.sw_sms_relay);
        spnGwAudioSource = findViewById(R.id.spn_gw_audio_source);
        btnStreamAudioTest = findViewById(R.id.btn_stream_audio_test);
        btnAudioTest = findViewById(R.id.btn_audio_test);
        tvGwCallStatus = findViewById(R.id.tv_gw_call_status);
        tvGwCallNumber = findViewById(R.id.tv_gw_call_number);
        tvGwCallEvents = findViewById(R.id.tv_gw_call_events);
        pbGwAudioTx = findViewById(R.id.pb_gw_audio_tx);
        pbGwAudioRx = findViewById(R.id.pb_gw_audio_rx);
        tvGwLogs = findViewById(R.id.tv_gw_logs);

        if (tvGwVersion != null) {
            tvGwVersion.setText(BuildConfig.AUTO_VERSION_NAME);
        }

        String savedRoom = (prefs != null) ? prefs.getString(PREF_KEY_ROOM, "GSM-1001") : "GSM-1001";
        if (etGwRoom != null) {
            etGwRoom.setText(savedRoom);
        }

        String savedServer = (prefs != null) ? prefs.getString(PREF_KEY_SERVER, CloudClient.DEFAULT_SERVER_URL) : CloudClient.DEFAULT_SERVER_URL;
        if (savedServer.contains("echo.websocket.events") || savedServer.contains("172.26.") || savedServer.trim().isEmpty()) {
            savedServer = CloudClient.DEFAULT_SERVER_URL;
        }
        if (etGwServerUrl != null) {
            etGwServerUrl.setText(savedServer);
        }

        if (btnGwToggleService != null) {
            btnGwToggleService.setOnClickListener(v -> toggleGatewayService());
        }

        if (swAudioBoost != null) {
            swAudioBoost.setOnCheckedChangeListener((btn, isChecked) -> {
                GsmGatewayService service = GsmGatewayService.getInstance();
                if (service != null) {
                    service.setAudioBoost(isChecked);
                } else if (audioRelayEngine != null) {
                    audioRelayEngine.setAudioBoost(isChecked);
                }
                appendLog("مضخم الصوت: " + (isChecked ? "مفعل (+14dB)" : "عادي"));
            });
        }

        if (swBluetoothSco != null) {
            swBluetoothSco.setOnCheckedChangeListener((btn, isChecked) -> {
                GsmGatewayService service = GsmGatewayService.getInstance();
                if (service != null) {
                    service.setBluetoothSco(isChecked);
                } else if (audioRelayEngine != null) {
                    audioRelayEngine.setBluetoothSco(isChecked);
                }
                appendCallEvent("توجيه صوت البلوتوث: " + (isChecked ? "مفعل (Bluetooth SCO On)" : "معطل"));
            });
        }

        if (swSmsRelay != null) {
            swSmsRelay.setOnCheckedChangeListener((btn, isChecked) -> {
                if (isChecked) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(this, "يرجى منح صلاحية إرسال الرسائل SMS", Toast.LENGTH_SHORT).show();
                        ActivityCompat.requestPermissions(this, new String[]{
                                Manifest.permission.SEND_SMS
                        }, PERMISSION_REQ_CODE);
                    }
                }
                GsmGatewayService service = GsmGatewayService.getInstance();
                if (service != null) {
                    service.setSmsRelayEnabled(isChecked);
                }
                appendLog("ترحيل الرسائل النصية SMS: " + (isChecked ? "مفعل" : "معطل"));
                appendCallEvent("ترحيل الرسائل النصية SMS: " + (isChecked ? "مفعل (SMS Relay Enabled)" : "معطل"));
            });
        }

        if (spnGwAudioSource != null) {
            String[] sources = {
                "✨ كشف ذكي تلقائي مع التعافي الذاتي (Smart Auto-Hunter)",
                "التعرف على الصوت المعزز بدون صدى (Voice Recognition #6)",
                "صوت خام غير معالج (Unprocessed Raw #9)",
                "الميكروفون القياسي الأساسي (Standard Mic #1)",
                "ميكروفون الكاميرا العلوي (Camcorder Mic #5)",
                "ميكروفون الاتصالات (Voice Communication #7)"
            };
            ArrayAdapter<String> sourceAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, sources);
            spnGwAudioSource.setAdapter(sourceAdapter);
            spnGwAudioSource.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                    int sourceValue;
                    switch (position) {
                        case 0: sourceValue = -1; break; // Auto smart hunter (6 -> 9 -> 1 -> 5 -> 7 -> 0)
                        case 1: sourceValue = android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION; break;
                        case 2: sourceValue = 9; break; // UNPROCESSED
                        case 3: sourceValue = android.media.MediaRecorder.AudioSource.MIC; break;
                        case 4: sourceValue = android.media.MediaRecorder.AudioSource.CAMCORDER; break;
                        case 5: sourceValue = android.media.MediaRecorder.AudioSource.VOICE_COMMUNICATION; break;
                        default: sourceValue = -1; break;
                    }
                    GsmGatewayService service = GsmGatewayService.getInstance();
                    if (service != null) {
                        service.setCustomAudioSource(sourceValue);
                    } else if (audioRelayEngine != null) {
                        audioRelayEngine.setCustomAudioSource(sourceValue);
                    }
                }
                @Override
                public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            });
        }

        if (btnStreamAudioTest != null) {
            btnStreamAudioTest.setOnClickListener(v -> toggleStreamAudioTest());
        }

        if (btnAudioTest != null) {
            btnAudioTest.setOnClickListener(v -> toggleAudioTest());
        }

        // Terminal UI Bindings
        cardTermConnection = findViewById(R.id.card_term_connection);
        dotTermGwStatus = findViewById(R.id.dot_term_gw_status);
        tvTermGwStatus = findViewById(R.id.tv_term_gw_status);
        tvTermGwCarrier = findViewById(R.id.tv_term_gw_carrier);
        tvTermMyExtensionBadge = findViewById(R.id.tv_term_my_extension_badge);
        etTermExtension = findViewById(R.id.et_term_extension);
        btnTermSaveExtension = findViewById(R.id.btn_term_save_extension);
        spnTermGateways = findViewById(R.id.spn_term_gateways);
        etTermServerUrl = findViewById(R.id.et_term_server_url);
        etTermRoom = findViewById(R.id.et_term_room);
        btnTermConnect = findViewById(R.id.btn_term_connect);
        swTermKeepScreenOn = findViewById(R.id.sw_term_keep_screen_on);
        if (swTermKeepScreenOn != null) {
            swTermKeepScreenOn.setChecked(savedKeepScreenOn);
            swTermKeepScreenOn.setOnCheckedChangeListener((btn, isChecked) -> onKeepScreenOnToggled(isChecked));
        }

        String savedExtension = (prefs != null) ? prefs.getString("terminal_extension", "#105") : "#105";
        if (etTermExtension != null) {
            etTermExtension.setText(savedExtension);
        }
        if (tvTermMyExtensionBadge != null) {
            tvTermMyExtensionBadge.setText("تحويلتي: " + savedExtension);
        }
        if (cloudClient != null) {
            cloudClient.setExtension(savedExtension);
        }

        if (btnTermSaveExtension != null) {
            btnTermSaveExtension.setOnClickListener(v -> {
                String inputExt = etTermExtension != null ? etTermExtension.getText().toString().trim() : "#105";
                if (inputExt.isEmpty()) inputExt = "#105";
                if (!inputExt.startsWith("#")) inputExt = "#" + inputExt;
                if (etTermExtension != null) etTermExtension.setText(inputExt);
                if (tvTermMyExtensionBadge != null) tvTermMyExtensionBadge.setText("تحويلتي: " + inputExt);

                if (prefs != null) {
                    prefs.edit().putString("terminal_extension", inputExt).apply();
                }

                if (cloudClient != null) {
                    cloudClient.setExtension(inputExt);
                    if (cloudClient.isConnected()) {
                        String currentRoom = etTermRoom != null ? etTermRoom.getText().toString().trim() : "GSM-1001";
                        cloudClient.sendMessage(ProtocolMessage.createUpdateExtension(currentRoom, inputExt));
                    }
                }

                GsmGatewayService activeService = GsmGatewayService.getInstance();
                if (activeService != null) {
                    activeService.setTerminalExtension(inputExt);
                }

                Toast.makeText(this, "تم تثبيت رقم تحويلة الهاتف الطرفي: " + inputExt, Toast.LENGTH_SHORT).show();
                appendCallEvent("تم تحديث وتثبيت تحويلة هذا الهاتف الطرفي: " + inputExt);
            });
        }
        viewDialpad = findViewById(R.id.view_dialpad);
        viewInCall = findViewById(R.id.view_in_call);
        etPhoneNumber = findViewById(R.id.et_phone_number);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && etPhoneNumber != null) {
            etPhoneNumber.setShowSoftInputOnFocus(false);
        }
        btnClearPhone = findViewById(R.id.btn_clear_phone);
        btnBackspace = findViewById(R.id.btn_backspace);
        btnTermDial = findViewById(R.id.btn_term_dial);
        tvInCallStatus = findViewById(R.id.tv_in_call_status);
        tvInCallNumber = findViewById(R.id.tv_in_call_number);
        tvInCallTimer = findViewById(R.id.tv_in_call_timer);
        pbTermAudioRx = findViewById(R.id.pb_term_audio_rx);
        pbTermAudioTx = findViewById(R.id.pb_term_audio_tx);
        tvTermCallEvents = findViewById(R.id.tv_term_call_events);
        btnTermMute = findViewById(R.id.btn_term_mute);
        tvTermMuteLabel = findViewById(R.id.tv_term_mute_label);
        btnTermSpeaker = findViewById(R.id.btn_term_speaker);
        tvTermSpeakerLabel = findViewById(R.id.tv_term_speaker_label);
        btnTermHangup = findViewById(R.id.btn_term_hangup);

        gatewayList.clear();
        gatewayList.add(new GatewayItem("", "بانتظار اكتشاف الهواتف الوسيطة...", "--", false));
        gatewayAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, gatewayList);
        if (spnTermGateways != null) {
            spnTermGateways.setAdapter(gatewayAdapter);
        }

        if (btnClearPhone != null) {
            btnClearPhone.setOnClickListener(v -> {
                if (etPhoneNumber != null) etPhoneNumber.setText("");
            });
        }

        if (etTermServerUrl != null) {
            etTermServerUrl.setText(savedServer);
        }
        if (etTermRoom != null) {
            etTermRoom.setText(savedRoom);
        }
        if (btnTermConnect != null) {
            btnTermConnect.setOnClickListener(v -> toggleTerminalConnection());
        }
        if (btnTermDial != null) {
            btnTermDial.setOnClickListener(v -> initiateTerminalCall());
        }
        if (btnTermHangup != null) {
            btnTermHangup.setOnClickListener(v -> hangupCall(ProtocolMessage.ROLE_TERMINAL));
        }

        if (btnTermMute != null) {
            btnTermMute.setOnClickListener(v -> {
                if (audioRelayEngine == null) return;
                boolean isMuted = !audioRelayEngine.isMuted();
                audioRelayEngine.setMuted(isMuted);
                btnTermMute.setImageResource(isMuted ? R.drawable.ic_mic_off : R.drawable.ic_mic_on);
                if (tvTermMuteLabel != null) {
                    tvTermMuteLabel.setText(isMuted ? "مكتوم" : "كتم");
                }
                appendCallEvent(isMuted ? "تم كتم الميكروفون" : "تم إلغاء كتم الميكروفون");
            });
        }

        if (btnTermSpeaker != null) {
            btnTermSpeaker.setOnClickListener(v -> {
                if (audioRelayEngine == null) return;
                isTerminalSpeaker = !isTerminalSpeaker;
                audioRelayEngine.setSpeaker(isTerminalSpeaker);
                btnTermSpeaker.setImageResource(isTerminalSpeaker ? R.drawable.ic_speaker_on : R.drawable.ic_speaker_off);
                if (tvTermSpeakerLabel != null) {
                    tvTermSpeakerLabel.setText(isTerminalSpeaker ? "مكبر الصوت" : "سماعة الأذن");
                }
                appendCallEvent(isTerminalSpeaker ? "تم تشغيل مكبر الصوت" : "تم التحويل إلى سماعة الأذن");
            });
        }

        if (btnBackspace != null) {
            btnBackspace.setOnClickListener(v -> {
                if (etPhoneNumber == null) return;
                String text = etPhoneNumber.getText().toString();
                if (!text.isEmpty()) {
                    etPhoneNumber.setText(text.substring(0, text.length() - 1));
                    etPhoneNumber.setSelection(etPhoneNumber.getText().length());
                }
            });

            btnBackspace.setOnLongClickListener(v -> {
                if (etPhoneNumber != null) etPhoneNumber.setText("");
                return true;
            });
        }
    }

    private void initComponents() {
        cloudClient = new CloudClient(this);
        audioRelayEngine = new AudioRelayEngine(this);
        gsmController = new GsmTelephonyController(this);

        audioRelayEngine.setAudioListener(new AudioRelayEngine.AudioListener() {
            @Override
            public void onAudioCaptured(byte[] data, int offset, int length) {
                cloudClient.sendAudioData(data, offset, length);
            }

            @Override
            public void onTxLevel(float level) {
                int progress = (int) (level * 100);
                handler.post(() -> {
                    if (currentMode == MODE_GATEWAY) {
                        if (pbGwAudioTx != null) pbGwAudioTx.setProgress(progress);
                    } else {
                        if (pbTermAudioTx != null) pbTermAudioTx.setProgress(progress);
                    }
                });
            }

            @Override
            public void onRxLevel(float level) {
                int progress = (int) (level * 100);
                handler.post(() -> {
                    if (currentMode == MODE_GATEWAY) {
                        if (pbGwAudioRx != null) pbGwAudioRx.setProgress(progress);
                    } else {
                        if (pbTermAudioRx != null) pbTermAudioRx.setProgress(progress);
                    }
                });
            }
        });

        gsmController.setListener(new GsmTelephonyController.TelephonyListener() {
            @Override
            public void onCallStateChanged(int state, String incomingNumber) {
                handler.post(() -> handleGsmCallState(state, incomingNumber));
            }

            @Override
            public void onGranularCallStateChanged(String granularState, String phoneNumber, String details) {
                handler.post(() -> {
                    Log.i("MainActivity", "Granular call state: " + granularState + " (" + details + ")");
                    if (currentMode == MODE_GATEWAY && cloudClient != null && cloudClient.isConnected()) {
                        ProtocolMessage stateMsg = ProtocolMessage.createCallState(granularState, phoneNumber, details);
                        cloudClient.sendMessage(stateMsg);
                    }
                });
            }

            @Override
            public void onSimStatusChanged(boolean ready, String carrier) {
                handler.post(() -> {
                    if (tvGwCarrier != null) {
                        tvGwCarrier.setText(ready ? carrier + " (جاهز)" : "لا توجد شريحة SIM");
                    }
                });
            }
        });

        cloudClient.setListener(new CloudClient.CloudListener() {
            @Override
            public void onConnected() {
                dotTopStatus.setBackgroundResource(R.drawable.bg_status_dot_green);
                tvTopStatus.setText("متصل بالسحابة");
                if (currentMode == MODE_GATEWAY) {
                    btnGwToggleService.setText(R.string.btn_stop_gateway);
                    btnGwToggleService.setBackgroundTintList(ContextCompat.getColorStateList(MainActivity.this, R.color.accent_red));
                    ProtocolMessage info = ProtocolMessage.createGatewayInfo(
                            etGwRoom.getText().toString().trim(),
                            gsmController.getSimCarrier(),
                            100,
                            isGsmCallActive ? ProtocolMessage.CALL_CONNECTED : ProtocolMessage.CALL_IDLE
                    );
                    cloudClient.sendMessage(info);
                    appendCallEvent("تم الاتصال بالسحابة بنجاح [الغرفة: " + etGwRoom.getText().toString().trim() + "] - بانتظار اتصالات الطرفي");
                } else {
                    isTerminalConnected = true;
                    btnTermConnect.setText(R.string.btn_disconnect_cloud);
                    btnTermConnect.setBackgroundTintList(ContextCompat.getColorStateList(MainActivity.this, R.color.accent_red));
                    if (dotTermGwStatus != null) dotTermGwStatus.setBackgroundResource(R.drawable.bg_status_dot_green);
                    if (tvTermGwStatus != null) tvTermGwStatus.setText("الطرفي: متصل 24/7");
                    appendCallEvent("تم الاتصال بالسحابة بنجاح [الغرفة: " + etTermRoom.getText().toString().trim() + "] - جاري استكشاف الهواتف الوسيطة...");
                }
            }

            @Override
            public void onDisconnected(String reason) {
                tvTopLatency.setText("-- ms");
                appendCallEvent("انقطع الاتصال بالسحابة: " + reason);
                if (currentMode == MODE_TERMINAL) {
                    if (cloudClient.isShouldReconnect()) {
                        dotTopStatus.setBackgroundResource(R.drawable.bg_status_dot_amber);
                        tvTopStatus.setText("جاري إعادة الاتصال تلقائياً...");
                        btnTermConnect.setText("إعادة الاتصال الآن 🔄");
                        btnTermConnect.setBackgroundTintList(ContextCompat.getColorStateList(MainActivity.this, R.color.accent_amber));
                        if (dotTermGwStatus != null) dotTermGwStatus.setBackgroundResource(R.drawable.bg_status_dot_amber);
                        if (tvTermGwStatus != null) tvTermGwStatus.setText("جاري إعادة الاتصال...");
                    } else {
                        isTerminalConnected = false;
                        dotTopStatus.setBackgroundResource(R.drawable.bg_status_dot_red);
                        tvTopStatus.setText("غير متصل");
                        btnTermConnect.setText(R.string.btn_connect_cloud);
                        btnTermConnect.setBackgroundTintList(ContextCompat.getColorStateList(MainActivity.this, R.color.accent_blue));
                        if (dotTermGwStatus != null) dotTermGwStatus.setBackgroundResource(R.drawable.bg_status_dot_red);
                        if (tvTermGwStatus != null) tvTermGwStatus.setText("الوسيط: غير متصل");
                    }
                } else {
                    if (cloudClient.isShouldReconnect()) {
                        dotTopStatus.setBackgroundResource(R.drawable.bg_status_dot_amber);
                        tvTopStatus.setText("جاري إعادة الاتصال تلقائياً...");
                    } else {
                        dotTopStatus.setBackgroundResource(R.drawable.bg_status_dot_red);
                        tvTopStatus.setText("غير متصل");
                    }
                }
            }

            @Override
            public void onReconnecting(int attempt, long delayMs) {
                dotTopStatus.setBackgroundResource(R.drawable.bg_status_dot_amber);
                tvTopStatus.setText("جاري إعادة الاتصال (" + attempt + ")...");
                if (currentMode == MODE_TERMINAL) {
                    btnTermConnect.setText("إعادة الاتصال الآن 🔄");
                    btnTermConnect.setBackgroundTintList(ContextCompat.getColorStateList(MainActivity.this, R.color.accent_amber));
                    if (dotTermGwStatus != null) dotTermGwStatus.setBackgroundResource(R.drawable.bg_status_dot_amber);
                    if (tvTermGwStatus != null) tvTermGwStatus.setText("جاري إعادة الاتصال تلقائياً...");
                }
            }

            @Override
            public void onMessageReceived(ProtocolMessage message) {
                handleIncomingCloudMessage(message);
            }

            @Override
            public void onAudioReceived(byte[] audioData) {
                if (audioRelayEngine == null || audioData == null) return;
                if (currentMode == MODE_GATEWAY) {
                    if (isGsmCallActive) {
                        audioRelayEngine.playIncomingAudio(audioData, 0, audioData.length);
                    }
                } else if (currentMode == MODE_TERMINAL) {
                    if (isTerminalInCall) {
                        audioRelayEngine.playIncomingAudio(audioData, 0, audioData.length);
                    }
                }
            }

            @Override
            public void onLatencyUpdated(long latencyMs) {
                tvTopLatency.setText(latencyMs + " ms");
            }

            @Override
            public void onLog(String log) {
                appendLog(log);
            }
        });
    }

    private void setupDialpad() {
        int[] keyIds = {
                R.id.key_1, R.id.key_2, R.id.key_3,
                R.id.key_4, R.id.key_5, R.id.key_6,
                R.id.key_7, R.id.key_8, R.id.key_9,
                R.id.key_star, R.id.key_0, R.id.key_hash
        };

        for (int id : keyIds) {
            Button btn = findViewById(id);
            if (btn != null) {
                btn.setOnClickListener(v -> {
                    if (etPhoneNumber != null) {
                        etPhoneNumber.append(btn.getText());
                        etPhoneNumber.setSelection(etPhoneNumber.getText().length());
                    }
                });

                if (id == R.id.key_0) {
                    btn.setOnLongClickListener(v -> {
                        if (etPhoneNumber != null) {
                            etPhoneNumber.append("+");
                            etPhoneNumber.setSelection(etPhoneNumber.getText().length());
                        }
                        return true;
                    });
                }
            }
        }
    }

    private void switchMode(int mode) {
        currentMode = mode;
        prefs.edit().putInt(PREF_KEY_MODE, mode).apply();

        if (mode == MODE_GATEWAY) {
            tabGateway.setBackgroundResource(R.drawable.bg_card);
            tabGateway.setTextColor(ContextCompat.getColor(this, R.color.accent_green));
            tabTerminal.setBackground(null);
            tabTerminal.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
            layoutGateway.setVisibility(View.VISIBLE);
            layoutTerminal.setVisibility(View.GONE);
            gsmController.checkSimStatus();
        } else {
            tabTerminal.setBackgroundResource(R.drawable.bg_card);
            tabTerminal.setTextColor(ContextCompat.getColor(this, R.color.accent_blue));
            tabGateway.setBackground(null);
            tabGateway.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
            layoutGateway.setVisibility(View.GONE);
            layoutTerminal.setVisibility(View.VISIBLE);
        }
    }

    // =========================================================================
    // GATEWAY ACTIONS (100% BULLETPROOF & CRASH-PROOF)
    // =========================================================================

    private final GsmGatewayService.GatewayServiceListener gatewayServiceListener = new GsmGatewayService.GatewayServiceListener() {
        @Override
        public void onCloudStatusChanged(boolean connected, String status) {
            handler.post(() -> {
                if (dotTopStatus != null) {
                    if (connected) {
                        dotTopStatus.setBackgroundResource(R.drawable.bg_status_dot_green);
                    } else if (status != null && status.contains("إعادة الاتصال")) {
                        dotTopStatus.setBackgroundResource(R.drawable.bg_status_dot_amber);
                    } else {
                        dotTopStatus.setBackgroundResource(R.drawable.bg_status_dot_red);
                    }
                }
                if (tvTopStatus != null) {
                    tvTopStatus.setText(status);
                }

                if (currentMode == MODE_TERMINAL) {
                    isTerminalConnected = connected;
                    if (btnTermConnect != null) {
                        if (connected) {
                            btnTermConnect.setText(R.string.btn_disconnect_cloud);
                            btnTermConnect.setBackgroundTintList(ContextCompat.getColorStateList(MainActivity.this, R.color.accent_red));
                            if (dotTermGwStatus != null) dotTermGwStatus.setBackgroundResource(R.drawable.bg_status_dot_green);
                            if (tvTermGwStatus != null) tvTermGwStatus.setText("الطرفي: متصل 24/7");
                        } else if (status != null && status.contains("إعادة الاتصال")) {
                            btnTermConnect.setText("إعادة الاتصال الآن 🔄");
                            btnTermConnect.setBackgroundTintList(ContextCompat.getColorStateList(MainActivity.this, R.color.accent_amber));
                            if (dotTermGwStatus != null) dotTermGwStatus.setBackgroundResource(R.drawable.bg_status_dot_amber);
                            if (tvTermGwStatus != null) tvTermGwStatus.setText("جاري إعادة الاتصال...");
                        } else {
                            btnTermConnect.setText(R.string.btn_connect_cloud);
                            btnTermConnect.setBackgroundTintList(ContextCompat.getColorStateList(MainActivity.this, R.color.accent_blue));
                            if (dotTermGwStatus != null) dotTermGwStatus.setBackgroundResource(R.drawable.bg_status_dot_red);
                            if (tvTermGwStatus != null) tvTermGwStatus.setText("الوسيط: غير متصل");
                        }
                    }
                } else if (currentMode == MODE_GATEWAY) {
                    isGatewayRunning = GsmGatewayService.isRunning();
                    if (btnGwToggleService != null) {
                        if (isGatewayRunning) {
                            btnGwToggleService.setText(R.string.btn_stop_gateway);
                            btnGwToggleService.setBackgroundTintList(ContextCompat.getColorStateList(MainActivity.this, R.color.accent_red));
                        } else {
                            btnGwToggleService.setText(R.string.btn_start_gateway);
                            btnGwToggleService.setBackgroundTintList(ContextCompat.getColorStateList(MainActivity.this, R.color.accent_green));
                        }
                    }
                }
            });
        }

        @Override
        public void onIncomingCloudMessage(ProtocolMessage message) {
            handler.post(() -> handleIncomingCloudMessage(message));
        }

        @Override
        public void onGsmCallStateChanged(int state, String incomingNumber) {
            handler.post(() -> {
                if (state == TelephonyManager.CALL_STATE_RINGING) {
                    if (tvGwCallStatus != null) {
                        tvGwCallStatus.setText(R.string.call_ringing);
                        tvGwCallStatus.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.accent_amber));
                    }
                    if (tvGwCallNumber != null) tvGwCallNumber.setText(incomingNumber);
                } else if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
                    isGsmCallActive = true;
                    if (tvGwCallStatus != null) {
                        tvGwCallStatus.setText(R.string.call_connected);
                        tvGwCallStatus.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.accent_green));
                    }
                    if (tvGwCallNumber != null) tvGwCallNumber.setText(incomingNumber);
                } else if (state == TelephonyManager.CALL_STATE_IDLE) {
                    isGsmCallActive = false;
                    if (tvGwCallStatus != null) {
                        tvGwCallStatus.setText(R.string.call_idle);
                        tvGwCallStatus.setTextColor(ContextCompat.getColor(MainActivity.this, R.color.text_muted));
                    }
                    if (tvGwCallNumber != null) tvGwCallNumber.setText("--");
                    if (pbGwAudioTx != null) pbGwAudioTx.setProgress(0);
                    if (pbGwAudioRx != null) pbGwAudioRx.setProgress(0);
                }
            });
        }

        @Override
        public void onSimStatusChanged(boolean ready, String carrier) {
            handler.post(() -> {
                if (tvGwCarrier != null) {
                    tvGwCarrier.setText(ready ? carrier + " (جاهز)" : "لا توجد شريحة SIM");
                }
            });
        }

        @Override
        public void onTxAudioLevel(float level) {
            handler.post(() -> {
                if (currentMode == MODE_GATEWAY) {
                    if (pbGwAudioTx != null) pbGwAudioTx.setProgress((int) (level * 100));
                } else {
                    if (pbTermAudioTx != null) pbTermAudioTx.setProgress((int) (level * 100));
                }
            });
        }

        @Override
        public void onRxAudioLevel(float level) {
            handler.post(() -> {
                if (currentMode == MODE_GATEWAY) {
                    if (pbGwAudioRx != null) pbGwAudioRx.setProgress((int) (level * 100));
                } else {
                    if (pbTermAudioRx != null) pbTermAudioRx.setProgress((int) (level * 100));
                }
            });
        }

        @Override
        public void onLatencyUpdated(long latencyMs) {
            handler.post(() -> {
                if (tvTopLatency != null) {
                    tvTopLatency.setText(latencyMs + " ms");
                }
            });
        }

        @Override
        public void onLogMessage(String log) {
            appendLog(log);
        }

        @Override
        public void onCallEvent(String event) {
            appendCallEvent(event);
        }
    };

    private void toggleGatewayService() {
        try {
            if (!GsmGatewayService.isRunning()) {
                String room = etGwRoom.getText().toString().trim();
                String server = etGwServerUrl.getText().toString().trim();
                if (room.isEmpty()) room = "GSM-1001";
                if (server.isEmpty()) server = CloudClient.DEFAULT_SERVER_URL;

                prefs.edit().putString(PREF_KEY_ROOM, room).putString(PREF_KEY_SERVER, server).apply();
                checkBatteryOptimizations();

                Intent serviceIntent = new Intent(this, GsmGatewayService.class);
                serviceIntent.setAction(GsmGatewayService.ACTION_START);
                serviceIntent.putExtra(GsmGatewayService.EXTRA_SERVER, server);
                serviceIntent.putExtra(GsmGatewayService.EXTRA_ROOM, room);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    ContextCompat.startForegroundService(this, serviceIntent);
                } else {
                    startService(serviceIntent);
                }

                final String finalRoom = room;
                final String finalServer = server;
                final boolean privateMode = (swPrivateRelay != null && swPrivateRelay.isChecked());
                final boolean speakerRelay = (swSpeakerRelay == null || swSpeakerRelay.isChecked());
                final boolean smsRelay = (swSmsRelay != null && swSmsRelay.isChecked());
                handler.postDelayed(() -> {
                    GsmGatewayService service = GsmGatewayService.getInstance();
                    if (service != null) {
                        service.startGateway(finalServer, finalRoom,
                                swAutoAnswer != null && swAutoAnswer.isChecked(),
                                speakerRelay,
                                swAudioBoost != null && swAudioBoost.isChecked(),
                                swBluetoothSco != null && swBluetoothSco.isChecked(),
                                privateMode,
                                smsRelay);
                        service.setServiceListener(gatewayServiceListener);
                    }
                }, 300);

                isGatewayRunning = true;
                btnGwToggleService.setText(R.string.btn_stop_gateway);
                btnGwToggleService.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.accent_red));
                appendLog("بدأت خدمة الوسيط الدائمة 24/7 على الغرفة: " + room);
                appendCallEvent("بدأت خدمة الوسيط الدائمة 24/7 على الغرفة: " + room);
                checkNetworkAndWarn();
            } else {
                GsmGatewayService service = GsmGatewayService.getInstance();
                if (service != null) {
                    service.stopGateway();
                }
                isGatewayRunning = false;
                btnGwToggleService.setText(R.string.btn_start_gateway);
                btnGwToggleService.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.accent_green));
                appendLog("تم إيقاف خدمة الوسيط");
                appendCallEvent("تم إيقاف خدمة الوسيط");
            }
        } catch (Throwable t) {
            Log.e(TAG, "Fatal error in toggleGatewayService", t);
            appendLog("تنبيه: " + t.getMessage());
            Toast.makeText(this, "تنبيه: " + t.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void checkNetworkAndWarn() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                android.net.Network activeNet = cm.getActiveNetwork();
                if (activeNet != null) {
                    android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(activeNet);
                    if (caps != null) {
                        if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                            appendLog("🌐 اتصال مستقر عبر Wi-Fi");
                        } else if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) {
                            appendLog("⚠️ اتصال عبر بيانات الجوال (Mobile Data)");
                            appendCallEvent("⚠️ تنبيه: الهاتف متصل عبر بيانات الشريحة. تأكد من تفعيل VoLTE أو ربط الهاتف بشبكة Wi-Fi لضمان عدم انقطاع الإنترنت أثناء المكالمة.");
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    private void toggleStreamAudioTest() {
        GsmGatewayService service = GsmGatewayService.getInstance();
        if (service == null || !service.isGatewayActive()) {
            Toast.makeText(this, "يرجى بدء خدمة الوسيط أولاً لبث نغمة الفحص!", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!service.isAudioTestStreaming()) {
            service.startAudioTestStream();
            if (btnStreamAudioTest != null) {
                btnStreamAudioTest.setText("⏹️ إيقاف بث النغمة للطرفي (Test Active)");
                btnStreamAudioTest.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.accent_amber));
            }
            appendLog("بدأ بث نغمة الفحص: استمع للهاتف الطرفي لسماع النغمات الموسيقية!");
        } else {
            service.stopAudioTestStream();
            if (btnStreamAudioTest != null) {
                btnStreamAudioTest.setText("📡 بث نغمة وصوت تجريبي إلى الطرفي (Test Stream)");
                btnStreamAudioTest.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.accent_blue));
            }
            appendLog("تم إنهاء بث نغمة الفحص");
        }
    }

    private void toggleAudioTest() {
        if (!audioRelayEngine.isLoopbackActive()) {
            audioRelayEngine.startLoopbackTest();
            btnAudioTest.setText("⏹️ إيقاف فحص الصوت (Loopback Active)");
            btnAudioTest.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.accent_amber));
            appendLog("بدأ فحص الصوت: تحدث في الهاتف لسماع صوتك مباشرة عبر السماعة!");
        } else {
            audioRelayEngine.stopRelay();
            btnAudioTest.setText("🔊 فحص واختبار الصوت المباشر (Mic Test)");
            btnAudioTest.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.keypad_btn_bg));
            appendLog("تم إنهاء فحص الصوت");
        }
    }

    private void handleGsmCallState(int state, String incomingNumber) {
        String room = etGwRoom.getText().toString().trim();
        switch (state) {
            case TelephonyManager.CALL_STATE_RINGING:
                tvGwCallStatus.setText(R.string.call_ringing);
                tvGwCallStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_amber));
                tvGwCallNumber.setText(incomingNumber.isEmpty() ? "رقم غير معروف" : incomingNumber);
                appendLog("رنين وارد عبر الشريحة: " + incomingNumber);
                appendCallEvent("رنين مكالمة خلوية واردة من: " + (incomingNumber.isEmpty() ? "رقم غير معروف" : incomingNumber));

                if (swAutoAnswer.isChecked()) {
                    appendLog("جاري الرد التلقائي على المكالمة...");
                    appendCallEvent("جاري الرد التلقائي على المكالمة الخلوية...");
                    handler.postDelayed(() -> {
                        answerCallReliably();
                    }, 1200);
                } else {
                    ProtocolMessage incomingMsg = ProtocolMessage.createIncomingCall(room, incomingNumber);
                    cloudClient.sendMessage(incomingMsg);
                }
                break;

            case TelephonyManager.CALL_STATE_OFFHOOK:
                isGsmCallActive = true;
                tvGwCallStatus.setText(R.string.call_connected);
                tvGwCallStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green));
                appendLog("المكالمة متصلة - بدء بث الصوت عبر السحابة");

                boolean isPrivate = (swPrivateRelay == null || swPrivateRelay.isChecked());
                if (isPrivate) {
                    appendCallEvent("المكالمة متصلة عبر GSM! تم تفعيل وضع الخصوصية والترحيل البرمجي الصامت كالفويب 🔒");
                } else {
                    appendCallEvent("المكالمة متصلة عبر GSM! جاري تفعيل مكبر الصوت وبث الصوت بجودة عالية.");
                }

                // Apply audio routing & enforcer
                applyGatewayCallAudioRouting();
                handler.post(speakerphoneEnforcerRunnable);

                boolean speaker = (swSpeakerRelay != null && swSpeakerRelay.isChecked()) && !isPrivate;
                audioRelayEngine.setRole(ProtocolMessage.ROLE_GATEWAY);
                audioRelayEngine.setPrivateRelay(isPrivate);
                audioRelayEngine.startRelay(speaker);

                ProtocolMessage connectedMsg = ProtocolMessage.createCallState(room, ProtocolMessage.CALL_CONNECTED, tvGwCallNumber.getText().toString());
                cloudClient.sendMessage(connectedMsg);
                break;

            case TelephonyManager.CALL_STATE_IDLE:
                isGsmCallActive = false;
                handler.removeCallbacks(speakerphoneEnforcerRunnable);
                tvGwCallStatus.setText(R.string.call_idle);
                tvGwCallStatus.setTextColor(ContextCompat.getColor(this, R.color.text_muted));
                tvGwCallNumber.setText("--");
                appendLog("انتهت المكالمة الخلوية");
                appendCallEvent("انتهت المكالمة الخلوية وإغلاق بث الصوت.");

                audioRelayEngine.stopRelay();
                pbGwAudioTx.setProgress(0);
                pbGwAudioRx.setProgress(0);

                ProtocolMessage endedMsg = ProtocolMessage.createCallState(room, ProtocolMessage.CALL_ENDED, "");
                cloudClient.sendMessage(endedMsg);
                break;
        }
    }

    private void answerCallReliably() {
        boolean answered = gsmController.acceptIncomingCall();
        if (!answered) {
            // Secondary media button answer fallback
            try {
                Intent buttonDown = new Intent(Intent.ACTION_MEDIA_BUTTON);
                buttonDown.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK));
                sendOrderedBroadcast(buttonDown, null);

                Intent buttonUp = new Intent(Intent.ACTION_MEDIA_BUTTON);
                buttonUp.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK));
                sendOrderedBroadcast(buttonUp, null);
                appendLog("تم إرسال أمر الرد البديل");
            } catch (Throwable t) {
                Log.e(TAG, "Media button answer fallback failed", t);
            }
        }
    }

    private void applyGatewayCallAudioRouting() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                boolean isPrivate = (swPrivateRelay == null || swPrivateRelay.isChecked());
                if (isPrivate) {
                    am.setMode(AudioManager.MODE_IN_CALL);
                    am.setSpeakerphoneOn(false);
                    am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, 1, 0);
                } else {
                    am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                    am.setSpeakerphoneOn(true);
                    int maxCall = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
                    am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, maxCall, 0);
                }
            }
        } catch (Throwable ignored) {}
    }

    private void updateKeepScreenOn(boolean keepOn) {
        try {
            if (keepOn) {
                getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } else {
                getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            }
            if (swKeepScreenOn != null && swKeepScreenOn.isChecked() != keepOn) {
                swKeepScreenOn.setChecked(keepOn);
            }
            if (swTermKeepScreenOn != null && swTermKeepScreenOn.isChecked() != keepOn) {
                swTermKeepScreenOn.setChecked(keepOn);
            }
        } catch (Throwable ignored) {}
    }

    private void onKeepScreenOnToggled(boolean isChecked) {
        if (prefs != null) {
            prefs.edit().putBoolean(PREF_KEY_KEEP_SCREEN_ON, isChecked).apply();
        }
        updateKeepScreenOn(isChecked);
        appendCallEvent(isChecked ? "تم تفعيل إبقاء الشاشة مضاءة لمنع قفل الهاتف" : "تم تعطيل إبقاء الشاشة مضاءة");
        Toast.makeText(this, isChecked ? "🔆 الشاشة ستبقى مضاءة دائماً أثناء تشغيل التطبيق" : "🌙 تم تعطيل إبقاء الشاشة مضاءة", Toast.LENGTH_SHORT).show();
    }

    private void acquireAppLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GsmRelay:AppWakeLock");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(); // Indefinite persistent lock for 24/7 screen-off operation
            }

            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && (wifiLock == null || !wifiLock.isHeld())) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "GsmRelay:AppWifiLock");
                } else {
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GsmRelay:AppWifiLock");
                }
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Throwable ignored) {}
    }

    private void releaseAppLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Throwable ignored) {}
    }

    // =========================================================================
    // TERMINAL ACTIONS
    // =========================================================================

    private void toggleTerminalConnection() {
        GsmGatewayService service = GsmGatewayService.getInstance();
        boolean isServiceActive = (service != null && service.isGatewayActive() && ProtocolMessage.ROLE_TERMINAL.equals(service.getCurrentRole()));

        // If currently in reconnecting state and user clicks button, force immediate reconnect
        if ((isTerminalConnected || isServiceActive) && (!cloudClient.isConnected() || (service != null && !service.getCloudClient().isConnected()))) {
            appendCallEvent("جاري إعادة الاتصال الفوري بالسحابة...");
            if (service != null) service.reconnectCloudNow();
            if (cloudClient != null) cloudClient.reconnectNow();
            Toast.makeText(this, "جاري إعادة الاتصال فوراً...", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!isTerminalConnected && !isServiceActive) {
            String room = etTermRoom.getText().toString().trim();
            String server = etTermServerUrl != null ? etTermServerUrl.getText().toString().trim() : "";
            if (server.isEmpty() || server.contains("echo.websocket.events") || server.contains("172.26.")) {
                server = etGwServerUrl != null ? etGwServerUrl.getText().toString().trim() : CloudClient.DEFAULT_SERVER_URL;
            }
            if (server.isEmpty() || server.contains("echo.websocket.events") || server.contains("172.26.")) {
                server = prefs.getString(PREF_KEY_SERVER, CloudClient.DEFAULT_SERVER_URL);
            }
            if (server.isEmpty() || server.contains("echo.websocket.events") || server.contains("172.26.")) {
                server = CloudClient.DEFAULT_SERVER_URL;
            }
            if (room.isEmpty()) room = "GSM-1001";

            String myExt = (etTermExtension != null) ? etTermExtension.getText().toString().trim() : "#105";
            if (myExt.isEmpty()) myExt = "#105";
            if (!myExt.startsWith("#")) myExt = "#" + myExt;

            prefs.edit().putString(PREF_KEY_ROOM, room).putString(PREF_KEY_SERVER, server).putString("terminal_extension", myExt).apply();
            checkBatteryOptimizations();

            // Start Foreground Service for Terminal mode for 24/7 background operation & screen-off persistence
            Intent serviceIntent = new Intent(this, GsmGatewayService.class);
            serviceIntent.setAction(GsmGatewayService.ACTION_START_TERMINAL);
            serviceIntent.putExtra(GsmGatewayService.EXTRA_SERVER, server);
            serviceIntent.putExtra(GsmGatewayService.EXTRA_ROOM, room);
            serviceIntent.putExtra(GsmGatewayService.EXTRA_ROLE, ProtocolMessage.ROLE_TERMINAL);
            serviceIntent.putExtra(GsmGatewayService.EXTRA_EXTENSION, myExt);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(this, serviceIntent);
            } else {
                startService(serviceIntent);
            }

            final String finalRoom = room;
            final String finalServer = server;
            final String finalExt = myExt;
            handler.postDelayed(() -> {
                GsmGatewayService activeService = GsmGatewayService.getInstance();
                if (activeService != null) {
                    activeService.startTerminal(finalServer, finalRoom, finalExt);
                    activeService.setServiceListener(gatewayServiceListener);
                }
            }, 300);

            cloudClient.connect(server, room, ProtocolMessage.ROLE_TERMINAL, myExt);
            isTerminalConnected = true;
            btnTermConnect.setText(R.string.btn_disconnect_cloud);
            btnTermConnect.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.accent_red));
        } else {
            if (service != null && ProtocolMessage.ROLE_TERMINAL.equals(service.getCurrentRole())) {
                service.stopTerminal();
            }
            cloudClient.disconnect();
            audioRelayEngine.stopRelay();
            isTerminalConnected = false;
            btnTermConnect.setText(R.string.btn_connect_cloud);
            btnTermConnect.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.accent_blue));
            dotTermGwStatus.setBackgroundResource(R.drawable.bg_status_dot_red);
            tvTermGwStatus.setText("الوسيط: غير متصل");
            tvTopStatus.setText("غير متصل");
            dotTopStatus.setBackgroundResource(R.drawable.bg_status_dot_red);
        }
    }

    private void initiateTerminalCall() {
        String number = etPhoneNumber.getText().toString().trim();
        if (number.isEmpty()) {
            Toast.makeText(this, "يرجى إدخال رقم الهاتف أولاً", Toast.LENGTH_SHORT).show();
            return;
        }

        String room = etTermRoom.getText().toString().trim();
        String targetGatewayId = null;
        String targetGatewayName = "تلقائي";

        int pos = spnTermGateways != null ? spnTermGateways.getSelectedItemPosition() : -1;
        if (pos >= 0 && pos < gatewayList.size()) {
            GatewayItem item = gatewayList.get(pos);
            if (!item.id.isEmpty()) {
                targetGatewayId = item.id;
                targetGatewayName = item.name;
                if (item.isBusy) {
                    Toast.makeText(this, "تنبيه: الهاتف الوسيط المختار مشغول بمكالمة حالياً!", Toast.LENGTH_SHORT).show();
                }
            }
        }

        ProtocolMessage dialMsg = ProtocolMessage.createDialRequest(room, number, targetGatewayId);
        cloudClient.sendMessage(dialMsg);

        showInCallView(number, "جاري طلب الرقم عبر الوسيط (" + targetGatewayName + ")...");
        appendCallEvent("تم إرسال طلب الاتصال بالرقم " + number + " عبر الوسيط: " + targetGatewayName);
        appendCallEvent("جاري فتح القناة الصوتية الثنائية المباشرة...");

        audioRelayEngine.setRole(ProtocolMessage.ROLE_TERMINAL);
        audioRelayEngine.startRelay(isTerminalSpeaker);
    }

    private void showInCallView(String number, String status) {
        isTerminalInCall = true;
        if (cardTermConnection != null) {
            cardTermConnection.setVisibility(View.GONE);
        }
        viewDialpad.setVisibility(View.GONE);
        viewInCall.setVisibility(View.VISIBLE);
        tvInCallNumber.setText(number);
        tvInCallStatus.setText(status);

        if (btnTermSpeaker != null) {
            btnTermSpeaker.setImageResource(isTerminalSpeaker ? R.drawable.ic_speaker_on : R.drawable.ic_speaker_off);
        }
        if (tvTermSpeakerLabel != null) {
            tvTermSpeakerLabel.setText(isTerminalSpeaker ? "مكبر الصوت" : "سماعة الأذن");
        }
        if (btnTermMute != null) {
            btnTermMute.setImageResource(R.drawable.ic_mic_on);
        }
        if (tvTermMuteLabel != null) {
            tvTermMuteLabel.setText("كتم");
        }

        callSeconds = 0;
        tvInCallTimer.setText("00:00");
        handler.removeCallbacks(timerRunnable);
        // Do not start call timer until CALL_CONNECTED is received!
    }

    private void endInCallView() {
        isTerminalInCall = false;
        handler.removeCallbacks(timerRunnable);
        viewInCall.setVisibility(View.GONE);
        if (cardTermConnection != null) {
            cardTermConnection.setVisibility(View.VISIBLE);
        }
        viewDialpad.setVisibility(View.VISIBLE);
        audioRelayEngine.stopRelay();
        pbTermAudioTx.setProgress(0);
        pbTermAudioRx.setProgress(0);
        appendCallEvent("تم إنهاء المكالمة وإغلاق قناة الصوت المباشر.");
    }

    private void hangupCall(String role) {
        String room = (currentMode == MODE_GATEWAY) ? etGwRoom.getText().toString().trim() : etTermRoom.getText().toString().trim();
        ProtocolMessage hangupMsg = ProtocolMessage.createHangupCall(room, role);
        cloudClient.sendMessage(hangupMsg);

        if (currentMode == MODE_GATEWAY) {
            gsmController.endCall();
            audioRelayEngine.stopRelay();
            appendCallEvent("تم إنهاء المكالمة الخلوية بواسطة الوسيط.");
        } else {
            endInCallView();
        }
    }

    // =========================================================================
    // INCOMING CLOUD MESSAGES DISPATCHER
    // =========================================================================

    private void handleIncomingCloudMessage(ProtocolMessage msg) {
        if (msg == null) return;

        if (currentMode == MODE_GATEWAY) {
            if (ProtocolMessage.TYPE_DIAL_REQUEST.equals(msg.type)) {
                appendLog("طلب اتصال وارد من الطرفي للرقم: " + msg.phoneNumber);
                appendCallEvent("تم استلام طلب اتصال من الطرفي للرقم: " + msg.phoneNumber);
                tvGwCallNumber.setText(msg.phoneNumber);
                tvGwCallStatus.setText(R.string.status_calling);
                gsmController.dialNumber(msg.phoneNumber);
                appendCallEvent("جاري إجراء المكالمة الخلوية عبر شريحة GSM...");
            } else if (ProtocolMessage.TYPE_ANSWER_CALL.equals(msg.type)) {
                appendLog("أمر قبول المكالمة وارد من الطرفي");
                appendCallEvent("تم استلام أمر قبول المكالمة من الطرفي");
                answerCallReliably();
            } else if (ProtocolMessage.TYPE_HANGUP_CALL.equals(msg.type)) {
                appendLog("أمر إنهاء المكالمة وارد من الطرفي");
                appendCallEvent("تم استلام أمر إنهاء المكالمة من الهاتف الطرفي");
                gsmController.endCall();
                audioRelayEngine.stopRelay();
            }
        } else if (currentMode == MODE_TERMINAL) {
            if ("EXTENSION_ASSIGNED".equals(msg.type)) {
                if (msg.extension != null && !msg.extension.isEmpty()) {
                    String assigned = msg.extension.startsWith("#") ? msg.extension : "#" + msg.extension;
                    if (etTermExtension != null) etTermExtension.setText(assigned);
                    if (tvTermMyExtensionBadge != null) tvTermMyExtensionBadge.setText("تحويلتي: " + assigned);
                    if (prefs != null) prefs.edit().putString("terminal_extension", assigned).apply();
                    appendCallEvent("تم تأكيد وتثبيت التحويلة الداخلية من الخادم: " + assigned);
                }
            } else if (ProtocolMessage.TYPE_GATEWAY_LIST.equals(msg.type)) {
                updateGatewayListFromMessage(msg);
            } else if (ProtocolMessage.TYPE_GATEWAY_INFO.equals(msg.type)) {
                dotTermGwStatus.setBackgroundResource(R.drawable.bg_status_dot_green);
                tvTermGwStatus.setText("الوسيط: متصل وجاهز");
                tvTermGwCarrier.setText(msg.carrier != null ? msg.carrier : "GSM Ready");
                appendCallEvent("الوسيط متصل وجاهز للخدمة (" + (msg.carrier != null ? msg.carrier : "GSM") + ")");
            } else if (ProtocolMessage.TYPE_INCOMING_CALL.equals(msg.type)) {
                appendCallEvent("تنبيه: مكالمة خلوية واردة من: " + msg.phoneNumber);
                showIncomingCallDialog(msg.phoneNumber);
            } else if (ProtocolMessage.TYPE_CALL_STATE.equals(msg.type)) {
                if (ProtocolMessage.CALL_DIALING.equals(msg.callState)) {
                    tvInCallStatus.setText("جاري طلب الرقم...");
                    tvInCallStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_blue));
                    appendCallEvent("جاري طلب الرقم عبر شريحة الهاتف الوسيط...");
                } else if (ProtocolMessage.CALL_RINGING.equals(msg.callState)) {
                    tvInCallStatus.setText("رنين الهاتف لدى الطرف الآخر...");
                    tvInCallStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_amber));
                    appendCallEvent("رنين... جاري رنين هاتف المتصل به");
                } else if (ProtocolMessage.CALL_BUSY.equals(msg.callState)) {
                    tvInCallStatus.setText("الرقم مشغول حالياً 🔴");
                    tvInCallStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_red));
                    appendCallEvent("الرقم مشغول حالياً (Line Busy) - لم يتم الاتصال");
                    handler.removeCallbacks(timerRunnable);
                    handler.postDelayed(this::endInCallView, 3000);
                } else if (ProtocolMessage.CALL_NO_ANSWER.equals(msg.callState) || ProtocolMessage.CALL_REJECTED.equals(msg.callState)) {
                    tvInCallStatus.setText("لم يتم الرد / تم الرفض 🔴");
                    tvInCallStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_red));
                    appendCallEvent("لم يتم الرد على المكالمة الخلوية");
                    handler.removeCallbacks(timerRunnable);
                    handler.postDelayed(this::endInCallView, 3000);
                } else if (ProtocolMessage.CALL_CONNECTED.equals(msg.callState)) {
                    tvInCallStatus.setText(R.string.call_connected);
                    tvInCallStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green));
                    appendCallEvent("المكالمة متصلة الآن مع الطرف الآخر! الصوت ينقل مباشرة وبوضوح.");
                    // Start call timer ONLY now when call is confirmed active!
                    callSeconds = 0;
                    tvInCallTimer.setText("00:00");
                    handler.removeCallbacks(timerRunnable);
                    handler.postDelayed(timerRunnable, 1000);
                } else if (ProtocolMessage.CALL_ENDED.equals(msg.callState)) {
                    appendCallEvent("انتهت المكالمة الخلوية وتم إغلاق الاتصال.");
                    endInCallView();
                    Toast.makeText(this, "تم إنهاء المكالمة", Toast.LENGTH_SHORT).show();
                }
            } else if (ProtocolMessage.TYPE_HANGUP_CALL.equals(msg.type)) {
                appendCallEvent("تم إنهاء المكالمة من الطرف الآخر.");
                endInCallView();
            }
        }
    }

    private void updateGatewayListFromMessage(ProtocolMessage msg) {
        handler.post(() -> {
            try {
                gatewayList.clear();
                if (msg.gatewaysRaw != null && !msg.gatewaysRaw.isEmpty()) {
                    JSONArray arr = new JSONArray(msg.gatewaysRaw);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject obj = arr.getJSONObject(i);
                        String id = obj.optString("id", "");
                        String name = obj.optString("name", "وسيط " + (i + 1));
                        String carrier = obj.optString("carrier", "GSM");
                        boolean busy = obj.optBoolean("isBusy", false);
                        gatewayList.add(new GatewayItem(id, name, carrier, busy));
                    }
                }

                if (gatewayList.isEmpty()) {
                    gatewayList.add(new GatewayItem("", "لا يوجد هواتف وسيطة متصلة بالغرفة حالياً", "--", false));
                    dotTermGwStatus.setBackgroundResource(R.drawable.bg_status_dot_red);
                    tvTermGwStatus.setText("الوسيط: غير متصل");
                    tvTermGwCarrier.setText("--");
                    appendCallEvent("تنبيه: لا يوجد هواتف وسيطة متصلة بالغرفة حالياً");
                } else {
                    int availableCount = 0;
                    for (GatewayItem item : gatewayList) {
                        if (!item.isBusy && !item.id.isEmpty()) availableCount++;
                    }
                    dotTermGwStatus.setBackgroundResource(R.drawable.bg_status_dot_green);
                    tvTermGwStatus.setText("الوسائط المتاحة: " + availableCount);
                    if (!gatewayList.get(0).id.isEmpty()) {
                        tvTermGwCarrier.setText(gatewayList.get(0).carrier);
                    }
                    appendCallEvent("تم اكتشاف " + gatewayList.size() + " جهاز وسيط في الغرفة (" + availableCount + " متاح)");
                }

                if (gatewayAdapter != null) {
                    gatewayAdapter.notifyDataSetChanged();
                }
            } catch (Throwable t) {
                Log.e(TAG, "Error parsing gateways", t);
            }
        });
    }

    private void appendCallEvent(String eventText) {
        handler.post(() -> {
            String timestamp = new java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new java.util.Date());
            String line = "[" + timestamp + "] " + eventText;
            if (tvTermCallEvents != null) {
                String current = tvTermCallEvents.getText().toString();
                tvTermCallEvents.setText(line + "\n" + current);
            }
            if (tvGwCallEvents != null) {
                String current = tvGwCallEvents.getText().toString();
                tvGwCallEvents.setText(line + "\n" + current);
            }
            appendLog(eventText);
        });
    }

    private void checkBatteryOptimizations() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                    new AlertDialog.Builder(this)
                            .setTitle("استثناء قيود توفير الطاقة 🔋")
                            .setMessage("لضمان استمرار عمل وبقاء الاتصال بالسيرفر نشطاً عند إغلاق أو قفل شاشة الهاتف دون انقطاع، يرجى استثناء التطبيق من قيود توفير البطارية.\n\nاضغط 'السماح بالعمل الدائم' ثم اختر 'السماح' في النافذة التالية.")
                            .setPositiveButton("السماح بالعمل الدائم", (dialog, which) -> {
                                try {
                                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                                    intent.setData(Uri.parse("package:" + getPackageName()));
                                    startActivity(intent);
                                } catch (Throwable t) {
                                    try {
                                        Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                                        startActivity(intent);
                                    } catch (Throwable ignored) {}
                                }
                            })
                            .setNegativeButton("لاحقاً", null)
                            .show();
                }
            } catch (Throwable t) {
                Log.w(TAG, "Battery optimizations request note: " + t.getMessage());
            }
        }
    }

    private void showIncomingCallDialog(String callerNumber) {
        if (incomingCallDialog != null && incomingCallDialog.isShowing()) {
            incomingCallDialog.dismiss();
        }

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_incoming_call, null);
        TextView tvCaller = dialogView.findViewById(R.id.tv_dialog_caller_number);
        Button btnReject = dialogView.findViewById(R.id.btn_dialog_reject);
        Button btnAccept = dialogView.findViewById(R.id.btn_dialog_accept);

        boolean isIntercom = callerNumber != null && callerNumber.startsWith("#");
        if (isIntercom) {
            tvCaller.setText("اتصال داخلي من التحويلة: " + callerNumber);
        } else {
            tvCaller.setText(callerNumber == null || callerNumber.isEmpty() ? "رقم غير معروف" : callerNumber);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setView(dialogView);
        builder.setCancelable(false);
        incomingCallDialog = builder.create();

        btnAccept.setOnClickListener(v -> {
            incomingCallDialog.dismiss();
            String room = etTermRoom.getText().toString().trim();
            ProtocolMessage answerMsg = ProtocolMessage.createAnswerCall(room);
            cloudClient.sendMessage(answerMsg);
            showInCallView(callerNumber, "المكالمة متصلة - جاري البث");
            audioRelayEngine.startRelay(isTerminalSpeaker);
        });

        btnReject.setOnClickListener(v -> {
            incomingCallDialog.dismiss();
            hangupCall(ProtocolMessage.ROLE_TERMINAL);
        });

        incomingCallDialog.show();
    }

    private void appendLog(String message) {
        handler.post(() -> {
            if (tvGwLogs != null) {
                String timestamp = new java.text.SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new java.util.Date());
                String entry = "[" + timestamp + "] " + message + "\n";
                tvGwLogs.append(entry);
            }
        });
    }

    private List<String> getMissingPermissions() {
        List<String> needed = new ArrayList<>();
        String[] permissions = {
                Manifest.permission.CALL_PHONE,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.MODIFY_AUDIO_SETTINGS,
                Manifest.permission.SEND_SMS
        };

        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                needed.add(perm);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.ANSWER_PHONE_CALLS);
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        return needed;
    }

    private void updatePermissionsUI() {
        List<String> missing = getMissingPermissions();
        if (bannerPermissions != null) {
            bannerPermissions.setVisibility(missing.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    private void checkAndRequestPermissions() {
        updatePermissionsUI();
        List<String> missing = getMissingPermissions();
        if (!missing.isEmpty()) {
            showPermissionRationaleAndRequest();
        }
    }

    private void showPermissionRationaleAndRequest() {
        List<String> missing = getMissingPermissions();
        if (missing.isEmpty()) {
            Toast.makeText(this, "✅ جميع الصلاحيات المطلوبة مفعلة وممنوحة بنجاح", Toast.LENGTH_SHORT).show();
            updatePermissionsUI();
            return;
        }

        new AlertDialog.Builder(this)
                .setTitle("صلاحيات بوابة الاتصال الصوتي و SMS 🛡️")
                .setMessage("لضمان عمل الهاتف كوسيط اتصالات خلوية وبث صوتي فائق الوضوح وإرسال الرسائل، يرجى الموافقة على الصلاحيات المطلوبة:\n\n" +
                        "📞 المكالمات الهاتفية: للتحكم في إجراء واستقبال المكالمات عن بُعد.\n" +
                        "🎙️ الميكروفون: لنقل وتسجيل صوت المكالمة في الاتجاهين بجودة نقية.\n" +
                        "✉️ الرسائل النصية SMS: لإرسال واستقبال الرسائل النصية عبر الشريحة عن بُعد.\n" +
                        "🔔 الإشعارات والعمل في الخلفية: لبقاء الخدمة متصلة 24/7 دون توقف عند قفل الشاشة.\n\n" +
                        "اضغط 'متابعة ومنح الصلاحيات' ثم اختر 'السماح' لجميع النوافذ التالية.")
                .setPositiveButton("متابعة ومنح الصلاحيات", (dialog, which) -> {
                    ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), PERMISSION_REQ_CODE);
                })
                .setNegativeButton("تخطي الآن", null)
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE) {
            gsmController.checkSimStatus();
            updatePermissionsUI();

            List<String> stillMissing = getMissingPermissions();
            if (!stillMissing.isEmpty()) {
                new AlertDialog.Builder(this)
                        .setTitle("تنبيه: الصلاحيات غير مكتملة ⚠️")
                        .setMessage("لم يتم منح بعض الصلاحيات الضرورية (مثل المكالمات أو الصوت أو SMS).\n\n" +
                                "يمكنك تفعيلها الآن بالضغط على 'إعدادات التطبيق' ثم الدخول إلى 'الأذونات' وتفعيلها.")
                        .setPositiveButton("فتح إعدادات التطبيق", (dialog, which) -> {
                            try {
                                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                intent.setData(Uri.parse("package:" + getPackageName()));
                                startActivity(intent);
                            } catch (Throwable t) {
                                Toast.makeText(this, "تعذر فتح الإعدادات تلقائياً", Toast.LENGTH_SHORT).show();
                            }
                        })
                        .setNegativeButton("لاحقاً", null)
                        .show();
            } else {
                Toast.makeText(this, "✅ تم تفعيل جميع الصلاحيات بنجاح!", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean keepScreenOn = (prefs != null) ? prefs.getBoolean(PREF_KEY_KEEP_SCREEN_ON, true) : true;
        updateKeepScreenOn(keepScreenOn);
        acquireAppLocks();
        updatePermissionsUI();
        GsmGatewayService service = GsmGatewayService.getInstance();
        if (service != null && service.isGatewayActive()) {
            service.setServiceListener(gatewayServiceListener);
            if (ProtocolMessage.ROLE_TERMINAL.equals(service.getCurrentRole())) {
                isTerminalConnected = true;
                if (btnTermConnect != null) {
                    btnTermConnect.setText(R.string.btn_disconnect_cloud);
                    btnTermConnect.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.accent_red));
                }
                if (dotTopStatus != null) dotTopStatus.setBackgroundResource(R.drawable.bg_status_dot_green);
                if (tvTopStatus != null) tvTopStatus.setText("متصل بالسحابة (طرفي 24/7)");
                if (dotTermGwStatus != null) dotTermGwStatus.setBackgroundResource(R.drawable.bg_status_dot_green);
                if (tvTermGwStatus != null) tvTermGwStatus.setText("الطرفي: متصل 24/7");
            } else {
                isGatewayRunning = true;
                if (btnGwToggleService != null) {
                    btnGwToggleService.setText(R.string.btn_stop_gateway);
                    btnGwToggleService.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.accent_red));
                }
                if (dotTopStatus != null) dotTopStatus.setBackgroundResource(R.drawable.bg_status_dot_green);
                if (tvTopStatus != null) tvTopStatus.setText("متصل بالسحابة (خدمة نشطة)");
                if (etGwRoom != null) etGwRoom.setText(service.getCurrentRoom());
                if (etGwServerUrl != null) etGwServerUrl.setText(service.getCurrentServer());
                if (tvGwCarrier != null) tvGwCarrier.setText(service.getSimCarrier() + (service.isSimReady() ? " (جاهز)" : ""));
                if (swSmsRelay != null) swSmsRelay.setChecked(service.isSmsRelayEnabled());
                if (btnStreamAudioTest != null) {
                    if (service.isAudioTestStreaming()) {
                        btnStreamAudioTest.setText("⏹️ إيقاف بث النغمة للطرفي (Test Active)");
                        btnStreamAudioTest.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.accent_amber));
                    } else {
                        btnStreamAudioTest.setText("📡 بث نغمة وصوت تجريبي إلى الطرفي (Test Stream)");
                        btnStreamAudioTest.setBackgroundTintList(ContextCompat.getColorStateList(this, R.color.accent_blue));
                    }
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Detach UI listener from service, NEVER kill background gateway/terminal service!
        GsmGatewayService service = GsmGatewayService.getInstance();
        if (service != null) {
            service.setServiceListener(null);
        }
        handler.removeCallbacks(timerRunnable);
    }
}
