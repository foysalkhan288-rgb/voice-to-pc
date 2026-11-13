# Privacy

SpeakType is local-first. There is no account, no cloud relay, no analytics, and no telemetry. The developer operates no server that your devices talk to.

## What leaves the phone

- **Audio: never to the PC.** The phone sends only recognized text over the local network. The microphone stream stays inside the phone's speech service.
- **Audio: possibly to the speech provider.** Recognition uses Android's speech service. With an offline language pack installed, recognition runs on-device. Without a pack, the service (e.g. Google) may process audio online, and the app retries online once if offline recognition is unavailable. If audio must never leave the phone, install the offline pack for your language.
- **Text: only to your paired PC.** Final transcripts and live partials go to the PC you paired with, over HTTPS on the same local network, pinned to the certificate fingerprint in the pairing QR code. The PC refuses non-local clients.

## What the PC does with text

- The final transcript is typed once into the focused window via Unicode keyboard events. The clipboard is never used and focus is never moved.
- Live partials are shown only in the dashboard's Live preview panel and expire after a few seconds.
- **No transcript history is kept on either side.** The PC persists only delivered message *ids* (not text) to suppress duplicate sends.

## Secrets and storage

| Item | Where | Protection |
|---|---|---|
| PC TLS key + certificate | `%LOCALAPPDATA%\SpeakType` | Self-signed, fingerprint pinned by the phone |
| PC session token | `session-token.dpapi` | Windows DPAPI (current user only) |
| Phone session token | App preferences | AES-GCM with an Android Keystore key |
| Settings | `settings.json` / preferences | Plain preferences, no personal data |

Unpairing clears the stored token and delivered ids on the PC; uninstalling the phone app drops its pairing.

## Permissions

- **Microphone** — dictation only, while you hold or tap the talk button.
- **Camera** — scanning the pairing QR code only.
- **Notifications** — the ongoing dictation/ready notification (Android 13+ asks once).
- **Install unknown apps** — only if you choose to install an in-app update; the system installer always asks for confirmation.

## Updates

The phone compares the SHA-256 of its installed APK with the build served by your PC (or, when unpaired, the GitHub release). Installs go through the system package installer, which enforces the same signing key and rejects downgrades. No update is installed without your tap and the system confirmation.

## Reporting

Privacy questions are welcome as public issues; security vulnerabilities belong in [private security advisories](../SECURITY.md).