package com.gsmrelay.cloud.protocol;

import org.json.JSONException;
import org.json.JSONObject;

public class ProtocolMessage {
    public static final String TYPE_JOIN = "JOIN";
    public static final String TYPE_PEER_JOINED = "PEER_JOINED";
    public static final String TYPE_PEER_LEFT = "PEER_LEFT";
    public static final String TYPE_GATEWAY_INFO = "GATEWAY_INFO";
    public static final String TYPE_DIAL_REQUEST = "DIAL_REQUEST";
    public static final String TYPE_INCOMING_CALL = "INCOMING_CALL";
    public static final String TYPE_ANSWER_CALL = "ANSWER_CALL";
    public static final String TYPE_HANGUP_CALL = "HANGUP_CALL";
    public static final String TYPE_CALL_STATE = "CALL_STATE";
    public static final String TYPE_GATEWAY_LIST = "GATEWAY_LIST";
    public static final String TYPE_GATEWAY_ANNOUNCE = "GATEWAY_ANNOUNCE";
    public static final String TYPE_PING = "PING";
    public static final String TYPE_PONG = "PONG";
    public static final String TYPE_SMS_INCOMING = "SMS_INCOMING";
    public static final String TYPE_SMS_SEND_REQUEST = "SMS_SEND_REQUEST";
    public static final String TYPE_SMS_SEND_STATUS = "SMS_SEND_STATUS";

    public static final String ROLE_GATEWAY = "GATEWAY";
    public static final String ROLE_TERMINAL = "TERMINAL";

    public static final String CALL_IDLE = "IDLE";
    public static final String CALL_RINGING = "RINGING";
    public static final String CALL_DIALING = "DIALING";
    public static final String CALL_BUSY = "BUSY";
    public static final String CALL_REJECTED = "REJECTED";
    public static final String CALL_NO_ANSWER = "NO_ANSWER";
    public static final String CALL_CONNECTED = "CONNECTED";
    public static final String CALL_ENDED = "ENDED";

    public String type;
    public String role;
    public String room;
    public String carrier;
    public String phoneNumber;
    public String callState;
    public String callStateDetails;
    public String gatewayId;
    public String gatewayName;
    public String targetGatewayId;
    public String gatewaysRaw;
    public boolean isBusy;
    public int battery;
    public long timestamp;

    public String smsSender;
    public String smsRecipient;
    public String smsBody;
    public boolean smsSuccess;
    public String smsError;
    public String smsId;
    public String extension;

    public static ProtocolMessage createJoin(String room, String role) {
        return createJoin(room, role, null, null, null, null);
    }

    public static ProtocolMessage createJoin(String room, String role, String gatewayId, String gatewayName, String carrier) {
        return createJoin(room, role, gatewayId, gatewayName, carrier, null);
    }

    public static ProtocolMessage createJoin(String room, String role, String gatewayId, String gatewayName, String carrier, String extension) {
        ProtocolMessage msg = new ProtocolMessage();
        msg.type = TYPE_JOIN;
        msg.room = room;
        msg.role = role;
        msg.gatewayId = gatewayId;
        msg.gatewayName = gatewayName;
        msg.carrier = carrier;
        msg.extension = extension;
        msg.timestamp = System.currentTimeMillis();
        return msg;
    }

    public static ProtocolMessage createUpdateExtension(String room, String extension) {
        ProtocolMessage msg = new ProtocolMessage();
        msg.type = "UPDATE_EXTENSION";
        msg.room = room;
        msg.role = ROLE_TERMINAL;
        msg.extension = extension;
        msg.timestamp = System.currentTimeMillis();
        return msg;
    }

    public static ProtocolMessage createGatewayInfo(String room, String carrier, int battery, String callState) {
        ProtocolMessage msg = new ProtocolMessage();
        msg.type = TYPE_GATEWAY_INFO;
        msg.room = room;
        msg.role = ROLE_GATEWAY;
        msg.carrier = carrier;
        msg.battery = battery;
        msg.callState = callState;
        msg.timestamp = System.currentTimeMillis();
        return msg;
    }

    public static ProtocolMessage createDialRequest(String room, String phoneNumber) {
        return createDialRequest(room, phoneNumber, null);
    }

    public static ProtocolMessage createDialRequest(String room, String phoneNumber, String targetGatewayId) {
        ProtocolMessage msg = new ProtocolMessage();
        msg.type = TYPE_DIAL_REQUEST;
        msg.room = room;
        msg.role = ROLE_TERMINAL;
        msg.phoneNumber = phoneNumber;
        msg.targetGatewayId = targetGatewayId;
        msg.timestamp = System.currentTimeMillis();
        return msg;
    }

    public static ProtocolMessage createIncomingCall(String room, String callerNumber) {
        ProtocolMessage msg = new ProtocolMessage();
        msg.type = TYPE_INCOMING_CALL;
        msg.room = room;
        msg.role = ROLE_GATEWAY;
        msg.phoneNumber = callerNumber;
        msg.timestamp = System.currentTimeMillis();
        return msg;
    }

    public static ProtocolMessage createAnswerCall(String room) {
        ProtocolMessage msg = new ProtocolMessage();
        msg.type = TYPE_ANSWER_CALL;
        msg.room = room;
        msg.role = ROLE_TERMINAL;
        msg.timestamp = System.currentTimeMillis();
        return msg;
    }

