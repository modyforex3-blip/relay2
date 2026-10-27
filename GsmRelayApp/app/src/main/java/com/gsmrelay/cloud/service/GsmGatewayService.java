package com.gsmrelay.cloud.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.KeyEvent;

import androidx.core.app.NotificationCompat;

import com.gsmrelay.cloud.MainActivity;
import com.gsmrelay.cloud.audio.AudioRelayEngine;
import com.gsmrelay.cloud.network.CloudClient;
import com.gsmrelay.cloud.protocol.ProtocolMessage;
import com.gsmrelay.cloud.telephony.GsmTelephonyController;
import com.gsmrelay.cloud.telephony.SmsController;

public class GsmGatewayService extends Service {
    private static final String TAG = "GsmGatewayService";
    private static final String CHANNEL_ID = "gsm_gateway_service_channel";
    private static final int NOTIF_ID = 2001;

    public static final String ACTION_START = "com.gsmrelay.action.START";
    public static final String ACTION_START_GATEWAY = "com.gsmrelay.action.START_GATEWAY";
    public static final String ACTION_START_TERMINAL = "com.gsmrelay.action.START_TERMINAL";
    public static final String ACTION_STOP = "com.gsmrelay.action.STOP";
    public static final String EXTRA_SERVER = "extra_server";
    public static final String EXTRA_ROOM = "extra_room";
    public static final String EXTRA_ROLE = "extra_role";
    public static final String EXTRA_EXTENSION = "extra_extension";

    private static volatile GsmGatewayService instance;

    public static GsmGatewayService getInstance() {
        return instance;
    }

    public static boolean isRunning() {
        return instance != null && instance.isServiceRunning;
    }

    public interface GatewayServiceListener {
        void onCloudStatusChanged(boolean connected, String status);
        void onGsmCallStateChanged(int state, String incomingNumber);
        void onSimStatusChanged(boolean ready, String carrier);
        void onTxAudioLevel(float level);
        void onRxAudioLevel(float level);
        void onLatencyUpdated(long latencyMs);
        void onLogMessage(String log);
        void onCallEvent(String event);
        default void onIncomingCloudMessage(ProtocolMessage message) {}
    }

    private final IBinder binder = new LocalBinder();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private CloudClient cloudClient;
    private AudioRelayEngine audioRelayEngine;
    private GsmTelephonyController gsmController;
    private SmsController smsController;
    private GatewayServiceListener serviceListener;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    private boolean isServiceRunning = false;
    private String currentRole = ProtocolMessage.ROLE_GATEWAY;
    private boolean isGsmCallActive = false;
    private boolean isAutoAnswer = true;
    private boolean isSpeakerRelay = false;
    private boolean isPrivateRelay = true;
    private boolean isSilentGateway = true;
    private boolean isAudioBoost = true;
    private boolean isBluetoothSco = false;
    private boolean isSmsRelayEnabled = true;

    private String currentServerUrl = CloudClient.DEFAULT_SERVER_URL;
    private String currentRoom = "GSM-1001";
    private String terminalExtension = "#105";
    private String gatewayId;
    private String gatewayName;
    private String currentCallNumber = "";

