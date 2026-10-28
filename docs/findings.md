# Device & Baseband Research Findings

## 1. SMS Reception Architecture (No Root Required)

Incoming SMS messages are processed through the standard Android Telephony stack:
```
Modem Baseband (CSFB/SGs attachment: service-domain = cs-ps)
    -> RIL framework
    -> Telephony SMS Provider (content://sms/inbox)
    -> com.ufi.smsfwd (BroadcastReceiver & DB Puller)
    -> Local HTTP API (:8088/sms.json)
    -> Host Reverse Proxy (:8080/api/sms)
```

Key observations:
- Baseband service-domain configuration `cs-ps` is persistent across reboots in Qualcomm NVRAM.
- `dumpsys telephony.registry` reports `mServiceState=0 0 home ... LTE` (voice in-service).
- Incoming OTP SMS (e.g. carrier verification messages) are received and parsed reliably without modifying system partitions.

## 2. Hardware Characteristics

- **USB VID/PID:**
  - Normal operation: `05c6:9024` (Qualcomm RNDIS + ADB composite device).
  - Transient boot window (approx 60-90s): `05c6:9091` factory composition (`/dev/cdc-wdm0`).
- **Baseband:** `MPSS.DPM.1.0.c7-00193-M8916EAAAANVZM-1` (Qualcomm MSM8916).
- **Kernel:** `Linux 3.10.28 SMP PREEMPT`.
- **Android OS:** 4.4.4 KitKat (API 19).
- **Host Interface:** `usb0` (USB RNDIS network interface with DHCP gateway, typically `192.168.18.1`).

## 3. Stock Modem Web Server Behavior

- Android runs NanoHTTPD inside `/system/priv-app/cpeweb.apk` on port 80.
- NanoHTTPD exhibits non-standard HTTP behavior:
  - Emits `Content-Length: -1` on HEAD requests.
  - Emits `Transfer-Encoding: chunked` simultaneously with gzip/raw payloads without proper chunk wrapping.
- The host reverse proxy (`server/ufi-sms-web.js`) strips hop-by-hop headers (`transfer-encoding`, `connection`, `keep-alive`), disables upstream gzip compression, and normalizes output into standard HTTP/1.1.
