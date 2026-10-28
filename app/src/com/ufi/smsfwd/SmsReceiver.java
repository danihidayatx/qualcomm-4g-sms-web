package com.ufi.smsfwd;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsMessage;
import java.text.SimpleDateFormat;
import java.util.Date;

/** Receives incoming SMS (any rx store) and appends a line to /sdcard/ufi/recv.txt */
public class SmsReceiver extends BroadcastReceiver {
    public static final String LOG = "/sdcard/ufi/recv.txt";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        try {
            Object[] pdus = (Object[]) intent.getExtras().get("pdus");
            if (pdus == null) return;
            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < pdus.length; i++) {
                byte[] pdu = (byte[]) pdus[i];
                SmsMessage m = SmsMessage.createFromPdu(pdu);
                if (m == null) continue;
                String addr = m.getOriginatingAddress();
                String body = m.getMessageBody();
                long ts = m.getTimestampMillis();
                out.append("IN|").append(addr).append("|").append(df.format(new Date(ts)))
                   .append("|").append(body == null ? "" : body.replace("\n", "\\n")).append("\n");
            }
            if (out.length() > 0) {
                Util.append(out.toString());
                HttpServer.ensureStarted(ctx.getApplicationContext());
                abortBroadcast(); // higher priority so we get it first; harmless for others
            }
        } catch (Throwable t) {
            Util.append("ERR|recv|" + t + "\n");
        }
    }
}
