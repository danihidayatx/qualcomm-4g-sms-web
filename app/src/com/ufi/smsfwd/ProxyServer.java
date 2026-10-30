package com.ufi.smsfwd;

import android.content.Context;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * In-modem Web UI proxy on port 8081.
 *
 * - Reverse-proxies the stock modem Web UI (cpeweb.apk, NanoHTTPD on 127.0.0.1:80)
 * - Injects sms-inbox.js into every HTML page (floating SMS/OTP widget)
 * - Serves /sms-inbox.js (embedded at build time, see SmsInboxJs.java)
 * - Serves /api/sms (parsed JSON inbox read directly from /sdcard/ufi/recv.txt)
 *
 * No SBC / host PC / ADB watchdog required: everything runs on the modem itself.
 */
public class ProxyServer {
    static volatile Thread serverThread;
    static Context appCtx;
    public static final int DEFAULT_PORT = 8081;
    public static final int WEB_PORT = 80; // stock Web UI (cpeweb.apk / NanoHTTPD)

    /** Listen port: /sdcard/ufi/port.txt (single line, 1-65535) or default 8081. */
    public static int listenPort() {
        try {
            java.io.File f = new java.io.File("/sdcard/ufi/port.txt");
            if (f.exists()) {
                FileInputStream fi = new FileInputStream(f);
                byte[] b = new byte[16];
                int n = fi.read(b);
                fi.close();
                if (n > 0) {
                    int p = Integer.parseInt(new String(b, 0, n, "UTF-8").trim());
                    if (p > 0 && p < 65536) return p;
                }
            }
        } catch (Throwable ignore) {}
        return DEFAULT_PORT;
    }

