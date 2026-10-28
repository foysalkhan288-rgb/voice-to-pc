from __future__ import annotations

import ctypes
import datetime
import hashlib
import hmac
import http.server
import ipaddress
import json
import os
import secrets
import socket
import ssl
import sys
import threading
import time
import winreg
from ctypes import wintypes
from pathlib import Path
from typing import Callable
import qrcode
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID
from PIL import Image
from settings import AppSettings, app_dir


APP_NAME = "Voice to PC"
APP_DIR = app_dir()
PORT = 48765
MAX_BODY = 64 * 1024
MAX_TEXT = 12_000
PAIR_TTL_SECONDS = 180
MAX_DELIVERED_IDS = 512
APP_RELEASE_NAME = "voice-to-pc.apk"
PREVIEW_TTL_SECONDS = 6.0
RELEASE_WATCH_SECONDS = 2.0
MIN_APK_BYTES = 64 * 1024
# The Gradle output, so a fresh build is served without running publish-apk.bat.
BUILD_APK_PATH = (
    Path(__file__).resolve().parent.parent
    / "android-app"
    / "app"
    / "build"
    / "outputs"
    / "apk"
    / "debug"
    / "app-debug.apk"
)
STARTUP_RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
STARTUP_ENTRY = "VoiceToPC"
DEVICE_NAME = socket.gethostname()

KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_UNICODE = 0x0004
INPUT_KEYBOARD = 1
UOI_NAME = 2
DESKTOP_READOBJECTS = 0x0001


class DataBlob(ctypes.Structure):
    _fields_ = [("cbData", wintypes.DWORD), ("pbData", ctypes.POINTER(ctypes.c_ubyte))]


crypt32 = ctypes.WinDLL("crypt32", use_last_error=True)
kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
user32 = ctypes.WinDLL("user32", use_last_error=True)
crypt32.CryptProtectData.argtypes = (
    ctypes.POINTER(DataBlob), wintypes.LPCWSTR, ctypes.POINTER(DataBlob),
    wintypes.LPVOID, wintypes.LPVOID, wintypes.DWORD, ctypes.POINTER(DataBlob),
)
crypt32.CryptProtectData.restype = wintypes.BOOL
crypt32.CryptUnprotectData.argtypes = (
    ctypes.POINTER(DataBlob), ctypes.POINTER(wintypes.LPWSTR),
    ctypes.POINTER(DataBlob), wintypes.LPVOID, wintypes.LPVOID,
    wintypes.DWORD, ctypes.POINTER(DataBlob),
)
crypt32.CryptUnprotectData.restype = wintypes.BOOL
kernel32.LocalFree.argtypes = (wintypes.HLOCAL,)
kernel32.LocalFree.restype = wintypes.HLOCAL
user32.OpenInputDesktop.argtypes = (wintypes.DWORD, wintypes.BOOL, wintypes.DWORD)
user32.OpenInputDesktop.restype = wintypes.HANDLE
user32.GetUserObjectInformationW.argtypes = (
    wintypes.HANDLE, ctypes.c_int, wintypes.LPVOID,
    wintypes.DWORD, ctypes.POINTER(wintypes.DWORD),
)
user32.GetUserObjectInformationW.restype = wintypes.BOOL
user32.CloseDesktop.argtypes = (wintypes.HANDLE,)
user32.CloseDesktop.restype = wintypes.BOOL
user32.GetForegroundWindow.argtypes = ()
user32.GetForegroundWindow.restype = wintypes.HWND


def _as_blob(data: bytes):
    buffer = ctypes.create_string_buffer(data)
    blob = DataBlob(len(data), ctypes.cast(buffer, ctypes.POINTER(ctypes.c_ubyte)))
    return blob, buffer


def protect_for_current_user(data: bytes) -> bytes:
    """Use Windows DPAPI so the saved phone token is readable only by this user."""
    source, keep_alive = _as_blob(data)
    destination = DataBlob()
    if not crypt32.CryptProtectData(
        ctypes.byref(source), None, None, None, None, 0x1, ctypes.byref(destination)
    ):
        raise ctypes.WinError()
    try:
        return ctypes.string_at(destination.pbData, destination.cbData)
    finally:
        kernel32.LocalFree(destination.pbData)


