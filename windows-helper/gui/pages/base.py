"""Base class shared by every GUI page."""
from __future__ import annotations

from typing import TYPE_CHECKING

import customtkinter as ctk

if TYPE_CHECKING:  # pragma: no cover - typing only, avoids a circular import
    from gui.app import VoiceToPcApp


class BasePage(ctk.CTkFrame):
    title: str = ""

    def __init__(self, master, app: "VoiceToPcApp") -> None:
        super().__init__(master, fg_color="transparent")
        self.app = app

    def on_show(self) -> None:
        """Refresh everything when the page becomes visible."""

    def tick(self) -> None:
        """Cheap ~1 Hz refresh hook; empty by default."""

    def on_settings_changed(self) -> None:
        """React to the Settings page being saved."""
