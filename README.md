# Phoneix

**Your phone, on your PC.** Phoneix links an Android phone to a Windows PC over your local network:
notifications, clipboard, files, calls, screen mirroring, a system-monitor "second screen" and a stream-deck-style button pad.

> 🇹🇷 **Türkçe kullanım kılavuzu:** [USER_MANUAL.tr.md](USER_MANUAL.tr.md)

Phoneix is made of two apps:

| Part | Folder | Stack |
|---|---|---|
| Android app | [`android/`](android) | Kotlin, plain Views (no Compose), minSdk 29 |
| Windows app | [`desktop/`](desktop) | Electron 33 |

It also works with [Dynamic Bar](https://github.com/sekullar/dynamic-bar-for-windows): phone notifications and
"phone connected" balloons show up on the bar, and you can drop files on the bar to send them to your phone.

## Features

- **Notifications** – pick which phone apps to forward; they appear on the PC. Messages with a reply action (WhatsApp, etc.) can be answered from the PC.
- **Calls** – see who is calling, answer, reject, hang up or toggle speaker from the PC; the phone can be made to ring to find it.
- **Clipboard** – text copied on the PC lands on the phone; send the phone's clipboard back via a Quick Settings tile or the Share sheet.
- **Files** – send files both ways (up to large files, chunked and encrypted); drag and drop on the PC.
- **Screen mirroring** – a soft, iOS-style mirroring window built on [scrcpy](https://github.com/Genymobile/scrcpy). It works over USB, or wirelessly: Phoneix switches on wireless debugging on the phone and connects by itself (about 3 s from cold).
- **System monitor mode** – the phone becomes a landscape second screen showing CPU, GPU, RAM, VRAM, temperatures and network speed from the PC.
- **Deck mode** – a button pad on the phone. Buttons are defined on the PC and can open URLs or apps (Start Menu picker, with icons), send key combos, control media, lock the PC, start mirroring, trigger Dynamic Bar, or (opt-in) run commands.
- **Themes** – light-blue animated theme on both apps; the phone also has a dark mode (Settings → Theme: Dark / Light / System).

## How it is built

- **Encrypted LAN protocol.** AES-256-GCM with a key exchanged by QR code at pairing, replay window of 60 s, rate limiting, and bare `404` for anything that does not authenticate. The full description is in [PROTOCOL.md](PROTOCOL.md).
- **Deck is safe by design.** The phone only sends a button id; what that button does is looked up in the PC's own `deck.json`. Shell commands are disabled unless you turn them on in the desktop app.
- **Local API.** The desktop app exposes `127.0.0.1:<port>` (token protected, details in the protocol doc) so other tools can send files, switch phone modes or toggle mirroring.

## Requirements

- Windows 10/11 and an Android 10+ phone on the same network (or a USB cable for mirroring)
- [Node.js](https://nodejs.org) 18+ to run the desktop app from source
- For mirroring: [scrcpy](https://github.com/Genymobile/scrcpy/releases) in `%USERPROFILE%\Android\scrcpy` (or `PHONEIX_SCRCPY`, or on `PATH`) and Android `platform-tools` (`adb`) in `%USERPROFILE%\Android\Sdk\platform-tools` (or `ANDROID_HOME`, or next to scrcpy)
- Only to build the APK yourself: Android SDK (set `ANDROID_HOME`) and JDK 17

## Install (no building needed)

1. **Phone:** download [`releases/Phoneix-0.2.0.apk`](releases/Phoneix-0.2.0.apk) on your phone and open it (Android will ask you to allow installing from your browser).
2. **PC:** install [Node.js](https://nodejs.org), then run:

   ```powershell
   cd desktop
   npm install
   npm start
   ```

3. Open Phoneix on both sides, scan the QR code shown by the desktop app, and confirm the PC name on the phone.
4. Optional, lets Phoneix turn wireless debugging on by itself for mirroring (run once with the phone connected by USB): `adb shell pm grant app.phoneix android.permission.WRITE_SECURE_SETTINGS`

The APK is signed with a debug key: fine for personal use, not a Play Store build.

## Build the Android app yourself

```powershell
cd android
$env:ANDROID_HOME = "C:\Users\<you>\Android\Sdk"
.\gradlew.bat assembleRelease   # app\build\outputs\apk\release\app-release.apk
```

## Known limitations

- **CPU temperature** needs a sensor source Windows only gives to administrators. In the desktop app (Phone modes → CPU temperature) press **Enable**: Phoneix downloads the open-source [LibreHardwareMonitor](https://github.com/LibreHardwareMonitor/LibreHardwareMonitor) and runs it at logon through a scheduled task created with a single admin (UAC) approval, then reads the value from its local web server (127.0.0.1 only). Without it the monitor shows "—" for CPU; GPU temperature (NVIDIA) works without it.
- Android may block opening the monitor/deck screen while another app is in the foreground; Phoneix then shows a notification to tap.
- Wireless mirroring needs wireless debugging; some phones switch it off on network changes, in which case Phoneix reconnects the next time you start mirroring.

## License

MIT
