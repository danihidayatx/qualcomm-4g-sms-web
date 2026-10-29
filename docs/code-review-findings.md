# Code Review Findings — Host-Proxy Architecture

Review tanggal 2026-10-29 terhadap codebase (commit `bffd8d9`).
Temuan ini berlaku untuk arsitektur host-proxy (Node.js di SBC/PC + APK di modem).

> **Update:** arsitektur host-proxy telah **dihapus** dan diganti sepenuhnya oleh
> APK in-modem (`ProxyServer.java`, port 8081). Temuan #1, #8, #9, #10, #12, #14, #15
> menyangkut komponen host yang sudah dihapus dan menjadi tidak berlaku.
> Temuan #2, #4, #6 sudah diperbaiki di APK in-modem; #3 (abortBroadcast) masih tertunda
> uji SMS asli; #5 (keystore debug) disengaja untuk sideload; #7 tidak berlaku (tidak ada
> service host). Detail perbaikan ada di bagian checklist bawah.

---

## 🔴 Keamanan (Kritis)

### 1. Bypass autentikasi `/api/sms`
**File:** `server/ufi-sms-web.js:128`

```js
if (sessionVal && (activeSessions.has(sessionVal) || /^[0-9a-fA-F-]{20,45}$/.test(sessionVal))) {
```

Cookie `ufi_session` diterima **hanya berdasarkan format** (hex + dash, 20–45 karakter).
String apa pun berformat UUID (contoh: `ffffffff-ffff-4fff-8fff-ffffffffffff`) dianggap
terautentikasi tanpa pernah login.

**Perbaikan:** hapus fallback regex, validasi hanya terhadap `activeSessions`.

### 2. HTTP server modem tanpa auth, bind `0.0.0.0:8088`
**File:** `app/src/com/ufi/smsfwd/HttpServer.java:49`

Server di `SmsService` listen di semua interface tanpa autentikasi:
- `GET /raw` → seluruh isi `/sdcard/ufi/recv.txt` (semua SMS)
- `GET /ussd?code=...` → menjalankan panggilan USSD via `Intent.ACTION_CALL`
  (berisiko biaya/finansial, bisa memicu transaksi pulsa)
- `GET /tx`, `GET /info` → info kartu & perangkat

Siapa pun yang menjangkau modem (termasuk klien Wi-Fi AP modem) bisa memakainya.

**Perbaikan:** token sederhana di daemon, atau minimal jangan bind publik.

### 3. `abortBroadcast()` memblokir SMS aplikasi lain
**File:** `app/src/com/ufi/smsfwd/SmsReceiver.java:30`

Receiver ber-priority 999 memanggil `abortBroadcast()` pada SMS broadcast berurutan
(ordered broadcast). Receiver lain dengan prioritas lebih rendah bisa kehilangan SMS.
Komentar "harmless for others" keliru.

**Perbaikan:** hapus `abortBroadcast()`.

### 4. XSS tersimpan di widget inbox
**File:** `server/sms-inbox.js:223` (`buildTableRowsHtml`)

`${msg.sender}`, `${msg.date}`, dan `${otp}` disisipkan ke HTML/atribut tanpa escaping.
Field `sender` dikendalikan pengirim SMS (attacker-controlled) → bisa menyuntik
markup/script ke halaman UI modem. `bodyText` hanya escape `<`/`>` tapi tidak `&`.

**Perbaikan:** fungsi escapeHTML() untuk semua interpolasi, termasuk atribut `data-*`.

### 5. Password keystore hardcode & keystore ter-commit
**File:** `app/build.sh` — `--ks-pass pass:pass123`, dan `app/ks.jks` ikut di git.

### 6. Token API lewat query string (`?token=...`)
Muncul di log server/history browser. Header (`X-API-Token` / `Bearer`) sudah didukung —
sebaiknya jadikan rekomendasi utama.

### 7. Node jalan sebagai `root`
**File:** `server/systemd/ufi-sms-web.service` — `User=root` tanpa kebutuhan jelas.

---

## 🟠 Bug / Robustness

### 8. Crash DoS via cookie malformed
**File:** `server/ufi-sms-web.js:94-106` (`parseCookies`)

`decodeURIComponent` melempar `URIError` untuk `%` tidak valid. Exception sinkron di
listener request = proses Node crash. Perlu try/catch.

### 9. `.sessions.json` tumbuh tanpa batas
Setiap login tercatat permanen; tidak ada expiry/rotasi session.

### 10. `tools/provision.sh`
- `adb wait-for-device` bisa hang selamanya (tanpa timeout).
- Hanya mendukung `apt`, padahal README mengklaim dukungan OpenWrt.
- `.env` tidak pernah dibuat dari `.env.example` (hanya example yang di-copy).

### 11. `app/build.sh` tidak portable
Path hardcode `/tmp/opencode/sdk-platform19/...` dan `/tmp/opencode/sdk-bt30/...`
(artefak environment dev, tidak ada di mesin lain).

### 12. README.md baris 183–186
Sisa blok JSON duplikat yang terpotong (typo hasil editing).

---

## 🟡 Minor

13. OTP pattern-2 (`\b\d{4,8}\b`) bisa menangkap nomor telepon/rupiah dalam teks
    berkonteks "kode" → false positive ringan.
14. Proxy mem-buffer seluruh response HTML ke memori (OK untuk UI modem, bukan untuk umum).
15. `readFile()` di `HttpServer.java` tidak menutup stream saat exception (tanpa `finally`).

---

## Rencana: Arsitektur In-Modem (menggantikan host-proxy)

APK sendiri reverse-proxy `127.0.0.1:80` (cpeweb.apk / NanoHTTPD) pada port `:8081`,
inject `<script src="/sms-inbox.js">`, sajikan `/api/sms` dari APK. Tanpa SBC/Node/systemd.

Checklist saat migrasi — **porting juga temuan keamanan di atas:**
- [ ] #1: session validation hanya via store aktif (tanpa regex UUID fallback) — *N/A di arsitektur in-modem*
- [x] #2: endpoint `/raw`, `/ussd`, `/tx`, `/info`, `/sms.json` wajib token (via `/sdcard/ufi/token.txt`) — sudah di APK in-modem
- [ ] #3: tanpa `abortBroadcast()` — *dipertahankan dulu agar perilaku terima-SMS identik dengan APK lama; putuskan setelah uji SMS asli*
- [x] #4: escaping HTML di widget (sender/date/body/otp) via `escapeHtml()` di `server/sms-inbox.js`
- [x] #6: token via header/query di endpoint daemon
- [x] Proxy pakai raw socket tolerant terhadap `content-length: -1` & header aneh NanoHTTPD (mirip `insecureHTTPParser`)
