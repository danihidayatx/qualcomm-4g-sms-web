package com.ufi.smsfwd;

import android.os.Message;
import java.lang.reflect.Method;

/** Forces the preferred network type via reflection into PhoneFactory (no UI needed). */
public class RfMode {
    // KitKat RILConstants values
    static final String[] NAMES = new String[]{
        "0:WCDMA_PREF", "1:GSM_ONLY", "2:WCDMA_ONLY", "3:GSM_UMTS",
        "4:CDMA", "5:CDMA_NO_EVDO", "6:EVDO_NO_CDMA", "7:GLOBAL",
        "8:LTE_CDMA_EVDO", "9:LTE_GSM_WCDMA", "10:LTE_CDMA_GSM", "11:LTE_ONLY", "12:LTE_WCDMA"
    };

    public static String set(String modeStr) {
        try {
            int mode = Integer.parseInt(modeStr);
            Class<?> pf = Class.forName("com.android.internal.telephony.PhoneFactory");
            Object phone;
            try {
                phone = pf.getMethod("getPhone", int.class).invoke(null, 0);
            } catch (Throwable ignore) {
                phone = pf.getMethod("getDefaultPhone").invoke(null);
            }
            Method m = null;
            for (Method mm : phone.getClass().getMethods()) {
                if ("setPreferredNetworkType".equals(mm.getName())) { m = mm; break; }
            }
            if (m == null) return "no-method";
            m.invoke(phone, mode, Message.obtain());
            return "sent-" + describe(mode);
        } catch (Throwable t) {
            return "err:" + t.toString();
        }
    }

    static String describe(int mode) {
        if (mode >= 0 && mode < NAMES.length) return NAMES[mode];
        return "mode" + mode;
    }
}
