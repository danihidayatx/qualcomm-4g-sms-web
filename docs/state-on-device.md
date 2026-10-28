# Artifact State on Device (Snapshot Reference)

## Modem Environment

`/data/local/tmp`:
- Cleaned up from experimental Dirty COW artifacts.
- No root exploits or system partition modifications are active.
- `/system/bin/syssn` is the stock ELF binary.
- Stock partition remains untouched (`/system` is read-only).

## Linux Host / SBC

Active services:
- `ufi-sms-web.service` — Node.js reverse proxy, header sanitizer, dynamic client injector, and SMS API watchdog.

## APK Package

- `com.ufi.smsfwd` is installed on the modem:
  - Listens for `SMS_RECEIVED` broadcasts.
  - Queries `content://sms/inbox` via `READ_SMS`.
  - Appends to `/sdcard/ufi/recv.txt`.
  - Serves JSON HTTP endpoints on port 8088 (`/sms.json`, `/health`, `/sync`).
