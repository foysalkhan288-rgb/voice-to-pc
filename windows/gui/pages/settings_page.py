from __future__ import annotations

import tkinter.messagebox as messagebox

import customtkinter as ctk

from gui.pages.base import BasePage
from gui.theme import CARD_GAP, CARD_PADDING, active_palette, apply_theme, get_fonts
from gui.widgets import (
    SegmentedControl,
    caption_label,
    card_body,
    card_headline,
    card_overline,
    make_card,
    make_divider,
    scroll_frame,
    setting_row,
    styled_button,
    styled_entry,
    styled_slider,
    styled_switch,
    value_label,
)
from settings import MAX_TYPING_DELAY_MS, MIN_PORT, MAX_PORT, THEMES, AppSettings
from speaktype import autostart_enabled, set_autostart


class SettingsPage(BasePage):
    title = "Settings"

    def __init__(self, master, app) -> None:
        super().__init__(master, app)
        colors = active_palette()
        self.fonts = get_fonts()

        self.port_var = ctk.StringVar()
        self.delay_var = ctk.IntVar()
        self.notify_var = ctk.BooleanVar()
        self.minimized_var = ctk.BooleanVar()
        self.autostart_var = ctk.BooleanVar()
        self.theme_var = ctk.StringVar()

        scroll = scroll_frame(self)
        scroll.pack(fill="both", expand=True)

        # ---- appearance: the phone's Theme card, segmented control and all ----
        appearance = self._card(scroll, "Appearance", "Theme")
        card_body(
            appearance,
            "Dark keeps the dashboard calm at night. Light matches a bright room. "
            "The change applies immediately.",
            wraplength=600,
        )
        self.theme_tabs = SegmentedControl(
            appearance, ["Dark", "Light"], selected=0, on_select=self._on_theme_select,
        )
        self.theme_tabs.pack(fill="x", padx=CARD_PADDING, pady=(12, CARD_PADDING))

        # ---- connection ----
        connection = self._card(scroll, "Connection", "This PC")
        port_row, _ = setting_row(connection, "Port", "TLS port the phone connects to.")
        port_row.pack(fill="x", padx=CARD_PADDING)
        styled_entry(port_row, textvariable=self.port_var, width=110).pack(side="right", padx=(12, 0))
        self.port_error = caption_label(connection, "", text_color=colors["danger"])
        self._conn_divider = make_divider(connection)
        firewall_row, self.firewall_status = setting_row(
            connection, "Firewall", "Checking…",
        )
        firewall_row.pack(fill="x", padx=CARD_PADDING)
        styled_button(
            firewall_row, "Allow phone connections", "quiet", height=40,
            command=self.app.fix_firewall,
        ).pack(side="right", padx=(12, 0))
        caption_label(
            connection,
            "Changing the port restarts SpeakType. Pairing is unaffected — the QR code carries "
            "the new port, but re-scan if the phone is already paired with the old one.",
            wraplength=600,
        ).pack(fill="x", padx=CARD_PADDING, pady=(0, CARD_PADDING))

        # ---- output ----
        output = self._card(scroll, "Output", "Typing")
        delay_row, _ = setting_row(output, "Typing delay", "Pause between characters for apps that drop fast input.")
        delay_row.pack(fill="x", padx=CARD_PADDING)
        delay_holder = ctk.CTkFrame(delay_row, fg_color="transparent")
        delay_holder.pack(side="right", padx=(12, 0))
        styled_slider(
            delay_holder, from_=0, to=MAX_TYPING_DELAY_MS, number_of_steps=MAX_TYPING_DELAY_MS,
            variable=self.delay_var, command=self._on_delay_change, width=220,
        ).pack(side="left")
        self.delay_label = value_label(delay_holder, "", width=70, anchor="e")
        self.delay_label.pack(side="left", padx=(12, 0))
        ctk.CTkFrame(output, height=1, fg_color="transparent").pack(fill="x", pady=(0, CARD_PADDING - 14))

        # ---- startup and notifications ----
        startup = self._card(scroll, "Startup", "Windows")
        minimized_row, _ = setting_row(startup, "Start minimized", "Open in the tray instead of showing the window.")
        minimized_row.pack(fill="x", padx=CARD_PADDING)
        styled_switch(minimized_row, variable=self.minimized_var).pack(side="right", padx=(12, 0))
        make_divider(startup)
        autostart_row, _ = setting_row(startup, "Start with Windows", "Launch SpeakType automatically at sign-in.")
        autostart_row.pack(fill="x", padx=CARD_PADDING)
        styled_switch(autostart_row, variable=self.autostart_var).pack(side="right", padx=(12, 0))
        make_divider(startup)
        notify_row, _ = setting_row(startup, "Notifications", "Show a tray toast when text arrives.")
        notify_row.pack(fill="x", padx=CARD_PADDING)
        styled_switch(notify_row, variable=self.notify_var).pack(side="right", padx=(12, 0))
        ctk.CTkFrame(startup, height=1, fg_color="transparent").pack(fill="x", pady=(0, CARD_PADDING - 14))

        # ---- save ----
        buttons = ctk.CTkFrame(scroll, fg_color="transparent")
        buttons.pack(fill="x", pady=(CARD_GAP, 4))
        styled_button(buttons, "Save", "primary", width=120, command=self.save).pack(side="left")
        styled_button(
            buttons, "Restore defaults", "quiet", width=150, command=self.restore_defaults,
        ).pack(side="left", padx=(10, 0))

    def _card(self, master, overline: str, headline: str) -> ctk.CTkFrame:
        card = make_card(master)
        card.pack(fill="x", pady=(0, CARD_GAP))
        card_overline(card, overline)
        card_headline(card, headline)
        return card

    # ---- theme ------------------------------------------------------------------------------

    def _on_theme_select(self, index: int) -> None:
        theme = THEMES[index] if index < len(THEMES) else "dark"
        self.theme_var.set(theme)
        # Rebuilding destroys this page, so let the click callback finish first.
        self.app.root.after(10, lambda: self.app.set_theme(theme))

    # ---- delay ------------------------------------------------------------------------------

    def _on_delay_change(self, value: float) -> None:
        self.delay_var.set(int(float(value)))
        self.delay_label.configure(text=self._delay_text(int(self.delay_var.get())))

    @staticmethod
    def _delay_text(delay_ms: int) -> str:
        return "instant" if delay_ms == 0 else f"{delay_ms} ms"

    # ---- lifecycle ----------------------------------------------------------------------------

    def on_show(self) -> None:
        settings = self.app.settings
        self.port_var.set(str(settings.port))
        self.delay_var.set(settings.typing_delay_ms)
        self.delay_label.configure(text=self._delay_text(settings.typing_delay_ms))
        self.notify_var.set(settings.show_notifications)
        self.minimized_var.set(settings.start_minimized)
        self.autostart_var.set(autostart_enabled())
        self.theme_var.set(settings.theme)
        self.theme_tabs.select(THEMES.index(settings.theme) if settings.theme in THEMES else 0)
        self._set_port_error("")
        self._render_firewall()

    def tick(self) -> None:
        self._render_firewall()

    def _render_firewall(self) -> None:
        colors = active_palette()
        label = getattr(self, "firewall_status", None)
        if label is None:
            return
        allowed = getattr(self.app, "firewall_ok", True)
        label.configure(
            text="Allowed" if allowed else "Blocked",
            text_color=colors["success"] if allowed else colors["danger"],
        )

    def restore_defaults(self) -> None:
        defaults = AppSettings()
        self.port_var.set(str(defaults.port))
        self.delay_var.set(defaults.typing_delay_ms)
        self.delay_label.configure(text=self._delay_text(defaults.typing_delay_ms))
        self.notify_var.set(defaults.show_notifications)
        self.minimized_var.set(defaults.start_minimized)
        self.autostart_var.set(autostart_enabled())
        self.theme_var.set(defaults.theme)
        self.theme_tabs.select(THEMES.index(defaults.theme) if defaults.theme in THEMES else 0)
        self._set_port_error("")

    def _set_port_error(self, text: str) -> None:
        """Show the validation line only when there is something to say."""
        self.port_error.configure(text=text)
        if text:
            self.port_error.pack(fill="x", padx=CARD_PADDING, before=self._conn_divider)
        else:
            self.port_error.pack_forget()

    def _validated_port(self) -> int | None:
        raw = self.port_var.get().strip()
        try:
            port = int(raw)
        except ValueError:
            self._set_port_error(f"Port must be a number between {MIN_PORT} and {MAX_PORT}.")
            return None
        if not MIN_PORT <= port <= MAX_PORT:
            self._set_port_error(f"Port must be between {MIN_PORT} and {MAX_PORT}.")
            return None
        self._set_port_error("")
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

        try:
            set_autostart(settings.start_with_windows)
        except Exception:
            pass

        if port != previous_port:
            if messagebox.askyesno(
                "SpeakType", "The port changed. Restart SpeakType now to listen on the new port?"
            ):
                self.app.restart_server()
                self.app.toast("Restarted on the new port")

        if settings.theme != previous_theme:
            # The segmented control normally applies live; this covers Restore defaults + Save.
            apply_theme(settings.theme)
            self.app.rebuild_ui()
        self.app.toast("Settings saved")
        for page in self.app.pages.values():
            page.on_settings_changed()