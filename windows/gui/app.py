from __future__ import annotations

import threading
import tkinter.messagebox as messagebox

import customtkinter as ctk
import pystray
from PIL import Image, ImageDraw, ImageTk

from gui.pages import DashboardPage, PairingPage, SecurityPage, SettingsPage
from gui.pages.base import BasePage
from gui.theme import (
    GUTTER,
    SIDEBAR_WIDTH,
    WINDOW_MIN_SIZE,
    WINDOW_SIZE,
    active_palette,
    apply_theme,
    get_fonts,
)
from gui.widgets import Dot, StatusPill, overline_label
from settings import AppSettings
from speaktype import (
    APP_DIR,
    APP_NAME,
    DEVICE_NAME,
    SpeakTypeService,
    asset_path,
    firewall_rule_present,
    local_ipv4_address,
    request_firewall_rule,
    set_autostart,
)

VERSION = "1.3.1"

NAV_ITEMS: tuple[tuple[str, str, type[BasePage]], ...] = (
    ("dashboard", "Dashboard", DashboardPage),
    ("pairing", "Pair phone", PairingPage),
    ("settings", "Settings", SettingsPage),
    ("security", "Security", SecurityPage),
)


class VoiceToPcApp:
    root: ctk.CTk
    settings: AppSettings
    service: SpeakTypeService
    pages: dict[str, BasePage]

    def __init__(self) -> None:
        self.settings = AppSettings.load()
        apply_theme(self.settings.theme)
        self.service = SpeakTypeService(self.settings)
        self.service.on_event = self._on_service_event
        self.service.notifier = self._notify
        self.service.state.preview_callback = self._on_preview
        self.current_page: BasePage | None = None
        self._current_name = "dashboard"
        self._status_after_id: str | None = None

        self.root = ctk.CTk()
        self.root.title(APP_NAME)
        self._apply_window_icon()
        self.root.geometry(WINDOW_SIZE)
        self.root.minsize(*WINDOW_MIN_SIZE)
        self.root.protocol("WM_DELETE_WINDOW", self.hide_window)

        self.fonts = get_fonts()

        self.root.grid_columnconfigure(2, weight=1)
        self.root.grid_rowconfigure(0, weight=1)

        self._build_chrome()

        self.tray = self._create_tray_icon()
        self.tray.run_detached()

        self.refresh_status()
        self.root.after(1000, self._periodic)

        try:
            self.service.start()
        except OSError as exc:
            messagebox.showerror(
                APP_NAME,
                f"SpeakType could not listen on port {self.settings.port}: {exc}\n\n"
                "Another program may be using it. Change the port in Settings, then start SpeakType.",
            )
        try:
            set_autostart(self.settings.start_with_windows)
        except Exception:
            pass
        self.refresh_status()

        self.firewall_ok = True
        self._firewall_prompted = (APP_DIR / ".firewall-asked").exists()
        threading.Thread(target=self._check_firewall, daemon=True).start()

        if self.settings.start_minimized:
            self.root.withdraw()

    def _check_firewall(self) -> None:
        """Off the UI thread: the netsh query takes about a second."""
        try:
            present = firewall_rule_present()
        except Exception:
            present = True
        self.firewall_ok = present
        try:
            self.root.after(0, self._on_firewall_result)
        except Exception:
            pass

    def _on_firewall_result(self) -> None:
        if self.current_page is not None:
            self.current_page.tick()
        if self.firewall_ok:
            return
        if not self._firewall_prompted:
            self._firewall_prompted = True
            try:
                (APP_DIR / ".firewall-asked").write_text("asked", encoding="utf-8")
            except OSError:
                pass
            if messagebox.askyesno(
                APP_NAME,
                "Your phone cannot reach this PC until Windows Firewall allows SpeakType.\n\n"
                "Allow it now? Windows will ask you to confirm.",
            ):
                self.fix_firewall()

    def fix_firewall(self) -> None:
        """One elevation prompt, then re-check what actually landed."""
        try:
            launched = request_firewall_rule()
        except Exception:
            launched = False
        if not launched:
            self.toast("Firewall change was cancelled")
            return
        self.toast("Waiting for Windows to add the firewall rule…")
        self.root.after(3500, self._recheck_firewall)

    def _recheck_firewall(self) -> None:
        threading.Thread(target=self._finish_firewall_check, daemon=True).start()

    def _finish_firewall_check(self) -> None:
        try:
            self.firewall_ok = firewall_rule_present()
        except Exception:
            self.firewall_ok = True
        try:
            self.root.after(0, self._apply_firewall_result)
        except Exception:
            pass

    def _apply_firewall_result(self) -> None:
        for page in self.pages.values():
            page.on_settings_changed()
        if self.current_page is not None:
            self.current_page.tick()
        self.toast(
            "Phone connections allowed"
            if self.firewall_ok
            else "Windows did not add the rule — try again from Settings"
        )

    def _apply_window_icon(self) -> None:
        """Title-bar and taskbar icon; the .ico carries every size Windows asks for."""
        try:
            self.root.iconbitmap(str(asset_path("speaktype.ico")))
            return
        except Exception:
            pass
        try:
            photo = ImageTk.PhotoImage(Image.open(asset_path("speaktype.png")))
            self.root.iconphoto(True, photo)
            self._window_icon_photo = photo  # keep a reference or Tk drops it
        except Exception:
            pass

    def _build_chrome(self) -> None:
        palette = active_palette()
        self._build_sidebar(palette)
        self._build_content(palette)
        self._build_status_bar(palette)
        self.pages = {name: page_class(self.content, self) for name, _, page_class in NAV_ITEMS}
        self.show_page(self._current_name)

    def rebuild_ui(self) -> None:
        """Repaint every surface after a theme change without touching service or tray."""
        for widget in (self.sidebar, self.nav_divider, self.content_shell, self.status_bar):
            widget.destroy()
        self.pages = {}
        self.current_page = None
        self._build_chrome()
        self.refresh_status()

    def set_theme(self, theme: str) -> None:
        """Persist and apply a theme immediately, the way the phone's theme tabs do."""
        self.settings.theme = theme
        self.settings.save()
        apply_theme(theme)
        self.rebuild_ui()
        self.toast("Theme saved")

    def _build_sidebar(self, palette: dict[str, str]) -> None:
        sidebar = ctk.CTkFrame(self.root, width=SIDEBAR_WIDTH, corner_radius=0, fg_color=palette["sidebar_bg"])
        sidebar.grid(row=0, column=0, sticky="nsew")
        self.sidebar = sidebar
        sidebar.grid_rowconfigure(len(NAV_ITEMS) + 3, weight=1)
        sidebar.grid_propagate(False)

        brand = ctk.CTkFrame(sidebar, fg_color="transparent")
        brand.grid(row=0, column=0, padx=20, pady=(22, 18), sticky="ew")
        overline_label(brand, "Voice to PC", color=palette["accent"]).pack(anchor="w")
        ctk.CTkLabel(
            brand, text=APP_NAME, font=self.fonts["brand"], text_color=palette["text"], anchor="w",
        ).pack(anchor="w", pady=(4, 0))
        ctk.CTkLabel(
            brand, text=DEVICE_NAME, font=self.fonts["caption"], text_color=palette["text_muted"], anchor="w",
        ).pack(anchor="w", pady=(2, 0))

        self.nav_buttons: dict[str, ctk.CTkButton] = {}
        for index, (name, label, _) in enumerate(NAV_ITEMS, start=1):
            button = ctk.CTkButton(
                sidebar, text=label, anchor="w", height=42, corner_radius=12,
                border_spacing=14, font=self.fonts["section"],
                fg_color="transparent", hover_color=palette["subtle"], text_color=palette["text_muted"],
                command=lambda target=name: self.show_page(target),
            )
            button.grid(row=index, column=0, padx=12, pady=3, sticky="ew")
            self.nav_buttons[name] = button

        footer = ctk.CTkFrame(sidebar, fg_color="transparent")
        footer.grid(row=len(NAV_ITEMS) + 4, column=0, padx=20, pady=(0, 18), sticky="sw")
        overline_label(footer, "Status").pack(anchor="w")
        dot_row = ctk.CTkFrame(footer, fg_color="transparent")
        dot_row.pack(anchor="w", pady=(6, 0))
        self.status_dot = Dot(dot_row, palette["danger"], size=10, bg=palette["sidebar_bg"])
        self.status_dot.pack(side="left")
        self.status_dot_text = ctk.CTkLabel(
            dot_row, text="Stopped", font=self.fonts["value"], text_color=palette["text"],
        )
        self.status_dot_text.pack(side="left", padx=(8, 0))
        ctk.CTkLabel(
            footer, text=f"v{VERSION}", font=self.fonts["caption"], text_color=palette["text_muted"],
        ).pack(anchor="w", pady=(6, 0))

        self.nav_divider = ctk.CTkFrame(self.root, width=1, corner_radius=0, fg_color=palette["hairline"])
        self.nav_divider.grid(row=0, column=1, sticky="ns")

    def _build_content(self, palette: dict[str, str]) -> None:
        shell = ctk.CTkFrame(self.root, corner_radius=0, fg_color=palette["bg"])
        shell.grid(row=0, column=2, sticky="nsew")
        self.content_shell = shell
        shell.grid_rowconfigure(2, weight=1)
        shell.grid_columnconfigure(0, weight=1)

        header = ctk.CTkFrame(shell, fg_color="transparent")
        header.grid(row=0, column=0, padx=GUTTER, pady=(20, 0), sticky="ew")
        self.header_title = ctk.CTkLabel(
            header, text="", font=self.fonts["title"], text_color=palette["text"], anchor="w",
        )
        self.header_title.pack(side="left")
        self.header_pill = StatusPill(header, "Stopped", palette["danger"], fg_color=palette["card_bg"])
        self.header_pill.pack(side="right")

        header_line = ctk.CTkFrame(shell, height=1, corner_radius=0, fg_color=palette["hairline"])
        header_line.grid(row=1, column=0, padx=GUTTER, pady=(14, 0), sticky="ew")

        self.content = ctk.CTkFrame(shell, fg_color="transparent")
        self.content.grid(row=2, column=0, padx=(GUTTER, GUTTER - 8), pady=(14, 12), sticky="nsew")

    def _build_status_bar(self, palette: dict[str, str]) -> None:
        bar = ctk.CTkFrame(self.root, height=34, corner_radius=0, fg_color=palette["sidebar_bg"])
        bar.grid(row=1, column=0, columnspan=3, sticky="ew")
        bar.grid_propagate(False)
        self.status_bar = bar
        ctk.CTkFrame(bar, height=1, corner_radius=0, fg_color=palette["hairline"]).pack(fill="x")
        self.status_label = ctk.CTkLabel(
            bar, text="", font=self.fonts["caption"], text_color=palette["text_muted"], anchor="w",
        )
        self.status_label.pack(side="left", padx=GUTTER, fill="y")

    def show_page(self, name: str) -> None:
        if name not in self.pages:
            return
        if self.current_page is not None:
            self.current_page.pack_forget()
        self.current_page = self.pages[name]
        self._current_name = name
        self.current_page.pack(fill="both", expand=True)
        self.header_title.configure(text=self.current_page.title)
        palette = active_palette()
        for nav_name, button in self.nav_buttons.items():
            selected = nav_name == name
            button.configure(
                fg_color=palette["accent_soft"] if selected else "transparent",
                hover_color=palette["accent_soft_pressed"] if selected else palette["subtle"],
                text_color=palette["accent_pressed"] if selected else palette["text_muted"],
            )
        self.current_page.on_show()
        self.refresh_status()

    def refresh_status(self) -> None:
        palette = active_palette()
        service = self.service
        if service.is_running and service.paired():
            dot, pill_text = palette["success"], "Connected"
        elif service.is_running:
            dot, pill_text = palette["warning"], "Not paired"
        else:
            dot, pill_text = palette["danger"], "Stopped"
        self.status_dot.set_color(dot)
        self.status_dot_text.configure(text="Running" if service.is_running else "Stopped")
        self.header_pill.set_state(pill_text, dot)

        if service.is_running:
            state = f"Running on {local_ipv4_address()}:{self.settings.port}  ·  same Wi-Fi only"
        else:
            state = "Stopped"
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
            messagebox.showerror(APP_NAME, f"Could not restart SpeakType: {exc}")
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

    def _on_service_event(self, message: str) -> None:
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

    def _tray_image(self) -> Image.Image:
        try:
            return Image.open(asset_path("speaktype-tray.png")).convert("RGBA")
        except Exception:
            # Drawn fallback so a missing asset can never leave the tray blank.
            image = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
            draw = ImageDraw.Draw(image)
            draw.rounded_rectangle((0, 0, 63, 63), radius=14, fill=(33, 110, 80, 255))
            draw.ellipse((9, 9, 55, 55), fill=(245, 255, 250, 255))
            draw.text((19, 18), "V", fill=(33, 110, 80, 255), stroke_width=1)
            return image

    def _create_tray_icon(self) -> pystray.Icon:
        image = self._tray_image()
        menu = pystray.Menu(
            pystray.MenuItem("Show dashboard", self._tray_show),
            pystray.MenuItem("Pair phone…", self._tray_pair),
            pystray.MenuItem("Quit", self._tray_quit),
        )
        return pystray.Icon("speaktype", image, APP_NAME, menu)

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