def unprotect_for_current_user(data: bytes) -> bytes:
    source, keep_alive = _as_blob(data)
    destination = DataBlob()
    if not crypt32.CryptUnprotectData(
        ctypes.byref(source), None, None, None, None, 0x1, ctypes.byref(destination)
    ):
        raise ctypes.WinError()
    try:
        return ctypes.string_at(destination.pbData, destination.cbData)
    finally:
        kernel32.LocalFree(destination.pbData)


def save_session_token(token: str, app_dir_path: Path | None = None) -> None:
    directory = app_dir_path or APP_DIR
    directory.mkdir(parents=True, exist_ok=True)
    temporary = directory / "session-token.tmp"
    temporary.write_bytes(protect_for_current_user(token.encode("utf-8")))
    os.replace(temporary, directory / "session-token.dpapi")


def load_session_token(app_dir_path: Path | None = None) -> str | None:
    try:
        return unprotect_for_current_user(
            (app_dir_path or APP_DIR).joinpath("session-token.dpapi").read_bytes()
        ).decode("utf-8")
    except FileNotFoundError:
        return None
    except (OSError, UnicodeError):
        return None


def delete_session_token(app_dir_path: Path | None = None) -> None:
    try:
        (app_dir_path or APP_DIR).joinpath("session-token.dpapi").unlink()
    except FileNotFoundError:
        pass


def ensure_certificate(app_dir_path: Path | None = None) -> tuple[str, str, str]:
    """Create a local self-signed certificate; the Android app pins its fingerprint."""
    directory = app_dir_path or APP_DIR
    key_path = directory / "server-key.pem"
    cert_path = directory / "server-cert.pem"
    directory.mkdir(parents=True, exist_ok=True)
    if key_path.exists() and cert_path.exists():
        try:
            certificate = x509.load_pem_x509_certificate(cert_path.read_bytes())
            fingerprint = certificate.fingerprint(hashes.SHA256()).hex()
            return str(key_path), str(cert_path), fingerprint
        except (ValueError, OSError):
            for name in ("server-key.pem", "server-cert.pem"):
                try:
                    (directory / name).unlink()
                except FileNotFoundError:
                    pass

    private_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    subject = issuer = x509.Name(
        [x509.NameAttribute(NameOID.COMMON_NAME, "Voice to PC local helper")]
    )
    now = datetime.datetime.now(datetime.timezone.utc)
    certificate = (
        x509.CertificateBuilder()
        .subject_name(subject)
        .issuer_name(issuer)
        .public_key(private_key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - datetime.timedelta(minutes=5))
        .not_valid_after(now + datetime.timedelta(days=3650))
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .add_extension(
            x509.ExtendedKeyUsage([ExtendedKeyUsageOID.SERVER_AUTH]), critical=False
        )
        .sign(private_key, hashes.SHA256())
    )
    key_path.write_bytes(
        private_key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
    )
    cert_path.write_bytes(certificate.public_bytes(serialization.Encoding.PEM))
    return (
        str(key_path),
        str(cert_path),
        certificate.fingerprint(hashes.SHA256()).hex(),
    )


def local_ipv4_address() -> str:
    """Discover the address Windows would use on the LAN without sending data."""
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("192.0.2.1", 9))
        address = sock.getsockname()[0]
        if address and not address.startswith("127."):
            return address
    except OSError:
        pass
    finally:
        sock.close()
    return "127.0.0.1"


def client_is_private(address: str) -> bool:
    try:
        parsed = ipaddress.ip_address(address.split("%", 1)[0])
        return parsed.is_private or parsed.is_loopback or parsed.is_link_local
    except ValueError:
        return False


def apk_looks_complete(path: Path) -> bool:
    """A real APK is a ZIP with a central directory at the end; a half-written build is not."""
    try:
        size = path.stat().st_size
        if size < MIN_APK_BYTES:
            return False
        with path.open("rb") as handle:
            if handle.read(4) != b"PK\x03\x04":
                return False
            handle.seek(max(0, size - 66560))
            return b"PK\x05\x06" in handle.read()
    except OSError:
        return False


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def newest_apk(paths) -> Path | None:
    """The freshest complete APK wins, so a Gradle build needs no publish step."""
    newest: tuple[Path, int] | None = None
    for path in paths:
        if not apk_looks_complete(path):
            continue
        try:
            mtime = path.stat().st_mtime_ns
        except OSError:
            continue
        if newest is None or mtime > newest[1]:
            newest = (path, mtime)
    return newest[0] if newest else None


