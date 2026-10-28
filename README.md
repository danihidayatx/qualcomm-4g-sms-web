# Qualcomm 4G USB Modem SMS Web UI & OTP Reader

A universal, automated solution for **integrating an SMS Inbox and OTP extractor** (e.g. for carrier quota checks, 2FA/login OTPs) directly into Qualcomm 4G LTE USB modem sticks (**MSM8916, MSM8909, Snapdragon 410, UFI, Zhihe, etc.**) connected to **any Linux host** (PC, Mini PC, Server, SBC / Raspberry Pi / Orange Pi, OpenWrt, Debian VM, Proxmox, NAS, etc.).

This solution is **vendor-agnostic**: it works dynamically with different modem stock Web UI variants (Wowi, Youdo, Zhihe, generic CPE, etc.) without requiring root exploits (*no Dirty COW*) and without modifying the modem's system partition (*zero brick risk*).

---

## Key Features

1. **Secure Dual-Access Model:**
   * **Authenticated Floating Widget (`http://localhost:8080/`):** Integrates cleanly into the modem's stock Web UI as an unobtrusive floating button in the bottom-right corner. It dynamically appears only after the user logs in, protecting SMS messages and OTP codes from view on the login screen.
   * **Protected REST API (`/api/sms`):** Fully protected by session cookies for web browsers, and supports optional API token authentication (`SMS_API_TOKEN` via `.env`) for automation scripts, cron jobs, and notification bots (Telegram/WhatsApp). Unauthenticated access returns `HTTP 401 Unauthorized`.
2. **Automatic OTP Detection & 1-Click Copy:**
   * Automatically extracts numeric OTP codes (4-8 digits) from SMS message bodies.
   * Highlights detected OTP codes with a prominent badge and provides a **"Copy"** button to copy codes directly to the clipboard.
3. **Universal & Reusable Across Qualcomm Dongles:**
   * Works across various Qualcomm Android-based LTE stick vendors.
   * Automatically discovers the modem's gateway IP on the `usb0` interface (e.g., `192.168.18.1`, `192.168.0.1`).
   * Normalizes non-standard HTTP headers emitted by the modem's internal NanoHTTPD server on-the-fly.
4. **Lightweight & Privacy-Safe:**
   * Built entirely using native Node.js standard libraries (zero third-party `npm` dependencies).
   * Minimal host memory footprint (< 15 MB RAM).
   * Sensitive SMS bodies and OTP codes are not written to persistent system logs.
5. **Auto-Recovery Watchdog:**
   * Built-in background watchdog checks modem status every 30 seconds.
   * If the modem is unplugged or rebooted, the Android SMS daemon is automatically reinstated via ADB.

---

## System Architecture

```
+-------------------------------------------------------------+
| Client Browser (PC / Laptop / Phone)                        |
|                                                             |
| - URL: http://localhost:8080/     -> Stock UI + SMS Tab     |
| - URL: http://localhost:8080/sms  -> Standalone SMS Inbox   |
+------------------------------+------------------------------+
                               | HTTP :8080
                               v
+-------------------------------------------------------------+
| Linux Host (PC / Server / SBC / Mini PC / Router VM)        |
|                                                             |
| Service: `ufi-sms-web.service`                              |
| Script : `/opt/ufi-sms/ufi-sms-web.js`                      |
|  - Auto-detects modem gateway IP on usb0                    |
|  - Reverse-proxies modem Web UI & sanitizes NanoHTTPD       |
|  - Serves JSON API at `/api/sms` (dedup + OTP extraction)   |
|  - Injects `sms-inbox.js` client adapter                    |
|  - Background ADB health watchdog                           |
+------------------------------+------------------------------+
                               | USB RNDIS (usb0) + ADB
                               v
+-------------------------------------------------------------+
| Qualcomm 4G USB Modem (MSM8916 / Android)                   |
|                                                             |
| 1. Port 80 (cpeweb.apk / web server): Stock Modem Web UI    |
| 2. Port 8088 (`com.ufi.smsfwd`): Android SMS Daemon         |
|    - BroadcastReceiver: Captures incoming SMS_RECEIVED      |
|    - Telephony Inbox: Reads content://sms/inbox             |
+-------------------------------------------------------------+
```

