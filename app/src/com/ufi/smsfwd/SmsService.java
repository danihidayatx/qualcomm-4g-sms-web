package com.ufi.smsfwd;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import java.io.File;
import java.net.ServerSocket;

public class SmsService extends Service {
    public static boolean running = false;
    private ServerSocket socket;
    static android.os.Handler schedules = new android.os.Handler();

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        HttpServer.ensureStarted(getApplicationContext());
        ProxyServer.ensureStarted(getApplicationContext());
        // kick a DB pull once in background (reads existing inbox via READ_SMS)
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() { DbPuller.pull(getApplicationContext()); }
        });
        t.start();
        // background poller: pull inbox every 60s so SMS anytime -> sms.json
        schedules.postDelayed(new Runnable() {
            @Override
            public void run() {
                try { DbPuller.pull(getApplicationContext()); } catch (Throwable ignore) {}
                schedules.postDelayed(this, 60000);
            }
        }, 60000);
        return Service.START_STICKY;
    }

    @Override
    public void onDestroy() {
        HttpServer.stop();
        running = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