def on_interactive_desktop() -> bool:
    """Reject lock/UAC secure desktops before inserting any characters."""
    desktop = user32.OpenInputDesktop(0, False, DESKTOP_READOBJECTS)
    if not desktop:
        return False
    try:
        buffer = ctypes.create_unicode_buffer(128)
        needed = wintypes.DWORD()
        ok = user32.GetUserObjectInformationW(
            desktop, UOI_NAME, buffer, ctypes.sizeof(buffer), ctypes.byref(needed)
        )
        return bool(ok and buffer.value.casefold() == "default")
    finally:
        user32.CloseDesktop(desktop)


ULONG_PTR = wintypes.WPARAM


class KeyboardInput(ctypes.Structure):
    _fields_ = [
        ("wVk", wintypes.WORD),
        ("wScan", wintypes.WORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ULONG_PTR),
    ]


class InputUnion(ctypes.Union):
    _fields_ = [
        ("ki", KeyboardInput),
        # INPUT's union is sized by MOUSEINPUT on Windows, larger than KEYBDINPUT.
        ("padding", ctypes.c_byte * (32 if ctypes.sizeof(ctypes.c_void_p) == 8 else 24)),
    ]


class InputEvent(ctypes.Structure):
    _fields_ = [("type", wintypes.DWORD), ("union", InputUnion)]


def _events_for_text(text: str) -> list[InputEvent]:
    units = text.encode("utf-16-le", errors="strict")
    events: list[InputEvent] = []
    for offset in range(0, len(units), 2):
        unit = int.from_bytes(units[offset : offset + 2], "little")
        down = InputEvent()
        down.type = INPUT_KEYBOARD
        down.union.ki = KeyboardInput(0, unit, KEYEVENTF_UNICODE, 0, 0)
        up = InputEvent()
        up.type = INPUT_KEYBOARD
        up.union.ki = KeyboardInput(
            0, unit, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP, 0, 0
        )
        events.extend((down, up))
    return events


def _send_input(events: list[InputEvent]) -> int:
    if not events:
        return 0
    array_type = InputEvent * len(events)
    array = array_type(*events)
    send_input = ctypes.windll.user32.SendInput
    send_input.argtypes = (wintypes.UINT, ctypes.POINTER(InputEvent), ctypes.c_int)
    send_input.restype = wintypes.UINT
    return int(send_input(len(events), array, ctypes.sizeof(InputEvent)))


def type_unicode_text(text: str, delay_ms: float = 0.0) -> int:
    """Type Unicode into the currently focused Windows control without the clipboard."""
    if delay_ms <= 0:
        return _send_input(_events_for_text(text))
    sent = 0
    pause = delay_ms / 1000.0
    for character in text:
        sent += _send_input(_events_for_text(character))
        time.sleep(pause)
    return sent


def autostart_enabled() -> bool:
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, STARTUP_RUN_KEY) as key:
        try:
            winreg.QueryValueEx(key, STARTUP_ENTRY)
            return True
        except FileNotFoundError:
            return False


def set_autostart(enabled: bool) -> None:
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, STARTUP_RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
        if enabled:
            command = f'"{sys.executable}" "{__file__}"'
            winreg.SetValueEx(key, STARTUP_ENTRY, 0, winreg.REG_SZ, command)
        else:
            try:
                winreg.DeleteValue(key, STARTUP_ENTRY)
            except FileNotFoundError:
                pass