---

## Requirements

* **Hardware:**
  * Qualcomm MSM8916 / Snapdragon 410 4G USB Modem (UFI003, UFI103, UFI JZ, etc.).
  * Linux Host (PC, Server, SBC, Mini PC, Raspberry Pi, Orange Pi, VM).
* **Software:**
  * Linux OS (Ubuntu, Debian, Armbian, Raspberry Pi OS, Arch, etc.).
  * Modem connected and recognized as a USB network device (`usb0`).

---

## Quick Installation

### Step 1: Copy repository to the Linux Host
From your workstation, copy this repository to the target Linux host:
```bash
rsync -avz --exclude '.git' /path/to/ufi-4g/ root@<HOST_IP>:/root/ufi-4g/
```

### Step 2: Run the provisioning script on the Host
Connect to the host via SSH and run:
```bash
sudo bash /root/ufi-4g/tools/provision.sh
```

The `provision.sh` script automatically:
1. Verifies and installs `adb` and `nodejs` packages if missing.
2. Waits for the modem ADB device and detects the modem IP on `usb0`.
3. Installs the signed SMS daemon APK (`app/ufi10.apk`) onto the modem.
4. Deploys proxy and client adapter files to `/opt/ufi-sms/`.
5. Configures, enables, and starts the systemd service `ufi-sms-web.service`.
6. Executes end-to-end automated connectivity tests.

---

## Usage

Following installation, the service binds securely to `127.0.0.1:8080`.

To access the Web UI from your computer/laptop:

1. Open an SSH port-forwarding tunnel in your terminal:
   ```bash
   ssh -L 8080:localhost:8080 root@<HOST_IP>
   ```
2. Open your browser:

### 1. Integrated Web UI
Open:
```
http://localhost:8080/
```
* **Login Protection:** The floating SMS button remains hidden on the login screen.
* **Floating Widget:** Immediately after logging in, an unobtrusive "SMS Inbox" floating button with a counter badge appears in the bottom-right corner. Clicking it opens a modal containing your SMS messages and extracted OTP codes.

### 2. Protected JSON API (Automation & Bots)

The `/api/sms` endpoint is protected by default:
* **From Web Browser:** Authenticated automatically via session cookie upon modem Web UI login.
* **From Automation Scripts / cURL / Bots:** Authenticated via API token.

#### Configuring an API Token:
1. Copy `.env.example` to `.env`:
   ```bash
   cp .env.example .env
   ```
2. Open `.env` and set your desired secret token:
   ```env
   SMS_API_TOKEN=your_secure_token_here
   ```
3. Restart the service to apply changes:
   ```bash
   sudo systemctl restart ufi-sms-web.service
   ```

#### Querying the API with a Token:
```bash
# Query via query parameter
curl "http://127.0.0.1:8080/api/sms?token=your_secure_token_here"

# Or query via HTTP Header
curl -H "X-API-Token: your_secure_token_here" http://127.0.0.1:8080/api/sms

# Or query via Bearer Authorization
curl -H "Authorization: Bearer your_secure_token_here" http://127.0.0.1:8080/api/sms

# Force resynchronization with modem SIM card inbox
curl "http://127.0.0.1:8080/api/sms?token=your_secure_token_here&sync=1"
```

Example JSON response:
```json
{
  "ok": true,
  "count": 1,
  "messages": [
    {
      "id": "YmltYSsgT1RQfDIw",
      "source": "IN",
      "sender": "bima+ OTP",
      "date": "2026-10-28 15:26:31",
      "body": "<#> OTP: 130807 is valid for 5 minutes.",
      "otp": "130807"
    }
  ]
}
```

If an unauthorized request is made without a session cookie or token, the API responds:
```json
HTTP/1.1 401 Unauthorized
{
  "ok": false,
  "error": "Unauthorized. Please login to the Modem Web UI or provide a valid API token (?token=YOUR_TOKEN)."
}
```
    }
  ]
}
```

---

## Network Access & Security (Localhost by Default)

A USB modem functions as a local peripheral dedicated exclusively to the host system it is attached to. To prevent unauthorized network eavesdropping on incoming OTP messages, the service binds by default to:

**`127.0.0.1:8080` (Localhost only)**

The service is accessible only from the host itself and is not exposed to external network interfaces by default.

---

### Access Methods

#### 1. SSH Port Forwarding (Recommended)
No firewall rules or router port changes required. Run on your client workstation:
```bash
ssh -L 8080:localhost:8080 user@<HOST_IP>
```
Then navigate to:
* Integrated Modem Web UI: **`http://localhost:8080/`**
* Standalone SMS Inbox: **`http://localhost:8080/sms`**

