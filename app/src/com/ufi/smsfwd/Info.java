package com.ufi.smsfwd;

import android.content.Context;
import android.telephony.TelephonyManager;

/** Device/SIM diagnostics for the /info endpoint */
public class Info {
    public static String snapshot(Context ctx) {
        try {
            TelephonyManager tm = (TelephonyManager) ctx.getSystemService(Context.TELEPHONY_SERVICE);
            StringBuilder sb = new StringBuilder();
            sb.append("{\"msisdn\":\"").append(j(tm.getLine1Number())).append("\"");
            sb.append(",\"operator\":\"").append(j(tm.getNetworkOperatorName())).append("\"");
            sb.append(",\"operatorCode\":\"").append(j(tm.getNetworkOperator())).append("\"");
            sb.append(",\"state\":\"").append(state(tm)).append("\"");
            sb.append(",\"dataState\":\"").append(tm.getDataState()).append("\"");
            sb.append(",\"deviceid\":\"").append(j(tm.getDeviceId())).append("\"");
            sb.append(",\"imsi\":\"").append(j(tm.getSubscriberId())).append("\"");
            sb.append(",\"country\":\"").append(j(tm.getNetworkCountryIso())).append("\"");
            sb.append("}");
            return sb.toString();
        } catch (Throwable t) {
            return "{\"err\":\"" + t + "\"}";
        }
    }
    static String state(TelephonyManager tm) {
        try {
            int s = tm.getCallState();
            int reg = tm.getNetworkType();
            int srv = tm.getSimState();
            return "call=" + s + ",net=" + reg + ",sim=" + srv;
        } catch (Throwable t) { return "?"; }
    }
    static String j(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
