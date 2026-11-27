# Calibrate

Android TV app that checks the picture chain automatically, shows pixel-exact 4K SDR/HDR10 test patterns and
guides TV calibration step by step. Built for a Homatics Box R 4K Plus → Hue Sync Box 8K → soundbar → Xiaomi TV
S Mini LED 2025 65".

Package id: `io.github.kesemb2.calibrate` (fixed forever).

## Install on the TV

```
https://github.com/kesemb2/tvcali/releases/latest/download/calibrate.apk
```

Open **Downloader** on the Homatics, enter the URL, press **Go**, then **Install**. Every build is signed with the
same key, so updates install over the previous one.

## Reading a report from a computer

```
adb -s <BOX_IP>:5555 logcat -d -s Calibrate
```

Plan, measurements and progress (Hebrew): [PLAN.md](PLAN.md).
