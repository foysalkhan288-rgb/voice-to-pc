# API

The Windows app serves a small HTTPS API on the local network. Base URL: `https://<pc-lan-ip>:<port>` (default port `48765`). All requests require a private, loopback, or link-local client address; public addresses get `403 local_network_only`. TLS 1.2 or newer. JSON responses carry `Cache-Control: no-store`.

## Global rules

- Request body cap: 64 KB (`MAX_BODY`). Valid JSON object only, otherwise `400 invalid_request`.
- `text` cap: 12,000 characters (`MAX_TEXT`). Longer text is `400 invalid_text`.
- Authenticated endpoints expect `Authorization: Bearer <session-token>`. Missing or wrong tokens get `401 not_paired`.
- Token comparison is constant-time (`hmac.compare_digest`).

## `GET /v1/health`

Discovery probe. No auth.

Response `200`:

```json
{ "ok": true, "paired": true, "hostname": "DESKTOP-EXAMPLE" }
```

| Field | Meaning |
|---|---|
| `ok` | Always `true` when the PC is reachable. |
| `paired` | Whether a session token exists on the PC. |
| `hostname` | PC hostname. |

## `POST /v1/pair`

Exchange a one-time bootstrap code (from the QR code) for a session token. No `Authorization` header.

Request:

```json
{ "code": "<bootstrap-code>" }
```

Response `200`:

```json
{ "ok": true, "session_token": "<token>", "hostname": "DESKTOP-EXAMPLE" }
```

Errors:

| Status | `error` | Meaning |
|---|---|---|
| `401` | `pair_code_expired` | Code missing, wrong, or older than 180 seconds. Show a fresh QR code and retry. |
| `400` | `invalid_request` | Body is not a JSON object or exceeds 64 KB. |

The code is consumed on success. The token is stored DPAPI-encrypted on the PC and AES-GCM-encrypted in the Android Keystore.

## `POST /v1/send`

Type one final transcript into the focused window. Requires auth.

Request:

```json
{ "id": "<uuid>", "text": "Hello world" }
```

- `id`: message id, string up to 80 characters. Used for duplicate suppression.
- `text`: transcript, non-empty after sanitising, up to 12,000 characters. Carriage returns, line feeds, and tabs are replaced with spaces; control characters below `U+0020` (except `U+00A0`) are stripped. A single trailing space is appended unless the text already ends with a space or no-break space, so consecutive dictations do not stick together in the target field.

Success `200`:

```json
{ "ok": true, "duplicate": false }
```

Duplicate `200`:

```json
{ "ok": true, "duplicate": true }
```

Errors:

| Status | `error` | Extra | Meaning |
|---|---|---|---|
| `401` | `not_paired` | — | Missing or wrong `Authorization`. |
| `400` | `invalid_text` | — | `id` or `text` missing, empty, or too long. |
| `400` | `empty_text` | — | Nothing left after sanitising. |
| `409` | `secure_desktop` | — | Windows is on a secure desktop (logon, UAC). Focus a normal window and retry. |
| `409` | `no_foreground_window` | — | No foreground window to receive input. |
| `409` | `delivery_uncertain` | `uncertain: true` | A send with the same `id` is in progress or its outcome is unknown. Retry with a new `id`; the PC will not double-type the same `id` once it is `done`. |
| `500` | `input_failed` | `uncertain: true` when partial | `SendInput` failed or typed only part of the text. |
| `500` | `delivery_state_unavailable` | — | Delivery state could not be persisted. |

## `POST /v1/preview`

Live partial transcript for the PC dashboard. Never typed into the keyboard. Requires auth.

Request:

```json
{ "text": "partial words…" }
```

Response `200`: `{ "ok": true }`. Only the newest text is kept; older text is dropped.

Errors:

| Status | `error` | Meaning |
|---|---|---|
| `401` | `not_paired` | Missing or wrong `Authorization`. |
| `400` | `invalid_text` | `text` missing or over 12,000 characters. |

## `GET /v1/app-release`

APK metadata for the update check. Requires auth.

Response `200` when an APK is available:

```json
{ "ok": true, "size": 12345678, "sha256": "…hex…", "modified": 1234567890 }
```

Errors:

| Status | `error` | Meaning |
|---|---|---|
| `401` | `not_paired` | Missing or wrong `Authorization`. |
| `404` | `no_app_release` | No complete APK is available to serve yet. |

## `GET /v1/app.apk`

APK bytes. Requires auth. Content type `application/vnd.android.package-archive`.

Response `200`: the `.apk` file bytes. The client compares the SHA-256 with the metadata from `/v1/app-release` before installing.

Errors:

| Status | `error` | Meaning |
|---|---|---|
| `401` | `not_paired` | Missing or wrong `Authorization`. |
| `404` | `no_app_release` | No complete APK is available to serve yet. |

## Discovery and rediscovery

The phone checks `/v1/health` on the subnet during **Reconnect** to find the PC after an IP change. Once the PC is found, the fingerprint is re-validated and the stored session token is reused; no fresh QR scan is needed unless the fingerprint changed.
