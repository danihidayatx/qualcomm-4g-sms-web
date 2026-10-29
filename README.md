# Qualcomm 4G USB Modem SMS Web UI & OTP Reader (In-Modem APK)

A single Android APK that runs **entirely on the modem itself** — no SBC, no Raspberry Pi, no
Linux host, no Node.js, no systemd. The APK reverse-proxies the modem's stock Web UI
(`cpeweb.apk`, NanoHTTPD on port 80), injects a floating **SMS Inbox & OTP Reader** widget
into every page, and exposes a small token-protected JSON API.

Works on Qualcomm MSM8916 / Snapdragon 410 4G USB dongles (UFI003, UFI103, UFI JZ, Wowi,
Youdo, Zhihe, generic CPE, etc.) running the stock Android 4.4.4 (API 19) firmware.
**No root, no system-partition changes, no brick risk.**

---

## How it works

```
+----------------------------------------------------------+
| Client Browser                                           |
|   http://<MODEM_IP>:8081   (login + SMS/OTP widget)      |
|   http://<MODEM_IP>:8081/api/sms   (JSON, for bots)      |
+---------------------------+------------------------------+
                            | USB RNDIS (usb0)
                            v
+----------------------------------------------------------+
| Qualcomm 4G USB Modem (Android 4.4.4, API 19)            |
|                                                          |
| com.ufi.smsfwd (this APK)                                |
|  :8081  Web UI proxy + widget injection + /api/sms       |
|           \-> proxies 127.0.0.1:80 (cpeweb.apk UI)       |
|                                                          |
| SMS capture:                                             |
|  1. SmsReceiver  — SMS_RECEIVED broadcast -> recv.txt    |
|  2. DbPuller     — polls content://sms/inbox every 60s   |
|                    and on demand (/api/sms?sync=1)       |
|  log: /sdcard/ufi/recv.txt                               |
+----------------------------------------------------------+
```

Everything runs on the modem's own CPU via loopback. The only thing you need on your
computer is `adb` (to install the APK and, optionally, to forward the port).

## Key Features

1. **Stock UI + widget at one URL** — browse `http://<MODEM_IP>:8081`, log in with your
   normal modem Web UI credentials; an unobtrusive "SMS Inbox" floating button appears in
   the bottom-right corner. The button is hidden on the login screen.
2. **Automatic OTP detection & 1-click copy** — numeric OTP codes (4–8 digits) in OTP-like
   messages are extracted, highlighted, and copyable with one click.
3. **JSON API for automation** — `/api/sms` returns the parsed, deduplicated, newest-first
   inbox with OTPs pre-extracted. Optional token auth for Telegram/WhatsApp/cron bots.
4. **Self-healing** — `SmsService` is `START_STICKY` and auto-starts on boot
   (`BootReceiver`); the HTTP server restarts itself if it ever dies.
5. **Zero dependencies on the host** — install once via `adb`, done.

---

## 1. Build the APK (required)

There is **no prebuilt APK** in this repository — you build and sign it yourself:

```bash
git clone <this-repo> && cd qualcomm-4g-sms-web
bash app/build.sh
# -> app/out/ufi-sms-proxy.apk
```

Requirements on the build machine (macOS or Linux):
* **JDK 8+** (`brew install openjdk` / `apt install default-jdk`)
* `curl`, `unzip`, `python3`, `bash`

Everything else is downloaded automatically on first run into `~/.android-ufi-sdk`:
* `android.jar` API 19 (platform, for compiling against KitKat)
* Android build-tools r33 (`aapt`, `zipalign`, `apksigner`)
* `dx.jar` (dexer, taken from build-tools r30 — pure Java, works on macOS too)

The APK is signed with the debug keystore `app/ks.jks` (password in `app/build.sh`).
That is fine for sideloading via `adb`; do not publish this keystore if you fork the project.

Override locations via env: `SDK_DIR`, `ANDROID_JAR`, `DX_JAR` (see `app/build.sh`).

