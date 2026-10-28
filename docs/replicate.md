# Replication Guide for Future SBC / Linux Devices

## 1. Prerequisites

1. Target host running Linux (Ubuntu, Debian, Armbian, Raspberry Pi OS, etc.).
2. Modem USB stick connected to the target host and recognized via ADB and USB network (`usb0`).
3. SSH access to the target host.

## 2. Copy Repository to Target Host

From your workstation:
```bash
rsync -avz --exclude '.git' /path/to/ufi-4g/ root@<NEW_HOST_IP>:/root/ufi-4g/
```

## 3. Run Provisioning Script

SSH into the target host and run:
```bash
sudo bash /root/ufi-4g/tools/provision.sh
```

The script will automatically:
- Install `adb` and `nodejs` if missing.
- Detect modem gateway IP on `usb0` interface.
- Install and start the SMS daemon APK (`app/ufi10.apk`) on the modem.
- Deploy proxy scripts to `/opt/ufi-sms/`.
- Configure and start `ufi-sms-web.service` on localhost (`127.0.0.1:8080`).
- Execute end-to-end verification.

## 4. Verification

Verify that the systemd service is active:
```bash
systemctl status ufi-sms-web.service
```

Test ADB and radio state:
```bash
adb devices
adb shell 'dumpsys telephony.registry' | grep -E "mServiceState|mSignalStrength"
```

## 5. Accessing the Dashboard

Forward port 8080 from the target host to your local machine:
```bash
ssh -L 8080:localhost:8080 root@<NEW_HOST_IP>
```
Then navigate to `http://localhost:8080/` in your local browser.
