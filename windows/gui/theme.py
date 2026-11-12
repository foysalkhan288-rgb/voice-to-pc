"""Design tokens ported from the phone app (android-app ui/Design.kt Palette + Metric).

The desktop dashboard and the phone read as one product: same emerald accent, same
calm green-tinted neutrals, same radii and spacing scale. Key names are kept stable
for the pages; every value below has a 1:1 twin in the phone Palette object.
"""
from __future__ import annotations

import customtkinter as ctk

# ---- geometry (phone Metric, dp -> px) ----------------------------------------------------
WINDOW_SIZE = "1000x680"
WINDOW_MIN_SIZE = (920, 600)
SIDEBAR_WIDTH = 224
SPACING_UNIT = 8
GUTTER = 20
CARD_PADDING = 16
CARD_GAP = 14
CARD_CORNER_RADIUS = 18
BUTTON_CORNER_RADIUS = 14
PILL_RADIUS = 24
PANEL_RADIUS = 12
BUTTON_HEIGHT = 44
HAIRLINE_WIDTH = 1

# ---- palette: exact hex twins of Palette.kt ------------------------------------------------
PALETTES: dict[str, dict[str, str]] = {
    "dark": {
        "bg": "#0C1210",               # BACKGROUND
        "sidebar_bg": "#151D19",       # SURFACE
        "card_bg": "#151D19",          # SURFACE
        "card_border": "#2B3732",      # BORDER
        "hairline": "#232D28",         # HAIRLINE
        "surface_alt": "#1B2420",      # SURFACE_ALT
        "accent": "#4FC49A",           # ACCENT
        "accent_hover": "#6FDDB4",     # ACCENT_PRESSED
        "accent_pressed": "#6FDDB4",   # ACCENT_PRESSED
        "accent_soft": "#16382B",      # ACCENT_SOFT
        "accent_soft_pressed": "#1C4536",  # ACCENT_SOFT_PRESSED
        "on_accent": "#07130D",        # ON_ACCENT
        "text": "#E7F0EB",             # TEXT
        "text_muted": "#A2B2AA",       # MUTED
        "subtle": "#1F2A25",           # SUBTLE
        "subtle_pressed": "#28352F",   # SUBTLE_PRESSED
        "quiet_pressed": "#1D2723",    # QUIET_PRESSED
        "success": "#4FC49A",          # SUCCESS
        "warning": "#E8BC6A",          # WARNING
        "danger": "#FF8A80",           # DANGER
        "danger_pressed": "#FFA79E",   # DANGER_PRESSED
        "danger_soft": "#3A1B17",      # DANGER_SOFT
        "on_danger": "#3A0704",        # ON_DANGER
        "disabled_fill": "#1E2623",    # DISABLED_FILL
        "disabled_text": "#6E7C76",    # DISABLED_TEXT
        "input_bg": "#1B2420",         # SURFACE_ALT
        "qr_bg": "#ffffff",
    },
    "light": {
        "bg": "#F5F7F6",
        "sidebar_bg": "#FFFFFF",
        "card_bg": "#FFFFFF",
        "card_border": "#DCE5E0",
        "hairline": "#E6EDE9",
        "surface_alt": "#F0F4F2",
        "accent": "#1F7A57",
        "accent_hover": "#186445",
        "accent_pressed": "#186445",
        "accent_soft": "#E4F0EA",
        "accent_soft_pressed": "#D5E7DF",
        "on_accent": "#FFFFFF",
        "text": "#14201B",
        "text_muted": "#55655E",
        "subtle": "#EDF3F0",
        "subtle_pressed": "#DEE8E3",
        "quiet_pressed": "#F1F5F3",
        "success": "#1F7A57",
        "warning": "#8A5D0A",
        "danger": "#B3261E",
        "danger_pressed": "#8F1D17",
        "danger_soft": "#FBE9E7",
        "on_danger": "#FFFFFF",
        "disabled_fill": "#EEF2F0",
        "disabled_text": "#98A6A0",
        "input_bg": "#F0F4F2",
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


def tracked(text: str) -> str:
    """Approximate the phone's 0.14em overline letter-spacing with hair spaces."""
    out: list[str] = []
    for index, char in enumerate(text):
        if index and char != " " and text[index - 1] != " ":
            out.append("\u200a")
        out.append(char)
    return "".join(out)


_FONTS: dict[str, ctk.CTkFont] | None = None


def get_fonts() -> dict[str, ctk.CTkFont]:
    """Build (once) the font registry; requires a CTk root to exist first.

    Scale mirrors Design.kt: title 26sp, headline 19sp, section 15sp, value/body 14sp,
    caption 12sp, overline 11sp. Desktop pixels run one step smaller than phone sp so
    the dashboard keeps a desktop density; "Semibold" is the sans-serif-medium twin.
    """
    global _FONTS
    if _FONTS is None:
        _FONTS = {
            "overline": ctk.CTkFont(family="Segoe UI", size=10, weight="bold"),
            "brand": ctk.CTkFont(family="Segoe UI", size=20, weight="bold"),
            "title": ctk.CTkFont(family="Segoe UI", size=24, weight="bold"),
            "headline": ctk.CTkFont(family="Segoe UI Semibold", size=17),
            "heading": ctk.CTkFont(family="Segoe UI Semibold", size=15),
            "section": ctk.CTkFont(family="Segoe UI Semibold", size=14),
            "value": ctk.CTkFont(family="Segoe UI Semibold", size=13),
            "body": ctk.CTkFont(family="Segoe UI", size=13),
            "small": ctk.CTkFont(family="Segoe UI", size=11),
            "caption": ctk.CTkFont(family="Segoe UI", size=11),
            "button": ctk.CTkFont(family="Segoe UI Semibold", size=13),
            "chevron": ctk.CTkFont(family="Segoe UI", size=18),
            "mono": ctk.CTkFont(family="Consolas", size=11),
            "mono_small": ctk.CTkFont(family="Consolas", size=10),
        }
    return _FONTS