---

## 2. Install onto the modem

Plug the modem into your computer (it enumerates as a Qualcomm Android device), then:

```bash
adb install -r app/out/ufi-sms-proxy.apk
adb shell am startservice -n com.ufi.smsfwd/.SmsService
```

That's the whole installation. The service also starts automatically after modem reboots.

---

## 3. Usage

### Web UI (with SMS/OTP widget)

From a Linux host that has the modem's `usb0` network interface:

```
http://192.168.18.1:8081
```

(The modem's gateway IP varies per vendor — `192.168.18.1`, `192.168.0.1`, … — check your
`usb0` interface or the modem's sticker.)

**From macOS** (no RNDIS driver, so `usb0` does not exist): use adb port forwarding instead:

```bash
adb forward tcp:8081 tcp:8081
# then open http://localhost:8081
```

Log in with your normal modem Web UI credentials. After login, the **SMS Inbox** floating
button appears at the bottom-right; clicking it opens the inbox modal with a 10-second
auto-refresh, a "Sync SIM" button (forces an immediate telephony-inbox pull), and
copy buttons for OTP codes and full message bodies.

### JSON API (automation & bots)

| Endpoint | Description |
| :--- | :--- |
| `GET /api/sms` | Parsed inbox: deduplicated, newest first, OTPs pre-extracted |
| `GET /api/sms?sync=1` | Same, but first forces a re-poll of the SIM inbox (`DbPuller`) |
| `GET /api/logout` | Clears the session cookie (used by the widget) |
| `GET /sms-inbox.js` | The widget script (served by the APK itself) |

Example response:

```json
{
  "ok": true,
  "count": 1,
  "messages": [
    {
      "id": "Mzg4OHwyMDI2LTEw",
      "source": "DB",
      "sender": "3888",
      "date": "2026-10-29 19:22:10",
      "body": "Kode verifikasi Shopee: 918273. Jangan bagikan. Berlaku 5 menit",
      "otp": "918273"
    }
  ]
}
```

`source` is `IN` (captured live via broadcast) or `DB` (pulled from the telephony inbox).

---

## API token — and how it relates to the Web UI password

**The API token is completely separate from the modem Web UI password.**

* The **Web UI password** belongs to the modem's stock web interface (`cpeweb.apk`). It
  protects the browser UI and is what you type at the login page. This APK does not read,
  know, or change it.
* The **API token** is a secret **you create yourself** on the modem. It only protects the
  JSON API (`/api/sms`) from scripted access.

Setup:

```bash
adb shell 'echo -n "CHANGE_ME_STRONG_SECRET" > /sdcard/ufi/token.txt'
```

The file is read at every request — no service restart needed. Delete the file to disable
token protection entirely.

Behavior:

* **No token file** → `/api/sms` is open (fine when the modem is only reachable over USB).
* **Token file present** → every `/api/sms` request must supply the token via one of:

```bash
# query parameter
curl "http://localhost:8081/api/sms?token=CHANGE_ME_STRONG_SECRET"

# HTTP header
curl -H "X-API-Token: CHANGE_ME_STRONG_SECRET" http://localhost:8081/api/sms

# bearer authorization
curl -H "Authorization: Bearer CHANGE_ME_STRONG_SECRET" http://localhost:8081/api/sms
```

Wrong or missing token → `401 Unauthorized`. The floating widget does **not** need the
token (it runs in your already-authenticated browser session); the token is only for
external scripts and bots.

> If you expose the modem's Wi-Fi AP to other people, set a token — otherwise anyone on
> that network can read `/api/sms` (and the daemon port `8088`, see below).

---

## Port configuration

* **Proxy/widget/API port**: default **`8081`**. To change it, write the new port to a file
  on the modem and restart the service:

  ```bash
  adb shell 'echo 9090 > /sdcard/ufi/port.txt'      # proxy now listens on 9090
  adb shell am force-stop com.ufi.smsfwd
  adb shell am startservice -n com.ufi.smsfwd/.SmsService
  ```

  Valid range: 1–65535. Delete the file to return to 8081.

* **Internal SMS daemon port** (`/health`, `/sync`, `/raw`, legacy): fixed **`8088`**.
  Sensitive endpoints on it (`/raw`, `/ussd`, `/tx`, `/info`, `/sms.json`) honor the same
  token file when one is configured (`?token=...`).

* The **stock Web UI keeps its port 80**, untouched — you can still use
  `http://192.168.18.1/` normally without the widget.

---

## Files on the modem

| Path | Purpose |
| :--- | :--- |
| `/sdcard/ufi/recv.txt` | SMS log (`IN\|sender\|date\|body` / `DB\|...` lines) — source for `/api/sms` |
| `/sdcard/ufi/token.txt` | Optional API token (see above) |
| `/sdcard/ufi/port.txt` | Optional proxy port override (see above) |

Clear the stored SMS log:

```bash
adb shell 'rm -f /sdcard/ufi/recv.txt'
```

---

## Directory structure

```
qualcomm-4g-sms-web/
├── README.md                # this file
├── LICENSE                  # MIT
├── app/                     # Android APK (the only deployable component)
│   ├── AndroidManifest.xml  # permissions & components (API 19)
│   ├── build.sh             # self-contained build: downloads toolchain, compiles, signs
│   ├── ks.jks               # debug signing keystore (sideload only)
│   └── src/com/ufi/smsfwd/
│       ├── SmsReceiver.java # SMS_RECEIVED broadcast -> /sdcard/ufi/recv.txt
│       ├── DbPuller.java    # polls content://sms/inbox (60s + on-demand sync)
│       ├── SmsService.java  # START_STICKY service; starts both HTTP servers
│       ├── HttpServer.java  # internal daemon :8088 (health/sync/raw)
│       ├── ProxyServer.java # public proxy :8081 + /api/sms + widget injection
│       ├── BootReceiver.java# auto-start on boot
│       ├── Info.java, Tx.java, RfMode.java, KickReceiver.java, Util.java
├── server/
│   └── sms-inbox.js         # the widget (single source of truth; embedded into the
│                            #   APK at build time as generated SmsInboxJs.java)
└── docs/                    # hardware research notes & code-review findings
```

---

## Maintenance & Troubleshooting

```bash
# service running?
adb shell 'netstat -ltn | grep 8081'

# restart the service
adb shell am force-stop com.ufi.smsfwd
adb shell am startservice -n com.ufi.smsfwd/.SmsService

# logs (the APK logs to /sdcard/ufi/recv.txt and to logcat tag "ufi")
adb logcat -d -s ufi:W
```

* **Widget doesn't appear?** It only shows after you are logged into the stock Web UI
  (it hides itself on the login page by design).
* **`/api/sms` returns `ok:false, error:"Failed to communicate..."`** — the modem's SMS
  daemon on `:8088` is down; restarting `SmsService` (above) brings it back.
* **Wrong dates on messages?** Without a SIM card the modem's clock is never synchronized
  (it stays in 1970). With a SIM, network time fixes this automatically.
* **SMS not captured live?** The broadcast capture path (`SmsReceiver`) needs a real
  incoming SMS; the `DbPuller` poll path (60s / `sync=1`) picks up anything that lands in
  the telephony inbox regardless.

---

## Security notes

* Set an API token if the modem's interfaces (USB network, Wi-Fi AP) are reachable by
  others (see "API token" above).
* The internal daemon (`:8088`) exposes USSD dialing and raw SMS dumps; it is meant for
  the local loopback/host only. Token-protect it by creating `/sdcard/ufi/token.txt`.
* Message bodies and OTP codes live in `/sdcard/ufi/recv.txt` on the modem's (emulated)
  SD storage — wipe it if you resell the dongle.

## License

Licensed under the [MIT License](LICENSE). Free for personal and commercial use.
