package com.gsmrelay.cloud.telephony;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

import com.gsmrelay.cloud.service.GsmGatewayService;

public class SmsRelayReceiver extends BroadcastReceiver {
    private static final String TAG = "SmsRelayReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) {
            return;
        }

        try {
            Bundle bundle = intent.getExtras();
            if (bundle == null) return;

            Object[] pdus = (Object[]) bundle.get("pdus");
            String format = bundle.getString("format");
            if (pdus == null || pdus.length == 0) return;

            StringBuilder fullBody = new StringBuilder();
            String sender = "";
            long timestamp = System.currentTimeMillis();

            for (Object pdu : pdus) {
                SmsMessage smsMessage;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    smsMessage = SmsMessage.createFromPdu((byte[]) pdu, format);
                } else {
                    smsMessage = SmsMessage.createFromPdu((byte[]) pdu);
                }
                if (smsMessage != null) {
                    if (sender.isEmpty()) {
                        sender = smsMessage.getDisplayOriginatingAddress();
                        timestamp = smsMessage.getTimestampMillis();
                    }
                    fullBody.append(smsMessage.getDisplayMessageBody());
                }
            }

            String body = fullBody.toString();
            Log.d(TAG, "Incoming SMS from: " + sender + ", body: " + body);

            GsmGatewayService service = GsmGatewayService.getInstance();
            if (service != null) {
                service.handleIncomingSms(sender, body, timestamp);
            }
        } catch (Throwable t) {
            Log.e(TAG, "Error handling incoming SMS", t);
        }
    }
}
