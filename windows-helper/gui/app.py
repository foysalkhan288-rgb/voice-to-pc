from __future__ import annotations

import tkinter.messagebox as messagebox

import customtkinter as ctk
import pystray
from PIL import Image, ImageDraw

from gui.pages import DashboardPage, PairingPage, SecurityPage, SettingsPage
from gui.pages.base import BasePage
from gui.theme import (
    SIDEBAR_WIDTH,
    WINDOW_MIN_SIZE,
    WINDOW_SIZE,
    active_palette,
    apply_theme,
    get_fonts,
)
from settings import AppSettings
from voice_to_text_helper import (
    APP_NAME,
    DEVICE_NAME,
    VoiceHelperService,
    local_ipv4_address,
)

VERSION = "1.0.3"

NAV_ITEMS: tuple[tuple[str, str, type[BasePage]], ...] = (
    ("dashboard", "Dashboard", DashboardPage),
    ("pairing", "Pair phone", PairingPage),
    ("settings", "Settings", SettingsPage),
    ("security", "Security", SecurityPage),
)


class VoiceToPcApp:
    root: ctk.CTk
    settings: AppSettings
    service: VoiceHelperService
    pages: dict[str, BasePage]

    def __init__(self) -> None:
        self.settings = AppSettings.load()
        palette = apply_theme(self.settings.theme)
        self.service = VoiceHelperService(self.settings)
        self.service.on_event = self._on_helper_event
        self.service.notifier = self._notify
        self.service.state.preview_callback = self._on_preview
        self.current_page: BasePage | None = None
        self._status_after_id: str | None = None

        self.root = ctk.CTk()
        self.root.title(APP_NAME)
        self.root.geometry(WINDOW_SIZE)
        self.root.minsize(*WINDOW_MIN_SIZE)
        self.root.protocol("WM_DELETE_WINDOW", self.hide_window)

        self.fonts = get_fonts()

        self.root.grid_columnconfigure(1, weight=1)
        self.root.grid_rowconfigure(0, weight=1)

        self._build_sidebar(palette)
        self._build_content(palette)
        self._build_status_bar(palette)

        self.pages = {name: page_class(self.content, self) for name, _, page_class in NAV_ITEMS}
        self.show_page("dashboard")

        self.tray = self._create_tray_icon()
        self.tray.run_detached()

        self.refresh_status()
        self.root.after(1000, self._periodic)

        try:
            self.service.start()
        except OSError as exc:
            messagebox.showerror(
                APP_NAME,
                f"The helper could not listen on port {self.settings.port}: {exc}\n\n"
                "Another program may be using it. Change the port in Settings, then start the helper.",
            )
        self.refresh_status()

        if self.settings.start_minimized:
            self.root.withdraw()

    def _build_sidebar(self, palette: dict[str, str]) -> None:
        sidebar = ctk.CTkFrame(self.root, width=SIDEBAR_WIDTH, corner_radius=0, fg_color=palette["sidebar_bg"])
        sidebar.grid(row=0, column=0, sticky="nsew")
        sidebar.grid_rowconfigure(len(NAV_ITEMS) + 1, weight=1)
        sidebar.grid_propagate(False)

        ctk.CTkLabel(
            sidebar, text=APP_NAME, font=self.fonts["title"], text_color=palette["text"], anchor="w",
        ).grid(row=0, column=0, padx=18, pady=(20, 2), sticky="w")
        ctk.CTkLabel(
            sidebar, text=DEVICE_NAME, font=self.fonts["small"], text_color=palette["text_muted"], anchor="w",
        ).grid(row=1, column=0, padx=18, pady=(0, 14), sticky="w")

        self.nav_buttons: dict[str, ctk.CTkButton] = {}
        for index, (name, label, _) in enumerate(NAV_ITEMS, start=2):
            button = ctk.CTkButton(
                sidebar, text=label, anchor="w", height=36, corner_radius=8,
                fg_color="transparent", hover_color=palette["card_bg"], text_color=palette["text"],
                command=lambda target=name: self.show_page(target),
            )
            button.grid(row=index, column=0, padx=12, pady=3, sticky="ew")
            self.nav_buttons[name] = button

        footer = ctk.CTkFrame(sidebar, fg_color="transparent")
        footer.grid(row=len(NAV_ITEMS) + 2, column=0, padx=18, pady=(0, 16), sticky="sw")
        ctk.CTkLabel(footer, text="Status", font=self.fonts["small"], text_color=palette["text_muted"], anchor="w").pack(
            side="top", anchor="w"
        )
        dot_row = ctk.CTkFrame(footer, fg_color="transparent")
        dot_row.pack(side="top", anchor="w", pady=(2, 0))
        self.status_dot = ctk.CTkLabel(dot_row, text="●", font=self.fonts["heading"], text_color=palette["danger"])
        self.status_dot.pack(side="left")
        self.status_dot_text = ctk.CTkLabel(
            dot_row, text="Stopped", font=self.fonts["small"], text_color=palette["text_muted"],
        )
        self.status_dot_text.pack(side="left", padx=(6, 0))
        ctk.CTkLabel(
            footer, text=f"v{VERSION}", font=self.fonts["small"], text_color=palette["text_muted"],
        ).pack(side="top", anchor="w", pady=(4, 0))

    def _build_content(self, palette: dict[str, str]) -> None:
        content = ctk.CTkFrame(self.root, corner_radius=0, fg_color=palette["bg"])
        content.grid(row=0, column=1, sticky="nsew")
        content.grid_rowconfigure(2, weight=1)
        content.grid_columnconfigure(0, weight=1)

        self.header_title = ctk.CTkLabel(
            content, text="", font=self.fonts["title"], text_color=palette["text"], anchor="w",
        )
        self.header_title.grid(row=0, column=0, padx=28, pady=(22, 0), sticky="w")

        header_line = ctk.CTkFrame(content, height=1, fg_color=palette["card_border"])
        header_line.grid(row=1, column=0, padx=28, pady=(12, 0), sticky="ew")

        self.content = ctk.CTkFrame(content, fg_color="transparent")
        self.content.grid(row=2, column=0, padx=20, pady=12, sticky="nsew")

    def _build_status_bar(self, palette: dict[str, str]) -> None:
        bar = ctk.CTkFrame(self.root, height=30, corner_radius=0, fg_color=palette["sidebar_bg"])
        bar.grid(row=1, column=0, columnspan=2, sticky="ew")
        self.status_label = ctk.CTkLabel(bar, text="", font=self.fonts["small"], text_color=palette["text_muted"], anchor="w")
        self.status_label.pack(side="left", padx=18, fill="y")

    def show_page(self, name: str) -> None:
        if name not in self.pages:
            return
        if self.current_page is not None:
            self.current_page.pack_forget()
        self.current_page = self.pages[name]
        self.current_page.pack(fill="both", expand=True)
        self.header_title.configure(text=self.current_page.title)
        for nav_name, button in self.nav_buttons.items():
            palette = active_palette()
            button.configure(
                fg_color=palette["accent"] if nav_name == name else "transparent",
                text_color="#ffffff" if nav_name == name else palette["text"],
                hover=nav_name != name,
            )
        self.current_page.on_show()
        self.refresh_status()

    def refresh_status(self) -> None:
        palette = active_palette()
        service = self.service
        if service.is_running:
            dot, dot_text = palette["success"], "Running"
        else:
            dot, dot_text = palette["danger"], "Stopped"
        if service.is_running and not service.paired():
            dot = palette["warning"]
        self.status_dot.configure(text_color=dot)
        self.status_dot_text.configure(text=dot_text)

        if service.is_running:
            state = f"Running on {local_ipv4_address()}:{self.settings.port}  ·  same Wi-Fi only"
        else:
            state = "Helper stopped"
        pairing = "phone paired" if service.paired() else "no phone paired"
        self.status_label.configure(text=f"{state}  ·  {pairing}")

    def set_status(self, text: str) -> None:
        self.status_label.configure(text=text)

    def toast(self, message: str) -> None:
        self.set_status(message)
        if self._status_after_id is not None:
            self.root.after_cancel(self._status_after_id)
        self._status_after_id = self.root.after(4000, self.refresh_status)

    def restart_server(self) -> None:
        try:
            self.service.stop()
            self.service.start()
        except OSError as exc:
            messagebox.showerror(APP_NAME, f"Could not restart the helper: {exc}")
        self.refresh_status()
        for page in self.pages.values():
            page.on_settings_changed()

    def hide_window(self) -> None:
        self.root.withdraw()

    def show_window(self) -> None:
        self.root.deiconify()
        self.root.lift()
        self.root.focus_force()

    def quit_app(self) -> None:
        def shutdown() -> None:
            try:
                self.tray.stop()
            except Exception:
                pass
            self.service.stop()
            self.root.quit()
            self.root.destroy()

        try:
            self.root.after(0, shutdown)
        except Exception:
            shutdown()

    def _periodic(self) -> None:
        self.refresh_status()
        if self.current_page is not None:
            self.current_page.tick()
        self.root.after(1000, self._periodic)

    def _on_helper_event(self, message: str) -> None:
        def update() -> None:
            self.refresh_status()
            if self.current_page is not None:
                self.current_page.tick()

        try:
            self.root.after(0, update)
        except Exception:
            pass

    def _on_preview(self) -> None:
        """Partial transcripts arrive from the request thread; repaint on the UI thread."""

        def update() -> None:
            if self.current_page is not None:
                self.current_page.tick()

        try:
            self.root.after(0, update)
        except Exception:
            pass

    def _notify(self, message: str) -> None:
        try:
            self.tray.notify(message, APP_NAME)
        except Exception:
            pass

    def _create_tray_icon(self) -> pystray.Icon:
        image = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
        draw = ImageDraw.Draw(image)
        draw.rounded_rectangle((0, 0, 63, 63), radius=14, fill=(33, 110, 80, 255))
        draw.ellipse((9, 9, 55, 55), fill=(245, 255, 250, 255))
        draw.text((19, 18), "V", fill=(33, 110, 80, 255), stroke_width=1)
        menu = pystray.Menu(
            pystray.MenuItem("Show dashboard", self._tray_show),
            pystray.MenuItem("Pair phone…", self._tray_pair),
            pystray.MenuItem("Quit", self._tray_quit),
        )
        return pystray.Icon("voice-to-pc", image, APP_NAME, menu)

    def _tray_show(self, icon=None, item=None) -> None:
        self.root.after(0, self.show_window)

    def _tray_pair(self, icon=None, item=None) -> None:
        def show_pair() -> None:
            self.show_window()
            self.show_page("pairing")

        self.root.after(0, show_pair)

    def _tray_quit(self, icon=None, item=None) -> None:
        self.quit_app()

    def run(self) -> None:
        self.root.mainloop()


def run_gui() -> None:
    VoiceToPcApp().run()
