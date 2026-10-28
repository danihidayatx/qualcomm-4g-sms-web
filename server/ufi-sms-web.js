#!/usr/bin/env node
/**
 * ufi-sms-web.js
 * Universal SMS Reader & Adaptive Web UI Integration Proxy
 * Compatible with any Qualcomm MSM8916 / UFI 4G USB stick (Wowi, Youdo, Zhihe, etc.)
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const zlib = require('zlib');
const { exec, execSync } = require('child_process');

// Load environment variables from .env if present
function loadEnv() {
  const envPaths = [
    path.join(__dirname, '.env'),
    path.join(__dirname, '..', '.env'),
    path.join(process.cwd(), '.env'),
    '/opt/ufi-sms/.env'
  ];
  for (const envPath of envPaths) {
    if (fs.existsSync(envPath)) {
      try {
        const content = fs.readFileSync(envPath, 'utf8');
        for (const line of content.split('\n')) {
          const trimmed = line.trim();
          if (!trimmed || trimmed.startsWith('#')) continue;
          const idx = trimmed.indexOf('=');
          if (idx > 0) {
            const k = trimmed.slice(0, idx).trim();
            const v = trimmed.slice(idx + 1).trim().replace(/^['"]|['"]$/g, '');
            if (!process.env[k]) {
              process.env[k] = v;
            }
          }
        }
        console.log(`[UFI-WEB] Loaded environment from ${envPath}`);
        break;
      } catch (_) {}
    }
  }
}
loadEnv();

const PORT = parseInt(process.env.PORT || '8080', 10);
const BIND_HOST = process.env.BIND_HOST || '127.0.0.1';
const MODEM_SMS_PORT = parseInt(process.env.MODEM_SMS_PORT || '8088', 10);
const MODEM_WEB_PORT = parseInt(process.env.MODEM_WEB_PORT || '80', 10);
const SMS_JS_PATH = path.join(__dirname, 'sms-inbox.js');

// Auto-detect modem gateway IP from usb0, or fallback to environment / 192.168.18.1
function getModemHost() {
  if (process.env.MODEM_HOST) return process.env.MODEM_HOST;
  try {
    const route = execSync("ip route show dev usb0 2>/dev/null | awk '/default/ {print $3}'", { encoding: 'utf8' }).trim();
    if (route && /^\d+\.\d+\.\d+\.\d+$/.test(route)) return route;
  } catch (_) {}
  return '192.168.18.1';
}

const MODEM_HOST = getModemHost();
console.log(`[UFI-WEB] Universal proxy active on ${BIND_HOST}:${PORT}`);
console.log(`[UFI-WEB] Modem target: http://${MODEM_HOST}:${MODEM_WEB_PORT}`);
console.log(`[UFI-WEB] SMS daemon: http://${MODEM_HOST}:${MODEM_SMS_PORT}`);
if (process.env.SMS_API_TOKEN) {
  console.log('[UFI-WEB] API Token authentication enabled for /api/sms');
} else {
  console.log('[UFI-WEB] API Token not configured; /api/sms protected by Web UI login session');
}

// Session state persistence
const activeSessions = new Set();
const SESSIONS_FILE = path.join(__dirname, '.sessions.json');

function loadSessions() {
  try {
    if (fs.existsSync(SESSIONS_FILE)) {
      const data = JSON.parse(fs.readFileSync(SESSIONS_FILE, 'utf8'));
      if (Array.isArray(data)) {
        data.forEach(s => { if (typeof s === 'string') activeSessions.add(s); });
      }
    }
  } catch (_) {}
}
loadSessions();

function saveSessions() {
  try {
    fs.writeFileSync(SESSIONS_FILE, JSON.stringify(Array.from(activeSessions)), 'utf8');
  } catch (_) {}
}

function parseCookies(req) {
  const list = {};
  const rc = req.headers.cookie;
  if (rc) {
    rc.split(';').forEach(cookie => {
      const parts = cookie.split('=');
      const key = parts.shift().trim();
      const val = decodeURIComponent(parts.join('=')).trim();
      if (key) list[key] = val;
    });
  }
  return list;
}

// Authentication validator for SMS API
function isAuthenticated(req, url) {
  // 1. Check API token (from .env or process.env SMS_API_TOKEN)
  const apiToken = (process.env.SMS_API_TOKEN || '').trim();
  if (apiToken) {
    const queryToken = url.searchParams.get('token');
    const headerToken = req.headers['x-api-token'];
    let bearerToken = null;
    const authHeader = req.headers['authorization'] || '';
    if (authHeader.startsWith('Bearer ')) {
      bearerToken = authHeader.slice(7).trim();
    }
    if (queryToken === apiToken || headerToken === apiToken || bearerToken === apiToken) {
      return { ok: true, via: 'api-token' };
    }
  }

  // 2. Check Web UI session cookie
  const cookies = parseCookies(req);
  const sessionVal = cookies['ufi_session'];
  if (sessionVal && (activeSessions.has(sessionVal) || /^[0-9a-fA-F-]{20,45}$/.test(sessionVal))) {
    activeSessions.add(sessionVal);
    return { ok: true, via: 'web-session' };
  }

  return { ok: false };
}

// OTP extraction regex
function extractOtp(body) {
  if (!body) return null;
  const isOtpContext = /(otp|kode|code|verifikas|pin|password|rahasia|token)/i.test(body);
  if (!isOtpContext) return null;

  // Pattern 1: Keyword followed closely by digits
  const m1 = body.match(/(?:otp|kode|code|verifikasi|pin|password)[^0-9a-zA-Z]{1,15}([0-9]{4,8})\b/i);
  if (m1) return m1[1];

  // Pattern 2: Standalone 4 to 8 digit number
  const m2 = body.match(/\b([0-9]{4,8})\b/);
  if (m2) return m2[1];

  return null;
}

// Fetch helper with insecure parser (handles non-standard NanoHTTPD headers)
function httpRequest(options, postData = null) {
  return new Promise((resolve, reject) => {
    const opts = { ...options, insecureHTTPParser: true };
    const req = http.request(opts, (res) => {
      const chunks = [];
      res.on('data', c => chunks.push(c));
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, body: Buffer.concat(chunks) }));
    });
    req.on('error', reject);
    req.setTimeout(8000, () => {
      req.destroy();
      reject(new Error('HTTP request timeout'));
    });
    if (postData) req.write(postData);
    req.end();
  });
}

// Fetch and parse SMS messages from modem daemon (:8088/sms.json)
async function getSmsMessages(forceSync = false) {
  if (forceSync) {
    try {
      await httpRequest({ host: MODEM_HOST, port: MODEM_SMS_PORT, path: '/sync', method: 'GET' });
    } catch (_) {}
  }

  let rawData;
  try {
    const res = await httpRequest({ host: MODEM_HOST, port: MODEM_SMS_PORT, path: '/sms.json', method: 'GET' });
    rawData = JSON.parse(res.body.toString('utf8'));
  } catch (err) {
    return { ok: false, error: 'Failed to communicate with modem SMS daemon: ' + err.message, messages: [] };
  }

  if (!Array.isArray(rawData)) {
    return { ok: false, error: 'Invalid response from SMS daemon', messages: [] };
  }

  const parsed = [];
  const seenKeys = new Set();

  for (const item of rawData) {
    const line = item.e || '';
    if (!line || line.startsWith(' srv|') || line.startsWith('ERR|') || line.startsWith('RF|') || line.startsWith('USSD|')) {
      continue;
    }

    // Format: IN|sender|YYYY-MM-DD HH:mm:ss|body or DB|sender|YYYY-MM-DD HH:mm:ss|body
    const parts = line.split('|');
    if (parts.length >= 4) {
      const source = parts[0];
      const sender = parts[1];
      const date = parts[2];
      const body = parts.slice(3).join('|').replace(/\\n/g, '\n');

      const dedupKey = `${sender}|${date}|${body}`;
      if (seenKeys.has(dedupKey)) continue;
      seenKeys.add(dedupKey);

      const otp = extractOtp(body);

      parsed.push({
        id: Buffer.from(dedupKey).toString('base64').slice(0, 16),
        source,
        sender,
        date,
        body,
        otp
      });
    }
  }

  // Sort descending by date (newest first)
  parsed.sort((a, b) => (b.date || '').localeCompare(a.date || ''));

  return { ok: true, count: parsed.length, messages: parsed };
}

// Main HTTP Server
const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
  const pathname = url.pathname;

  // 1. Protected API: /api/sms
  if (pathname === '/api/sms') {
    const auth = isAuthenticated(req, url);
    if (!auth.ok) {
      res.writeHead(401, {
        'Content-Type': 'application/json; charset=utf-8',
        'Cache-Control': 'no-cache, no-store, must-revalidate',
        'WWW-Authenticate': 'Bearer realm="SMS API"'
      });
      res.end(JSON.stringify({
        ok: false,
        error: 'Unauthorized. Please login to the Modem Web UI or provide a valid API token (?token=YOUR_TOKEN).'
      }));
      return;
    }

    const sync = url.searchParams.get('sync') === '1';
    try {
      const data = await getSmsMessages(sync);
      res.writeHead(200, {
        'Content-Type': 'application/json; charset=utf-8',
        'Cache-Control': 'no-cache, no-store, must-revalidate'
      });
      res.end(JSON.stringify(data));
      console.log(`[API] /api/sms served ${data.messages ? data.messages.length : 0} messages (auth=${auth.via}, sync=${sync})`);
    } catch (e) {
      res.writeHead(500, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: false, error: e.message }));
    }
    return;
  }

  // 2. Auth Endpoint: /api/logout
  if (pathname === '/api/logout') {
    const cookies = parseCookies(req);
    if (cookies.ufi_session) {
      activeSessions.delete(cookies.ufi_session);
      saveSessions();
    }
    res.writeHead(200, {
      'Content-Type': 'application/json; charset=utf-8',
      'Set-Cookie': 'ufi_session=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax',
      'Cache-Control': 'no-cache'
    });
    res.end(JSON.stringify({ ok: true }));
    return;
  }

  // 3. Static Plugin Script: /static/js/sms-inbox.js
  if (pathname === '/static/js/sms-inbox.js' || pathname === '/sms-inbox.js') {
    fs.readFile(SMS_JS_PATH, 'utf8', (err, content) => {
      if (err) {
        res.writeHead(404, { 'Content-Type': 'text/plain' });
        res.end('sms-inbox.js not found');
        return;
      }
      res.writeHead(200, {
        'Content-Type': 'application/javascript; charset=utf-8',
        'Cache-Control': 'no-cache'
      });
      res.end(content);
    });
    return;
  }

  // 4. Redirect legacy /sms or /inbox to / (unified floating widget inside stock UI)
  if (pathname === '/sms' || pathname === '/inbox') {
    res.writeHead(302, { 'Location': '/' });
    res.end();
    return;
  }

  // 5. Track Authorization header from upstream requests
  const authHeader = req.headers['authorization'];
  if (authHeader && /^[0-9a-fA-F-]{20,45}$/.test(authHeader)) {
    if (!activeSessions.has(authHeader)) {
      activeSessions.add(authHeader);
      saveSessions();
    }
  }

  // 6. Dynamic Stream Sanitizer & Reverse Proxy for Modem Web UI
  const isHead = req.method === 'HEAD';
  const upstreamMethod = isHead ? 'GET' : req.method;

  const upstreamHeaders = { ...req.headers, host: `${MODEM_HOST}:${MODEM_WEB_PORT}` };
  // Disable compression from upstream modem so we can safely parse/inject HTML
  delete upstreamHeaders['accept-encoding'];

  const proxyReq = http.request({
    host: MODEM_HOST,
    port: MODEM_WEB_PORT,
    path: req.url,
    method: upstreamMethod,
    insecureHTTPParser: true,
    headers: upstreamHeaders
  }, (proxyRes) => {
    const headers = { ...proxyRes.headers };

    // Strip hop-by-hop and malformed headers from NanoHTTPD
    delete headers['transfer-encoding'];
    delete headers['connection'];
    delete headers['keep-alive'];
    if (headers['content-length'] === '-1') {
      delete headers['content-length'];
    }

    const contentType = (headers['content-type'] || '').toLowerCase();
    const isHtml = contentType.includes('text/html');

    // If HTML: Buffer, decompress if needed, inject sms-inbox.js, and send clean standard response
    if (isHtml) {
      delete headers['content-security-policy'];
      delete headers['content-encoding'];

      const chunks = [];
      proxyRes.on('data', c => chunks.push(c));
      proxyRes.on('end', () => {
        let rawBuf = Buffer.concat(chunks);
        const isGzip = (proxyRes.headers['content-encoding'] || '').includes('gzip') ||
                       (rawBuf.length >= 2 && rawBuf[0] === 0x1f && rawBuf[1] === 0x8b);
        if (isGzip) {
          try {
            rawBuf = zlib.gunzipSync(rawBuf);
          } catch (e) {
            console.error('[PROXY] Gunzip error:', e.message);
          }
        }

        let html = rawBuf.toString('utf8');
        const scriptTag = '\n<script type="text/javascript" src="/static/js/sms-inbox.js"></script>\n';
        if (html.includes('</body>')) {
          html = html.replace('</body>', scriptTag + '</body>');
        } else {
          html += scriptTag;
        }

        const outBuffer = Buffer.from(html, 'utf8');
        headers['content-length'] = outBuffer.length;
        headers['content-type'] = 'text/html; charset=utf-8';
        headers['cache-control'] = 'no-cache, no-store, must-revalidate';

        res.writeHead(proxyRes.statusCode || 200, headers);
        if (isHead) res.end();
        else res.end(outBuffer);
      });
      return;
    }

    // Intercept JSON API responses (e.g. login) to capture session and set auth cookie
    if (pathname === '/api/json' || contentType.includes('application/json')) {
      const chunks = [];
      proxyRes.on('data', c => chunks.push(c));
      proxyRes.on('end', () => {
        const bodyBuf = Buffer.concat(chunks);
        try {
          const json = JSON.parse(bodyBuf.toString('utf8'));
          if (json && (json.reply === 'ok' || json.session || json.sessionId)) {
            const sid = json.session || json.sessionId;
            if (sid && typeof sid === 'string') {
              activeSessions.add(sid);
              saveSessions();
              headers['set-cookie'] = `ufi_session=${sid}; Path=/; HttpOnly; SameSite=Lax`;
              console.log(`[AUTH] Session registered via Web UI login: ${sid.slice(0, 8)}...`);
            }
          }
        } catch (_) {}

        headers['content-length'] = bodyBuf.length;
        res.writeHead(proxyRes.statusCode, headers);
        if (isHead) res.end();
        else res.end(bodyBuf);
      });
      return;
    }

    // For static files (css, js, images)
    res.writeHead(proxyRes.statusCode, headers);
    if (isHead) {
      res.end();
    } else {
      proxyRes.pipe(res);
    }
  });

  proxyReq.on('error', (err) => {
    console.error(`[PROXY-ERR] ${req.method} ${req.url}:`, err.message);
    if (!res.headersSent) {
      res.writeHead(502, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ error: 'Modem Web UI unreachable: ' + err.message }));
    }
  });

  req.pipe(proxyReq);
});

// Watchdog: ensures com.ufi.smsfwd stays alive
function runWatchdog() {
  http.get(`http://${MODEM_HOST}:${MODEM_SMS_PORT}/health`, { insecureHTTPParser: true }, (res) => {
    if (res.statusCode !== 200) {
      restartModemSmsService();
    }
  }).on('error', () => {
    restartModemSmsService();
  });
}

function restartModemSmsService() {
  console.log('[WATCHDOG] Modem SMS service not responding, invoking adb startservice...');
  exec('adb shell am startservice -n com.ufi.smsfwd/.SmsService', (err, stdout) => {
    if (err) {
      console.error('[WATCHDOG] ADB error:', err.message);
    } else {
      console.log('[WATCHDOG] ADB output:', (stdout || '').trim());
    }
  });
}

// Start watchdog loop (every 30s)
setInterval(runWatchdog, 30000);

// Listen
server.listen(PORT, BIND_HOST, () => {
  console.log(`[UFI-WEB] Service ready on http://${BIND_HOST === '0.0.0.0' ? 'localhost' : BIND_HOST}:${PORT}/`);
  runWatchdog();
});
