# Voice to PC

Speak into an Android phone and type the resulting text into the focused field on a Windows PC over the same Wi-Fi network.

The workspace contains two parts:

- `android-app/` — native Android app source. It uses Android's speech service, scans the Windows helper's pairing QR code, and sends one final transcript at a time.
- `windows-helper/` — Python tray helper. It accepts authenticated HTTPS messages on the local network and injects Unicode into the current foreground application without replacing the clipboard.

## Windows helper

Use Python 3.11 or newer on Windows 10/11. On first use, double-click `windows-helper/setup.bat`; it creates a private Python environment, installs the helper's dependencies, then starts the tray app. This step needs an internet connection to download those packages. Later, double-click `windows-helper/run.bat` to start it again.

```powershell
cd windows-helper
setup.bat
```

The helper appears in the system tray. Choose **ফোন pair করুন…**, then scan the QR code from the Android app. Allow Windows Firewall access on your private Wi-Fi network if prompted. The helper binds to port `48765`, rejects non-private client addresses, and uses a locally generated TLS certificate whose SHA-256 fingerprint is pinned by the phone app.

Before speaking, click the PC text field where the transcript should appear. The helper sends Unicode keyboard input to the foreground window; it does not take focus or alter the clipboard. It will refuse to send while Windows is on a secure desktop such as sign-in or a UAC prompt. Elevated apps may also block input from a non-elevated helper.

The helper stores its certificate and encrypted pairing token under `%LOCALAPPDATA%\\VoiceToTextPC`. It retains only recent message IDs to prevent duplicate typing after a retry; transcript text is never written to disk or logs.

## Android app

Open `android-app/` in Android Studio with JDK 17 or newer and install Android SDK platform 36. The project pins Gradle 9.6.0 and Android Gradle Plugin 9.4.0; the first build downloads Gradle and Android dependencies. Or, from `android-app/`, run `.\gradlew.bat assembleDebug`. The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

At first launch, select **Online** or **Offline only** recognition. Online recognition can send audio to the phone's speech provider. Offline mode uses Android's on-device recognizer only and never falls back to online; support depends on the phone and downloaded language models. The app requires microphone permission for dictation and camera permission to scan the pairing QR.

Tap the output button to switch between Bengali script and Banglish. Banglish uses a local phonetic transliteration; spellings can differ from personal conventions. On Android 14 or newer, the language button can request automatic Bangla/English switching when the recognition service supports it; otherwise select a language manually.

If the PC is disconnected, the unsent transcript stays only in the app's current memory. Retry it after reconnecting or discard it. No transcript history is kept.

## Pairing and privacy

Pairing and transcript transfer stay on the local network; the PC helper does not use a cloud relay. TLS is pinned to the certificate fingerprint in the QR code. The Android speech provider is separate from that PC connection: in Online mode it may process audio remotely, while Offline only uses a device model if one is available.

## Build an APK with GitHub Actions (no Android Studio)

The workflow in .github/workflows/build-android-apk.yml builds a debug APK on GitHub's hosted runner and uploads it as the voice-to-pc-debug-apk artifact. The build runs on pushes to the main branch; it can also be started manually from the repository's Actions tab.

To use it, create a private, empty GitHub repository, extract the project ZIP, and upload the extracted project files to the repository. Include the .github/workflows folder so GitHub can detect the build workflow. After the workflow finishes successfully, download the voice-to-pc-debug-apk artifact and extract app-debug.apk from it. This is a debug-signed APK for sideloading, not a Play Store release.

ZIP-to-APK converter sites do not compile Android source projects. A ZIP must already contain a compiled installable APK for an archive converter to produce an APK file; renaming or repacking the project ZIP does not create an Android application.
