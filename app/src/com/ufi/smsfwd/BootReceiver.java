package com.ufi.smsfwd;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        Intent i = new Intent(ctx, SmsService.class);
        ctx.startService(i);
    }
}
