package com.ufi.smsfwd;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.telephony.SmsMessage;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

/** Reads the telephony SMS inbox via READ_SMS and appends new rows to /sdcard/ufi/recv.txt */
public class DbPuller {
    static Set<String> seen = new HashSet<String>();
    static boolean first = false;

    public static synchronized int pull(Context ctx) {
        android.util.Log.w("ufi", "dbpull start");
        int added = 0;
        Cursor c = null;
        try {
            Uri uri = Uri.parse("content://sms/inbox");
            String[] proj = new String[]{"_id", "address", "body", "date"};
            c = ctx.getContentResolver().query(uri, proj, null, null, "_id DESC");
            android.util.Log.w("ufi", "dbpull rows=" + (c == null ? -1 : c.getCount()));
            if (c == null) return 0;
            SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            int n = 0;
            while (c.moveToNext() && n < 300) {
                n++;
                long id = c.getLong(0);
                String addr = c.getString(1);
                String body = c.getString(2);
                long ts = c.getLong(3);
                String key = String.valueOf(id) + "|" + addr + "|" + ts;
                if (seen.contains(key)) continue;
                seen.add(key);
                if (!first && added == 0) first = true; // allow existing history
                String line = "DB|" + addr + "|" + df.format(new Date(ts)) + "|" + (body == null ? "" : body.replace("\n", "\\n"));
                Util.append(line);
                added++;
            }
        } catch (Throwable t) {
            android.util.Log.w("ufi", "dbpull err " + t);
            Util.append("ERR|dbpull|" + t + "\n");
        } finally {
            try { if (c != null) c.close(); } catch (Throwable ignore) {}
        }
    first = true;
        return added;
    }
}
