# SpeakType

[![CI](https://github.com/foysalkhan288-rgb/voice-to-pc/actions/workflows/build-android-apk.yml/badge.svg)](https://github.com/foysalkhan288-rgb/voice-to-pc/actions/workflows/build-android-apk.yml)
[![Release](https://img.shields.io/github/v/release/foysalkhan288-rgb/voice-to-pc?include_prereleases)](https://github.com/foysalkhan288-rgb/voice-to-pc/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

<img src="docs/logo.png" alt="SpeakType" width="360">

Speak into an Android phone and type the resulting text into the focused field on a Windows PC over the same Wi-Fi network.

The workspace contains two parts:

- `android-app/` — native Android app source. It uses Android's speech service, scans the SpeakType pairing QR code, and sends one final transcript at a time.
- `windows/` — Python desktop app. It accepts authenticated HTTPS messages on the local network and injects Unicode into the current foreground application without replacing the clipboard.

## Screenshots

<img src="docs/logo.png" alt="SpeakType logo" width="320">

SpeakType — Android voice dictation typed into the focused Windows window.

See the repository for the latest build: [Releases](https://github.com/foysalkhan288-rgb/voice-to-pc/releases).

## Features

- Voice dictation from Android phone to Windows PC on the same Wi-Fi network.
- Unicode keyboard injection into the focused window — no clipboard use and no focus steal.
- Refuses to send while Windows is on a secure desktop such as sign-in or a UAC prompt.
- Live preview of partial transcripts on the PC; only the final transcript is typed, once.
- QR-code pairing with TLS pinned to the certificate fingerprint in the QR code.
- Pairing survives a PC restart; the phone rediscovers the PC after a Wi-Fi IP change, on the same subnet.
- Android output in Bengali script, Banglish, and English.
- Online or Offline only speech recognition (offline never falls back to online).
- `Start speaking` (one utterance) and `Hold to talk` (finalize on release).
- Windows tray app with dashboard, pairing, settings, and security screens.
- Automatic Windows Firewall rule with one administrator prompt.
- Optional autostart with Windows and tray notifications.
- In-app Android update from the PC-served build, compared by SHA-256.

## System Requirements

### End users

- Windows 10 or 11 PC and an Android phone on the same Wi-Fi network.
- Android phone with microphone (required) and camera (required to scan the pairing QR code).

### Building from source

- Windows: Python 3.11 or newer on Windows 10/11.
- Android: JDK 17 or newer and Android SDK platform 36 (Gradle 9.6.0 and Android Gradle Plugin 9.4.0).

## Install

Get the latest build from [GitHub Releases](https://github.com/foysalkhan288-rgb/voice-to-pc/releases) — the `latest` tag carries the Android builds (`app-debug.apk`, `app-release.apk`) and `SHA256SUMS.txt`. The Windows exe is built locally with `windows/build-exe.bat` (see below); if a `SpeakType.exe` is attached to a release, it is downloaded from the same page.

### Windows — SpeakType.exe

From a release download:

1. Download `SpeakType.exe` from the [latest release](https://github.com/foysalkhan288-rgb/voice-to-pc/releases/tag/latest).
2. Run it. On first launch it registers itself to start with Windows and opens the dashboard with a tray icon.
3. The first time it runs, answer **Yes** in the firewall prompt (confirm the Windows administrator prompt).

From source (no release exe):

```batch
cd windows
setup.bat
```

`windows/setup.bat` creates a private Python environment, installs dependencies (needs internet), then starts the app. Later runs use `windows/run.bat` — no console window:

```batch
windows\run.bat
```

A standalone `windows/dist/SpeakType.exe` can be built with `windows/build-exe.bat` (requires the venv from `setup.bat`); it needs no Python install and registers itself to start with Windows on first launch.

Every icon — the exe, the tray, the window and the Android launcher — is generated from one drawing. After changing the artwork, run `windows/.venv/Scripts/python.exe tools/make_icons.py` and rebuild the exe.

### Android — APK

1. Open the [latest release](https://github.com/foysalkhan288-rgb/voice-to-pc/releases/tag/latest) on the phone and download `app-debug.apk` (or `app-release.apk`).
2. Allow SpeakType to install unknown apps when Android asks.
3. Install the APK, open **SpeakType**, then pair using the QR code shown on the PC (**Pair phone** screen).

Or build locally with Android Studio or Gradle:

```batch
cd android-app
.\gradlew.bat assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## SpeakType for Windows

The dashboard has four screens on the left: **Dashboard**, **Pair phone**, **Settings**, and **Security**. The dashboard shows whether SpeakType is listening, whether a phone is paired, this PC's name and local address, how many transcripts have been typed, and a live activity feed. Closing the window keeps SpeakType running in the tray; **Quit** in the tray menu actually exits it. A colored dot next to the version number shows the state: green means running, amber means running but no phone paired yet, red means stopped.

Before the first dictation, click the target text field where the transcript should appear. SpeakType sends Unicode keyboard input to the foreground window; it does not take focus or alter the clipboard. It will refuse to send while Windows is on a secure desktop such as sign-in or a UAC prompt. Elevated apps may also block input from a non-elevated app.

Keep in mind, SpeakType stores its certificate, encrypted pairing token, and settings under `%LOCALAPPDATA%\SpeakType`. A pairing survives a PC restart; if the PC gets a new Wi-Fi IP, the phone rediscovers it automatically on the same subnet.

### Windows Firewall

Windows blocks incoming connections by default, so SpeakType checks on every start whether the firewall already allows it. If it does not, the dashboard shows a **Allow phone connections** banner and SpeakType asks once, the first time it runs. Answer **Yes**, confirm the Windows administrator prompt, and the rule is added for you — there is no command to type.

If you dismissed it earlier, use the banner on the Dashboard or the **Firewall** row in Settings to add the rule later. SpeakType only ever opens the port it listens on, for its own executable.

### Settings (PC side)

Everything on the Settings screen is saved automatically to disk and applied at once:

- **Port** — the TCP port SpeakType listens on (1024–65535). Changing it restarts SpeakType; re-scan the QR code if the phone was paired on the old port.
- **Typing delay** — a pause between typed characters, 0 to 100 ms. Instant typing works everywhere, but a small delay helps applications that drop fast input.
- **Notifications** — tray balloons when a transcript arrives.
- **Start minimized** — launches straight to the tray without showing the window.
- **Start with Windows** — registry autostart, so SpeakType comes back after every boot without any manual step.
- **Firewall** — shows whether Windows allows your phone to reach this PC, with a button that adds the rule through one administrator prompt.
- **Theme** — dark or light.

| Setting | Default | Effect |
|---|---|---|
| Port | `48765` | TCP port SpeakType listens on (1024–65535). Changing it restarts SpeakType; re-scan the QR code. |
| Typing delay | `0 ms` | Pause between typed characters, 0–100 ms. |
| Notifications | On | Tray balloons when a transcript arrives. |
| Start minimized | Off | Launches straight to the tray. |
| Start with Windows | On | Registry autostart after every boot. |
| Firewall | Prompt on first run | Allow phone connections for the SpeakType executable only. |
| Theme | Dark | Dark or light dashboard. |

The Security screen shows the certificate fingerprint the phone pins, lets you replace the certificate or unpair the phone, and opens the data folder. Replacing the certificate invalidates the pairing, so the phone must be paired again afterwards.

## Android app

At first launch, select **Online** or **Offline only** recognition. Online recognition can send audio to the phone's speech provider. Offline mode uses Android's on-device recognizer only and never falls back to online; support depends on the phone and downloaded language models. The app requires microphone permission for dictation and camera permission to scan the pairing QR.

Tap the output button to cycle between Bengali script, Banglish, and English. Banglish uses a local phonetic transliteration; spellings can differ from personal conventions. English recognizes in `en-US` and then transliterates any Bengali words left in the transcript, so it is not a real translator. On Android 14 or newer, the language button can request automatic Bangla/English switching when the recognition service supports it; otherwise select a language manually.

**Start speaking** records one utterance and sends it automatically. **Hold to talk** listens while you keep your finger on it and finalizes when you lift it. While you speak, the words appear in SpeakType's **Live preview** panel on the PC; nothing is typed into the focused window until you stop, and the finished transcript is then typed once. While the phone is paired, a status dot shows the link: green means reachable, red means the PC was not found. **Reconnect** re-scans the local network for the PC, so a new router-assigned IP no longer forces a fresh QR scan.

If the PC is disconnected, the unsent transcript stays only in the app's current memory. Retry it after reconnecting or discard it. No transcript history is kept.

## Updating the app on the phone

Every build is signed with one fixed key, `android-app/app/keystore/speaktype-debug.jks`. Android only replaces an installed app when the signing key matches, so the key must stay the same; changing it forces an uninstall, which also drops the pairing. Increase `versionCode` in `android-app/app/build.gradle.kts` for every change you publish.

To move a new build onto the phone without copying files:

1. Build the APK (GitHub Actions artifact, or `.\gradlew.bat assembleDebug` locally), or download it from the [latest release](https://github.com/foysalkhan288-rgb/voice-to-pc/releases/tag/latest).
2. Make sure SpeakType is running. It serves the newest APK it can find, so a build in `android-app/app/build/outputs/apk/debug/` is picked up within a couple of seconds and shown as **Android build served to the phone** on the dashboard. Running `windows\publish-apk.bat path\to\app-debug.apk` is only needed for an APK built somewhere else; it copies the file into the data folder.
3. On the phone, open **SpeakType** and tap **Check for app update**, then **Install update** and confirm the system dialog.

The app compares the SHA-256 of its own APK with the build served by the PC, so it only offers an update when the files differ. A half-written build is ignored until it is complete. The first time, Android asks you to allow SpeakType to install unknown apps. If no APK has been published, the check reports that nothing is available.

## Pairing and privacy

Pairing and transcript transfer stay on the local network; SpeakType does not use a cloud relay. TLS is pinned to the certificate fingerprint in the QR code. The Android speech provider is separate from that PC connection: in Online mode it may process audio remotely, while Offline only uses a device model if one is available.

## Uninstall

### Windows

1. Quit SpeakType from the tray menu (**Quit**).
2. In **Settings**, turn **Start with Windows** off (removes the registry autostart), or delete the `SpeakType` entry under `Software\Microsoft\Windows\CurrentVersion\Run` in `HKCU`.
3. Delete `%LOCALAPPDATA%\SpeakType` (certificate, encrypted pairing token, and settings).
4. Delete `SpeakType.exe` (or the `windows` source folder) if you no longer want it.

### Android

Uninstall **SpeakType** from the phone's app list. Uninstalling drops the saved pairing on the phone; the PC keeps its own certificate until you unpair from the Security screen if you want it removed there too.

## Troubleshooting / FAQ

- **The phone cannot see the PC** — check the Windows Firewall banner on the Dashboard and allow the connection, and confirm both devices are on the same Wi-Fi. See [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md).
- **The PC got a new IP** — most of the time **Reconnect** on the phone finds the PC again on the same subnet. If it cannot, re-scan the QR code from **Pair phone**.
- **Certificate fingerprint mismatch** — replace the certificate from the Security screen, then pair again. The phone pins the fingerprint, so a new certificate needs a new pairing.
- **Install blocked / unknown sources prompt** — allow SpeakType to install unknown apps, and make sure the new APK is signed with the same fixed debug key. See [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md).
- **Nothing was typed** — click the target text field first, and try again outside sign-in or a UAC prompt (secure desktop). Elevated apps may block non-elevated input.

Full guide: [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md). API details: [docs/API.md](docs/API.md). Architecture: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Build an APK with GitHub Actions (no Android Studio)

The workflow in .github/workflows/build-android-apk.yml builds a debug APK on GitHub's hosted runner and uploads it as the speaktype-debug-apk artifact. The build runs on pushes to the main branch; it can also be started manually from the repository's Actions tab.

To use it, create a private, empty GitHub repository, extract the project ZIP, and upload the extracted project files to the repository. Include the .github/workflows folder so GitHub can detect the build workflow. After the workflow finishes successfully, download the speaktype-debug-apk artifact and extract app-debug.apk from it. This is a debug-signed APK for sideloading, not a Play Store release.

ZIP-to-APK converter sites do not compile Android source projects. A ZIP must already contain a compiled installable APK for an archive converter to produce an APK file; renaming or repacking the project ZIP does not create an Android application.

## Documentation

- [Architecture](docs/ARCHITECTURE.md)
- [API](docs/API.md)
- [Troubleshooting](docs/TROUBLESHOOTING.md)
- [Changelog](CHANGELOG.md)
- [Contributing](CONTRIBUTING.md)
- [Code of Conduct](CODE_OF_CONDUCT.md)
- [Security](SECURITY.md)
- [Releases](https://github.com/foysalkhan288-rgb/voice-to-pc/releases)

## License

SpeakType is MIT-licensed. See [LICENSE](LICENSE).
