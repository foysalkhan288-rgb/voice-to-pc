from __future__ import annotations

import time

import customtkinter as ctk

from gui.pages.base import BasePage
from gui.theme import CARD_GAP, CARD_PADDING, active_palette, get_fonts
from gui.widgets import (
    Dot,
    apply_button_style,
    body_label,
    card_overline,
    make_card,
    make_panel,
    overline_label,
    scroll_frame,
    styled_button,
    styled_textbox,
    value_label,
)
from speaktype import DEVICE_NAME, PREVIEW_TTL_SECONDS, local_ipv4_address


class DashboardPage(BasePage):
    title = "Dashboard"

    def __init__(self, master, app) -> None:
        super().__init__(master, app)
        colors = active_palette()
        self.fonts = get_fonts()
        self._last_event_count = -1

        scroll = scroll_frame(self)
        scroll.pack(fill="both", expand=True)

        # ---- metric cards: overline + value, the phone's card vocabulary ----
        cards = ctk.CTkFrame(scroll, fg_color="transparent")
        cards.pack(fill="x", pady=(2, 0))
        for column in range(3):
            cards.grid_columnconfigure(column, weight=1, uniform="metric")

        self.server_card, self.server_dot, self.server_value = self._metric_card(
            cards, "Server", "Stopped", dot=True,
        )
        self.server_card.grid(row=0, column=0, padx=(0, CARD_GAP // 2), pady=(0, CARD_GAP), sticky="nsew")
        self.phone_card, self.phone_dot, self.phone_value = self._metric_card(
            cards, "Phone", "Not paired", dot=True,
        )
        self.phone_card.grid(row=0, column=1, padx=CARD_GAP // 2, pady=(0, CARD_GAP), sticky="nsew")
        self.device_card, _, self.device_value = self._metric_card(cards, "This PC", DEVICE_NAME)
        self.device_card.grid(row=0, column=2, padx=(CARD_GAP // 2, 0), pady=(0, CARD_GAP), sticky="nsew")

        self.address_card, _, self.address_value = self._metric_card(
            cards, "Local address", "…", small=True,
        )
        self.address_card.grid(row=1, column=0, columnspan=2, padx=(0, CARD_GAP // 2), pady=(0, CARD_GAP), sticky="nsew")
        self.count_card, _, self.count_value = self._metric_card(cards, "Transcripts typed", "0")
        self.count_card.grid(row=1, column=2, padx=(CARD_GAP // 2, 0), pady=(0, CARD_GAP), sticky="nsew")

        self.build_card, _, self.build_value = self._metric_card(
            cards, "Android build served to the phone", "No APK yet", small=True,
        )
        self.build_card.grid(row=2, column=0, columnspan=3, sticky="nsew")

        # ---- actions ----
        buttons = ctk.CTkFrame(scroll, fg_color="transparent")
        buttons.pack(fill="x", pady=(CARD_GAP, 0))
        self.toggle_button = styled_button(
            buttons, "Start SpeakType", "primary", command=self.toggle_server, width=170,
        )
        self.toggle_button.pack(side="left")
        styled_button(
            buttons, "Pair phone", "secondary", width=130,
            command=lambda: self.app.show_page("pairing"),
        ).pack(side="left", padx=(10, 0))
        styled_button(
            buttons, "Settings", "quiet", width=110,
            command=lambda: self.app.show_page("settings"),
        ).pack(side="left", padx=(10, 0))

        # ---- firewall caution banner (only packed when blocked) ----
        self.firewall_banner = make_card(scroll, border_color=colors["warning"])
        banner_row = ctk.CTkFrame(self.firewall_banner, fg_color="transparent")
        banner_row.pack(fill="x", padx=CARD_PADDING, pady=CARD_PADDING)
        self.firewall_dot = Dot(banner_row, colors["warning"], size=10, bg=colors["card_bg"])
        self.firewall_dot.pack(side="left", padx=(0, 10))
        body_label(
            banner_row,
            "Your phone cannot reach this PC until Windows Firewall allows SpeakType.",
            text_color=colors["text"], wraplength=420,
        ).pack(side="left", fill="x", expand=True)
        styled_button(
            banner_row, "Allow phone connections", "primary", height=40,
            command=self.app.fix_firewall,
        ).pack(side="right", padx=(12, 0))

        # ---- live preview: the phone's quiet status panel ----
        preview = make_card(scroll)
        preview.pack(fill="x", pady=(CARD_GAP, 0))
        self.preview_frame = preview
        card_overline(preview, "Live preview")
        panel = make_panel(preview, height=72)
        panel.pack(fill="x", padx=CARD_PADDING, pady=(10, CARD_PADDING))
        panel.pack_propagate(False)
        preview_row = ctk.CTkFrame(panel, fg_color="transparent")
        preview_row.pack(fill="both", expand=True, padx=14)
        self.preview_dot = Dot(preview_row, colors["disabled_text"], size=10, bg=colors["surface_alt"])
        self.preview_dot.pack(side="left", padx=(0, 10))
        self.preview_label = value_label(
            preview_row, "", font=self.fonts["body"], text_color=colors["text_muted"],
            justify="left", wraplength=560,
        )
        self.preview_label.pack(side="left", fill="x", expand=True)
        self._preview_hint = (
            "Words appear here while you speak on the phone. Nothing is typed into the "
            "focused window until you finish."
        )

        # ---- recent activity ----
        activity = make_card(scroll)
        activity.pack(fill="both", expand=True, pady=(CARD_GAP, 4))
        card_overline(activity, "Recent activity")
        self.feed = styled_textbox(activity, height=170)
        self.feed.configure(state="disabled")
        self.feed.pack(fill="both", expand=True, padx=CARD_PADDING, pady=(10, CARD_PADDING))

    def _metric_card(self, master, title: str, value: str, dot: bool = False, small: bool = False):
        """One Design.card: overline label over an emphasized value line."""
        colors = active_palette()
        card = make_card(master)
        overline_label(card, title).pack(fill="x", padx=CARD_PADDING, pady=(CARD_PADDING, 0))
        row = ctk.CTkFrame(card, fg_color="transparent")
        row.pack(fill="x", padx=CARD_PADDING, pady=(6, CARD_PADDING))
        dot_widget = None
        if dot:
            dot_widget = Dot(row, colors["disabled_text"], size=10, bg=colors["card_bg"])
            dot_widget.pack(side="left", padx=(0, 8))
        value_view = value_label(
            row, value, font=self.fonts["value" if small else "heading"],
        )
        value_view.pack(side="left", fill="x", expand=True)
        return card, dot_widget, value_view

    def _set_value(self, label, value: str, color: str | None = None) -> None:
        colors = active_palette()
        label.configure(text=value, text_color=color or colors["text"])

    def on_show(self) -> None:
        self.refresh()

    def tick(self) -> None:
        self.refresh()

    def refresh(self) -> None:
        colors = active_palette()
        service = self.app.service
        if service.is_running:
            self._set_value(self.server_value, "Running", colors["success"])
            self.server_dot.set_color(colors["success"])
            apply_button_style(self.toggle_button, "secondary")
            self.toggle_button.configure(text="Stop")
        else:
            self._set_value(self.server_value, "Stopped", colors["danger"])
            self.server_dot.set_color(colors["danger"])
            apply_button_style(self.toggle_button, "primary")
            self.toggle_button.configure(text="Start SpeakType")
        paired = service.paired()
        self._set_value(
            self.phone_value,
            "Paired" if paired else "Not paired",
            colors["success"] if paired else colors["text_muted"],
        )
        self.phone_dot.set_color(colors["success"] if paired else colors["disabled_text"])
        self._set_value(self.device_value, DEVICE_NAME)
        port = self.app.settings.port
        if service.is_running:
            self._set_value(self.address_value, f"{local_ipv4_address()}:{port}  ·  same Wi-Fi only")
        else:
            self._set_value(self.address_value, f"Port {port}  ·  not listening")
        self._set_value(self.count_value, str(len(service.state.delivered_ids)))
        release = service.state.app_release()
        if release:
            built = time.strftime("%d %b %H:%M", time.localtime(release["modified"]))
            self._set_value(
                self.build_value,
                f"{release['size'] / (1024 * 1024):.1f} MB  ·  built {built}",
            )
        else:
            self._set_value(self.build_value, "No APK yet — build the app", colors["warning"])
        self._render_firewall_banner()
        self._render_preview()
        self._render_feed()

    def _render_firewall_banner(self) -> None:
        if getattr(self.app, "firewall_ok", True):
            self.firewall_banner.pack_forget()
        else:
            self.firewall_banner.pack(fill="x", pady=(CARD_GAP, 0), before=self.preview_frame)

    def _render_preview(self) -> None:
        colors = active_palette()
        text, age = self.app.service.state.preview_snapshot()
        if text and age <= PREVIEW_TTL_SECONDS:
            self.preview_label.configure(text=text, text_color=colors["text"])
            self.preview_dot.set_color(colors["accent"])
        else:
            self.preview_label.configure(text=self._preview_hint, text_color=colors["text_muted"])
            self.preview_dot.set_color(colors["disabled_text"])

    def _render_feed(self) -> None:
        events = self.app.service.state.events
        if len(events) == self._last_event_count:
            return
        self._last_event_count = len(events)
        self.feed.configure(state="normal")
        self.feed.delete("1.0", "end")
        self.feed.insert("end", "\n".join(events[-60:]) or "No activity yet.")
        self.feed.see("end")
        self.feed.configure(state="disabled")

    def toggle_server(self) -> None:
        service = self.app.service
        try:
            if service.is_running:
                service.stop()
            else:
                service.start()
        except OSError as exc:
            self.app.toast(f"Could not start SpeakType: {exc}")
        self.refresh()
        self.app.refresh_status()