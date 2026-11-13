# Troubleshooting

## Phone cannot reach the PC

- Confirm both devices are on the same Wi-Fi network.
- In the SpeakType dashboard, look for the **Allow phone connections** banner. If it is visible, choose allow and confirm the Windows administrator prompt. The firewall rule covers only the SpeakType executable and the port it listens on.
- If you dismissed the prompt earlier, use the banner on the Dashboard or the **Firewall** row in Settings to add the rule later.
- On the phone, tap **Reconnect** to re-scan the subnet for the PC. The status dot shows green when the PC is reachable and red when it is not found.
- Check that no other app is holding the same port. Change the port in Settings only if you can also re-pair (see below).

## PC got a new Wi-Fi IP

- In most cases **Reconnect** on the phone finds the PC again automatically on the same subnet; no new QR scan is needed.
- If the PC is still red, open the **Pair phone** screen on the PC and scan the QR code again. A port change (Settings > Port) also requires a fresh scan if the phone was paired on the old port.

## Certificate fingerprint mismatch

- The phone pins the SHA-256 fingerprint scanned from the QR code. If the PC certificate was replaced, regenerated after corruption, or the Security screen shows a different fingerprint, the phone will refuse the connection.
- Open the **Security** screen on the PC, confirm the fingerprint, and if needed use **Replace certificate** (or repair a corrupt certificate, which the PC can regenerate). Then pair again with a fresh QR scan. Replacing the certificate invalidates the pairing.

## Install blocked — unknown sources

- When installing the APK from the [latest release](https://github.com/foysalkhan288-rgb/voice-to-pc/releases/tag/latest), Android asks you to allow SpeakType to install unknown apps. Grant it for SpeakType only.
- If the system dialog does not appear, open the phone's **Settings > Security > Install unknown apps** (wording varies by Android version), select **SpeakType**, and allow.

## Debug signing — installing a new build

- Every build is signed with one fixed key: `android-app/app/keystore/speaktype-debug.jks`. Android only replaces an installed app when the signing key matches.
- Install the new APK over the old one; do not uninstall first. Uninstalling drops the pairing and the saved session token.
- If you built locally with a different key, or imported a release key from GitHub Secrets, the install will fail with a signature mismatch. Reinstall the build signed with the shared debug key, or uninstall and re-pair.
- The PC serves the newest complete build it can find (Gradle output in `android-app/app/build/outputs/apk/debug/`, or a file copied with `windows\publish-apk.bat`). A half-written build is ignored until it is complete; the phone only offers an update when the SHA-256 differs.

## Dictation types nothing

- Click the target text field first. SpeakType types into the foreground window only; it does not take focus for you.
- SpeakType refuses to send on a secure desktop (sign-in, lock screen, UAC prompt). Focus a normal window and try again.
- Elevated apps (run-as-administrator) may block input from a non-elevated SpeakType. Run the target app non-elevated, or accept that such windows cannot receive the input.
- If fast typing drops characters in a specific app, raise **Typing delay** in Settings (0–100 ms). Leave it at 0 ms when not needed.

## Recognition answers sound wrong

- Recognition prefers the phone's on-device recognizer. Install the offline language pack for your language (Android settings → System → Languages → speech recognition offline packs, wording varies by vendor) to keep audio on the phone; without a pack the speech service may recognize online, and the app retries online once if offline recognition is unavailable.
- Banglish is local phonetic transliteration and may differ from your spelling conventions.
- English recognition uses `en-US` and then transliterates remaining Bengali words; it is not a full translator. On Android 14 or newer, the language button may offer automatic Bangla/English switching when the recognition service supports it.

## Still stuck?

- On the PC, the Security screen opens the data folder (`%LOCALAPPDATA%\SpeakType`). You can verify the certificate, token, and settings files exist.
- Unpair from the Security screen and pair again with a fresh QR code as a last resort. Unpairing clears delivered message ids and the stored session token on the PC.
- Check the [Architecture](ARCHITECTURE.md) and [API](API.md) docs for the exact endpoints and error codes.
