package com.gsmrelay.cloud.telephony;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.telephony.SmsManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;

public class SmsController {
    private static final String TAG = "SmsController";
    private static final String ACTION_SMS_SENT = "com.gsmrelay.SMS_SENT";

    public interface SmsSendCallback {
        void onResult(boolean success, String error);
    }

    private final Context context;

    public SmsController(Context context) {
        this.context = context.getApplicationContext();
    }

    public void sendSms(String recipient, String message, String smsId, SmsSendCallback callback) {
        if (recipient == null || recipient.trim().isEmpty()) {
            if (callback != null) callback.onResult(false, "رقم المستلم غير محدد");
            return;
        }
        if (message == null || message.trim().isEmpty()) {
            if (callback != null) callback.onResult(false, "نص الرسالة فارغ");
            return;
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            String errorMsg = "لا توجد صلاحيات للإرسال (SEND_SMS) على الهاتف الوسيط. يرجى فتح التطبيق ومنح صلاحية الرسائل النصية.";
            Log.e(TAG, errorMsg);
            if (callback != null) callback.onResult(false, errorMsg);
            return;
        }

        try {
            SmsManager smsManager;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                smsManager = context.getSystemService(SmsManager.class);
            } else {
                smsManager = SmsManager.getDefault();
            }

            if (smsManager == null) {
                if (callback != null) callback.onResult(false, "تعذر الوصول إلى مشغل الرسائل SmsManager");
                return;
            }

            final String sendAction = ACTION_SMS_SENT + "_" + (smsId != null ? smsId : System.currentTimeMillis());

            BroadcastReceiver sendReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent intent) {
                    try {
                        context.unregisterReceiver(this);
                    } catch (Throwable ignored) {}

                    int resultCode = getResultCode();
                    if (resultCode == android.app.Activity.RESULT_OK) {
                        Log.i(TAG, "SMS successfully dispatched to baseband: " + recipient);
                        if (callback != null) callback.onResult(true, null);
                    } else {
                        String errMsg;
                        switch (resultCode) {
                            case SmsManager.RESULT_ERROR_GENERIC_FAILURE:
                                errMsg = "فشل عام في شبكة الاتصال الخلوية (Generic Failure)";
                                break;
                            case SmsManager.RESULT_ERROR_NO_SERVICE:
                                errMsg = "لا توجد تغطية شبكة خلوية (No Service)";
                                break;
                            case SmsManager.RESULT_ERROR_RADIO_OFF:
                                errMsg = "وضع الطيران مفعل أو الراديو مغلق (Radio Off)";
                                break;
                            case SmsManager.RESULT_ERROR_NULL_PDU:
                                errMsg = "خطأ في ترميز رسالة SMS (Null PDU)";
                                break;
                            default:
                                errMsg = "فشل الإرسال (رمز: " + resultCode + ")";
                                break;
                        }
                        Log.w(TAG, "SMS dispatch failed: " + errMsg);
                        if (callback != null) callback.onResult(false, errMsg);
                    }
                }
            };

            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(sendReceiver, new IntentFilter(sendAction), Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(sendReceiver, new IntentFilter(sendAction));
            }

            PendingIntent sentPi = PendingIntent.getBroadcast(
                    context,
                    (int) (System.currentTimeMillis() & 0xffff),
                    new Intent(sendAction),
                    flags
            );

            ArrayList<String> parts = smsManager.divideMessage(message);
            if (parts.size() > 1) {
                ArrayList<PendingIntent> sentPis = new ArrayList<>();
                for (int i = 0; i < parts.size(); i++) {
                    sentPis.add(i == parts.size() - 1 ? sentPi : null);
                }
                smsManager.sendMultipartTextMessage(recipient, null, parts, sentPis, null);
            } else {
                smsManager.sendTextMessage(recipient, null, message, sentPi, null);
            }
            Log.d(TAG, "sendTextMessage requested for: " + recipient);
        } catch (SecurityException se) {
            String errorMsg = "لا توجد صلاحيات للإرسال (SecurityException). يرجى منح صلاحية إرسال الرسائل للتطبيق من إعدادات الهاتف.";
            Log.e(TAG, errorMsg, se);
            if (callback != null) callback.onResult(false, errorMsg);
        } catch (Throwable t) {
            Log.e(TAG, "Exception sending SMS", t);
            if (callback != null) callback.onResult(false, t.getMessage());
        }
    }
}
