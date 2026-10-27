package com.gsmrelay.cloud.telephony;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.telecom.TelecomManager;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

public class GsmTelephonyController {
    private static final String TAG = "GsmTelephonyController";

    public interface TelephonyListener {
        void onCallStateChanged(int state, String incomingNumber);
        default void onGranularCallStateChanged(String granularState, String phoneNumber, String details) {}
        void onSimStatusChanged(boolean ready, String carrier);
    }

    private final Context context;
    private final TelephonyManager telephonyManager;
    private final TelecomManager telecomManager;
    private TelephonyListener listener;

    private PhoneStateListener phoneStateListener;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());

    private boolean isOutgoingCall = false;
    private boolean isCallActive = false;
    private long dialStartTime = 0;
    private String activePhoneNumber = "";
    private Runnable ringingTransitionRunnable = null;

    public GsmTelephonyController(Context context) {
        this.context = context.getApplicationContext();
        this.telephonyManager = (TelephonyManager) this.context.getSystemService(Context.TELEPHONY_SERVICE);
        this.telecomManager = (TelecomManager) this.context.getSystemService(Context.TELECOM_SERVICE);
    }

    public void setListener(TelephonyListener listener) {
        this.listener = listener;
        checkSimStatus();
    }

    public void registerCallListener() {
        if (telephonyManager == null) return;

        // Verify permission first
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "READ_PHONE_STATE not granted yet, skipping telephony listener registration");
            return;
        }

        try {
            if (phoneStateListener == null) {
                // Listen to standard CALL_STATE plus PRECISE_CALL_STATE flag (2048 / 0x800)
                phoneStateListener = new PhoneStateListener() {
                    @Override
                    public void onCallStateChanged(int state, String phoneNumber) {
                        handleLegacyCallState(state, phoneNumber);
                    }
                };
            }
            // Use 0x00000800 (LISTEN_PRECISE_CALL_STATE) if available
            int events = PhoneStateListener.LISTEN_CALL_STATE;
            try {
                events |= 0x00000800; // LISTEN_PRECISE_CALL_STATE
            } catch (Throwable ignored) {}

            telephonyManager.listen(phoneStateListener, events);
            Log.d(TAG, "Registered PhoneStateListener successfully with events: " + events);
        } catch (Throwable t) {
            Log.e(TAG, "Error registering PhoneStateListener", t);
            try {
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
            } catch (Throwable ignored) {}
        }
    }

    private void handleLegacyCallState(int state, String phoneNumber) {
        if (phoneNumber != null && !phoneNumber.trim().isEmpty()) {
            activePhoneNumber = phoneNumber.trim();
        }

        if (listener != null) {
            listener.onCallStateChanged(state, activePhoneNumber);
        }

        switch (state) {
            case TelephonyManager.CALL_STATE_RINGING:
                isOutgoingCall = false;
                isCallActive = false;
                cancelRingingTransition();
                notifyGranularState("RINGING", activePhoneNumber, "رنين مكالمة خلوية واردة...");
                break;

            case TelephonyManager.CALL_STATE_OFFHOOK:
                if (isOutgoingCall) {
                    // Outgoing call in progress: it starts in DIALING state
                    notifyGranularState("DIALING", activePhoneNumber, "جاري طلب الرقم عبر الشريحة...");

                    // Schedule automatic transition to RINGING after 2.5s network setup time
                    cancelRingingTransition();
                    ringingTransitionRunnable = () -> {
                        if (isOutgoingCall && !isCallActive) {
                            notifyGranularState("RINGING", activePhoneNumber, "جاري رنين الهاتف لدى الطرف الآخر...");
                            // After ringing for typical answer delay (e.g. 7s), if still offhook, declare active
                            mainHandler.postDelayed(() -> {
                                if (isOutgoingCall && !isCallActive) {
                                    isCallActive = true;
                                    notifyGranularState("CONNECTED", activePhoneNumber, "المكالمة متصلة - جاري تمرير الصوت بدقة");
                                }
                            }, 5000);
                        }
                    };
                    mainHandler.postDelayed(ringingTransitionRunnable, 2500);
                } else {
                    // Incoming call answered
                    isCallActive = true;
                    notifyGranularState("CONNECTED", activePhoneNumber, "المكالمة متصلة - جاري تمرير الصوت بدقة");
                }
                break;

            case TelephonyManager.CALL_STATE_IDLE:
                cancelRingingTransition();
                long callDuration = (dialStartTime > 0) ? (System.currentTimeMillis() - dialStartTime) : 0;

                if (isOutgoingCall && !isCallActive) {
                    // Call ended before being marked active
                    if (callDuration > 0 && callDuration < 9500) {
                        // Quick disconnect (<9.5s) on dialed call indicates BUSY / REJECTED line!
                        Log.i(TAG, "Quick disconnect after " + callDuration + "ms -> Calling party is BUSY!");
                        notifyGranularState("BUSY", activePhoneNumber, "الرقم مشغول حالياً (Line Busy)");
                    } else {
                        notifyGranularState("NO_ANSWER", activePhoneNumber, "لم يتم الرد على المكالمة");
                    }
                } else {
                    notifyGranularState("ENDED", activePhoneNumber, "تم إنهاء المكالمة الخلوية");
                }

                isOutgoingCall = false;
                isCallActive = false;
                dialStartTime = 0;
                break;
        }
    }

    private void cancelRingingTransition() {
        if (ringingTransitionRunnable != null) {
            mainHandler.removeCallbacks(ringingTransitionRunnable);
            ringingTransitionRunnable = null;
        }
    }

    private void notifyGranularState(String granularState, String number, String details) {
        if (listener != null) {
            mainHandler.post(() -> listener.onGranularCallStateChanged(granularState, number, details));
        }
    }

    public void unregisterCallListener() {
        if (telephonyManager == null) return;
        try {
            if (phoneStateListener != null) {
                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
                phoneStateListener = null;
            }
        } catch (Throwable ignored) {}
    }

    public boolean isSimReady() {
        if (telephonyManager == null) return false;
        try {
            return telephonyManager.getSimState() == TelephonyManager.SIM_STATE_READY;
        } catch (Throwable t) {
            return false;
        }
    }

    public String getSimCarrier() {
        if (telephonyManager == null) return "No SIM";
        try {
            String carrier = telephonyManager.getSimOperatorName();
            if (carrier == null || carrier.trim().isEmpty()) {
                carrier = telephonyManager.getNetworkOperatorName();
            }
            return (carrier != null && !carrier.trim().isEmpty()) ? carrier : "SIM Detected";
        } catch (Throwable t) {
            return "SIM Ready";
        }
    }

    public void checkSimStatus() {
        try {
            boolean ready = isSimReady();
            String carrier = getSimCarrier();
            if (listener != null) {
                listener.onSimStatusChanged(ready, carrier);
            }
        } catch (Throwable ignored) {}
    }

    public boolean dialNumber(String phoneNumber) {
        if (phoneNumber == null || phoneNumber.trim().isEmpty()) return false;
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "CALL_PHONE permission not granted");
                return false;
            }

            this.activePhoneNumber = phoneNumber.trim();
            this.isOutgoingCall = true;
            this.isCallActive = false;
            this.dialStartTime = System.currentTimeMillis();
            notifyGranularState("DIALING", activePhoneNumber, "جاري طلب الرقم عبر الشريحة...");

            Intent callIntent = new Intent(Intent.ACTION_CALL);
            callIntent.setData(Uri.parse("tel:" + Uri.encode(phoneNumber.trim())));
            callIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(callIntent);
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "Error dialing phone number: " + phoneNumber, t);
            return false;
        }
    }

    public boolean acceptIncomingCall() {
        if (telecomManager == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    telecomManager.acceptRingingCall();
                    return true;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error accepting ringing call via TelecomManager", t);
        }
        return false;
    }

    public boolean endCall() {
        if (telecomManager == null) return false;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    return telecomManager.endCall();
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error ending call via TelecomManager", t);
        }
        return false;
    }
}
