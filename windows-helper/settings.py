"""Persistent user settings for the Voice to PC Windows helper."""
from __future__ import annotations

import json
import os
from dataclasses import dataclass, fields
from pathlib import Path

SETTINGS_FILENAME = "settings.json"
DEFAULT_PORT = 48765
MIN_PORT = 1024
MAX_PORT = 65535
MAX_TYPING_DELAY_MS = 100
THEMES = ("dark", "light")


def app_dir() -> Path:
    """Local application data folder shared with the helper's certificate and pairing token."""
    return Path(os.environ.get("LOCALAPPDATA", Path.home())) / "VoiceToTextPC"


def _as_int(value: object, default: int) -> int:
    if isinstance(value, bool):
        return default
    if isinstance(value, int):
        return value
    if isinstance(value, str):
        try:
            return int(value.strip())
        except ValueError:
            return default
    return default


def _as_bool(value: object, default: bool) -> bool:
    if isinstance(value, bool):
        return value
    return default


@dataclass
class AppSettings:
    port: int = DEFAULT_PORT
    typing_delay_ms: int = 0
    show_notifications: bool = True
    start_minimized: bool = False
    start_with_windows: bool = False
    theme: str = "dark"

    @classmethod
    def settings_path(cls) -> Path:
        return app_dir() / SETTINGS_FILENAME

    @classmethod
    def load(cls, path: Path | None = None) -> "AppSettings":
        target = path or cls.settings_path()
        try:
            raw = json.loads(target.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError):
            return cls()
        if not isinstance(raw, dict):
            return cls()
        known = {field.name for field in fields(cls)}
        values = {key: value for key, value in raw.items() if key in known}
        settings = cls(**values)
        return settings.sanitized()

    def sanitized(self) -> "AppSettings":
        port = _as_int(self.port, DEFAULT_PORT)
        if not MIN_PORT <= port <= MAX_PORT:
            port = DEFAULT_PORT
        delay = _as_int(self.typing_delay_ms, 0)
        if not 0 <= delay <= MAX_TYPING_DELAY_MS:
            delay = 0
        theme = self.theme.strip().lower() if isinstance(self.theme, str) else "dark"
        if theme not in THEMES:
            theme = "dark"
        return AppSettings(
            port=port,
            typing_delay_ms=delay,
            show_notifications=_as_bool(self.show_notifications, True),
            start_minimized=_as_bool(self.start_minimized, False),
            start_with_windows=_as_bool(self.start_with_windows, False),
            theme=theme,
        )

    def save(self, path: Path | None = None) -> None:
        target = path or self.settings_path()
        target.parent.mkdir(parents=True, exist_ok=True)
        temporary = target.with_name(target.name + ".tmp")
        payload = {field.name: getattr(self, field.name) for field in fields(self)}
        temporary.write_text(
            json.dumps(payload, indent=2, ensure_ascii=False), encoding="utf-8"
        )
        os.replace(temporary, target)
