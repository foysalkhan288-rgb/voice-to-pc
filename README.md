# SpeakType

<img src="docs/logo.png" alt="SpeakType" width="360">

Speak into an Android phone and type the resulting text into the focused field on a Windows PC over the same Wi-Fi network.

The workspace contains two parts:

- `android-app/` — native Android app source. It uses Android's speech service, scans the SpeakType pairing QR code, and sends one final transcript at a time.
- `windows/` — Python desktop app. It accepts authenticated HTTPS messages on the local network and injects Unicode into the current foreground application without replacing the clipboard.

## SpeakType for Windows

Use Python 3.11 or newer on Windows 10/11. On first use, double-click `windows/setup.bat`; it creates a private Python environment, installs the app's dependencies, then starts it. This step needs an internet connection to download those packages. Later, double-click `windows/run.bat` to start it again — it runs without a console window and opens the dashboard with a system tray icon beside it.

A standalone `windows/dist/SpeakType.exe` can be built with `windows/build-exe.bat` (requires the venv from `setup.bat`); it needs no Python install and registers itself to start with Windows on first launch.

Every icon — the exe, the tray, the window and the Android launcher — is generated from one drawing. After changing the artwork, run `windows/.venv/Scripts/python.exe tools/make_icons.py` and rebuild the exe.

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

The Security screen shows the certificate fingerprint the phone pins, lets you replace the certificate or unpair the phone, and opens the data folder. Replacing the certificate invalidates the pairing, so the phone must be paired again afterwards.

## Android app

Open `android-app/` in Android Studio with JDK 17 or newer and install Android SDK platform 36. The project pins Gradle 9.6.0 and Android Gradle Plugin 9.4.0; the first build downloads Gradle and Android dependencies. Or, from `android-app/`, run `.\gradlew.bat assembleDebug`. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

At first launch, select **Online** or **Offline only** recognition. Online recognition can send audio to the phone's speech provider. Offline mode uses Android's on-device recognizer only and never falls back to online; support depends on the phone and downloaded language models. The app requires microphone permission for dictation and camera permission to scan the pairing QR.

Tap the output button to cycle between Bengali script, Banglish, and English. Banglish uses a local phonetic transliteration; spellings can differ from personal conventions. English recognizes in `en-US` and then transliterates any Bengali words left in the transcript, so it is not a real translator. On Android 14 or newer, the language button can request automatic Bangla/English switching when the recognition service supports it; otherwise select a language manually.

**Start speaking** records one utterance and sends it automatically. **Hold to talk** listens while you keep your finger on it and finalizes when you lift it. While you speak, the words appear in SpeakType's **Live preview** panel on the PC; nothing is typed into the focused window until you stop, and the finished transcript is then typed once. While the phone is paired, a status dot shows the link: green means reachable, red means the PC was not found. **Reconnect** re-scans the local network for the PC, so a new router-assigned IP no longer forces a fresh QR scan.

If the PC is disconnected, the unsent transcript stays only in the app's current memory. Retry it after reconnecting or discard it. No transcript history is kept.

## Updating the app on the phone

Every build is signed with one fixed key, `android-app/app/keystore/speaktype-debug.jks`. Android only replaces an installed app when the signing key matches, so the key must stay the same; changing it forces an uninstall, which also drops the pairing. Increase `versionCode` in `android-app/app/build.gradle.kts` for every change you publish.

To move a new build onto the phone without copying files:

1. Build the APK (GitHub Actions artifact, or `.\gradlew.bat assembleDebug` locally).
2. Make sure SpeakType is running. It serves the newest APK it can find, so a build in `android-app/app/build/outputs/apk/debug/` is picked up within a couple of seconds and shown as **Android build served to the phone** on the dashboard. Running `windows\publish-apk.bat path\to\app-debug.apk` is only needed for an APK built somewhere else; it copies the file into the data folder.
3. On the phone, open **SpeakType** and tap **Check for app update**, then **Install update** and confirm the system dialog.

The app compares the SHA-256 of its own APK with the build served by the PC, so it only offers an update when the files differ. A half-written build is ignored until it is complete. The first time, Android asks you to allow SpeakType to install unknown apps. If no APK has been published, the check reports that nothing is available.

## Pairing and privacy

Pairing and transcript transfer stay on the local network; SpeakType does not use a cloud relay. TLS is pinned to the certificate fingerprint in the QR code. The Android speech provider is separate from that PC connection: in Online mode it may process audio remotely, while Offline only uses a device model if one is available.

## Build an APK with GitHub Actions (no Android Studio)

The workflow in .github/workflows/build-android-apk.yml builds a debug APK on GitHub's hosted runner and uploads it as the speaktype-debug-apk artifact. The build runs on pushes to the main branch; it can also be started manually from the repository's Actions tab.

To use it, create a private, empty GitHub repository, extract the project ZIP, and upload the extracted project files to the repository. Include the .github/workflows folder so GitHub can detect the build workflow. After the workflow finishes successfully, download the speaktype-debug-apk artifact and extract app-debug.apk from it. This is a debug-signed APK for sideloading, not a Play Store release.

ZIP-to-APK converter sites do not compile Android source projects. A ZIP must already contain a compiled installable APK for an archive converter to produce an APK file; renaming or repacking the project ZIP does not create an Android application.
