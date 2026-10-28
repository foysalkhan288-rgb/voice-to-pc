from __future__ import annotations

import time
import tkinter.messagebox as messagebox

import customtkinter as ctk

from gui.pages.base import BasePage
from gui.theme import CARD_CORNER_RADIUS, active_palette, get_fonts
from voice_to_text_helper import PAIR_TTL_SECONDS


class PairingPage(BasePage):
    title = "Pair phone"

    def __init__(self, master, app) -> None:
        super().__init__(master, app)
        colors = active_palette()
        FONTS = get_fonts()
        self.payload: dict | None = None
        self.qr_photo = None
        self.expires_at = 0.0
        self.expired_shown = False

        scroll = ctk.CTkScrollableFrame(self, fg_color="transparent")
        scroll.pack(fill="both", expand=True)

        ctk.CTkLabel(
            scroll,
            text="Scan this QR code once with the phone app. The phone and this PC must share the same Wi-Fi. "
            "Pairing survives PC restarts; a new router-assigned IP is rediscovered automatically.",
            font=FONTS["body"], text_color=colors["text"], wraplength=620, justify="left",
        ).pack(fill="x", padx=6, pady=(6, 12))

        self.banner = ctk.CTkLabel(scroll, text="", font=FONTS["heading"], justify="left")
        self.banner.pack(fill="x", padx=6, pady=(0, 10))

        qr_frame = ctk.CTkFrame(
            scroll, width=320, height=320, corner_radius=CARD_CORNER_RADIUS,
            fg_color=colors["qr_bg"],
        )
        qr_frame.pack(padx=6, pady=(0, 4))
        qr_frame.pack_propagate(False)
        self.qr_label = ctk.CTkLabel(qr_frame, text="")
        self.qr_label.pack(expand=True)

        self.countdown = ctk.CTkLabel(scroll, text="", font=FONTS["body"], text_color=colors["text_muted"])
        self.countdown.pack(padx=6, pady=(6, 10), anchor="w")

        buttons = ctk.CTkFrame(scroll, fg_color="transparent")
        buttons.pack(fill="x", padx=6, pady=(0, 12))
        ctk.CTkButton(buttons, text="New QR code", width=150, command=self.new_code).pack(side="left")
        self.unpair_button = ctk.CTkButton(
            buttons, text="Unpair phone", width=150, fg_color=colors["danger"], hover_color="#c94b4b",
            command=self.unpair,
        )
        self.unpair_button.pack(side="left", padx=8)

        fingerprint_card = ctk.CTkFrame(
            scroll, corner_radius=CARD_CORNER_RADIUS, border_width=1,
            fg_color=colors["card_bg"], border_color=colors["card_border"],
        )
        fingerprint_card.pack(fill="x", padx=6, pady=(0, 8))
        ctk.CTkLabel(
            fingerprint_card, text="Certificate fingerprint", font=FONTS["small"],
            text_color=colors["text_muted"], anchor="w",
        ).pack(fill="x", padx=14, pady=(12, 2))
        ctk.CTkLabel(
            fingerprint_card, text="The phone pins this fingerprint; a replaced certificate needs a fresh pairing.",
            font=FONTS["small"], text_color=colors["text_muted"], anchor="w", wraplength=600, justify="left",
        ).pack(fill="x", padx=14, pady=(0, 4))
        self.fingerprint_label = ctk.CTkLabel(
            fingerprint_card, text="", font=FONTS["mono_small"], text_color=colors["text"],
            anchor="w", wraplength=600, justify="left",
        )
        self.fingerprint_label.pack(fill="x", padx=14, pady=(0, 14))

    def on_show(self) -> None:
        self.refresh()

    def tick(self) -> None:
        remaining = int(self.expires_at - time.monotonic())
        if self.payload is None:
            return
        if remaining > 0:
            self.expired_shown = False
            self.countdown.configure(text=f"Scan within {remaining // 60}:{remaining % 60:02d}")
        elif not self.expired_shown:
            self.expired_shown = True
            self.countdown.configure(text="Code expired — press “New QR code”.")

    def refresh(self) -> None:
        colors = active_palette()
        fingerprint = self.app.service.fingerprint()
        self.fingerprint_label.configure(text=fingerprint or "Certificate not created yet.")
        paired = self.app.service.paired()
        if paired:
            self.banner.configure(text="Phone paired — dictation is live.", text_color=colors["success"])
            self.unpair_button.configure(state="normal")
        else:
            self.banner.configure(
                text="No phone paired yet. Run the app on the phone and scan the code below.",
                text_color=colors["warning"],
            )
            self.unpair_button.configure(state="disabled")
        if self.payload is None:
            self.new_code()

    def new_code(self) -> None:
        service = self.app.service
        if not service.is_running:
            self.app.toast("Start the helper before pairing.")
            return
        self.payload = service.new_pairing()
        self.expires_at = time.monotonic() + PAIR_TTL_SECONDS
        self.expired_shown = False
        image = service.qr_image(self.payload, size=280)
        self.qr_photo = ctk.CTkImage(light_image=image, dark_image=image, size=(280, 280))
        self.qr_label.configure(image=self.qr_photo, text="")
        self.countdown.configure(text=f"Scan within {PAIR_TTL_SECONDS // 60}:00")

    def unpair(self) -> None:
        if not self.app.service.paired():
            return
        if not messagebox.askyesno("Voice to PC", "Unpair the phone? Dictation stops until you pair again."):
            return
        self.app.service.unpair()
        self.app.refresh_status()
        self.refresh()
