from __future__ import annotations

import tkinter.messagebox as messagebox

import customtkinter as ctk

from gui.pages.base import BasePage
from gui.theme import CARD_CORNER_RADIUS, active_palette, apply_theme, get_fonts
from settings import MAX_TYPING_DELAY_MS, MIN_PORT, MAX_PORT, THEMES, AppSettings
from voice_to_text_helper import autostart_enabled, set_autostart


class SettingsPage(BasePage):
    title = "Settings"

    def __init__(self, master, app) -> None:
        super().__init__(master, app)
        colors = active_palette()
        self.fonts = get_fonts()

        scroll = ctk.CTkScrollableFrame(self, fg_color="transparent")
        scroll.pack(fill="both", expand=True)

        card = ctk.CTkFrame(
            scroll, corner_radius=CARD_CORNER_RADIUS, border_width=1,
            fg_color=colors["card_bg"], border_color=colors["card_border"],
        )
        card.pack(fill="x", padx=4, pady=4)

        self.port_error = ctk.CTkLabel(card, text="", font=self.fonts["small"], text_color=colors["danger"], justify="left", anchor="w")
        self.port_error.pack(fill="x", padx=16, pady=(12, 0))

        self.port_var = ctk.StringVar()
        self.delay_var = ctk.IntVar()
        self.notify_var = ctk.BooleanVar()
        self.minimized_var = ctk.BooleanVar()
        self.autostart_var = ctk.BooleanVar()
        self.theme_var = ctk.StringVar()

        self._row(card, "Port", self._build_port_row, stretch=False)
        self._row(card, "Typing delay", self._build_delay_row)
        self._row(card, "Notifications", self._build_notify_row, stretch=False)
        self._row(card, "Startup", self._build_startup_row, stretch=False)
        self._row(card, "Theme", self._build_theme_row, stretch=False)

        buttons = ctk.CTkFrame(card, fg_color="transparent")
        buttons.pack(fill="x", padx=16, pady=(6, 16))
        ctk.CTkButton(buttons, text="Save", width=110, command=self.save).pack(side="left")
        ctk.CTkButton(
            buttons, text="Restore defaults", width=140, fg_color="transparent", border_width=1,
            border_color=colors["card_border"], text_color=colors["text"], hover_color=colors["card_bg"],
            command=self.restore_defaults,
        ).pack(side="left", padx=8)

        ctk.CTkLabel(
            card,
            text="Changing the port restarts the helper. Pairing is unaffected — the QR code carries the new port, "
            "but re-scan if the phone is already paired with the old one.",
            font=self.fonts["small"], text_color=colors["text_muted"], wraplength=600, justify="left", anchor="w",
        ).pack(fill="x", padx=16, pady=(0, 16))

    def _row(self, master, label: str, build, stretch: bool = True) -> None:
        colors = active_palette()
        row = ctk.CTkFrame(master, fg_color="transparent")
        row.pack(fill="x", padx=16, pady=6)
        ctk.CTkLabel(row, text=label, font=self.fonts["body"], text_color=colors["text"], width=140, anchor="w").pack(side="left")
        # The control has to be created inside the row. Building it against the card and then
        # packing it here put it in the card's packing order instead, which cascaded every row
        # across the page and pushed the Save button off the right edge.
        control = build(row)
        if stretch:
            control.pack(side="left", fill="x", expand=True)
        else:
            control.pack(side="left")

    def _build_port_row(self, master) -> ctk.CTkBaseClass:
        entry = ctk.CTkEntry(master, textvariable=self.port_var, width=120)
        return entry

    def _build_delay_row(self, master) -> ctk.CTkBaseClass:
        colors = active_palette()
        holder = ctk.CTkFrame(master, fg_color="transparent")
        slider = ctk.CTkSlider(
            holder, from_=0, to=MAX_TYPING_DELAY_MS, number_of_steps=MAX_TYPING_DELAY_MS,
            variable=self.delay_var, command=self._on_delay_change,
        )
        slider.pack(side="left", fill="x", expand=True)
        self.delay_label = ctk.CTkLabel(holder, text="", font=self.fonts["body"], text_color=colors["text"], width=110, anchor="w")
        self.delay_label.pack(side="left", padx=(10, 0))
        return holder

    def _build_notify_row(self, master) -> ctk.CTkBaseClass:
        return ctk.CTkSwitch(
            master, text="Show tray notifications when text arrives", variable=self.notify_var,
        )

    def _build_startup_row(self, master) -> ctk.CTkBaseClass:
        holder = ctk.CTkFrame(master, fg_color="transparent")
        ctk.CTkSwitch(holder, text="Start minimized to the tray", variable=self.minimized_var).pack(side="left")
        ctk.CTkSwitch(holder, text="Start with Windows", variable=self.autostart_var).pack(side="left", padx=(16, 0))
        return holder

    def _build_theme_row(self, master) -> ctk.CTkBaseClass:
        return ctk.CTkOptionMenu(master, variable=self.theme_var, values=list(THEMES), width=140)

    def _on_delay_change(self, value: float) -> None:
        self.delay_var.set(int(float(value)))
        self.delay_label.configure(text=self._delay_text(int(self.delay_var.get())))

    @staticmethod
    def _delay_text(delay_ms: int) -> str:
        return "instant" if delay_ms == 0 else f"{delay_ms} ms"

    def on_show(self) -> None:
        settings = self.app.settings
        self.port_var.set(str(settings.port))
        self.delay_var.set(settings.typing_delay_ms)
        self.delay_label.configure(text=self._delay_text(settings.typing_delay_ms))
        self.notify_var.set(settings.show_notifications)
        self.minimized_var.set(settings.start_minimized)
        self.autostart_var.set(autostart_enabled())
        self.theme_var.set(settings.theme)
        self.port_error.configure(text="")

    def restore_defaults(self) -> None:
        defaults = AppSettings()
        self.port_var.set(str(defaults.port))
        self.delay_var.set(defaults.typing_delay_ms)
        self.delay_label.configure(text=self._delay_text(defaults.typing_delay_ms))
        self.notify_var.set(defaults.show_notifications)
        self.minimized_var.set(defaults.start_minimized)
        self.autostart_var.set(autostart_enabled())
        self.theme_var.set(defaults.theme)
        self.port_error.configure(text="")

    def _validated_port(self) -> int | None:
        raw = self.port_var.get().strip()
        try:
            port = int(raw)
        except ValueError:
            self.port_error.configure(text=f"Port must be a number between {MIN_PORT} and {MAX_PORT}.")
            return None
        if not MIN_PORT <= port <= MAX_PORT:
            self.port_error.configure(text=f"Port must be between {MIN_PORT} and {MAX_PORT}.")
            return None
        self.port_error.configure(text="")
        return port

    def save(self) -> None:
        port = self._validated_port()
        if port is None:
            self.app.toast("Fix the port before saving.")
            return
        previous_port = self.app.settings.port
        previous_theme = self.app.settings.theme
        settings = self.app.settings
        settings.port = port
        settings.typing_delay_ms = int(self.delay_var.get())
        settings.show_notifications = bool(self.notify_var.get())
        settings.start_minimized = bool(self.minimized_var.get())
        settings.start_with_windows = bool(self.autostart_var.get())
        settings.theme = self.theme_var.get()
        settings.save()

        set_autostart(settings.start_with_windows)
        apply_theme(settings.theme)
        if settings.theme != previous_theme:
            self.app.toast("Theme saved — it repaints fully the next time the helper starts")
        else:
            self.app.toast("Settings saved")

        for page in self.app.pages.values():
            page.on_settings_changed()

        if port != previous_port:
            if not messagebox.askyesno(
                "Voice to PC", "The port changed. Restart the helper now to listen on the new port?"
            ):
                return
            self.app.restart_server()
            self.app.toast("Helper restarted on the new port")
