from __future__ import annotations

import tkinter.messagebox as messagebox

import customtkinter as ctk

from gui.pages.base import BasePage
from gui.theme import CARD_CORNER_RADIUS, active_palette, get_fonts
from voice_to_text_helper import APP_DIR, MAX_TEXT


class SecurityPage(BasePage):
    title = "Security"

    def __init__(self, master, app) -> None:
        super().__init__(master, app)
        colors = active_palette()
        FONTS = get_fonts()

        scroll = ctk.CTkScrollableFrame(self, fg_color="transparent")
        scroll.pack(fill="both", expand=True)

        info = ctk.CTkFrame(
            scroll, corner_radius=CARD_CORNER_RADIUS, border_width=1,
            fg_color=colors["card_bg"], border_color=colors["card_border"],
        )
        info.pack(fill="x", padx=4, pady=4)
        ctk.CTkLabel(
            info,
            text="Pairing and transcripts never leave this network. The connection is TLS pinned to this PC's "
            "certificate fingerprint, and the pairing token is encrypted with Windows DPAPI so only your user "
            "account can read it. Nothing is sent to a cloud service.",
            font=FONTS["body"], text_color=colors["text"], wraplength=620, justify="left",
        ).pack(fill="x", padx=16, pady=16)

        cert = ctk.CTkFrame(
            scroll, corner_radius=CARD_CORNER_RADIUS, border_width=1,
            fg_color=colors["card_bg"], border_color=colors["card_border"],
        )
        cert.pack(fill="x", padx=4, pady=4)
        ctk.CTkLabel(
            cert, text="Certificate fingerprint", font=FONTS["heading"], text_color=colors["text"], anchor="w",
        ).pack(fill="x", padx=16, pady=(14, 4))
        self.fingerprint_label = ctk.CTkLabel(
            cert, text="", font=FONTS["mono_small"], text_color=colors["text"],
            wraplength=600, justify="left", anchor="w",
        )
        self.fingerprint_label.pack(fill="x", padx=16, pady=(0, 8))
        ctk.CTkLabel(
            cert,
            text="Replace the certificate only if you suspect it leaked. The phone must be paired again afterwards.",
            font=FONTS["small"], text_color=colors["text_muted"], wraplength=600, justify="left", anchor="w",
        ).pack(fill="x", padx=16, pady=(0, 12))

        buttons = ctk.CTkFrame(scroll, fg_color="transparent")
        buttons.pack(fill="x", padx=4, pady=(0, 8))
        ctk.CTkButton(
            buttons, text="Replace certificate", width=160,
            fg_color=colors["danger"], hover_color="#c94b4b", command=self.replace_certificate,
        ).pack(side="left")
        ctk.CTkButton(buttons, text="Unpair phone", width=140, command=self.unpair).pack(side="left", padx=8)
        ctk.CTkButton(buttons, text="Open data folder", width=160, command=self.app.service.open_data_folder).pack(side="left")

        storage = ctk.CTkFrame(
            scroll, corner_radius=CARD_CORNER_RADIUS, border_width=1,
            fg_color=colors["card_bg"], border_color=colors["card_border"],
        )
        storage.pack(fill="x", padx=4, pady=(0, 8))
        ctk.CTkLabel(
            storage, text="Storage", font=FONTS["heading"], text_color=colors["text"], anchor="w",
        ).pack(fill="x", padx=16, pady=(14, 4))
        ctk.CTkLabel(
            storage, text=str(APP_DIR), font=FONTS["mono_small"], text_color=colors["text_muted"],
            wraplength=600, justify="left", anchor="w",
        ).pack(fill="x", padx=16, pady=(0, 4))
        ctk.CTkLabel(
            storage,
            text=f"Settings file, certificate, pairing token, and delivery ledger live here. "
            f"One transcript can be up to {MAX_TEXT:,} characters. Transcripts are never stored after they are typed.",
            font=FONTS["small"], text_color=colors["text_muted"], wraplength=600, justify="left", anchor="w",
        ).pack(fill="x", padx=16, pady=(0, 16))

    def on_show(self) -> None:
        self.refresh()

    def refresh(self) -> None:
        fingerprint = self.app.service.fingerprint()
        self.fingerprint_label.configure(text=fingerprint or "Certificate not created yet.")

    def replace_certificate(self) -> None:
        if not messagebox.askyesno(
            "Voice to PC",
            "Replace the certificate? The current phone pairing is invalidated and you must scan a new QR code.",
        ):
            return
        self.app.service.regenerate_certificate()
        self.app.refresh_status()
        self.refresh()

    def unpair(self) -> None:
        if not self.app.service.paired():
            self.app.toast("No phone is paired.")
            return
        if not messagebox.askyesno("Voice to PC", "Unpair the phone? Dictation stops until you pair again."):
            return
        self.app.service.unpair()
        self.app.refresh_status()
        self.refresh()
