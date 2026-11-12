from __future__ import annotations

import tkinter.messagebox as messagebox

import customtkinter as ctk

from gui.pages.base import BasePage
from gui.theme import CARD_GAP, CARD_PADDING, active_palette, get_fonts
from gui.widgets import (
    card_body,
    card_headline,
    card_overline,
    make_card,
    make_panel,
    scroll_frame,
    styled_button,
)
from speaktype import APP_DIR, MAX_TEXT


class SecurityPage(BasePage):
    title = "Security"

    def __init__(self, master, app) -> None:
        super().__init__(master, app)
        colors = active_palette()
        self.fonts = get_fonts()

        scroll = scroll_frame(self)
        scroll.pack(fill="both", expand=True)

        # ---- privacy ----
        info = make_card(scroll)
        info.pack(fill="x", pady=(2, CARD_GAP))
        card_overline(info, "Privacy")
        card_headline(info, "Local by design")
        card_body(
            info,
            "Pairing and transcripts never leave this network. The connection is TLS pinned to this PC's "
            "certificate fingerprint, and the pairing token is encrypted with Windows DPAPI so only your user "
            "account can read it. Nothing is sent to a cloud service.",
            wraplength=640,
        )
        ctk.CTkFrame(info, height=1, fg_color="transparent").pack(fill="x", pady=(0, CARD_PADDING))

        # ---- certificate ----
        cert = make_card(scroll)
        cert.pack(fill="x", pady=(0, CARD_GAP))
        card_overline(cert, "Security")
        card_headline(cert, "Certificate fingerprint")
        panel = make_panel(cert)
        panel.pack(fill="x", padx=CARD_PADDING, pady=(10, 0))
        self.fingerprint_label = ctk.CTkLabel(
            panel, text="", font=self.fonts["mono_small"], text_color=colors["text"],
            wraplength=580, justify="left", anchor="w",
        )
        self.fingerprint_label.pack(fill="x", padx=14, pady=12)
        card_body(
            cert,
            "Replace the certificate only if you suspect it leaked. The phone must be paired again afterwards.",
            wraplength=620,
        )
        cert_buttons = ctk.CTkFrame(cert, fg_color="transparent")
        cert_buttons.pack(fill="x", padx=CARD_PADDING, pady=(12, CARD_PADDING))
        styled_button(
            cert_buttons, "Replace certificate", "danger", command=self.replace_certificate,
        ).pack(side="left")
        styled_button(
            cert_buttons, "Unpair phone", "quiet", command=self.unpair,
        ).pack(side="left", padx=(10, 0))
        styled_button(
            cert_buttons, "Open data folder", "quiet", command=self.app.service.open_data_folder,
        ).pack(side="left", padx=(10, 0))

        # ---- storage ----
        storage = make_card(scroll)
        storage.pack(fill="x", pady=(0, 4))
        card_overline(storage, "Storage")
        card_headline(storage, "Data folder")
        path_panel = make_panel(storage)
        path_panel.pack(fill="x", padx=CARD_PADDING, pady=(10, 0))
        ctk.CTkLabel(
            path_panel, text=str(APP_DIR), font=self.fonts["mono_small"], text_color=colors["text_muted"],
            wraplength=580, justify="left", anchor="w",
        ).pack(fill="x", padx=14, pady=12)
        card_body(
            storage,
            f"Settings file, certificate, pairing token, and delivery ledger live here. "
            f"One transcript can be up to {MAX_TEXT:,} characters. Transcripts are never stored after they are typed.",
            wraplength=640,
        )
        ctk.CTkFrame(storage, height=1, fg_color="transparent").pack(fill="x", pady=(0, CARD_PADDING))

    def on_show(self) -> None:
        self.refresh()

    def refresh(self) -> None:
        fingerprint = self.app.service.fingerprint()
        self.fingerprint_label.configure(text=fingerprint or "Certificate not created yet.")

    def replace_certificate(self) -> None:
        if not messagebox.askyesno(
            "SpeakType",
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
        if not messagebox.askyesno("SpeakType", "Unpair the phone? Dictation stops until you pair again."):
            return
        self.app.service.unpair()
        self.app.refresh_status()
        self.refresh()