    // Bluetooth SCO broadcast receiver
    private final BroadcastReceiver scoReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1);
            if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
                notifyLog("تم تفعيل وتوصيل قناة صوت البلوتوث (SCO Connected)");
                notifyCallEvent("قناة صوت البلوتوث متصلة وجاهزة لتمرير المكالمة بدقة عالية");
            } else if (state == AudioManager.SCO_AUDIO_STATE_DISCONNECTED) {
                notifyLog("تم فصل قناة صوت البلوتوث (SCO Disconnected)");
            }
        }
    };

    // Screen Off/On receiver for 100% 24/7 background persistence
    private final BroadcastReceiver screenAndPowerReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null) return;
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                notifyLog("🔒 تم إغلاق أو قفل شاشة الهاتف - تعزيز أقفال العمل في الخلفية 24/7");
                acquireLocks();
                if (cloudClient != null) {
                    if (cloudClient.isConnected()) {
                        cloudClient.sendMessage(ProtocolMessage.createPing(currentRoom));
                    } else if (isServiceRunning) {
                        cloudClient.connect(currentServerUrl, currentRoom, currentRole, gatewayId, gatewayName, (gsmController != null ? gsmController.getSimCarrier() : null), terminalExtension);
                    }
                }
            } else if (Intent.ACTION_SCREEN_ON.equals(action) || Intent.ACTION_USER_PRESENT.equals(action)) {
                acquireLocks();
                if (cloudClient != null && !cloudClient.isConnected() && isServiceRunning) {
                    notifyLog("🔓 الشاشة نشطة - التحقق من سلامة الاتصال بالسحابة");
                    cloudClient.connect(currentServerUrl, currentRoom, currentRole, gatewayId, gatewayName, (gsmController != null ? gsmController.getSimCarrier() : null), terminalExtension);
                }
            }
        }
    };

    // Continuous audio routing & silence enforcer during call
    private final Runnable audioRoutingEnforcerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isGsmCallActive && isServiceRunning && ProtocolMessage.ROLE_GATEWAY.equals(currentRole)) {
                enforceAudioRouting();
                handler.postDelayed(this, 1200);
            }
        }
    };

    private void enforceAudioRouting() {
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            if (am != null) {
                am.setMicrophoneMute(false);

                if (isBluetoothSco) {
                    if (am.isBluetoothScoAvailableOffCall() || am.isBluetoothA2dpOn()) {
                        if (!am.isBluetoothScoOn()) {
                            am.startBluetoothSco();
                            am.setBluetoothScoOn(true);
                        }
                    }
                    if (am.isSpeakerphoneOn()) {
                        am.setSpeakerphoneOn(false);
                    }
                    int maxCall = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
                    am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (int)(maxCall * 0.85f), 0);
                } else if (isPrivateRelay || isSilentGateway) {
                    if (am.isBluetoothScoOn()) {
                        am.setBluetoothScoOn(false);
                        am.stopBluetoothSco();
                    }
                    if (am.isSpeakerphoneOn()) {
                        am.setSpeakerphoneOn(false);
                    }
                    am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, 1, 0);
                } else {
                    // Smart High-Gain Acoustic Bridge
                    if (am.isBluetoothScoOn()) {
                        am.setBluetoothScoOn(false);
                        am.stopBluetoothSco();
                    }
                    if (am.getMode() != AudioManager.MODE_IN_COMMUNICATION) {
                        am.setMode(AudioManager.MODE_IN_COMMUNICATION);
                    }
                    if (!am.isSpeakerphoneOn()) {
                        am.setSpeakerphoneOn(true);
                    }
                    int maxCall = am.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL);
                    int targetVol = (int) (maxCall * 0.82f);
                    am.setStreamVolume(AudioManager.STREAM_VOICE_CALL, Math.max(1, targetVol), 0);
                }
            }
        } catch (Throwable ignored) {}
    }

    public class LocalBinder extends Binder {
        public GsmGatewayService getService() {
            return GsmGatewayService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        gatewayId = "GW-" + (Build.MODEL != null ? Build.MODEL.replaceAll("[^a-zA-Z0-9]", "") : "DEVICE") + "-" + (int)(1000 + Math.random() * 9000);
        gatewayName = (Build.MANUFACTURER != null ? Build.MANUFACTURER.toUpperCase() : "GSM") + " " + (Build.MODEL != null ? Build.MODEL : "Gateway");

        createNotificationChannel();
        acquireLocks();
        initCoreEngines();

        try {
            registerReceiver(scoReceiver, new IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED));
        } catch (Throwable ignored) {}

        try {
            IntentFilter screenFilter = new IntentFilter();
            screenFilter.addAction(Intent.ACTION_SCREEN_OFF);
            screenFilter.addAction(Intent.ACTION_SCREEN_ON);
            screenFilter.addAction(Intent.ACTION_USER_PRESENT);
            registerReceiver(screenAndPowerReceiver, screenFilter);
        } catch (Throwable ignored) {}
    }

    private void initCoreEngines() {
        cloudClient = new CloudClient(this);
        audioRelayEngine = new AudioRelayEngine(this);
        audioRelayEngine.setRole(currentRole);
        audioRelayEngine.setAudioBoost(isAudioBoost);

        gsmController = new GsmTelephonyController(this);
        smsController = new SmsController(this);

        audioRelayEngine.setAudioListener(new AudioRelayEngine.AudioListener() {
            @Override
            public void onAudioCaptured(byte[] data, int offset, int length) {
                if (cloudClient != null) {
                    cloudClient.sendAudioData(data, offset, length);
                }
            }

            @Override
            public void onTxLevel(float level) {
                if (serviceListener != null) {
                    serviceListener.onTxAudioLevel(level);
                }
            }

            @Override
            public void onRxLevel(float level) {
                if (serviceListener != null) {
                    serviceListener.onRxAudioLevel(level);
                }
            }

            @Override
            public void onSourceSwitched(String sourceName, boolean locked) {
                if (locked) {
                    notifyLog("✅ تم تثبيت التقاط الصوت بنجاح عبر: " + sourceName);
                    notifyCallEvent("🎤 نقل الصوت نشط الآن بنجاح عبر: " + sourceName);
                } else {
                    notifyLog("🔄 تجربة مصدر التقاط بديل: " + sourceName);
                    notifyCallEvent("🔄 جاري فحص مصدر الصوت: " + sourceName);
                }
            }
        });

        gsmController.setListener(new GsmTelephonyController.TelephonyListener() {
            @Override
            public void onCallStateChanged(int state, String incomingNumber) {
                // Handled in granular callback below
            }

            @Override
            public void onGranularCallStateChanged(String granularState, String phoneNumber, String details) {
                handler.post(() -> handleGranularCallState(granularState, phoneNumber, details));
            }

            @Override
            public void onSimStatusChanged(boolean ready, String carrier) {
                handler.post(() -> {
                    if (serviceListener != null) {
                        serviceListener.onSimStatusChanged(ready, carrier);
                    }
                });
            }
        });

        cloudClient.setListener(new CloudClient.CloudListener() {
            @Override
            public void onConnected() {
                updateNotification("متصل بالسحابة", "الغرفة: " + currentRoom + (ProtocolMessage.ROLE_TERMINAL.equals(currentRole) ? " | الطرفي متصل 24/7" : " | الوسيط جاهز 24/7"));
                if (ProtocolMessage.ROLE_GATEWAY.equals(currentRole)) {
                    sendGatewayAnnouncement();
                    notifyCallEvent("تم الاتصال بالسحابة بنجاح [الغرفة: " + currentRoom + "] - بانتظار اتصالات الطرفي");
                } else {
                    notifyCallEvent("تم الاتصال بالسحابة بنجاح [الغرفة: " + currentRoom + "] - الطرفي متصل ويعمل 24/7");
                }
                if (serviceListener != null) {
                    serviceListener.onCloudStatusChanged(true, "متصل بالسحابة");
                }
            }

            @Override
            public void onDisconnected(String reason) {
                updateNotification("غير متصل", "جاري إعادة الاتصال بالسحابة تلقائياً...");
                notifyCallEvent("انقطع الاتصال بالسحابة مؤقتاً: " + reason + " (جاري إعادة الاتصال تلقائياً)");
                if (serviceListener != null) {
                    serviceListener.onCloudStatusChanged(false, "جاري إعادة الاتصال تلقائياً...");
                }
            }

            @Override
            public void onReconnecting(int attempt, long delayMs) {
                updateNotification("جاري إعادة الاتصال...", "محاولة رقم " + attempt + " | الغرفة: " + currentRoom);
                notifyLog("محاولة إعادة الاتصال بالسحابة #" + attempt);
                if (serviceListener != null) {
                    serviceListener.onCloudStatusChanged(false, "جاري إعادة الاتصال بالسحابة (" + attempt + ")...");
                }
            }

            @Override
            public void onMessageReceived(ProtocolMessage message) {
                if (serviceListener != null) {
                    serviceListener.onIncomingCloudMessage(message);
                }
                handleIncomingCloudMessage(message);
            }

            @Override
            public void onAudioReceived(byte[] audioData) {
                if (audioRelayEngine != null && audioData != null) {
                    if (ProtocolMessage.ROLE_GATEWAY.equals(currentRole)) {
                        if (isGsmCallActive) {
                            audioRelayEngine.playIncomingAudio(audioData, 0, audioData.length);
                        }
                    }
                }
            }

            @Override
            public void onLatencyUpdated(long latencyMs) {
                if (serviceListener != null) {
                    serviceListener.onLatencyUpdated(latencyMs);
                }
            }

            @Override
            public void onLog(String log) {
                notifyLog(log);
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                stopGateway();
                return START_NOT_STICKY;
            }

            String server = intent.getStringExtra(EXTRA_SERVER);
            String room = intent.getStringExtra(EXTRA_ROOM);
            String role = intent.getStringExtra(EXTRA_ROLE);

            if (server != null && !server.trim().isEmpty()) {
                currentServerUrl = server.trim();
            }
            if (room != null && !room.trim().isEmpty()) {
                currentRoom = room.trim();
            }
            String ext = intent.getStringExtra(EXTRA_EXTENSION);
            if (ext != null && !ext.trim().isEmpty()) {
                terminalExtension = ext.trim().startsWith("#") ? ext.trim() : "#" + ext.trim();
            }

            if (ACTION_START_TERMINAL.equals(action) || ProtocolMessage.ROLE_TERMINAL.equals(role)) {
                currentRole = ProtocolMessage.ROLE_TERMINAL;
            } else {
                currentRole = ProtocolMessage.ROLE_GATEWAY;
            }
        }

        startInForeground();
        acquireLocks();
        return START_STICKY;
    }

    public synchronized void setTerminalExtension(String ext) {
        if (ext != null && !ext.trim().isEmpty()) {
            this.terminalExtension = ext.trim().startsWith("#") ? ext.trim() : "#" + ext.trim();
            if (cloudClient != null) {
                cloudClient.setExtension(this.terminalExtension);
            }
        }
    }

    public String getTerminalExtension() {
        return terminalExtension;
    }

    public synchronized void startGateway(String serverUrl, String room, boolean autoAnswer, boolean speakerRelay, boolean audioBoost, boolean bluetoothSco) {
        startGateway(serverUrl, room, autoAnswer, speakerRelay, audioBoost, bluetoothSco, false);
    }

    public synchronized void startGateway(String serverUrl, String room, boolean autoAnswer, boolean speakerRelay, boolean audioBoost, boolean bluetoothSco, boolean silentGateway) {
        startGateway(serverUrl, room, autoAnswer, speakerRelay, audioBoost, bluetoothSco, silentGateway, true);
    }

    public synchronized void startGateway(String serverUrl, String room, boolean autoAnswer, boolean speakerRelay, boolean audioBoost, boolean bluetoothSco, boolean silentGateway, boolean smsRelay) {
        this.currentRole = ProtocolMessage.ROLE_GATEWAY;
        this.currentServerUrl = (serverUrl != null && !serverUrl.trim().isEmpty()) ? serverUrl.trim() : CloudClient.DEFAULT_SERVER_URL;
        this.currentRoom = (room != null && !room.trim().isEmpty()) ? room.trim() : "GSM-1001";
        this.isAutoAnswer = autoAnswer;
        this.isSpeakerRelay = speakerRelay;
        this.isPrivateRelay = silentGateway;
        this.isSilentGateway = silentGateway;
        this.isAudioBoost = audioBoost;
        this.isBluetoothSco = bluetoothSco;
        this.isSmsRelayEnabled = smsRelay;

        acquireLocks();
        startInForeground();

        if (audioRelayEngine != null) {
            audioRelayEngine.setRole(ProtocolMessage.ROLE_GATEWAY);
            audioRelayEngine.setPrivateRelay(silentGateway);
            audioRelayEngine.setAudioBoost(audioBoost);
            if (bluetoothSco) {
                audioRelayEngine.setBluetoothSco(true);
            }
        }

        if (gsmController != null) {
            gsmController.registerCallListener();
        }

        if (cloudClient != null) {
            cloudClient.connect(currentServerUrl, currentRoom, ProtocolMessage.ROLE_GATEWAY, gatewayId, gatewayName, gsmController.getSimCarrier());
        }

        isServiceRunning = true;
        notifyLog("بدأت خدمة الوسيط الدائمة 24/7 على الغرفة: " + currentRoom + (silentGateway ? " [الوضع الصامت]" : ""));
        notifyCallEvent("بدأت خدمة الوسيط الدائمة 24/7 على الغرفة: " + currentRoom + (silentGateway ? " [الوضع الصامت المانع للصوت المحلي]" : ""));
        checkNetworkVoLteSafety();
    }

    private void checkNetworkVoLteSafety() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null) {
                android.net.Network activeNet = cm.getActiveNetwork();
                if (activeNet != null) {
                    android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(activeNet);
                    if (caps != null) {
                        if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) {
                            notifyLog("🌐 نوع الاتصال: Wi-Fi (مثالي ومستقر 100% للمكالمات الصوتية)");
                        } else if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) {
                            notifyLog("⚠️ تنبيه: هاتف الوسيط متصل عبر بيانات الشريحة (Mobile Data)");
                            notifyCallEvent("⚠️ تنبيه: هاتف الوسيط متصل عبر بيانات الشريحة. تأكد من تفعيل VoLTE أو ربط الهاتف بشبكة Wi-Fi لضمان عدم فصل الإنترنت أثناء المكالمات الخلوية.");
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
    }

    public synchronized void startTerminal(String serverUrl, String room) {
        startTerminal(serverUrl, room, terminalExtension);
    }

    public synchronized void startTerminal(String serverUrl, String room, String extension) {
        this.currentRole = ProtocolMessage.ROLE_TERMINAL;
        this.currentServerUrl = (serverUrl != null && !serverUrl.trim().isEmpty()) ? serverUrl.trim() : CloudClient.DEFAULT_SERVER_URL;
        this.currentRoom = (room != null && !room.trim().isEmpty()) ? room.trim() : "GSM-1001";
        if (extension != null && !extension.trim().isEmpty()) {
            this.terminalExtension = extension.trim().startsWith("#") ? extension.trim() : "#" + extension.trim();
        }

        acquireLocks();
        startInForeground();

        if (audioRelayEngine != null) {
            audioRelayEngine.setRole(ProtocolMessage.ROLE_TERMINAL);
            audioRelayEngine.setSilentGateway(false);
            audioRelayEngine.setAudioBoost(false);
            audioRelayEngine.stopRelay();
        }

        if (cloudClient != null) {
            cloudClient.connect(currentServerUrl, currentRoom, ProtocolMessage.ROLE_TERMINAL, gatewayId, gatewayName, null, terminalExtension);
        }

        isServiceRunning = true;
        notifyLog("بدأت خدمة الهاتف الطرفي (" + terminalExtension + ") الدائمة 24/7 على الغرفة: " + currentRoom);
        notifyCallEvent("بدأت خدمة الهاتف الطرفي (" + terminalExtension + ") الدائمة 24/7 على الغرفة: " + currentRoom);
    }

    public synchronized void stopGateway() {
        isServiceRunning = false;
        handler.removeCallbacks(audioRoutingEnforcerRunnable);

        if (audioRelayEngine != null) {
            audioRelayEngine.stopAudioTestStream();
            audioRelayEngine.stopRelay();
            audioRelayEngine.setBluetoothSco(false);
        }

        if (cloudClient != null) {
            cloudClient.disconnect();
        }

        if (gsmController != null) {
            gsmController.unregisterCallListener();
        }

        releaseLocks();
        stopForeground(true);
        stopSelf();

        notifyLog("تم إيقاف خدمة البوابة/الطرفي بالكامل");
        notifyCallEvent("تم إيقاف الخدمة بالكامل");

        if (serviceListener != null) {
            serviceListener.onCloudStatusChanged(false, "تم إيقاف الخدمة");
        }
    }

    public synchronized void stopTerminal() {
        stopGateway();
    }

    public void reconnectCloudNow() {
        if (cloudClient != null) {
            cloudClient.reconnectNow();
        }
    }

    public void setPrivateRelay(boolean privateRelay) {
        this.isPrivateRelay = privateRelay;
        this.isSilentGateway = privateRelay;
        if (privateRelay) {
            this.isSpeakerRelay = false;
        }
        if (audioRelayEngine != null) {
            audioRelayEngine.setPrivateRelay(privateRelay);
        }
        enforceAudioRouting();
    }

    public boolean isPrivateRelay() {
        return isPrivateRelay;
    }

    public void setSilentGateway(boolean silent) {
        setPrivateRelay(silent);
    }

    public boolean isSilentGateway() {
        return isPrivateRelay;
    }

    public void startAudioTestStream() {
        if (audioRelayEngine != null) {
            audioRelayEngine.startAudioTestStream();
            notifyLog("بدأ بث نغمة وصوت الفحص إلى الطرفي عبر السحابة...");
            notifyCallEvent("بدأ بث نغمة الفحص التجريبي إلى الطرفي - راقب سماع الصوت في الطرفي");
        }
    }

    public void stopAudioTestStream() {
        if (audioRelayEngine != null) {
            audioRelayEngine.stopAudioTestStream();
            notifyLog("تم إيقاف بث نغمة الفحص");
            notifyCallEvent("تم إيقاف بث نغمة الفحص");
        }
    }

    public boolean isAudioTestStreaming() {
        return audioRelayEngine != null && audioRelayEngine.isAudioTestStreaming();
    }

    public void setAudioBoost(boolean boost) {
        this.isAudioBoost = boost;
        if (audioRelayEngine != null) {
            audioRelayEngine.setAudioBoost(boost);
        }
    }

    public void setBluetoothSco(boolean enable) {
        this.isBluetoothSco = enable;
        if (audioRelayEngine != null) {
            audioRelayEngine.setBluetoothSco(enable);
        }
    }

    public void setCustomAudioSource(int source) {
        if (audioRelayEngine != null) {
            audioRelayEngine.setCustomAudioSource(source);
        }
    }

    public void setAutoAnswer(boolean autoAnswer) {
        this.isAutoAnswer = autoAnswer;
    }

    public void setSpeakerRelay(boolean speakerRelay) {
        this.isSpeakerRelay = speakerRelay;
    }

    public void setSmsRelayEnabled(boolean enabled) {
        this.isSmsRelayEnabled = enabled;
        notifyLog("ترحيل الرسائل النصية SMS: " + (enabled ? "مفعل" : "معطل"));
    }

    public boolean isSmsRelayEnabled() {
        return isSmsRelayEnabled;
    }

    public void handleIncomingSms(String sender, String body, long timestamp) {
        if (!isSmsRelayEnabled) {
            Log.i(TAG, "SMS relay disabled, ignoring incoming SMS from: " + sender);
            return;
        }
        notifyLog("رسالة SMS واردة من: " + sender);
        notifyCallEvent("📩 استلام رسالة نصية SMS من (" + sender + ") - جاري ترحيلها للسحابة");
        if (cloudClient != null && cloudClient.isConnected()) {
            ProtocolMessage smsMsg = ProtocolMessage.createSmsIncoming(currentRoom, gatewayId, sender, body, timestamp);
            cloudClient.sendMessage(smsMsg);
        }
    }

    public void setServiceListener(GatewayServiceListener listener) {
        this.serviceListener = listener;
        if (listener != null) {
            boolean connected = (cloudClient != null && cloudClient.isConnected());
            String status = connected ? "متصل بالسحابة" : (cloudClient != null && cloudClient.isShouldReconnect() ? "جاري إعادة الاتصال تلقائياً..." : "غير متصل");
            listener.onCloudStatusChanged(connected, status);
            if (gsmController != null) {
                listener.onSimStatusChanged(gsmController.isSimReady(), gsmController.getSimCarrier());
            }
        }
    }

    private void handleGranularCallState(String state, String incomingNumber, String details) {
        if (incomingNumber != null && !incomingNumber.isEmpty()) {
            currentCallNumber = incomingNumber;
        }

        switch (state) {
            case ProtocolMessage.CALL_DIALING:
                isGsmCallActive = true;
                notifyCallEvent("جاري طلب الرقم عبر الشريحة: " + currentCallNumber);
                notifyLog("طلب رقم: " + currentCallNumber);
                enforceAudioRouting();
                handler.post(audioRoutingEnforcerRunnable);
                if (audioRelayEngine != null) {
                    audioRelayEngine.setRole(ProtocolMessage.ROLE_GATEWAY);
                    audioRelayEngine.setPrivateRelay(isPrivateRelay);
                    audioRelayEngine.startRelay(isSpeakerRelay || !isPrivateRelay);
                }
                if (cloudClient != null) {
                    ProtocolMessage msg = ProtocolMessage.createCallState(currentRoom, ProtocolMessage.CALL_DIALING, currentCallNumber, details != null ? details : "جاري طلب الرقم...");
                    cloudClient.sendMessage(msg);
                }
                break;

            case ProtocolMessage.CALL_RINGING:
                notifyCallEvent("رنين... الهاتف يرن لدى الطرف الآخر");
                notifyLog("رنين: " + currentCallNumber);
                if (isAutoAnswer && !isGsmCallActive) {
                    notifyCallEvent("جاري الرد التلقائي على المكالمة الواردة...");
                    handler.postDelayed(this::answerCallReliably, 1200);
                }
                if (audioRelayEngine != null) {
                    audioRelayEngine.setRole(ProtocolMessage.ROLE_GATEWAY);
                    audioRelayEngine.setPrivateRelay(isPrivateRelay);
                    audioRelayEngine.startRelay(isSpeakerRelay || !isPrivateRelay);
                }
                if (cloudClient != null) {
                    ProtocolMessage msg = ProtocolMessage.createCallState(currentRoom, ProtocolMessage.CALL_RINGING, currentCallNumber, details != null ? details : "جاري رنين الهاتف لدى الطرف الآخر...");
                    cloudClient.sendMessage(msg);
                }
                break;

            case ProtocolMessage.CALL_BUSY:
                isGsmCallActive = false;
                handler.removeCallbacks(audioRoutingEnforcerRunnable);
                if (audioRelayEngine != null) audioRelayEngine.stopRelay();

                notifyCallEvent("⚠️ الرقم مشغول حالياً (Line Busy) 🔴");
                notifyLog("الرقم مشغول: " + currentCallNumber);

                if (cloudClient != null) {
                    ProtocolMessage msg = ProtocolMessage.createCallState(currentRoom, ProtocolMessage.CALL_BUSY, currentCallNumber, "الرقم مشغول حالياً (Line Busy)");
                    cloudClient.sendMessage(msg);
                }
                handler.postDelayed(() -> gsmController.endCall(), 1200);
                break;

            case ProtocolMessage.CALL_NO_ANSWER:
            case ProtocolMessage.CALL_REJECTED:
                isGsmCallActive = false;
                handler.removeCallbacks(audioRoutingEnforcerRunnable);
                if (audioRelayEngine != null) audioRelayEngine.stopRelay();

                notifyCallEvent(state.equals(ProtocolMessage.CALL_REJECTED) ? "تم رفض المكالمة من الطرف الآخر" : "لم يتم الرد على المكالمة");
                notifyLog("لم يتم الرد / تم الرفض: " + currentCallNumber);

                if (cloudClient != null) {
                    ProtocolMessage msg = ProtocolMessage.createCallState(currentRoom, state, currentCallNumber, details != null ? details : "لم يتم الرد على المكالمة");
                    cloudClient.sendMessage(msg);
                }
                handler.postDelayed(() -> gsmController.endCall(), 1200);
                break;

            case ProtocolMessage.CALL_CONNECTED:
                isGsmCallActive = true;
                notifyCallEvent("المكالمة متصلة (جارية الآن)! نقل الصوت عبر " + (isPrivateRelay ? "الترحيل الصامت 🔒" : "جسر مكبر الصوت عالي الحساسية 🔊"));
                notifyLog("المكالمة متصلة - بدء نقل الصوت للطرفي عبر " + (isPrivateRelay ? "الوضع الصامت" : "جسر مكبر الصوت عالي الحساسية"));

                enforceAudioRouting();
                handler.post(audioRoutingEnforcerRunnable);

                if (audioRelayEngine != null) {
                    audioRelayEngine.setRole(ProtocolMessage.ROLE_GATEWAY);
                    audioRelayEngine.setPrivateRelay(isPrivateRelay);
                    audioRelayEngine.startRelay(isSpeakerRelay || !isPrivateRelay);
                }

                if (cloudClient != null) {
                    ProtocolMessage connectedMsg = ProtocolMessage.createCallState(currentRoom, ProtocolMessage.CALL_CONNECTED, currentCallNumber, details != null ? details : "المكالمة متصلة (جارية)");
                    cloudClient.sendMessage(connectedMsg);
                }
                break;

            case ProtocolMessage.CALL_ENDED:
                isGsmCallActive = false;
                handler.removeCallbacks(audioRoutingEnforcerRunnable);
                notifyCallEvent("انتهت المكالمة الخلوية وإغلاق بث الصوت.");
                notifyLog("انتهت المكالمة الخلوية");

                if (audioRelayEngine != null) {
                    audioRelayEngine.stopRelay();
                }

                if (cloudClient != null) {
                    ProtocolMessage endedMsg = ProtocolMessage.createCallState(currentRoom, ProtocolMessage.CALL_ENDED, "", details != null ? details : "تم إنهاء المكالمة الخلوية");
                    cloudClient.sendMessage(endedMsg);
                }
                currentCallNumber = "";
                break;
        }

        if (serviceListener != null) {
            int legacyState = isGsmCallActive ? TelephonyManager.CALL_STATE_OFFHOOK : TelephonyManager.CALL_STATE_IDLE;
            serviceListener.onGsmCallStateChanged(legacyState, currentCallNumber);
        }
    }

    private void handleIncomingCloudMessage(ProtocolMessage message) {
        if (message == null || message.type == null) return;

        if (ProtocolMessage.ROLE_GATEWAY.equals(currentRole)) {
            switch (message.type) {
                case ProtocolMessage.TYPE_DIAL_REQUEST:
                    if (message.targetGatewayId == null || message.targetGatewayId.isEmpty() || gatewayId.equals(message.targetGatewayId)) {
                        final String numberToDial = message.phoneNumber;
                        notifyLog("طلب اتصال وارد من الطرفي: " + numberToDial);
                        notifyCallEvent("استقبال طلب اتصال بالرقم " + numberToDial + " من الهاتف الطرفي... جاري الاتصال عبر GSM");
                        currentCallNumber = numberToDial;

                        handler.post(() -> {
                            boolean dialed = gsmController.dialNumber(numberToDial);
                            if (dialed) {
                                notifyCallEvent("تم بدء طلب الرقم عبر شريحة الهاتف الوسيط");
                                if (cloudClient != null) {
                                    ProtocolMessage stateMsg = ProtocolMessage.createCallState(currentRoom, ProtocolMessage.CALL_DIALING, numberToDial);
                                    cloudClient.sendMessage(stateMsg);
                                }
                            } else {
                                notifyCallEvent("فشل طلب الرقم - تأكد من صلاحيات CALL_PHONE وشريحة SIM");
                                if (cloudClient != null) {
                                    ProtocolMessage failMsg = ProtocolMessage.createCallState(currentRoom, ProtocolMessage.CALL_ENDED, numberToDial, "فشل طلب الرقم عبر الشريحة - تأكد من صلاحيات المكالمات وشريحة SIM");
                                    cloudClient.sendMessage(failMsg);
                                }
                            }
                        });
                    }
                    break;

                case ProtocolMessage.TYPE_ANSWER_CALL:
                    notifyLog("أمر رد على المكالمة من الطرفي");
                    notifyCallEvent("استقبال أمر الرد على المكالمة من الهاتف الطرفي");
                    handler.post(this::answerCallReliably);
                    break;

                case ProtocolMessage.TYPE_HANGUP_CALL:
                    notifyLog("أمر إنهاء المكالمة من الطرفي");
                    notifyCallEvent("استقبال أمر إنهاء المكالمة من الهاتف الطرفي");
                    handler.post(() -> {
                        boolean ended = gsmController.endCall();
                        if (!ended) {
                            try {
                                Intent mediaDown = new Intent(Intent.ACTION_MEDIA_BUTTON);
                                mediaDown.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK));
                                sendOrderedBroadcast(mediaDown, null);
                                Intent mediaUp = new Intent(Intent.ACTION_MEDIA_BUTTON);
                                mediaUp.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK));
                                sendOrderedBroadcast(mediaUp, null);
                            } catch (Throwable ignored) {}
                        }
                    });
                    break;

                case ProtocolMessage.TYPE_SMS_SEND_REQUEST:
                    if (!isSmsRelayEnabled) {
                        notifyLog("تم رفض طلب إرسال SMS لأن ميزة الترحيل معطلة في هذا الوسيط");
                        if (cloudClient != null) {
                            ProtocolMessage statusMsg = ProtocolMessage.createSmsSendStatus(currentRoom, gatewayId, message.smsId, message.smsRecipient, false, "خاصية ترحيل SMS معطلة على هذا الهاتف الوسيط");
                            cloudClient.sendMessage(statusMsg);
                        }
                        break;
                    }
                    if (message.targetGatewayId == null || message.targetGatewayId.isEmpty() || gatewayId.equals(message.targetGatewayId)) {
                        final String recipient = (message.smsRecipient != null && !message.smsRecipient.isEmpty()) ? message.smsRecipient : message.phoneNumber;
                        final String body = message.smsBody;
                        final String smsId = message.smsId;
                        notifyLog("طلب إرسال SMS إلى: " + recipient);
                        notifyCallEvent("📤 طلب إرسال SMS إلى (" + recipient + ") عبر الشريحة الخلوية...");
                        handler.post(() -> {
                            if (smsController != null) {
                                smsController.sendSms(recipient, body, smsId, (success, error) -> {
                                    if (success) {
                                        notifyLog("تم إرسال SMS بنجاح إلى: " + recipient);
                                        notifyCallEvent("✓ تم إرسال رسالة SMS إلى (" + recipient + ") بنجاح");
                                    } else {
                                        notifyLog("فشل إرسال SMS إلى: " + recipient + " (" + error + ")");
                                        notifyCallEvent("❌ فشل إرسال SMS إلى (" + recipient + "): " + error);
                                    }
                                    if (cloudClient != null) {
                                        ProtocolMessage statusMsg = ProtocolMessage.createSmsSendStatus(currentRoom, gatewayId, smsId, recipient, success, error);
                                        cloudClient.sendMessage(statusMsg);
                                    }
                                });
                            }
                        });
                    }
                    break;
            }
        } else if (ProtocolMessage.ROLE_TERMINAL.equals(currentRole)) {
            // Screen wake on incoming call for terminal
            if (ProtocolMessage.TYPE_INCOMING_CALL.equals(message.type)) {
                wakeScreenForCall();
            }
        }
    }

    private void wakeScreenForCall() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                PowerManager.WakeLock wake = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "GsmRelay:TerminalIncomingCallWake"
                );
                wake.acquire(8000);
            }
        } catch (Throwable ignored) {}
    }

    private void answerCallReliably() {
        boolean answered = gsmController.acceptIncomingCall();
        if (!answered) {
            try {
                Intent buttonDown = new Intent(Intent.ACTION_MEDIA_BUTTON);
                buttonDown.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HEADSETHOOK));
                sendOrderedBroadcast(buttonDown, null);

                Intent buttonUp = new Intent(Intent.ACTION_MEDIA_BUTTON);
                buttonUp.putExtra(Intent.EXTRA_KEY_EVENT, new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HEADSETHOOK));
                sendOrderedBroadcast(buttonUp, null);
                notifyLog("تم إرسال أمر الرد البديل");
            } catch (Throwable t) {
                Log.e(TAG, "Media button answer fallback failed", t);
            }
        }
    }

    private void sendGatewayAnnouncement() {
        if (cloudClient != null && cloudClient.isConnected()) {
            int batteryLevel = getBatteryLevel();
            ProtocolMessage info = ProtocolMessage.createGatewayInfo(
                    currentRoom,
                    gsmController.getSimCarrier(),
                    batteryLevel,
                    isGsmCallActive ? ProtocolMessage.CALL_CONNECTED : ProtocolMessage.CALL_IDLE
            );
            info.gatewayId = gatewayId;
            info.gatewayName = gatewayName;
            cloudClient.sendMessage(info);
        }
    }

    private int getBatteryLevel() {
        try {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent bStatus = registerReceiver(null, ifilter);
            if (bStatus != null) {
                int level = bStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = bStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                return (int) ((level / (float) scale) * 100);
            }
        } catch (Throwable ignored) {}
        return 100;
    }

    private void notifyLog(String log) {
        if (serviceListener != null) {
            serviceListener.onLogMessage(log);
        }
    }

    private void notifyCallEvent(String event) {
        if (serviceListener != null) {
            serviceListener.onCallEvent(event);
        }
    }

    private void startInForeground() {
        try {
            Intent notificationIntent = new Intent(this, MainActivity.class);
            notificationIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, 0, notificationIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            String title = ProtocolMessage.ROLE_TERMINAL.equals(currentRole) ?
                    "هاتف طرفي GSM متصل بالسحابة (نشط 24/7)" : "وسيط مكالمات GSM السحابي نشط (24/7)";
            String text = "الغرفة: " + currentRoom + " | الخادم: " + currentServerUrl;

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build();

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                int serviceType = android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    serviceType |= android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
                }
                startForeground(NOTIF_ID, notification, serviceType);
            } else {
                startForeground(NOTIF_ID, notification);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Failed to start service in foreground safely", t);
            try {
                startForeground(NOTIF_ID, new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setContentTitle("خدمة GSM Relay نشطة")
                        .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                        .build());
            } catch (Throwable ignored) {}
        }
    }

    private void updateNotification(String title, String text) {
        try {
            Intent notificationIntent = new Intent(this, MainActivity.class);
            notificationIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, 0, notificationIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            String rolePrefix = ProtocolMessage.ROLE_TERMINAL.equals(currentRole) ? "هاتف طرفي: " : "وسيط GSM: ";

            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setContentTitle(rolePrefix + title)
                    .setContentText(text)
                    .setSmallIcon(android.R.drawable.stat_sys_phone_call)
                    .setContentIntent(pendingIntent)
                    .setOngoing(true)
                    .setCategory(NotificationCompat.CATEGORY_CALL)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build();

            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIF_ID, notification);
            }
        } catch (Throwable ignored) {}
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "GSM Gateway Service",
                        NotificationManager.IMPORTANCE_DEFAULT
                );
                channel.setDescription("إشعارات خدمة وسيط GSM ونقل الصوت الدائمة 24/7");
                channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null) {
                    manager.createNotificationChannel(channel);
                }
            } catch (Throwable t) {
                Log.e(TAG, "Error creating notification channel", t);
            }
        }
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GsmRelay:GatewayWakeLock");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(); // Indefinite persistent lock
            }

            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && (wifiLock == null || !wifiLock.isHeld())) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "GsmRelay:GatewayWifiLock");
                } else {
                    wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "GsmRelay:GatewayWifiLock");
                }
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Throwable ignored) {}
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Throwable ignored) {}
    }

    public boolean isGatewayActive() {
        return isServiceRunning;
    }

    public String getCurrentRole() {
        return currentRole;
    }

    public String getCurrentRoom() {
        return currentRoom;
    }

    public String getCurrentServer() {
        return currentServerUrl;
    }

    public String getGatewayId() {
        return gatewayId;
    }

    public String getGatewayName() {
        return gatewayName;
    }

    public String getSimCarrier() {
        return (gsmController != null) ? gsmController.getSimCarrier() : "GSM";
    }

    public boolean isSimReady() {
        return (gsmController != null) && gsmController.isSimReady();
    }

    public CloudClient getCloudClient() {
        return cloudClient;
    }

    public AudioRelayEngine getAudioRelayEngine() {
        return audioRelayEngine;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(scoReceiver);
        } catch (Throwable ignored) {}
        try {
            unregisterReceiver(screenAndPowerReceiver);
        } catch (Throwable ignored) {}
        releaseLocks();
        instance = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}