    public static ProtocolMessage createHangupCall(String room, String role) {
        ProtocolMessage msg = new ProtocolMessage();
        msg.type = TYPE_HANGUP_CALL;
        msg.room = room;
        msg.role = role;
        msg.timestamp = System.currentTimeMillis();
        return msg;
    }

    public static ProtocolMessage createCallState(String room, String state, String number) {
        return createCallState(room, state, number, null);
    }

    public static ProtocolMessage createCallState(String room, String state, String number, String details) {
        ProtocolMessage msg = new ProtocolMessage();
        msg.type = TYPE_CALL_STATE;
        msg.room = room;
        msg.callState = state;
        msg.phoneNumber = number;
        msg.callStateDetails = details;
        msg.timestamp = System.currentTimeMillis();
        return msg;
    }

    public static ProtocolMessage createPing(String room) {
        ProtocolMessage msg = new ProtocolMessage();
        msg.type = TYPE_PING;
        msg.room = room;
        msg.timestamp = System.currentTimeMillis();
        return msg;
    }

    public static ProtocolMessage createPong(String room) {
        ProtocolMessage msg = new ProtocolMessage();
        msg.type = TYPE_PONG;
        msg.room = room;
        msg.timestamp = System.currentTimeMillis();
        return msg;
    }

    public static ProtocolMessage createSmsIncoming(String room, String gatewayId, String sender, String body, long timestamp) {
        ProtocolMessage msg = new ProtocolMessage();
        msg.type = TYPE_SMS_INCOMING;
        msg.room = room;
        msg.role = ROLE_GATEWAY;
        msg.gatewayId = gatewayId;
        msg.smsSender = sender;
        msg.smsBody = body;
        msg.timestamp = timestamp > 0 ? timestamp : System.currentTimeMillis();
        return msg;
    }

    public static ProtocolMessage createSmsSendStatus(String room, String gatewayId, String smsId, String recipient, boolean success, String error) {
        ProtocolMessage msg = new ProtocolMessage();
        msg.type = TYPE_SMS_SEND_STATUS;
        msg.room = room;
        msg.role = ROLE_GATEWAY;
        msg.gatewayId = gatewayId;
        msg.smsId = smsId;
        msg.smsRecipient = recipient;
        msg.smsSuccess = success;
        msg.smsError = error;
        msg.timestamp = System.currentTimeMillis();
        return msg;
    }

    public String toJson() {
        try {
            JSONObject obj = new JSONObject();
            if (type != null) obj.put("type", type);
            if (role != null) obj.put("role", role);
            if (room != null) obj.put("room", room);
            if (carrier != null) obj.put("carrier", carrier);
            if (phoneNumber != null) obj.put("phoneNumber", phoneNumber);
            if (callState != null) obj.put("callState", callState);
            if (callStateDetails != null) obj.put("callStateDetails", callStateDetails);
            if (gatewayId != null) obj.put("gatewayId", gatewayId);
            if (gatewayName != null) obj.put("gatewayName", gatewayName);
            if (targetGatewayId != null) obj.put("targetGatewayId", targetGatewayId);
            if (smsSender != null) obj.put("smsSender", smsSender);
            if (smsRecipient != null) obj.put("smsRecipient", smsRecipient);
            if (smsBody != null) obj.put("smsBody", smsBody);
            if (smsError != null) obj.put("smsError", smsError);
            if (smsId != null) obj.put("smsId", smsId);
            if (extension != null) obj.put("extension", extension);
            obj.put("smsSuccess", smsSuccess);
            obj.put("isBusy", isBusy);
            obj.put("battery", battery);
            obj.put("timestamp", timestamp > 0 ? timestamp : System.currentTimeMillis());
            return obj.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    public static ProtocolMessage fromJson(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            ProtocolMessage msg = new ProtocolMessage();
            msg.type = obj.optString("type", "");
            msg.role = obj.optString("role", "");
            msg.room = obj.optString("room", "");
            msg.carrier = obj.optString("carrier", "");
            msg.phoneNumber = obj.optString("phoneNumber", "");
            msg.callState = obj.optString("callState", "");
            msg.callStateDetails = obj.optString("callStateDetails", obj.optString("details", ""));
            msg.gatewayId = obj.optString("gatewayId", "");
            msg.gatewayName = obj.optString("gatewayName", "");
            msg.targetGatewayId = obj.optString("targetGatewayId", "");
            msg.smsSender = obj.optString("smsSender", obj.optString("sender", ""));
            msg.smsRecipient = obj.optString("smsRecipient", obj.optString("recipient", ""));
            msg.smsBody = obj.optString("smsBody", obj.optString("body", ""));
            msg.smsError = obj.optString("smsError", obj.optString("error", ""));
            msg.smsId = obj.optString("smsId", obj.optString("id", ""));
            msg.extension = obj.optString("extension", "");
            msg.smsSuccess = obj.optBoolean("smsSuccess", obj.optBoolean("success", false));
            msg.isBusy = obj.optBoolean("isBusy", false);
            if (obj.has("gateways")) {
                msg.gatewaysRaw = obj.opt("gateways").toString();
            }
            msg.battery = obj.optInt("battery", 100);
            msg.timestamp = obj.optLong("timestamp", 0);
            return msg;
        } catch (JSONException e) {
            return null;
        }
    }
}
