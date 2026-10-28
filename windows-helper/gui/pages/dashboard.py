from __future__ import annotations

import time

import customtkinter as ctk

from gui.pages.base import BasePage
from gui.theme import CARD_CORNER_RADIUS, BUTTON_CORNER_RADIUS, active_palette, get_fonts
from voice_to_text_helper import DEVICE_NAME, PREVIEW_TTL_SECONDS, local_ipv4_address


class DashboardPage(BasePage):
    title = "Dashboard"

    def __init__(self, master, app) -> None:
        super().__init__(master, app)
        colors = active_palette()
        self.fonts = get_fonts()
        self._last_event_count = -1

        scroll = ctk.CTkScrollableFrame(self, fg_color="transparent")
        scroll.pack(fill="both", expand=True)

        cards = ctk.CTkFrame(scroll, fg_color="transparent")
        cards.pack(fill="x", padx=4, pady=(4, 10))
        for column in range(3):
            cards.grid_columnconfigure(column, weight=1)

        self.server_card = self._card(cards, "Helper", "Stopped")
        self.server_card.grid(row=0, column=0, padx=(0, 8), pady=(0, 8), sticky="nsew")
        self.phone_card = self._card(cards, "Phone", "Not paired")
        self.phone_card.grid(row=0, column=1, padx=8, pady=(0, 8), sticky="nsew")
        self.device_card = self._card(cards, "This PC", DEVICE_NAME)
        self.device_card.grid(row=0, column=2, padx=(8, 0), pady=(0, 8), sticky="nsew")
        self.address_card = self._card(cards, "Local address", "…")
        self.address_card.grid(row=1, column=0, columnspan=2, padx=(0, 8), pady=(0, 8), sticky="nsew")
        self.count_card = self._card(cards, "Transcripts typed", "0")
        self.count_card.grid(row=1, column=2, padx=(8, 0), pady=(0, 8), sticky="nsew")
        self.build_card = self._card(cards, "Android build served to the phone", "No APK yet")
        self.build_card.grid(row=2, column=0, columnspan=3, padx=(0, 0), pady=(0, 8), sticky="nsew")

        buttons = ctk.CTkFrame(scroll, fg_color="transparent")
        buttons.pack(fill="x", padx=4, pady=(0, 10))
        ctk.CTkButton(
            buttons,
            text="Start / Stop helper",
            width=170,
            corner_radius=BUTTON_CORNER_RADIUS,
            command=self.toggle_server,
        ).pack(side="left")
        ctk.CTkButton(
            buttons,
            text="Pair phone",
            width=130,
            corner_radius=BUTTON_CORNER_RADIUS,
            command=lambda: self.app.show_page("pairing"),
        ).pack(side="left", padx=8)
        ctk.CTkButton(
            buttons,
            text="Settings",
            width=110,
            corner_radius=BUTTON_CORNER_RADIUS,
            command=lambda: self.app.show_page("settings"),
        ).pack(side="left")

        preview = ctk.CTkFrame(
            scroll, corner_radius=CARD_CORNER_RADIUS, border_width=1, fg_color=colors["card_bg"], border_color=colors["card_border"]
        )
        preview.pack(fill="x", padx=4, pady=(0, 10))
        ctk.CTkLabel(
            preview, text="Live preview", font=self.fonts["heading"], text_color=colors["text"], anchor="w"
        ).pack(fill="x", padx=14, pady=(12, 4))
        self.preview_label = ctk.CTkLabel(
            preview, text="", font=self.fonts["body"], text_color=colors["text_muted"],
            anchor="w", justify="left", wraplength=600,
        )
        self.preview_label.pack(fill="x", padx=14, pady=(0, 12))
        self._preview_hint = (
            "Words appear here while you speak on the phone. Nothing is typed into the "
            "focused window until you finish."
        )

        activity = ctk.CTkFrame(
            scroll, corner_radius=CARD_CORNER_RADIUS, border_width=1, fg_color=colors["card_bg"], border_color=colors["card_border"]
        )
        activity.pack(fill="both", expand=True, padx=4, pady=(0, 4))
        ctk.CTkLabel(
            activity, text="Recent activity", font=self.fonts["heading"], text_color=colors["text"], anchor="w"
        ).pack(fill="x", padx=14, pady=(12, 6))
        self.feed = ctk.CTkTextbox(activity, height=180, font=self.fonts["mono_small"], wrap="word")
        self.feed.configure(state="disabled")
        self.feed.pack(fill="both", expand=True, padx=10, pady=(0, 12))

    def _card(self, master, title: str, value: str) -> ctk.CTkFrame:
        colors = active_palette()
        card = ctk.CTkFrame(
            master, corner_radius=CARD_CORNER_RADIUS, border_width=1,
            fg_color=colors["card_bg"], border_color=colors["card_border"],
        )
        ctk.CTkLabel(card, text=title, font=self.fonts["small"], text_color=colors["text_muted"], anchor="w").pack(
            fill="x", padx=14, pady=(12, 0)
        )
        value_label = ctk.CTkLabel(card, text=value, font=self.fonts["heading"], text_color=colors["text"], anchor="w")
        value_label.pack(fill="x", padx=14, pady=(0, 12))
        card.value_label = value_label  # type: ignore[attr-defined]
        return card

    def _set_card(self, card: ctk.CTkFrame, value: str, color: str | None = None) -> None:
        colors = active_palette()
        card.value_label.configure(text=value, text_color=color or colors["text"])  # type: ignore[attr-defined]

    def on_show(self) -> None:
        self.refresh()

    def tick(self) -> None:
        self.refresh()

    def refresh(self) -> None:
        colors = active_palette()
        service = self.app.service
        if service.is_running:
            self._set_card(self.server_card, "Running", colors["success"])
        else:
            self._set_card(self.server_card, "Stopped", colors["danger"])
        self._set_card(
            self.phone_card,
            "Paired" if service.paired() else "Not paired",
            colors["success"] if service.paired() else colors["warning"],
        )
        self._set_card(self.device_card, DEVICE_NAME)
        port = self.app.settings.port
        if service.is_running:
            self._set_card(self.address_card, f"{local_ipv4_address()}:{port}  ·  same Wi-Fi only")
        else:
            self._set_card(self.address_card, f"Port {port}  ·  not listening")
        self._set_card(self.count_card, str(len(service.state.delivered_ids)))
        release = service.state.app_release()
        if release:
            built = time.strftime("%d %b %H:%M", time.localtime(release["modified"]))
            self._set_card(
                self.build_card,
                f"{release['size'] / (1024 * 1024):.1f} MB  ·  built {built}",
            )
        else:
            self._set_card(self.build_card, "No APK yet — build the app", colors["warning"])
        self._render_preview()
        self._render_feed()

    def _render_preview(self) -> None:
        colors = active_palette()
        text, age = self.app.service.state.preview_snapshot()
        if text and age <= PREVIEW_TTL_SECONDS:
            self.preview_label.configure(text=text, text_color=colors["text"])
        else:
            self.preview_label.configure(text=self._preview_hint, text_color=colors["text_muted"])

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
            self.app.toast(f"Could not start the helper: {exc}")
        self.refresh()
        self.app.refresh_status()
