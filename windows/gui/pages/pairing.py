from __future__ import annotations

import time
import tkinter.messagebox as messagebox

import customtkinter as ctk

from gui.pages.base import BasePage
from gui.theme import CARD_GAP, CARD_PADDING, active_palette, get_fonts
from gui.widgets import (
    StatusPill,
    body_label,
    caption_label,
    card_body,
    card_headline,
    card_overline,
    make_card,
    make_panel,
    scroll_frame,
    set_button_disabled,
    styled_button,
    value_label,
)
from speaktype import PAIR_TTL_SECONDS


class PairingPage(BasePage):
    title = "Pair phone"

    def __init__(self, master, app) -> None:
        super().__init__(master, app)
        colors = active_palette()
        self.fonts = get_fonts()
        self.payload: dict | None = None
        self.qr_photo = None
        self.expires_at = 0.0
        self.expired_shown = False

        scroll = scroll_frame(self)
        scroll.pack(fill="both", expand=True)

        body_label(
            scroll,
            "Scan this QR code once with the phone app. The phone and this PC must share the same Wi-Fi. "
            "Pairing survives PC restarts; a new router-assigned IP is rediscovered automatically.",
            wraplength=640,
        ).pack(fill="x", pady=(2, 12))

        # Desktop adaptation of the phone's single column: QR left, actions and
        # fingerprint right, so nothing important hides below the fold.
        columns = ctk.CTkFrame(scroll, fg_color="transparent")
        columns.pack(fill="x", expand=False)
        left = ctk.CTkFrame(columns, fg_color="transparent")
        left.pack(side="left", anchor="n")
        right = ctk.CTkFrame(columns, fg_color="transparent")
        right.pack(side="left", fill="both", expand=True, padx=(CARD_GAP + 6, 0))

        # The QR sits on a white card: scanners need the quiet zone, the phone needs the contrast.
        qr_card = make_card(left, fg_color=colors["qr_bg"], width=312, height=312)
        qr_card.pack(anchor="center", pady=(0, 10))
        qr_card.pack_propagate(False)
        self.qr_label = ctk.CTkLabel(qr_card, text="")
        self.qr_label.pack(expand=True)

        self.countdown = value_label(left, "", text_color=colors["text_muted"])
        self.countdown.pack(anchor="center", pady=(0, 4))
        self.address_label = ctk.CTkLabel(
            left, text="", font=self.fonts["mono_small"], text_color=colors["text_muted"],
            wraplength=300, justify="center",
        )
        self.address_label.pack(anchor="center", pady=(0, CARD_GAP))

        self.banner = StatusPill(right, "", colors["warning"])
        self.banner.pack(anchor="w", pady=(0, CARD_GAP))

        buttons = ctk.CTkFrame(right, fg_color="transparent")
        buttons.pack(anchor="w", pady=(0, CARD_GAP))
        styled_button(buttons, "New QR code", "primary", width=150, command=self.new_code).pack(side="left")
        self.unpair_button = styled_button(buttons, "Unpair phone", "danger", width=150, command=self.unpair)
        self.unpair_button.pack(side="left", padx=(10, 0))

        self.test_label = caption_label(right, "", wraplength=380)
        self.test_label.pack(fill="x", pady=(0, CARD_GAP))

        fingerprint_card = make_card(right)
        fingerprint_card.pack(fill="x", pady=(0, 4))
        card_overline(fingerprint_card, "Security")
        card_headline(fingerprint_card, "Certificate fingerprint")
        card_body(
            fingerprint_card,
            "The phone pins this fingerprint; a replaced certificate needs a fresh pairing.",
            wraplength=360,
        )
        panel = make_panel(fingerprint_card)
        panel.pack(fill="x", padx=CARD_PADDING, pady=(10, CARD_PADDING))
        self.fingerprint_label = ctk.CTkLabel(
            panel, text="", font=self.fonts["mono_small"], text_color=colors["text"],
            anchor="w", justify="left", wraplength=340,
        )
        self.fingerprint_label.pack(fill="x", padx=14, pady=12)

    def on_show(self) -> None:
        self.refresh()

    def tick(self) -> None:
        if self.payload is None:
            return
        remaining = int(self.expires_at - time.monotonic())
        if remaining > 0:
            self.expired_shown = False
            self.countdown.configure(text=f"Scan within {remaining // 60}:{remaining % 60:02d}")
        elif not self.expired_shown:
            self.expired_shown = True
            if self.app.service.is_running:
                # A dead QR code left on screen is what people scan; rotate it immediately.
                self.new_code()
            else:
                self.countdown.configure(text="Code expired — press “New QR code”.")

    def refresh(self) -> None:
        colors = active_palette()
        fingerprint = self.app.service.fingerprint()
        self.fingerprint_label.configure(text=fingerprint or "Certificate not created yet.")
        paired = self.app.service.paired()
        if paired:
            self.banner.set_state("Phone paired — dictation is live.", colors["success"])
            set_button_disabled(self.unpair_button, False)
        else:
            self.banner.set_state("No phone paired yet — scan the code below.", colors["warning"])
            set_button_disabled(self.unpair_button, True)
        if self.payload is None:
            self.new_code()

    def new_code(self) -> None:
        service = self.app.service
        if not service.is_running:
            self.app.toast("Start SpeakType before pairing.")
            return
        self.payload = service.new_pairing()
        self.expires_at = time.monotonic() + PAIR_TTL_SECONDS
        self.expired_shown = False
        image = service.qr_image(self.payload, size=280)
        self.qr_photo = ctk.CTkImage(light_image=image, dark_image=image, size=(280, 280))
        self.qr_label.configure(image=self.qr_photo, text="")
        self.address_label.configure(
            text=f"{self.payload['host']}:{self.payload['port']}  ·  the phone must be on this Wi-Fi"
        )
        self.test_label.configure(
            text=f"Phone says unreachable? Open https://{self.payload['host']}:{self.payload['port']}/v1/health "
            "in its browser — it must show a response instead of timing out."
        )
        self.countdown.configure(text=f"Scan within {PAIR_TTL_SECONDS // 60}:00")

    def unpair(self) -> None:
        if not self.app.service.paired():
            return
        if not messagebox.askyesno("SpeakType", "Unpair the phone? Dictation stops until you pair again."):
            return
        self.app.service.unpair()
        self.app.refresh_status()
        self.refresh()