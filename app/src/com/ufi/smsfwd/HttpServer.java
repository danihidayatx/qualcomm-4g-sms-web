package com.ufi.smsfwd;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.FilenameFilter;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;

public class HttpServer {
    static volatile Thread serverThread;
    static Context appCtx;

    public static synchronized void ensureStarted(Context c) {
        appCtx = c.getApplicationContext();
        if (serverThread != null && serverThread.isAlive()) return;
        serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runServer();
            }
        });
        serverThread.setDaemon(false);
        serverThread.start();
        try {
            Util.append(" srv|http:" + 8088 + "\n");
        } catch (Throwable ignore) {
        }
    }

    public static synchronized void stop() {
        try { Thread t = serverThread; if (t != null) t.interrupt(); } catch (Throwable ignore) {}
        serverThread = null;
    }

    static void runServer() {
        while (true) {
            try {
                ServerSocket ss = new ServerSocket(8088, 10, java.net.InetAddress.getByName("0.0.0.0"));
                while (true) {
                    final Socket s = ss.accept();
                    Thread h = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            handle(s);
                        }
                    });
                    h.start();
                }
            } catch (Throwable t) {
                Util.append(" srv|restart|" + t + "\n");
                try { Thread.sleep(3000); } catch (Exception ignore) {}
            }
        }
    }

    static void handle(Socket s) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = s.getInputStream();
            out = s.getOutputStream();
            BufferedReader rd = new BufferedReader(new InputStreamReader(in), 4096);
            String req = rd.readLine();
            byte[] body = null;
            if (req == null) return;
            String path = req.substring(req.indexOf(' ') + 1, req.lastIndexOf(' '));
            path = java.net.URLDecoder.decode(path, "UTF-8");
            if (path.indexOf("ussd=") >= 0) {
                String code = path.substring(path.indexOf("ussd=") + 5);
                int e = code.indexOf('&');
                if (e > 0) code = code.substring(0, e);
                dialUssd(code);
                body = ("{\"ok\":1,\"duaded\":\"" + code + "\"}\n").getBytes("UTF-8");
            } else if (path.startsWith("/raw")) {
                File f = new File("/sdcard/ufi/recv.txt");
                if (!f.exists()) { body = "{}\n".getBytes(); }
                else body = readFile(f);
            } else if (path.startsWith("/info")) {
                body = Info.snapshot(appCtx).getBytes("UTF-8");
            } else if (path.startsWith("/tx")) {
                body = Tx.list().getBytes("UTF-8");
            } else if (path.indexOf("mode=") >= 0) {
                String m = path.substring(path.indexOf("mode=") + 5);
                int e = m.indexOf('&');
                if (e > 0) m = m.substring(0, e);
                String r = RfMode.set(m.trim());
                Util.append(" RF|mode|" + m + "|" + r + "\n");
                body = ("{\"mode\":\"" + m + "\",\"res\":\"" + r + "\"}\n").getBytes("UTF-8");
            } else if (path.startsWith("/sync")) {
                int n = DbPuller.pull(appCtx);
                body = ("{\"pulled\":" + n + "}\n").getBytes("UTF-8");
            } else if (path.startsWith("/sms.json")) {
                File f = new File("/sdcard/ufi/recv.txt");
                if (!f.exists()) { body = "[]\n".getBytes(); }
                else body = jsonFile(f);
            } else if (path.startsWith("/health")) {
                body = ("{\"up\":1}\n").getBytes();
            } else {
                body = ("<pre>UFI SMS FWD\nGET /sms.json=TABLE\nGET /raw=raw\nGET /ussd?code=*888#\n</pre>\n").getBytes();
            }
            String hdr = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " +
                body.length + "\r\nConnection: close\r\n\r\n";
            out.write(hdr.getBytes("UTF-8"));
            out.write(body);
            out.flush();
        } catch (Throwable t) {
            try { out.write(("HTTP/1.1 500 ERR\r\n\r\n" + t).getBytes()); } catch (Exception ignore) {}
        } finally {
            try { s.close(); } catch (Throwable ignore) {}
        }
    }

    static void dialUssd(String code) {
        if (code == null) return;
        try {
            Util.append("USSD|req|\n");
            Intent i = new Intent(Intent.ACTION_CALL, Uri.parse("tel:" + code));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            appCtx.startActivity(i);
        } catch (Throwable t) {
            Util.append("USSD|err|" + t + "\n");
        }
    }

    static byte[] readFile(File f) throws Exception {
        FileInputStream fi = new FileInputStream(f);
        byte[] b = new byte[(int) f.length()];
        fi.read(b);
        fi.close();
        return b;
    }

    static byte[] jsonFile(File f) throws Exception {
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f)), 8192);
        StringBuilder sb = new StringBuilder("[\n");
        StringBuilder tmp = new StringBuilder();
        String line;
        int n = 0;
        while ((line = r.readLine()) != null) tmp.append(line).append("\n");
        r.close();
        String[] lines = tmp.toString().split("\n");
        for (int i = Math.max(0, lines.length - 200); i < lines.length; i++) {
            String l = lines[i];
            if (l.length() < 4) continue;
            String j = l.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", " ");
            sb.append("{\"e\":\"").append(j).append("\"},");
            n++;
        }
        if (n > 0) sb.setLength(sb.length() - 1);
        sb.append("\n]");
        return sb.toString().getBytes("UTF-8");
    }
}
