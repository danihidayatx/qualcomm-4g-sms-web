# QMI Baseband Diagnostics Reference

## Overview

During the transient boot stage (approx 60-90 seconds after boot), the Qualcomm modem briefly exposes USB composition `05c6:9091` (`/dev/cdc-wdm0`), which is bound to the Linux `qmi_wwan` driver.

## Radio State & Service Domain Preference

Key NAS commands:
- `qmicli -p -d /dev/cdc-wdm0 --nas-get-serving-system`
  - Reports CS and PS attachment status.
- `qmicli -p -d /dev/cdc-wdm0 --nas-get-system-selection-preference`
  - Field: `Service domain preference`.
  - When set to `cs-ps`, the modem baseband registers for both packet-switched data and circuit-switched fallback (CSFB/SGs) SMS reception.
  - Setting `cs-ps` in Qualcomm NVRAM persists across reboots.

## Note on Runtime Architecture

The production setup does NOT require running QMI polling services or background watchers. The userland Android SMS daemon (`com.ufi.smsfwd`) handles SMS reception continuously through Android's standard RIL and Telephony framework over normal USB composition (`05c6:9024`).
