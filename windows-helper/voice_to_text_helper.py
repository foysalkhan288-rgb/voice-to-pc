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
import tkinter as tk
import winreg
from ctypes import wintypes
from pathlib import Path
from tkinter import messagebox, ttk
import pystray
import qrcode
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import ExtendedKeyUsageOID, NameOID
from PIL import Image, ImageDraw, ImageTk


APP_NAME = "Voice to PC"
APP_DIR = Path(os.environ.get("LOCALAPPDATA", Path.home())) / "VoiceToTextPC"
PORT = 48765
DISCOVER_PORT = 48766
MAX_BODY = 64 * 1024
MAX_TEXT = 12_000
PAIR_TTL_SECONDS = 180
MAX_DELIVERED_IDS = 512
STARTUP_RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
STARTUP_ENTRY = "VoiceToPC"
DEVICE_NAME = socket.gethostname()

KEY_PATH = APP_DIR / "server-key.pem"
CERT_PATH = APP_DIR / "server-cert.pem"
SESSION_PATH = APP_DIR / "session-token.dpapi"
DELIVERED_PATH = APP_DIR / "delivered-ids.json"

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


def save_session_token(token: str) -> None:
    APP_DIR.mkdir(parents=True, exist_ok=True)
    temporary = SESSION_PATH.with_suffix(".tmp")
    temporary.write_bytes(protect_for_current_user(token.encode("utf-8")))
    os.replace(temporary, SESSION_PATH)


def load_session_token() -> str | None:
    try:
        return unprotect_for_current_user(SESSION_PATH.read_bytes()).decode("utf-8")
    except FileNotFoundError:
        return None
    except (OSError, UnicodeError):
        return None