    public static synchronized void ensureStarted(Context c) {
        appCtx = c.getApplicationContext();
        if (serverThread != null && serverThread.isAlive()) return;
        serverThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runServer();
            }
        });
        serverThread.start();
    }

    static void runServer() {
        while (true) {
            try {
                int port = listenPort();
                Util.append(" proxy|listen|" + port + "\n");
                ServerSocket ss = new ServerSocket(port, 10, java.net.InetAddress.getByName("0.0.0.0"));
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
                Util.append(" proxy|restart|" + t + "\n");
                try { Thread.sleep(3000); } catch (Exception ignore) {}
            }
        }
    }

    // ---------------- request parsing ----------------

    static String readAsciiLine(InputStream in) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        boolean any = false;
        while (true) {
            int b = in.read();
            if (b == -1) break;
            any = true;
            if (b == '\n') break;
            buf.write(b);
        }
        if (!any) return null;
        byte[] arr = buf.toByteArray();
        int len = arr.length;
        if (len > 0 && arr[len - 1] == '\r') len--;
        return new String(arr, 0, len, "ISO-8859-1");
    }

    static byte[] readFully(InputStream in, int len) throws Exception {
        byte[] b = new byte[len];
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n < 0) break;
            off += n;
        }
        return b;
    }

    static void handle(Socket s) {
        InputStream in = null;
        OutputStream out = null;
        try {
            s.setSoTimeout(30000);
            in = s.getInputStream();
            out = s.getOutputStream();

            String reqLine = readAsciiLine(in);
            if (reqLine == null) return;
            String[] tok = reqLine.split(" ");
            if (tok.length < 2) return;
            final String method = tok[0].toUpperCase();
            String path = tok[1];

            Map<String, String> headers = new LinkedHashMap<String, String>();
            String line;
            while ((line = readAsciiLine(in)) != null && line.length() > 0) {
                int ci = line.indexOf(':');
                if (ci > 0) {
                    headers.put(line.substring(0, ci).trim().toLowerCase(),
                                line.substring(ci + 1).trim());
                }
            }

            byte[] body = null;
            String cl = headers.get("content-length");
            if (cl != null) {
                try {
                    int n = Integer.parseInt(cl.trim());
                    if (n > 0 && n < 1024 * 1024) body = readFully(in, n);
                } catch (NumberFormatException ignore) {}
            }

            String q = "";
            int qi = path.indexOf('?');
            String purePath = path;
            if (qi >= 0) {
                q = path.substring(qi + 1);
                purePath = path.substring(0, qi);
            }

            // ---- local endpoints ----
            if (purePath.equals("/api/sms")) {
                apiSms(q, headers, out);
                return;
            }
            if (purePath.equals("/api/logout")) {
                simpleJson(out, 200, "{\"ok\":1}");
                return;
            }
            if (purePath.equals("/sms-inbox.js")) {
                byte[] js = SmsInboxJs.JS.getBytes("UTF-8");
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/javascript; charset=utf-8" +
                        "\r\nContent-Length: " + js.length + "\r\nCache-Control: no-cache" +
                        "\r\nConnection: close\r\n\r\n").getBytes("ISO-8859-1"));
                out.write(js);
                out.flush();
                return;
            }

            // ---- reverse proxy to stock Web UI ----
            proxy(purePath, q, method, headers, body, out);

        } catch (Throwable t) {
            try {
                if (out != null) {
                    out.write(("HTTP/1.1 500 ERR\r\n\r\n" + t).getBytes("UTF-8"));
                }
            } catch (Exception ignore) {}
        } finally {
            try { s.close(); } catch (Throwable ignore) {}
        }
    }

    // ---------------- /api/sms ----------------

    static void apiSms(String query, Map<String, String> headers, OutputStream out) throws Exception {
        if (!Util.tokenOk(query) && !headerTokenOk(headers)) {
            simpleJson(out, 401, "{\"ok\":false,\"error\":\"Unauthorized. Provide valid token (?token=, X-API-Token, or Authorization: Bearer).\"}");
            return;
        }
        if (query != null && query.contains("sync=1")) {
            try { DbPuller.pull(appCtx); } catch (Throwable ignore) {}
        }
        File f = new File(Util.LOG);
        List<String[]> msgs = new ArrayList<String[]>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        // read + parse
        List<String> raw = readLines(f);
        for (String l : raw) {
            if (l.length() < 4) continue;
            if (l.startsWith(" srv|") || l.startsWith("ERR|") || l.startsWith("RF|") || l.startsWith("USSD|")) continue;
            String[] parts = l.split("\\|", -1);
            if (parts.length < 4) continue;
            String source = parts[0];
            String sender = parts[1];
            String date = parts[2];
            StringBuilder sb = new StringBuilder(parts[3]);
            for (int i = 4; i < parts.length; i++) sb.append('|').append(parts[i]);
            String bodyTxt = sb.toString().replace("\\n", "\n");
            String key = sender + "|" + date + "|" + bodyTxt;
            if (!seen.contains(key)) {
                seen.add(key);
                msgs.add(new String[]{source, sender, date, bodyTxt});
            }
        }
        // newest first (date format "YYYY-MM-DD HH:mm:ss" sorts lexicographically)
        Collections.sort(msgs, new Comparator<String[]>() {
            @Override
            public int compare(String[] a, String[] b) {
                return (b[2] == null ? "" : b[2]).compareTo(a[2] == null ? "" : a[2]);
            }
        });

        StringBuilder json = new StringBuilder("{\"ok\":true,\"count\":").append(msgs.size())
                .append(",\"messages\":[");
        boolean first = true;
        for (String[] m : msgs) {
            if (!first) json.append(',');
            first = false;
            String otp = extractOtp(m[3]);
            String id = Util.b64((m[1] + "|" + m[2] + "|" + m[3]).getBytes("UTF-8"));
            if (id.length() > 16) id = id.substring(0, 16);
            json.append("{\"id\":\"").append(jesc(id))
                .append("\",\"source\":\"").append(jesc(m[0]))
                .append("\",\"sender\":\"").append(jesc(m[1]))
                .append("\",\"date\":\"").append(jesc(m[2]))
                .append("\",\"body\":\"").append(jesc(m[3]))
                .append("\",\"otp\":").append(otp == null ? "null" : "\"" + jesc(otp) + "\"")
                .append('}');
        }
        json.append("]}");
        simpleJson(out, 200, json.toString());
    }

    static List<String> readLines(File f) {
        List<String> out = new ArrayList<String>();
        if (f == null || !f.exists()) return out;
        FileInputStream fi = null;
        try {
            fi = new FileInputStream(f);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = fi.read(b)) > 0) buf.write(b, 0, n);
            String[] lines = new String(buf.toByteArray(), "UTF-8").split("\n");
            for (String l : lines) out.add(l.replace("\r", ""));
        } catch (Throwable ignore) {
        } finally {
            try { if (fi != null) fi.close(); } catch (Throwable ignore) {}
        }
        return out;
    }

    static String extractOtp(String body) {
        if (body == null || body.length() == 0) return null;
        Pattern ctx = Pattern.compile("(otp|kode|code|verifikas|pin|password|rahasia|token)", Pattern.CASE_INSENSITIVE);
        if (!ctx.matcher(body).find()) return null;
        Matcher m1 = Pattern.compile("(?:otp|kode|code|verifikasi|pin|password)[^0-9a-zA-Z]{1,15}([0-9]{4,8})\\b",
                Pattern.CASE_INSENSITIVE).matcher(body);
        if (m1.find()) return m1.group(1);
        Matcher m2 = Pattern.compile("\\b([0-9]{4,8})\\b").matcher(body);
        if (m2.find()) return m2.group(1);
        return null;
    }

    /** Accepts X-API-Token or Authorization: Bearer when a token is configured. */
    static boolean headerTokenOk(Map<String, String> headers) {
        String tok = Util.readToken();
        if (tok.length() == 0) return false;
        String h = headers.get("x-api-token");
        if (tok.equals(h)) return true;
        String auth = headers.get("authorization");
        if (auth != null && auth.startsWith("Bearer ") && tok.equals(auth.substring(7).trim())) return true;
        return false;
    }

    static String jesc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    static void simpleJson(OutputStream out, int code, String json) throws Exception {
        byte[] b = json.getBytes("UTF-8");
        out.write(("HTTP/1.1 " + code + (code == 200 ? " OK" : code == 401 ? " Unauthorized" : " ERR") +
                "\r\nContent-Type: application/json; charset=utf-8" +
                "\r\nContent-Length: " + b.length +
                "\r\nCache-Control: no-cache, no-store, must-revalidate" +
                "\r\nConnection: close\r\n\r\n").getBytes("ISO-8859-1"));
        out.write(b);
        out.flush();
    }

    // ---------------- reverse proxy ----------------
    //
    // Uses raw sockets instead of HttpURLConnection: the stock NanoHTTPD emits
    // malformed headers (e.g. "Content-Length: -1", duplicated headers) which
    // make HttpURLConnection hang/throw. Raw parsing is tolerant, mirroring the
    // "insecureHTTPParser" approach of the original Node.js host proxy.

    static void proxy(String path, String query, String method, Map<String, String> reqHeaders,
                      byte[] reqBody, OutputStream out) throws Exception {
        boolean isHead = method.equals("HEAD");
        Socket up = new Socket();
        try {
            up.setSoTimeout(15000);
            up.connect(new java.net.InetSocketAddress("127.0.0.1", WEB_PORT), 5000);
        } catch (Throwable t) {
            out.write(("HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain\r\nConnection: close\r\n\r\nModem Web UI unreachable: " + t).getBytes("UTF-8"));
            return;
        }
        try {
            OutputStream uo = up.getOutputStream();
            InputStream ui = up.getInputStream();

            StringBuilder req = new StringBuilder();
            req.append(method).append(' ').append(path);
            if (query.length() > 0) req.append('?').append(query);
            req.append(" HTTP/1.0\r\n");
            req.append("Host: 127.0.0.1:").append(WEB_PORT).append("\r\n");
            req.append("Accept-Encoding: identity\r\n");
            req.append("Connection: close\r\n");
            for (Map.Entry<String, String> e : reqHeaders.entrySet()) {
                String k = e.getKey();
                if (k.equals("host") || k.equals("connection") || k.equals("keep-alive") ||
                    k.equals("transfer-encoding") || k.equals("content-length") ||
                    k.equals("accept-encoding")) continue;
                req.append(k).append(": ").append(e.getValue()).append("\r\n");
            }
            if (reqBody != null) {
                req.append("Content-Length: ").append(reqBody.length).append("\r\n");
            } else if (method.equals("POST") || method.equals("PUT") || method.equals("PATCH")) {
                // cpeweb's NanoHTTPD blocks forever on POSTs without Content-Length
                // (e.g. the frontend sends "Content-Length: 0" with an empty body) —
                // always frame body methods explicitly.
                req.append("Content-Length: 0\r\n");
            }
            req.append("\r\n");
            uo.write(req.toString().getBytes("ISO-8859-1"));
            if (reqBody != null) uo.write(reqBody);
            uo.flush();

            // status line
            String statusLine = readAsciiLine(ui);
            if (statusLine == null) return;
            int code = 200;
            try {
                String[] st = statusLine.split(" ");
                if (st.length >= 2) code = Integer.parseInt(st[1]);
            } catch (Throwable ignore) {}

            // headers (tolerant: keep first value, ignore malformed like "Content-Length: -1")
            Map<String, String> respHeaders = new LinkedHashMap<String, String>();
            String line;
            while ((line = readAsciiLine(ui)) != null && line.length() > 0) {
                int ci = line.indexOf(':');
                if (ci > 0) {
                    String k = line.substring(0, ci).trim().toLowerCase();
                    String v = line.substring(ci + 1).trim();
                    if (!respHeaders.containsKey(k)) respHeaders.put(k, v);
                }
            }

            String contentType = respHeaders.get("content-type");
            if (contentType == null) contentType = "";
            // cpeweb labels JSON API responses as "text/html" too — only treat the
            // payload as HTML (and inject the widget) when it really is markup,
            // otherwise the <script> tag would corrupt JSON responses.
            boolean isHtml = contentType.toLowerCase().contains("text/html");

            // Determine body framing: trust a sane positive content-length,
            // otherwise read until EOF (handles the "-1" quirk).
            int contentLen = -1;
            String cl = respHeaders.get("content-length");
            if (cl != null) {
                try {
                    int n = Integer.parseInt(cl.trim());
                    if (n >= 0) contentLen = n;
                } catch (Throwable ignore) {}
            }

            byte[] payload;
            if (contentLen >= 0) {
                payload = readFully(ui, contentLen);
            } else {
                ByteArrayOutputStream buf = new ByteArrayOutputStream();
                byte[] b = new byte[8192];
                int n;
                while ((n = ui.read(b)) > 0) buf.write(b, 0, n);
                payload = buf.toByteArray();
            }

            if (isHtml) {
                if (payload.length >= 2 && payload[0] == 0x1f && payload[1] == 0x8b) {
                    try {
                        GZIPInputStream gz = new GZIPInputStream(new java.io.ByteArrayInputStream(payload));
                        ByteArrayOutputStream b2 = new ByteArrayOutputStream();
                        byte[] b = new byte[8192];
                        int n;
                        while ((n = gz.read(b)) > 0) b2.write(b, 0, n);
                        payload = b2.toByteArray();
                    } catch (Throwable ignore) {}
                }
                String html = new String(payload, "UTF-8");
                // Inject only into real HTML documents (they end with </body>);
                // JSON/other payloads mislabeled as text/html are passed through untouched.
                int idx = html.lastIndexOf("</body>");
                if (idx >= 0) {
                    String scriptTag = "\n<script type=\"text/javascript\" src=\"/sms-inbox.js\"></script>\n";
                    html = html.substring(0, idx) + scriptTag + html.substring(idx);
                    payload = html.getBytes("UTF-8");
                }
            }

            StringBuilder h = new StringBuilder();
            h.append("HTTP/1.1 ").append(code).append(" OK\r\n");
            h.append("Content-Type: ").append(contentType.length() == 0 ? "application/octet-stream" : contentType).append("\r\n");
            h.append("Content-Length: ").append(isHead ? 0 : payload.length).append("\r\n");
            String cc = respHeaders.get("cache-control");
            h.append("Cache-Control: ").append(cc == null ? "no-cache, no-store, must-revalidate" : cc).append("\r\n");
            h.append("Connection: close\r\n\r\n");
            out.write(h.toString().getBytes("ISO-8859-1"));
            if (!isHead) out.write(payload);
            out.flush();
        } finally {
            try { up.close(); } catch (Throwable ignore) {}
        }
    }
}
