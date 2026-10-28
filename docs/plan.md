# Engineering Roadmap & Architecture History

## 1. Selected Implementation Path: No-Root Telephony Integration

Initial research explored root exploitation (CVE-2016-5195 Dirty COW) versus clean userland Android Telephony integration. The clean userland approach was selected:
- **Zero brick risk:** Does not alter system partitions (`/system` remains read-only).
- **Vendor-agnostic:** Works across multiple Qualcomm modem vendors without device-specific binary patches.
- **Persistent:** Does not rely on transient memory exploits that reset across reboots.

## 2. SMS Reception Pipeline

- Modem baseband CSFB/SGs attachment is active (`cs-ps` domain).
- The `com.ufi.smsfwd` daemon uses standard Android Telephony APIs:
  - Broadcast receiver for `android.provider.Telephony.SMS_RECEIVED`.
  - Database reader for `content://sms/inbox` using `READ_SMS` permission.
  - Exposes `/sms.json` on HTTP port 8088.

## 3. Host Proxy Architecture

- The Node.js reverse proxy (`server/ufi-sms-web.js`) sits between the client browser and the modem:
  - Sanitizes NanoHTTPD header bugs.
  - Automatically injects the client widget script (`sms-inbox.js`).
  - Protects the `/api/sms` endpoint with session cookies and optional API tokens.
  - Watchdog monitors modem health and auto-starts the daemon when needed.
