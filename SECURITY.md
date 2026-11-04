# Security

SpeakType keeps pairing and transcript transfer on the local network. TLS is pinned to the certificate fingerprint in the QR code. Session tokens are DPAPI-encrypted on Windows and AES-GCM in the Android Keystore.

## Supported versions

| Version | Supported |
| ------- | --------- |
| `latest` (main branch) | ✅ Yes |
| `vX.Y.Z` tags | ✅ Yes (latest tag) |
| Older tags | ❌ No |

Only the newest `vX.Y.Z` tag and the `main` branch receive security fixes.

## Reporting a vulnerability

Please report security issues privately via GitHub's security advisory feature rather than public issues.

1. Go to the repository's **Security** tab and click **Report a vulnerability** (GitHub Security Advisories).
2. Describe the issue, affected versions, and steps to reproduce.
3. Do not open a public issue or share the report outside GitHub until a fix is published.

We will acknowledge receipt, investigate, and coordinate disclosure with you.

## Scope

In scope:

- TLS pairing (certificate fingerprint pinning) and pairing QR flow.
- Token and key storage (DPAPI on Windows, Android Keystore AES-GCM).
- Local-network transcript transfer between the Android app and the Windows helper.

## Out of scope

- Vulnerabilities in third-party dependencies unless they directly affect the code above; report those upstream.
- Physical-access attacks, social engineering, and denial-of-service against local services.
- The Android speech provider's audio processing in Online mode.

## Response timeline

- Acknowledgement: within 7 days.
- Fix and coordinated disclosure: as soon as practical after validation; we will agree a disclosure date with the reporter.
