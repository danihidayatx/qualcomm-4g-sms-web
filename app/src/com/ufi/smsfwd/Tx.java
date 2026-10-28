package com.ufi.smsfwd;

import java.lang.reflect.Field;
import java.util.TreeMap;

/** Enumerates ITelephony.Stub.TRANSACTION_* binder ids of THIS framework build. */
public class Tx {
    public static String list() {
        try {
            Class<?> stub = Class.forName("com.android.internal.telephony.ITelephony$Stub");
            TreeMap<Integer, String> map = new TreeMap<Integer, String>();
            for (Field f : stub.getDeclaredFields()) {
                String n = f.getName();
                if (n.startsWith("TRANSACTION_")) {
                    f.setAccessible(true);
                    map.put(f.getInt(null), n.substring("TRANSACTION_".length()));
                }
            }
            StringBuilder sb = new StringBuilder("{\"tx\":{");
            boolean first = true;
            for (Integer id : map.keySet()) {
                if (!first) sb.append(',');
                first = false;
                sb.append('"').append(map.get(id)).append("\":").append(id.intValue());
            }
            sb.append("}}");
            return sb.toString();
        } catch (Throwable t) {
            return "{\"err\":\"" + String.valueOf(t).replace("\"", "'") + "\"}";
        }
    }
}