class HelperState:
    def __init__(self, app_dir_path: Path | None = None) -> None:
        self.lock = threading.RLock()
        self.app_dir = app_dir_path or APP_DIR
        self.settings: AppSettings | None = None
        self.session_token = load_session_token(self.app_dir)
        self.bootstrap_code: str | None = None
        self.bootstrap_expires = 0.0
        self.fingerprint = ""
        self.delivered_ids = self._load_delivered_ids()
        self.status_callback = None
        self.preview_callback: Callable[[], None] | None = None
        self.preview_text = ""
        self.preview_updated = 0.0
        self._release_source: tuple[str, int, int] | None = None
        self.events: list[str] = []

    @property
    def delivered_path(self) -> Path:
        return self.app_dir / "delivered-ids.json"

    @property
    def app_release_path(self) -> Path:
        """APK the phone can install; publish-apk.bat copies a new build here."""
        return self.app_dir / APP_RELEASE_NAME

    def app_release(self) -> dict | None:
        path = self.app_release_path
        try:
            data = path.read_bytes()
        except OSError:
            return None
        return {
            "size": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
            "modified": int(path.stat().st_mtime),
        }

    def release_sources(self) -> list[Path]:
        """Where a build may appear: the served copy and the Gradle output."""
        return [self.app_release_path, BUILD_APK_PATH]

    def sync_app_release(self, sources=None) -> dict | None:
        """Copy the newest complete build into the served path; None when nothing changed."""
        newest = newest_apk(self.release_sources() if sources is None else sources)
        if newest is None:
            return None
        try:
            stat = newest.stat()
        except OSError:
            return None
        signature = (str(newest), stat.st_mtime_ns, stat.st_size)
        if signature == self._release_source:
            return None

        target = self.app_release_path
        try:
            unchanged = target.exists() and (
                target.samefile(newest)
                or (
                    target.stat().st_size == stat.st_size
                    and sha256_file(target) == sha256_file(newest)
                )
            )
        except OSError:
            unchanged = False
        if unchanged:
            self._release_source = signature
            return None

        try:
            data = newest.read_bytes()
            self.app_dir.mkdir(parents=True, exist_ok=True)
            temporary = target.with_name(APP_RELEASE_NAME + ".tmp")
            temporary.write_bytes(data)
            os.replace(temporary, target)
        except OSError:
            return None
        self._release_source = signature
        return {
            "size": len(data),
            "sha256": hashlib.sha256(data).hexdigest(),
            "name": newest.name,
        }

    def _load_delivered_ids(self) -> dict[str, str]:
        try:
            values = json.loads(self.delivered_path.read_text(encoding="utf-8"))
            if isinstance(values, dict):
                return {str(key): str(value) for key, value in list(values.items())[-MAX_DELIVERED_IDS:]}
            if isinstance(values, list):
                return {str(item): "done" for item in values[-MAX_DELIVERED_IDS:]}
        except (OSError, UnicodeError, json.JSONDecodeError):
            pass
        return {}

    def _save_delivery_states(self) -> None:
        self.delivered_ids = dict(list(self.delivered_ids.items())[-MAX_DELIVERED_IDS:])
        self.app_dir.mkdir(parents=True, exist_ok=True)
        temporary = self.app_dir / "delivered-ids.tmp"
        temporary.write_text(json.dumps(self.delivered_ids), encoding="utf-8")
        os.replace(temporary, self.delivered_path)

    def reserve_delivery(self, message_id: str) -> None:
        self.delivered_ids[message_id] = "sending"
        self._save_delivery_states()

    def finish_delivery(self, message_id: str, status: str) -> None:
        self.delivered_ids[message_id] = status
        self._save_delivery_states()

    def forget_delivery(self, message_id: str) -> None:
        self.delivered_ids.pop(message_id, None)
        self._save_delivery_states()

    def set_preview(self, text: str) -> None:
        """Hold the in-progress transcript for the dashboard; nothing is typed for it."""
        with self.lock:
            self.preview_text = text
            self.preview_updated = time.monotonic()
        callback = self.preview_callback
        if callback:
            try:
                callback()
            except Exception:  # pragma: no cover - a broken GUI must not break delivery
                pass

    def clear_preview(self) -> None:
        self.set_preview("")

    def preview_snapshot(self) -> tuple[str, float]:
        with self.lock:
            text = self.preview_text
            updated = self.preview_updated
        age = time.monotonic() - updated if updated else float("inf")
        return text, age

    def status(self, message: str) -> None:
        self.events.append(f"{time.strftime('%H:%M:%S')}  {message}")
        if len(self.events) > 200:
            del self.events[: len(self.events) - 200]
        callback = self.status_callback
        if callback:
            callback(message)


