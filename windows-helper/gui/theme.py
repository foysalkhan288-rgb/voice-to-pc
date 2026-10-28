"""Colors, fonts, and geometry tokens for the helper GUI."""
from __future__ import annotations

import customtkinter as ctk

WINDOW_SIZE = "960x640"
WINDOW_MIN_SIZE = (860, 560)
SIDEBAR_WIDTH = 200
CARD_CORNER_RADIUS = 12
BUTTON_CORNER_RADIUS = 8

PALETTES: dict[str, dict[str, str]] = {
    "dark": {
        "bg": "#10171a",
        "sidebar_bg": "#0c1214",
        "card_bg": "#182126",
        "card_border": "#233036",
        "accent": "#2f8f6b",
        "accent_hover": "#37a37c",
        "text": "#e8f1ee",
        "text_muted": "#93a8a1",
        "success": "#43b581",
        "warning": "#d9a441",
        "danger": "#e05c5c",
        "input_bg": "#131c21",
        "qr_bg": "#ffffff",
    },
    "light": {
        "bg": "#f4f7f5",
        "sidebar_bg": "#e7ede9",
        "card_bg": "#ffffff",
        "card_border": "#d3ded8",
        "accent": "#2f8f6b",
        "accent_hover": "#27805e",
        "text": "#1b2622",
        "text_muted": "#5d6f68",
        "success": "#2e8b57",
        "warning": "#b57e1f",
        "danger": "#c0392b",
        "input_bg": "#ffffff",
        "qr_bg": "#ffffff",
    },
}

_ACTIVE: dict[str, str] = dict(PALETTES["dark"])


def apply_theme(appearance: str) -> dict[str, str]:
    """Switch customtkinter's appearance mode and return the active palette."""
    global _ACTIVE
    mode = appearance if appearance in PALETTES else "dark"
    ctk.set_appearance_mode(mode)
    _ACTIVE = dict(PALETTES[mode])
    return _ACTIVE


def active_palette() -> dict[str, str]:
    return dict(_ACTIVE)


_FONTS: dict[str, ctk.CTkFont] | None = None


def get_fonts() -> dict[str, ctk.CTkFont]:
    """Build (once) the font registry; requires a CTk root to exist first."""
    global _FONTS
    if _FONTS is None:
        _FONTS = {
            "title": ctk.CTkFont(family="Segoe UI", size=16, weight="bold"),
            "heading": ctk.CTkFont(family="Segoe UI", size=13, weight="bold"),
            "body": ctk.CTkFont(family="Segoe UI", size=12),
            "small": ctk.CTkFont(family="Segoe UI", size=11),
            "mono": ctk.CTkFont(family="Consolas", size=11),
            "mono_small": ctk.CTkFont(family="Consolas", size=10),
        }
    return _FONTS
