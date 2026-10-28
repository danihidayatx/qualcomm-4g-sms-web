package com.ufi.smsfwd;

import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;

public class Util {
    public static void append(String line) {
        try {
            File dir = new File("/sdcard/ufi");
            if (!dir.exists()) dir.mkdirs();
            FileOutputStream f = new FileOutputStream("/sdcard/ufi/recv.txt", true);
            f.write(("" + line + "\n").getBytes("UTF-8"));
            f.flush();
            f.close();
            File d = new File("/sdcard/ufi/recv.txt");
            d.setReadable(true, false);
            d.setWritable(true, false);
        } catch (Throwable t) {
            Log.w("ufi", "append fail " + t);
        }
    }
}
