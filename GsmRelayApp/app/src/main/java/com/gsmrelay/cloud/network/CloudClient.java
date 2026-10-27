package com.gsmrelay.cloud.network;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import com.gsmrelay.cloud.protocol.ProtocolMessage;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

public class CloudClient {
    private static final String TAG = "CloudClient";

    public static final String DEFAULT_SERVER_URL = "wss://relay1-tdgn.onrender.com";

    public interface CloudListener {
        void onConnected();
        void onDisconnected(String reason);
        default void onReconnecting(int attempt, long delayMs) {}
        void onMessageReceived(ProtocolMessage message);
        void onAudioReceived(byte[] audioData);
        void onLatencyUpdated(long latencyMs);
        void onLog(String log);
    }

    private final OkHttpClient httpClient;
    private WebSocket webSocket;
    private CloudListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Dedicated Background Daemon Thread for 24/7 Screen-Off Keepalive & Reconnect
    private final ScheduledExecutorService backgroundExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "CloudClientDaemon");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY + 1);
        return t;
    });
    private ScheduledFuture<?> pingTask = null;
    private ScheduledFuture<?> reconnectTask = null;
    private ScheduledFuture<?> watchdogTask = null;

    private String currentServerUrl = DEFAULT_SERVER_URL;
    private String currentRoom = "GSM-1001";
    private String currentRole = ProtocolMessage.ROLE_GATEWAY;
    private String currentGatewayId = null;
    private String currentGatewayName = null;
    private String currentCarrier = null;
    private String currentExtension = "#105";

    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicBoolean isConnecting = new AtomicBoolean(false);
    private final AtomicBoolean shouldReconnect = new AtomicBoolean(false);
    private final AtomicInteger reconnectAttempts = new AtomicInteger(0);
    private long lastPingTime = 0;

    private Context context;
    private PowerManager.WakeLock wakeLock;

    // Pre-allocated packet buffer for high-speed audio transmission without GC overhead
    private final byte[] audioPacketBuffer = new byte[4096];

    public CloudClient() {
        this(null);
    }

    public CloudClient(Context context) {
        if (context != null) {
            this.context = context.getApplicationContext();
            try {
                PowerManager pm = (PowerManager) this.context.getSystemService(Context.POWER_SERVICE);
                if (pm != null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GsmRelay:CloudClientWakeLock");
                    wakeLock.setReferenceCounted(false);
                }
            } catch (Throwable ignored) {}
        }

        this.httpClient = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .pingInterval(5, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build();
    }

    public void setContext(Context context) {
        if (context != null && this.context == null) {
            this.context = context.getApplicationContext();
            try {
                PowerManager pm = (PowerManager) this.context.getSystemService(Context.POWER_SERVICE);
                if (pm != null && wakeLock == null) {
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GsmRelay:CloudClientWakeLock");
                    wakeLock.setReferenceCounted(false);
                }
            } catch (Throwable ignored) {}
        }
    }

    public void setListener(CloudListener listener) {
        this.listener = listener;
    }

    public void setExtension(String ext) {
        if (ext != null && !ext.trim().isEmpty()) {
            this.currentExtension = ext.trim().startsWith("#") ? ext.trim() : "#" + ext.trim();
        }
    }

    public String getExtension() {
        return currentExtension;
    }

    public void connect(String serverUrl, String room, String role) {
        connect(serverUrl, room, role, null, null, null, null);
    }

    public void connect(String serverUrl, String room, String role, String extension) {
        connect(serverUrl, room, role, null, null, null, extension);
    }

    public synchronized void connect(String serverUrl, String room, String role, String gatewayId, String gatewayName, String carrier) {
        connect(serverUrl, room, role, gatewayId, gatewayName, carrier, null);
    }

    public synchronized void connect(String serverUrl, String room, String role, String gatewayId, String gatewayName, String carrier, String extension) {
        if (serverUrl == null || serverUrl.trim().isEmpty() || serverUrl.contains("echo.websocket.events") || serverUrl.contains("172.26.")) {
            serverUrl = DEFAULT_SERVER_URL;
        }
        String sanitized = serverUrl.trim();
        if (sanitized.startsWith("http://")) {
            sanitized = "ws://" + sanitized.substring(7);
        } else if (sanitized.startsWith("https://")) {
            sanitized = "wss://" + sanitized.substring(8);
        } else if (!sanitized.startsWith("ws://") && !sanitized.startsWith("wss://")) {
            sanitized = "wss://" + sanitized;
        }
        if (sanitized.endsWith("/")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1);
        }
        this.currentServerUrl = sanitized;
        this.currentRoom = (room != null && !room.trim().isEmpty()) ? room.trim() : "GSM-1001";
        this.currentRole = role;
        this.currentGatewayId = gatewayId;
        this.currentGatewayName = gatewayName;
        this.currentCarrier = carrier;
        if (extension != null && !extension.trim().isEmpty()) {
            setExtension(extension);
        }

        // User or system explicitly asked to be connected
        this.shouldReconnect.set(true);
        this.reconnectAttempts.set(0);

        acquireWakeLock();
        closeCurrentSocketOnly();
        startWatchdog();
        startWebSocketConnection();
    }

    private void startWatchdog() {
        if (watchdogTask == null || watchdogTask.isDone()) {
            watchdogTask = backgroundExecutor.scheduleWithFixedDelay(() -> {
                try {
                    if (shouldReconnect.get() && !isConnected.get() && !isConnecting.get()) {
                        log("Watchdog: Connection is down while session is active. Re-triggering connection...");
                        startWebSocketConnection();
                    }
                } catch (Throwable t) {
                    Log.e(TAG, "Watchdog error", t);
                }
            }, 5, 5, TimeUnit.SECONDS);
        }
    }

    private void acquireWakeLock() {
        try {
            if (wakeLock != null && !wakeLock.isHeld()) {
                wakeLock.acquire();
            }
        } catch (Throwable ignored) {}
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {}
    }

    private synchronized void startWebSocketConnection() {
        if (!shouldReconnect.get()) return;
        if (isConnected.get()) return;
        if (!isConnecting.compareAndSet(false, true)) {
            log("Connection attempt already in progress, skipping duplicate...");
            return;
        }

        try {
            log("Connecting to cloud: " + currentServerUrl + " [Room: " + currentRoom + " | Role: " + currentRole + "]...");
            Request request = new Request.Builder()
                    .url(currentServerUrl)
                    .build();

            webSocket = httpClient.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket ws, Response response) {
                    isConnecting.set(false);
                    isConnected.set(true);
                    reconnectAttempts.set(0);
                    acquireWakeLock();
                    log("Connected to cloud broker successfully!");

                    ProtocolMessage joinMsg = ProtocolMessage.createJoin(
                            currentRoom,
                            currentRole,
                            currentGatewayId,
                            currentGatewayName,
                            currentCarrier,
                            currentExtension
                    );
                    sendMessage(joinMsg);

                    sendPing();
                    if (pingTask != null && !pingTask.isDone()) pingTask.cancel(true);
                    pingTask = backgroundExecutor.scheduleWithFixedDelay(() -> {
                        if (isConnected.get()) {
                            sendPing();
                        }
                    }, 3, 5, TimeUnit.SECONDS);

                    mainHandler.post(() -> {
                        if (listener != null) listener.onConnected();
                    });
                }

                @Override
                public void onMessage(WebSocket ws, String text) {
                    try {
                        ProtocolMessage msg = ProtocolMessage.fromJson(text);
                        if (msg != null) {
                            if ("EXTENSION_ASSIGNED".equals(msg.type)) {
                                if (msg.extension != null && !msg.extension.isEmpty()) {
                                    currentExtension = msg.extension;
                                    log("تم تأكيد التحويلة الداخلية من الخادم: " + currentExtension);
                                }
                            }
                            if (ProtocolMessage.TYPE_PING.equals(msg.type)) {
                                ProtocolMessage pong = ProtocolMessage.createPong(currentRoom);
                                sendMessage(pong);
                            } else if (ProtocolMessage.TYPE_PONG.equals(msg.type)) {
                                long latency = System.currentTimeMillis() - lastPingTime;
                                mainHandler.post(() -> {
                                    if (listener != null) listener.onLatencyUpdated(latency);
                                });
                            } else {
                                mainHandler.post(() -> {
                                    if (listener != null) listener.onMessageReceived(msg);
                                });
                            }
                        }
                    } catch (Throwable e) {
                        Log.e(TAG, "Error parsing message", e);
                    }
                }

                @Override
                public void onMessage(WebSocket ws, ByteString bytes) {
                    try {
                        byte[] data = bytes.toByteArray();
                        if (data.length > 1 && data[0] == 0x01) { // Audio frame header
                            byte[] audioPcm = new byte[data.length - 1];
                            System.arraycopy(data, 1, audioPcm, 0, audioPcm.length);
                            // Dispatch directly on network background thread to avoid UI thread queue latency & jitter
                            if (listener != null) {
                                listener.onAudioReceived(audioPcm);
                            }
                        }
                    } catch (Throwable ignored) {}
                }

                @Override
                public void onClosing(WebSocket ws, int code, String reason) {
                    try {
                        ws.close(1000, null);
                    } catch (Throwable ignored) {}
                    isConnecting.set(false);
                    handleDisconnect("Closing: " + reason);
                }

                @Override
                public void onFailure(WebSocket ws, Throwable t, Response response) {
                    isConnecting.set(false);
                    handleDisconnect("Connection failed: " + (t != null ? t.getMessage() : "Unknown network error"));
                }
            });

        } catch (Throwable e) {
            isConnecting.set(false);
            handleDisconnect("Connect exception: " + e.getMessage());
        }
    }

    private void handleDisconnect(String reason) {
        isConnected.set(false);
        if (pingTask != null) {
            pingTask.cancel(true);
            pingTask = null;
        }
        log(reason);
        mainHandler.post(() -> {
            if (listener != null) listener.onDisconnected(reason);
        });

        if (shouldReconnect.get()) {
            scheduleReconnect();
        }
    }

    private synchronized void scheduleReconnect() {
        if (!shouldReconnect.get()) return;
        if (reconnectTask != null && !reconnectTask.isDone()) {
            return; // Already pending
        }

        int attempts = reconnectAttempts.incrementAndGet();
        // Exponential backoff with ceiling: 1.5s, 3s, 5s, max 8s
        long delayMs = Math.min(1500L * attempts, 8000L);
        log("Auto-reconnect attempt #" + attempts + " scheduled in " + delayMs + "ms...");

        mainHandler.post(() -> {
            if (listener != null) listener.onReconnecting(attempts, delayMs);
        });

        reconnectTask = backgroundExecutor.schedule(() -> {
            if (shouldReconnect.get() && !isConnected.get()) {
                log("Executing auto-reconnect attempt #" + attempts + "...");
                startWebSocketConnection();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    public synchronized void reconnectNow() {
        log("Manual instant reconnect requested");
        this.shouldReconnect.set(true);
        if (reconnectTask != null) {
            reconnectTask.cancel(true);
            reconnectTask = null;
        }
        closeCurrentSocketOnly();
        startWebSocketConnection();
    }

    private synchronized void closeCurrentSocketOnly() {
        isConnected.set(false);
        isConnecting.set(false);
        if (pingTask != null) {
            pingTask.cancel(true);
            pingTask = null;
        }
        if (webSocket != null) {
            try {
                webSocket.close(1000, "Resetting connection");
            } catch (Throwable ignored) {}
            webSocket = null;
        }
    }

    public synchronized void disconnect() {
        log("Explicit user disconnect");
        shouldReconnect.set(false);
        isConnected.set(false);
        isConnecting.set(false);

        if (pingTask != null) {
            pingTask.cancel(true);
            pingTask = null;
        }
        if (reconnectTask != null) {
            reconnectTask.cancel(true);
            reconnectTask = null;
        }
        if (watchdogTask != null) {
            watchdogTask.cancel(true);
            watchdogTask = null;
        }
        if (webSocket != null) {
            try {
                webSocket.close(1000, "User disconnected");
            } catch (Throwable ignored) {}
            webSocket = null;
        }
        releaseWakeLock();
    }

    public boolean isConnected() {
        return isConnected.get();
    }

    public boolean isShouldReconnect() {
        return shouldReconnect.get();
    }

    public void sendMessage(ProtocolMessage message) {
        if (webSocket != null && isConnected.get() && message != null) {
            try {
                String json = message.toJson();
                webSocket.send(json);
            } catch (Throwable ignored) {}
        }
    }

    public synchronized void sendAudioData(byte[] data, int offset, int length) {
        if (webSocket != null && isConnected.get() && data != null && length > 0 && length < audioPacketBuffer.length) {
            try {
                audioPacketBuffer[0] = 0x01; // Audio identifier
                System.arraycopy(data, offset, audioPacketBuffer, 1, length);
                webSocket.send(ByteString.of(audioPacketBuffer, 0, length + 1));
            } catch (Throwable ignored) {}
        }
    }

    public void sendPing() {
        if (webSocket != null && isConnected.get()) {
            try {
                lastPingTime = System.currentTimeMillis();
                ProtocolMessage ping = ProtocolMessage.createPing(currentRoom);
                sendMessage(ping);
            } catch (Throwable ignored) {}
        }
    }

    private void log(String message) {
        Log.d(TAG, message);
        mainHandler.post(() -> {
            if (listener != null) listener.onLog(message);
        });
    }
}