class VoiceRequestHandler(http.server.BaseHTTPRequestHandler):
    server_version = "VoiceToPC/1.0"

    @property
    def state(self) -> HelperState:
        return self.server.state  # type: ignore[attr-defined]

    def log_message(self, fmt: str, *args) -> None:
        # Transcripts, tokens, and addresses are deliberately not written to logs.
        return

    def _reply(self, status: int, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _reply_bytes(self, status: int, content_type: str, body: bytes) -> None:
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def _authorised(self) -> bool:
        token = self.headers.get("Authorization", "")
        if not token.startswith("Bearer "):
            return False
        with self.state.lock:
            expected = self.state.session_token
        return bool(expected) and hmac.compare_digest(token[7:], expected)

    def _read_json(self) -> dict:
        raw_length = self.headers.get("Content-Length", "")
        if not raw_length.isdecimal() or int(raw_length) > MAX_BODY:
            raise ValueError("Request is too large")
        raw = self.rfile.read(int(raw_length))
        value = json.loads(raw.decode("utf-8"))
        if not isinstance(value, dict):
            raise ValueError("Expected a JSON object")
        return value

    def do_GET(self) -> None:
        if not client_is_private(self.client_address[0]):
            self._reply(403, {"ok": False, "error": "local_network_only"})
            return
        if self.path == "/v1/health":
            with self.state.lock:
                paired = bool(self.state.session_token)
            self._reply(200, {"ok": True, "paired": paired, "hostname": DEVICE_NAME})
            return
        if self.path == "/v1/app-release":
            if not self._authorised():
                self._reply(401, {"ok": False, "error": "not_paired"})
                return
            release = self.state.app_release()
            if release is None:
                self._reply(404, {"ok": False, "error": "no_app_release"})
                return
            self._reply(200, {"ok": True, **release})
            return
        if self.path == "/v1/app.apk":
            if not self._authorised():
                self._reply(401, {"ok": False, "error": "not_paired"})
                return
            try:
                data = self.state.app_release_path.read_bytes()
            except OSError:
                self._reply(404, {"ok": False, "error": "no_app_release"})
                return
            self._reply_bytes(200, "application/vnd.android.package-archive", data)
            return
        self._reply(404, {"ok": False, "error": "not_found"})

    def do_POST(self) -> None:
        remote = self.client_address[0]
        if not client_is_private(remote):
            self._reply(403, {"ok": False, "error": "local_network_only"})
            return
        try:
            payload = self._read_json()
        except (ValueError, UnicodeError, json.JSONDecodeError):
            self._reply(400, {"ok": False, "error": "invalid_request"})
            return

        if self.path == "/v1/pair":
            self._pair(payload)
        elif self.path == "/v1/send":
            self._send_text(payload)
        elif self.path == "/v1/preview":
            self._preview(payload)
        else:
            self._reply(404, {"ok": False, "error": "not_found"})

    def _preview(self, payload: dict) -> None:
        """Live preview of speech in progress: dashboard only, never keyboard input."""
        if not self._authorised():
            self._reply(401, {"ok": False, "error": "not_paired"})
            return
        text = payload.get("text")
        if not isinstance(text, str) or len(text) > MAX_TEXT:
            self._reply(400, {"ok": False, "error": "invalid_text"})
            return
        self.state.set_preview(text.strip())
        self._reply(200, {"ok": True})

    def _pair(self, payload: dict) -> None:
        code = payload.get("code")
        with self.state.lock:
            valid = (
                isinstance(code, str)
                and self.state.bootstrap_code is not None
                and time.monotonic() < self.state.bootstrap_expires
                and hmac.compare_digest(code, self.state.bootstrap_code)
            )
            if not valid:
                self._reply(401, {"ok": False, "error": "pair_code_expired"})
                return
            token = secrets.token_urlsafe(32)
            self.state.bootstrap_code = None
            self.state.bootstrap_expires = 0.0
            self.state.session_token = token
            save_session_token(token, self.state.app_dir)
        self.state.status("Phone paired successfully")
        self._reply(200, {"ok": True, "session_token": token, "hostname": DEVICE_NAME})

    def _send_text(self, payload: dict) -> None:
        message_id = payload.get("id")
        text = payload.get("text")
        if (
            not isinstance(message_id, str)
            or len(message_id) > 80
            or not isinstance(text, str)
            or not text.strip()
            or len(text) > MAX_TEXT
        ):
            self._reply(400, {"ok": False, "error": "invalid_text"})
            return

        token = self.headers.get("Authorization", "")
        if not token.startswith("Bearer "):
            self._reply(401, {"ok": False, "error": "not_paired"})
            return
        supplied = token[7:]

        with self.state.lock:
            expected = self.state.session_token
            if not expected or not hmac.compare_digest(supplied, expected):
                self._reply(401, {"ok": False, "error": "not_paired"})
                return
            previous = self.state.delivered_ids.get(message_id)
            if previous == "done":
                self._reply(200, {"ok": True, "duplicate": True})
                return
            if previous in {"sending", "uncertain"}:
                self._reply(409, {"ok": False, "uncertain": True, "error": "delivery_uncertain"})
                return
            if not on_interactive_desktop():
                self._reply(409, {"ok": False, "error": "secure_desktop"})
                return
            if not ctypes.windll.user32.GetForegroundWindow():
                self._reply(409, {"ok": False, "error": "no_foreground_window"})
                return

            safe_text = text.replace("\r", " ").replace("\n", " ").replace("\t", " ")
            safe_text = "".join(ch for ch in safe_text if ch >= " " or ch == "\u00a0")
            if not safe_text:
                self._reply(400, {"ok": False, "error": "empty_text"})
                return
            try:
                self.state.reserve_delivery(message_id)
            except OSError:
                self._reply(500, {"ok": False, "error": "delivery_state_unavailable"})
                return

        # Typing runs outside the lock so a slow delay never freezes the GUI.
        expected_events = len(safe_text.encode("utf-16-le"))
        delay_ms = getattr(self.state.settings, "typing_delay_ms", 0) if self.state.settings else 0
        try:
            inserted = type_unicode_text(safe_text, delay_ms)
        except (OSError, ctypes.ArgumentError):
            with self.state.lock:
                self.state.forget_delivery(message_id)
            self._reply(500, {"ok": False, "error": "input_failed"})
            return

        uncertain = False
        with self.state.lock:
            if inserted != expected_events:
                uncertain = inserted > 0
                if uncertain:
                    self.state.finish_delivery(message_id, "uncertain")
                else:
                    self.state.forget_delivery(message_id)
            else:
                self.state.finish_delivery(message_id, "done")

        if uncertain:
            self._reply(409, {"ok": False, "uncertain": True, "error": "input_failed"})
            self.state.status("Text input may have failed or may be incomplete")
            return
        self.state.clear_preview()
        self.state.status("Text was typed on the PC")
        self._reply(200, {"ok": True, "duplicate": False})


class VoiceHTTPServer(http.server.ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, handler, state: HelperState):
        self.state = state
        super().__init__(address, handler)


class VoiceHelperService:
    """Owns the TLS server, pairing state, and certificate lifecycle for the GUI."""

    def __init__(
        self,
        settings: AppSettings,
        app_dir_path: Path | None = None,
        release_sources: tuple[Path, ...] | None = None,
    ) -> None:
        self.settings = settings
        self.app_dir = app_dir_path or APP_DIR
        # None keeps the defaults; an empty tuple disables build detection (used by tests).
        self.release_sources = release_sources
        self._release_stop = threading.Event()
        self._release_thread: threading.Thread | None = None
        self.state = HelperState(self.app_dir)
        self.state.settings = settings
        self.server: VoiceHTTPServer | None = None
        self.server_thread: threading.Thread | None = None
        self.on_event: Callable[[str], None] | None = None
        self.notifier: Callable[[str], None] | None = None

    @property
    def is_running(self) -> bool:
        return self.server is not None

    def fingerprint(self) -> str:
        return self.state.fingerprint

    def paired(self) -> bool:
        return bool(self.state.session_token)

    def start(self) -> None:
        """Bind the TLS server on the configured port; raises OSError if taken."""
        if self.server is not None:
            return
        key_path, cert_path, fingerprint = ensure_certificate(self.app_dir)
        self.state.fingerprint = fingerprint
        server = VoiceHTTPServer(("0.0.0.0", self.settings.port), VoiceRequestHandler, self.state)
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.minimum_version = ssl.TLSVersion.TLSv1_2
        tls.load_cert_chain(certfile=cert_path, keyfile=key_path)
        server.socket = tls.wrap_socket(server.socket, server_side=True)
        self.server = server
        self.server_thread = threading.Thread(target=server.serve_forever, daemon=True)
        self.server_thread.start()
        self._start_release_watch()
        self.state.status(f"Helper listening on {local_ipv4_address()}:{self.settings.port}")

    def _start_release_watch(self) -> None:
        if self.release_sources is not None and not self.release_sources:
            return
        self.publish_newest_build()
        if self._release_thread is not None and self._release_thread.is_alive():
            return
        self._release_stop.clear()
        self._release_thread = threading.Thread(target=self._watch_releases, daemon=True)
        self._release_thread.start()

    def _watch_releases(self) -> None:
        while not self._release_stop.wait(RELEASE_WATCH_SECONDS):
            try:
                self.publish_newest_build()
            except Exception:  # pragma: no cover - a bad build must not kill the watcher
                continue

    def publish_newest_build(self) -> bool:
        """Serve the newest APK on disk, so a fresh Gradle build reaches the phone by itself."""
        changed = self.state.sync_app_release(self.release_sources)
        if not changed:
            return False
        self.state.status(
            f"New Android build ready ({changed['size'] // 1024} KB) "
            "— tap Check for app update on the phone"
        )
        return True

    def stop(self) -> None:
        self._release_stop.set()
        watcher = self._release_thread
        self._release_thread = None
        if watcher is not None:
            watcher.join(timeout=2.0)
        server = self.server
        self.server = None
        thread = self.server_thread
        self.server_thread = None
        if server is None:
            return
        try:
            server.shutdown()
            server.server_close()
        except OSError:
            pass
        if thread is not None:
            thread.join(timeout=2.0)
        self.state.status("Helper stopped")

    def new_pairing(self) -> dict:
        """Issue a fresh one-time pairing code and return the QR payload for it."""
        with self.state.lock:
            code = secrets.token_urlsafe(24)
            self.state.bootstrap_code = code
            self.state.bootstrap_expires = time.monotonic() + PAIR_TTL_SECONDS
        return {
            "app": "voice-to-pc",
            "host": local_ipv4_address(),
            "port": self.settings.port,
            "code": code,
            "fingerprint": self.state.fingerprint,
        }

    def qr_image(self, payload: dict, size: int = 280) -> Image.Image:
        qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=6, border=3)
        qr.add_data(json.dumps(payload, separators=(",", ":")))
        qr.make(fit=True)
        image = qr.make_image(fill_color="black", back_color="white").convert("RGB")
        return image.resize((size, size), Image.Resampling.NEAREST)

    def unpair(self) -> None:
        with self.state.lock:
            self.state.session_token = None
        delete_session_token(self.app_dir)
        self.state.status("Phone unpaired")

    def regenerate_certificate(self) -> str:
        """Replace the certificate (invalidating any paired phone) and return its fingerprint."""
        for name in ("server-key.pem", "server-cert.pem"):
            try:
                (self.app_dir / name).unlink()
            except FileNotFoundError:
                pass
        self.unpair()
        _, _, fingerprint = ensure_certificate(self.app_dir)
        with self.state.lock:
            self.state.fingerprint = fingerprint
        self.state.status("Certificate replaced; pair the phone again")
        return fingerprint

    def open_data_folder(self) -> None:
        self.app_dir.mkdir(parents=True, exist_ok=True)
        os.startfile(self.app_dir)  # noqa: S601 - fixed, locally generated path

    def notify(self, message: str) -> None:
        if not self.settings.show_notifications:
            return
        notifier = self.notifier
        if notifier:
            notifier(message)


def main() -> None:
    if os.name != "nt":
        raise SystemExit("This helper is designed for Windows 10/11.")
    from gui.app import run_gui

    run_gui()


if __name__ == "__main__":
    main()

