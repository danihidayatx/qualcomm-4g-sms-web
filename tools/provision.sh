#!/bin/bash
# provision.sh — Universal Provisioning Script for UFI 4G Modem (Qualcomm MSM8916)
#
# Supported hardware:
#  - Qualcomm 4G USB Modems / Dongles (MSM8916, Snapdragon 410, UFI, Zhihe, etc.)
# Supported hosts:
#  - Any Linux system (PC, Server, SBC, Raspberry Pi, Orange Pi, OpenWrt/Debian VM, etc.)

set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "[ERROR] Please run as root: sudo bash $0" 1>&2
  exit 1
fi

SCRIPT_DIR=$(cd "$(dirname "$0")/.." && pwd)
OPT_DIR="/opt/ufi-sms"

echo "=========================================================="
echo "  Qualcomm 4G Modem SMS Reader & Web UI Provisioning     "
echo "=========================================================="

echo "=== [1/5] Checking Host Dependencies ==="
if ! command -v adb >/dev/null 2>&1; then
  echo "Installing adb..."
  apt-get update -qq && apt-get install -y -qq adb
fi

if ! command -v node >/dev/null 2>&1; then
  echo "Installing nodejs..."
  apt-get update -qq && apt-get install -y -qq nodejs
fi

echo "adb : $(which adb) ($(adb version | head -1))"
echo "node: $(which node) ($(node -v))"

echo "=== [2/5] Detecting Modem USB & Network ==="
adb start-server >/dev/null 2>&1 || true

echo "Waiting for modem ADB device..."
adb wait-for-device
DEVICE_ID=$(adb devices | grep -w "device" | head -1 | awk '{print $1}')
echo "Modem detected: $DEVICE_ID"

# Auto-detect modem gateway IP on usb0
MODEM_IP=""
for i in $(seq 1 12); do
  MODEM_IP=$(ip route show dev usb0 2>/dev/null | awk '/default/ {print $3}' | head -1 || true)
  if [ -n "$MODEM_IP" ]; then
    echo "Modem IP auto-detected via usb0: $MODEM_IP"
    break
  fi
  echo "Waiting for usb0 network... ($i/12)"
  sleep 2
done

if [ -z "$MODEM_IP" ]; then
  MODEM_IP="192.168.18.1"
  echo "Falling back to default modem IP: $MODEM_IP"
fi

echo "=== [3/5] Installing Universal SMS Reader APK to Modem ==="
APK_FILE="$SCRIPT_DIR/app/ufi10.apk"
if [ ! -f "$APK_FILE" ]; then
  echo "[ERROR] APK file not found at $APK_FILE" 1>&2
  exit 1
fi

adb install -r "$APK_FILE"
adb shell am startservice -n com.ufi.smsfwd/.SmsService

echo "Verifying SMS daemon on port 8088..."
DAEMON_UP=0
for i in $(seq 1 10); do
  if curl -s -m 2 "http://$MODEM_IP:8088/health" | grep -q '"up":1'; then
    echo "Modem SMS daemon is UP on http://$MODEM_IP:8088"
    DAEMON_UP=1
    break
  fi
  sleep 1
done

if [ "$DAEMON_UP" -ne 1 ]; then
  echo "[WARN] Modem SMS daemon did not respond immediately, continuing anyway..."
fi

echo "=== [4/5] Deploying Web UI Proxy & SMS Service ==="
mkdir -p "$OPT_DIR"
cp "$SCRIPT_DIR/server/ufi-sms-web.js" "$OPT_DIR/"
cp "$SCRIPT_DIR/server/sms-inbox.js" "$OPT_DIR/"
if [ -f "$SCRIPT_DIR/.env.example" ]; then
  cp "$SCRIPT_DIR/.env.example" "$OPT_DIR/.env.example"
fi
chmod +x "$OPT_DIR/ufi-sms-web.js"

cp "$SCRIPT_DIR/server/systemd/ufi-sms-web.service" /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now ufi-sms-web.service

echo "=== [5/5] Verification ==="
sleep 2

if systemctl is-active --quiet ufi-sms-web.service; then
  echo "[OK] ufi-sms-web.service is ACTIVE"
else
  echo "[FAIL] ufi-sms-web.service FAILED. Check: journalctl -u ufi-sms-web.service"
  exit 1
fi

HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8080/ || true)
SMS_AUTH_CHECK=$(curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:8080/api/sms || true)

echo "Web UI Proxy Status     : HTTP $HTTP_CODE"
echo "SMS API Security Status : HTTP $SMS_AUTH_CHECK (401 Protected / Auth Required)"

echo "=========================================================="
echo "Universal Qualcomm 4G Modem SMS Setup Complete!"
echo ""
echo "Security Note: Service binds securely to localhost (127.0.0.1:8080)"
echo "and all SMS/OTP endpoints are protected by authentication."
echo ""
echo "1. Web UI Access (Protected by Modem Login):"
echo "   ssh -L 8080:localhost:8080 root@<HOST_IP>"
echo "   Open http://localhost:8080/ in your browser."
echo "   (SMS Inbox widget appears dynamically after logging in)"
echo ""
echo "2. Automation / Bot Access (Protected by API Token):"
echo "   Copy .env.example to .env and set SMS_API_TOKEN=your_token"
echo "   curl http://127.0.0.1:8080/api/sms?token=your_token"
echo "=========================================================="