def ensure_certificate() -> tuple[str, str, str]:
    """Create a local self-signed certificate; the Android app pins its fingerprint."""
    APP_DIR.mkdir(parents=True, exist_ok=True)
    if KEY_PATH.exists() and CERT_PATH.exists():
        key_pem = KEY_PATH.read_bytes()
        cert_pem = CERT_PATH.read_bytes()
        certificate = x509.load_pem_x509_certificate(cert_pem)
        fingerprint = certificate.fingerprint(hashes.SHA256()).hex()
        return str(KEY_PATH), str(CERT_PATH), fingerprint

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
    KEY_PATH.write_bytes(
        private_key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
    )
    CERT_PATH.write_bytes(certificate.public_bytes(serialization.Encoding.PEM))
    return (
        str(KEY_PATH),
        str(CERT_PATH),
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


def type_unicode_text(text: str) -> int:
    """Type Unicode into the currently focused Windows control without the clipboard."""
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

    if not events:
        return 0
    array_type = InputEvent * len(events)
    array = array_type(*events)
    send_input = ctypes.windll.user32.SendInput
    send_input.argtypes = (wintypes.UINT, ctypes.POINTER(InputEvent), ctypes.c_int)
    send_input.restype = wintypes.UINT
    return int(send_input(len(events), array, ctypes.sizeof(InputEvent)))


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
    def __init__(self) -> None:
        self.lock = threading.RLock()
        self.session_token = load_session_token()
        self.bootstrap_code: str | None = None
        self.bootstrap_expires = 0.0
        self.fingerprint = ""
        self.delivered_ids = self._load_delivered_ids()
        self.status_callback = None
        self.events: list[str] = []

    def _load_delivered_ids(self) -> dict[str, str]:
        try:
            values = json.loads(DELIVERED_PATH.read_text(encoding="utf-8"))
            if isinstance(values, dict):
                return {str(key): str(value) for key, value in list(values.items())[-MAX_DELIVERED_IDS:]}
            if isinstance(values, list):
                return {str(item): "done" for item in values[-MAX_DELIVERED_IDS:]}
        except (OSError, UnicodeError, json.JSONDecodeError):
            pass
        return {}

    def _save_delivery_states(self) -> None:
        self.delivered_ids = dict(list(self.delivered_ids.items())[-MAX_DELIVERED_IDS:])
        APP_DIR.mkdir(parents=True, exist_ok=True)
        temporary = DELIVERED_PATH.with_suffix(".tmp")
        temporary.write_text(json.dumps(self.delivered_ids), encoding="utf-8")
        os.replace(temporary, DELIVERED_PATH)

    def reserve_delivery(self, message_id: str) -> None:
        self.delivered_ids[message_id] = "sending"
        self._save_delivery_states()

    def finish_delivery(self, message_id: str, status: str) -> None:
        self.delivered_ids[message_id] = status
        self._save_delivery_states()

    def forget_delivery(self, message_id: str) -> None:
        self.delivered_ids.pop(message_id, None)
        self._save_delivery_states()

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
        else:
            self._reply(404, {"ok": False, "error": "not_found"})

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
            save_session_token(token)
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
            expected_events = len(safe_text.encode("utf-16-le"))
            try:
                inserted = type_unicode_text(safe_text)
            except (OSError, ctypes.ArgumentError):
                self.state.forget_delivery(message_id)
                self._reply(500, {"ok": False, "error": "input_failed"})
                return
            if inserted != expected_events:
                if inserted > 0:
                    self.state.finish_delivery(message_id, "uncertain")
                else:
                    self.state.forget_delivery(message_id)
                self._reply(
                    409,
                    {"ok": False, "uncertain": inserted > 0, "error": "input_failed"},
                )
                self.state.status("Text input may have failed or may be incomplete")
                return
            self.state.finish_delivery(message_id, "done")

        self.state.status("Text was typed on the PC")
        self._reply(200, {"ok": True, "duplicate": False})


class VoiceHTTPServer(http.server.ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def __init__(self, address, handler, state: HelperState):
        self.state = state
        super().__init__(address, handler)


class PairWindow:
    def __init__(self, root: tk.Tk, state: HelperState, fingerprint: str, on_close=None):
        self.root = root
        self.state = state
        self.fingerprint = fingerprint
        self.on_close = on_close
        self.window = tk.Toplevel(root)
        self.window.title("Voice to PC")
        self.window.resizable(False, False)
        self.window.configure(bg="#f3f6f4")
        self.window.protocol("WM_DELETE_WINDOW", self.close)

        ttk.Label(
            self.window,
            text=f"{APP_NAME}  ·  {DEVICE_NAME}",
            justify="center",
            font=("Segoe UI", 13, "bold"),
        ).pack(padx=20, pady=(18, 4))
        ttk.Label(
            self.window,
            text="Phone and PC must share the same Wi-Fi. Scan this QR code once; it is remembered across PC restarts.",
            wraplength=340,
            justify="center",
        ).pack(padx=18, pady=(0, 10))
        frame = tk.Frame(self.window, bg="#ffffff", highlightbackground="#dbe4df", highlightthickness=1)
        frame.pack(padx=18, pady=(0, 8))
        self.qr_label = ttk.Label(frame)
        self.qr_label.pack(padx=10, pady=10)
        self.status_label = ttk.Label(self.window, text="Scan within 3 minutes.", justify="center")
        self.status_label.pack(padx=18, pady=(0, 6))
        ttk.Button(self.window, text="New QR code", command=self.refresh).pack(padx=18, pady=(0, 8))

        bottom = ttk.Frame(self.window)
        bottom.pack(fill="x", padx=18, pady=(0, 16))
        self.autostart_button = ttk.Button(
            bottom, text=self._autostart_label(), width=18, command=self.toggle_autostart
        )
        self.autostart_button.pack(side="left")
        ttk.Button(bottom, text="Hide", width=10, command=self.close).pack(side="right")
        self.refresh()

    @staticmethod
    def _autostart_label() -> str:
        return "Start with Windows: On" if autostart_enabled() else "Start with Windows: Off"

    def toggle_autostart(self):
        set_autostart(not autostart_enabled())
        self.autostart_button.configure(text=self._autostart_label())

    def refresh(self):
        with self.state.lock:
            code = secrets.token_urlsafe(24)
            self.state.bootstrap_code = code
            self.state.bootstrap_expires = time.monotonic() + PAIR_TTL_SECONDS
        address = local_ipv4_address()
        payload = {
            "app": "voice-to-pc",
            "host": address,
            "port": PORT,
            "code": code,
            "fingerprint": self.fingerprint,
        }
        qr = qrcode.QRCode(error_correction=qrcode.constants.ERROR_CORRECT_M, box_size=6, border=3)
        qr.add_data(json.dumps(payload, separators=(",", ":")))
        qr.make(fit=True)
        image = qr.make_image(fill_color="black", back_color="white").convert("RGB")
        image = image.resize((260, 260), Image.Resampling.NEAREST)
        self.photo = ImageTk.PhotoImage(image)
        self.qr_label.configure(image=self.photo)
        self.status_label.configure(text=f"{address}:{PORT}  ·  Scan within 3 minutes")

    def close(self):
        with self.state.lock:
            self.state.bootstrap_code = None
            self.state.bootstrap_expires = 0.0
        self.window.destroy()
        if self.on_close:
            self.on_close()


class DesktopApp:
    def __init__(self):
        APP_DIR.mkdir(parents=True, exist_ok=True)
        key_path, cert_path, fingerprint = ensure_certificate()
        self.state = HelperState()
        self.state.fingerprint = fingerprint
        self.root = tk.Tk()
        self.root.title(APP_NAME)
        self.root.resizable(False, False)
        self.root.configure(bg="#f3f6f4")
        self.root.protocol("WM_DELETE_WINDOW", self.hide_window)
        self.state.status_callback = self.show_status

        self.server = VoiceHTTPServer(("0.0.0.0", PORT), VoiceRequestHandler, self.state)
        tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        tls.minimum_version = ssl.TLSVersion.TLSv1_2
        tls.load_cert_chain(certfile=cert_path, keyfile=key_path)
        self.server.socket = tls.wrap_socket(self.server.socket, server_side=True)
        self.server_thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.server_thread.start()
        self.pair_window: PairWindow | None = None
        self.icon = self._create_tray_icon()
        self.icon.run_detached()
        self._build_dashboard()

    def _build_dashboard(self):
        pad = {"padx": 18, "pady": 8}
        ttk.Label(
            self.root,
            text=f"{APP_NAME}  ·  {DEVICE_NAME}",
            font=("Segoe UI", 14, "bold"),
            background="#f3f6f4",
        ).pack(fill="x", **pad)
        ttk.Label(
            self.root,
            text=f"Listening on {local_ipv4_address()}:{PORT}  ·  same Wi-Fi only",
            background="#f3f6f4",
        ).pack(fill="x", **pad)

        self.link_label = ttk.Label(self.root, text="", font=("Segoe UI", 11, "bold"))
        self.link_label.pack(fill="x", **pad)

        buttons = ttk.Frame(self.root)
        buttons.pack(fill="x", padx=18, pady=(4, 12))
        ttk.Button(buttons, text="Pair phone", command=self.open_pair_window).pack(side="left")
        ttk.Button(buttons, text="Start with Windows", command=self.toggle_autostart).pack(side="left", padx=8)
        ttk.Button(buttons, text="Hide to tray", command=self.hide_window).pack(side="right")

        log_frame = ttk.LabelFrame(self.root, text="Recent activity")
        log_frame.pack(fill="both", expand=True, padx=18, pady=(0, 16))
        self.log = tk.Text(log_frame, height=8, width=46, wrap="word", state="disabled", font=("Consolas", 9))
        scrollbar = ttk.Scrollbar(log_frame, command=self.log.yview)
        self.log.configure(yscrollcommand=scrollbar.set)
        scrollbar.pack(side="right", fill="y")
        self.log.pack(side="left", fill="both", expand=True)
        self._render_log()

    def _render_log(self):
        self.log.configure(state="normal")
        self.log.delete("1.0", "end")
        self.log.insert("end", "\n".join(self.state.events[-60:]) or "No activity yet.")
        self.log.configure(state="disabled")

    def toggle_autostart(self):
        set_autostart(not autostart_enabled())
        self._render_status()

    def _create_tray_icon(self):
        image = Image.new("RGBA", (64, 64), (33, 110, 80, 255))
        draw = ImageDraw.Draw(image)
        draw.ellipse((9, 9, 55, 55), fill=(245, 255, 250, 255))
        draw.text((19, 18), "V", fill=(33, 110, 80, 255), stroke_width=1)
        menu = pystray.Menu(
            pystray.MenuItem("Show dashboard", self.show_window),
            pystray.MenuItem("Pair phone…", self.open_pair_window),
            pystray.MenuItem("Quit", self.quit),
        )
        return pystray.Icon("voice-to-pc", image, APP_NAME, menu)

    def show_status(self, message: str):
        def notify():
            self._render_status()
            if not self.icon:
                return
            try:
                self.icon.notify(message, APP_NAME)
            except (NotImplementedError, RuntimeError):
                pass
        self.root.after(0, notify)

    def _render_status(self):
        with self.state.lock:
            paired = bool(self.state.session_token)
        state_text = "Phone paired" if paired else "Phone not paired"
        startup = "on" if autostart_enabled() else "off"
        self.link_label.configure(text=f"{state_text}  ·  Start with Windows: {startup}")
        self._render_log()

    def show_window(self, icon=None, item=None):
        def show():
            self.root.deiconify()
            self.root.lift()
            self._render_status()
        self.root.after(0, show)

    def hide_window(self, icon=None, item=None):
        def hide():
            if self.pair_window and self.pair_window.window.winfo_exists():
                self.pair_window.close()
            self.root.withdraw()
        self.root.after(0, hide)

    def open_pair_window(self, icon=None, item=None):
        def show():
            if self.pair_window and self.pair_window.window.winfo_exists():
                self.pair_window.window.deiconify()
                self.pair_window.window.lift()
            else:
                self.pair_window = PairWindow(self.root, self.state, self.state.fingerprint)
        self.root.after(0, show)

    def quit(self, icon=None, item=None):
        if self.icon:
            self.icon.stop()
        self.server.shutdown()
        self.server.server_close()
        self.root.after(0, self.root.quit)

    def run(self):
        self._render_status()
        self.root.mainloop()


def main() -> None:
    if os.name != "nt":
        raise SystemExit("This helper is designed for Windows 10/11.")
    try:
        app = DesktopApp()
        app.run()
    except OSError as exc:
        messagebox.showerror(APP_NAME, f"Could not start the helper: {exc}")
        raise


if __name__ == "__main__":
    main()

