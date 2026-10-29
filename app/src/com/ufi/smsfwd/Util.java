package com.ufi.smsfwd;

import android.util.Log;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;

public class Util {
    public static final String LOG = "/sdcard/ufi/recv.txt";
    public static final String TOKEN_FILE = "/sdcard/ufi/token.txt";

    public static void append(String line) {
        try {
            File dir = new File("/sdcard/ufi");
            if (!dir.exists()) dir.mkdirs();
            FileOutputStream f = new FileOutputStream(LOG, true);
            f.write(("" + line + "\n").getBytes("UTF-8"));
            f.flush();
            f.close();
            File d = new File(LOG);
            d.setReadable(true, false);
            d.setWritable(true, false);
        } catch (Throwable t) {
            Log.w("ufi", "append fail " + t);
        }
    }

    /** Base64 without newlines (NO_WRAP) — API 19 compatible. */
    public static String b64(byte[] data) {
        try {
            return android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";
        }
    }

    /** Read optional API token from /sdcard/ufi/token.txt (empty if absent). */
    public static synchronized String readToken() {
        try {
            File f = new File(TOKEN_FILE);
            if (!f.exists()) return "";
            FileInputStream fi = new FileInputStream(f);
            byte[] b = new byte[256];
            int n = fi.read(b);
            fi.close();
            if (n <= 0) return "";
            return new String(b, 0, n, "UTF-8").trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Token gate for sensitive endpoints. If no token is configured
     * (/sdcard/ufi/token.txt absent/empty) everything stays open, matching
     * legacy behavior. Otherwise the request must carry ?token=... equal
     * to the configured token.
     */
    public static boolean tokenOk(String query) {
        String tok = readToken();
        if (tok.length() == 0) return true;
        if (query == null) return false;
        for (String kv : query.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0 && kv.substring(0, eq).equals("token")) {
                return kv.substring(eq + 1).trim().equals(tok);
            }
        }
        return false;
    }
}