#### 2. Direct Access on Linux Host GUI
If the host runs a graphical desktop environment:
```
http://127.0.0.1:8080/
```

#### 3. Exposing to LAN / Tailscale / External Networks (Optional)
If you prefer to make the Web UI accessible across your local network (LAN/Wi-Fi) or VPN mesh without an SSH tunnel:
1. Edit the systemd service:
   ```bash
   sudo systemctl edit --full ufi-sms-web.service
   ```
2. Change `BIND_HOST` to `0.0.0.0`:
   ```ini
   Environment=BIND_HOST=0.0.0.0
   ```
3. Save and restart:
   ```bash
   sudo systemctl daemon-reload
   sudo systemctl restart ufi-sms-web.service
   ```
   The UI will now be reachable directly via the host's LAN or Tailscale IP (e.g., `http://192.168.1.50:8080/` or `http://100.89.7.3:8080/`).

#### 4. Reverse Proxy Setup (Nginx, Caddy, Cloudflare Tunnel)
To publish the dashboard under a private or internal domain with SSL/TLS, forward incoming traffic to `http://127.0.0.1:8080`.
Sample Nginx block:
```nginx
location / {
    proxy_pass http://127.0.0.1:8080;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
}
```

---

## Configuration Reference

Service configuration can be adjusted using environment variables in the systemd service definition (`/etc/systemd/system/ufi-sms-web.service`):

| Variable | Default | Description |
| :--- | :--- | :--- |
| `PORT` | `8080` | Local HTTP listening port on the host system. |
| `BIND_HOST` | `127.0.0.1` | Network interface to bind (`127.0.0.1` for local-only security, or `0.0.0.0` for all interfaces). |
| `MODEM_HOST` | `Auto-detect` | Gateway IP address of the modem on `usb0` (automatically discovered, e.g. `192.168.18.1`). |
| `MODEM_WEB_PORT` | `80` | Upstream port of the modem's native Web UI. |
| `MODEM_SMS_PORT` | `8088` | Upstream port of the modem's internal Android SMS daemon. |

---

## Directory Structure

```
ufi-4g/
├── README.md               # Primary project documentation
├── app/                    # Android SMS daemon source and build scripts (API 19 / KK 4.4.4)
│   ├── AndroidManifest.xml # Permission declarations and intent receivers
│   ├── build.sh            # Standalone APK compilation script
│   ├── src/                # Java source code (SmsReceiver, DbPuller, HttpServer)
│   └── ufi10.apk           # Pre-compiled and signed APK
├── server/                 # Host proxy and client integration components
│   ├── ufi-sms-web.js      # Reverse proxy, stream sanitizer, and SMS API server
│   ├── sms-inbox.js        # Universal client-side DOM injector and UI widget
│   └── systemd/            # Systemd service unit definition
├── tools/
│   └── provision.sh        # Automated single-command installation script
└── docs/                   # Hardware notes and research references
```

---

## Maintenance & Troubleshooting

### Check service status on host:
```bash
sudo systemctl status ufi-sms-web.service
```

### View real-time service logs:
```bash
sudo journalctl -u ufi-sms-web.service -f
```

### Verify ADB connectivity with the modem:
```bash
adb devices
adb shell 'dumpsys telephony.registry' | grep -E "mServiceState|mSignalStrength"
```

### Send a synthetic SMS for testing:
```bash
adb shell 'echo "IN|bima+|$(date \"+%Y-%m-%d %H:%M:%S\")|Your verification code is: 772910." >> /sdcard/ufi/recv.txt'
```

### Clear stored SMS message log on the modem:
```bash
adb shell 'rm -f /sdcard/ufi/recv.txt'
```

---

## License
Licensed under the [MIT License](LICENSE). Free for personal and commercial use.
