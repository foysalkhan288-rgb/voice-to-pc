"""Phone-style components composed from customtkinter primitives.

Every widget here mirrors a builder in android-app ui/Design.kt: cards, pills, inset
panels, dividers, the segmented control, setting rows and the four button weights.
Colors come from gui.theme, which carries the exact Palette.kt hex twins.
"""
from __future__ import annotations

import tkinter
from typing import Callable

import customtkinter as ctk

from gui.theme import (
    BUTTON_CORNER_RADIUS,
    BUTTON_HEIGHT,
    CARD_CORNER_RADIUS,
    CARD_PADDING,
    HAIRLINE_WIDTH,
    PANEL_RADIUS,
    PILL_RADIUS,
    active_palette,
    get_fonts,
    tracked,
)


# ---- surfaces -----------------------------------------------------------------------------

def make_card(master, **kwargs) -> ctk.CTkFrame:
    """Design.card: SURFACE fill, BORDER hairline, 18px radius."""
    colors = active_palette()
    options = dict(
        corner_radius=CARD_CORNER_RADIUS,
        border_width=HAIRLINE_WIDTH,
        fg_color=colors["card_bg"],
        border_color=colors["card_border"],
    )
    options.update(kwargs)
    return ctk.CTkFrame(master, **options)


def make_panel(master, **kwargs) -> ctk.CTkFrame:
    """Design.panel: quiet inset surface, 12px radius, HAIRLINE stroke."""
    colors = active_palette()
    options = dict(
        corner_radius=PANEL_RADIUS,
        border_width=HAIRLINE_WIDTH,
        fg_color=colors["surface_alt"],
        border_color=colors["hairline"],
    )
    options.update(kwargs)
    return ctk.CTkFrame(master, **options)


def make_divider(master, pady: tuple[int, int] | int = 0) -> ctk.CTkFrame:
    colors = active_palette()
    line = ctk.CTkFrame(master, height=HAIRLINE_WIDTH, corner_radius=0, fg_color=colors["hairline"])
    line.pack(fill="x", padx=CARD_PADDING, pady=pady)
    return line


def scroll_frame(master) -> ctk.CTkScrollableFrame:
    colors = active_palette()
    return ctk.CTkScrollableFrame(
        master,
        fg_color="transparent",
        scrollbar_button_color=colors["subtle"],
        scrollbar_button_hover_color=colors["card_border"],
    )


# ---- typography helpers -------------------------------------------------------------------

def overline_label(master, text: str, color: str | None = None, **kwargs) -> ctk.CTkLabel:
    colors = active_palette()
    options = dict(
        text=tracked(text.upper()),
        font=get_fonts()["overline"],
        text_color=color or colors["text_muted"],
        anchor="w",
    )
    options.update(kwargs)
    return ctk.CTkLabel(master, **options)


def headline_label(master, text: str, **kwargs) -> ctk.CTkLabel:
    colors = active_palette()
    options = dict(text=text, font=get_fonts()["headline"], text_color=colors["text"], anchor="w")
    options.update(kwargs)
    return ctk.CTkLabel(master, **options)


def body_label(master, text: str, **kwargs) -> ctk.CTkLabel:
    colors = active_palette()
    options = dict(
        text=text, font=get_fonts()["body"], text_color=colors["text_muted"],
        anchor="w", justify="left",
    )
    options.update(kwargs)
    return ctk.CTkLabel(master, **options)


def caption_label(master, text: str, **kwargs) -> ctk.CTkLabel:
    colors = active_palette()
    options = dict(
        text=text, font=get_fonts()["caption"], text_color=colors["text_muted"],
        anchor="w", justify="left",
    )
    options.update(kwargs)
    return ctk.CTkLabel(master, **options)


def value_label(master, text: str, **kwargs) -> ctk.CTkLabel:
    colors = active_palette()
    options = dict(text=text, font=get_fonts()["value"], text_color=colors["text"], anchor="w")
    options.update(kwargs)
    return ctk.CTkLabel(master, **options)


def card_overline(card, text: str, color: str | None = None) -> ctk.CTkLabel:
    label = overline_label(card, text, color)
    label.pack(fill="x", padx=CARD_PADDING, pady=(CARD_PADDING, 0))
    return label


def card_headline(card, text: str) -> ctk.CTkLabel:
    label = headline_label(card, text)
    label.pack(fill="x", padx=CARD_PADDING, pady=(6, 0))
    return label


def card_body(card, text: str, wraplength: int = 560) -> ctk.CTkLabel:
    label = body_label(card, text, wraplength=wraplength)
    label.pack(fill="x", padx=CARD_PADDING, pady=(6, 0))
    return label


# ---- status dot and pill ------------------------------------------------------------------

class Dot(ctk.CTkCanvas):
    """Design.dot: a crisp coloured circle (canvas instead of the phone's text glyph)."""

    def __init__(self, master, color: str, size: int = 10, bg: str | None = None) -> None:
        colors = active_palette()
        super().__init__(
            master, width=size, height=size,
            bg=bg or colors["card_bg"], highlightthickness=0, bd=0,
        )
        self._size = size
        self._oval = self.create_oval(1, 1, size - 1, size - 1, fill=color, outline="")

    def set_color(self, color: str) -> None:
        self.itemconfigure(self._oval, fill=color)


class StatusPill(ctk.CTkFrame):
    """Design.pill: SUBTLE stadium holding a dot plus a short value line."""

    def __init__(self, master, text: str = "", dot_color: str | None = None, **kwargs) -> None:
        colors = active_palette()
        options = dict(
            corner_radius=PILL_RADIUS,
            border_width=HAIRLINE_WIDTH,
            fg_color=colors["subtle"],
            border_color=colors["hairline"],
        )
        options.update(kwargs)
        super().__init__(master, **options)
        self.dot = Dot(self, dot_color or colors["disabled_text"], size=10, bg=colors["subtle"])
        self.dot.pack(side="left", padx=(12, 8), pady=8)
        self.label = value_label(self, text)
        self.label.pack(side="left", padx=(0, 14), pady=8)

    def set_state(self, text: str, dot_color: str) -> None:
        self.label.configure(text=text)
        self.dot.set_color(dot_color)


# ---- segmented control ----------------------------------------------------------------------

class SegmentedControl(ctk.CTkFrame):
    """ui/SegmentedControl.kt: SURFACE stadium, 4px inner pad, 40px segments,
    the active one on an ACCENT_SOFT wash with ACCENT_PRESSED text."""

    def __init__(
        self,
        master,
        labels: list[str],
        selected: int = 0,
        on_select: Callable[[int], None] | None = None,
        height: int = 48,
    ) -> None:
        colors = active_palette()
        super().__init__(
            master,
            height=height,
            corner_radius=PILL_RADIUS,
            border_width=HAIRLINE_WIDTH,
            fg_color=colors["card_bg"],
            border_color=colors["card_border"],
        )
        self.grid_propagate(False)
        self._colors = colors
        self._on_select = on_select
        self._buttons: list[ctk.CTkButton] = []
        self.selected_index = 0
        segment_height = height - 8
        for index, label in enumerate(labels):
            self.grid_columnconfigure(index, weight=1, uniform="segment")
            button = ctk.CTkButton(
                self,
                text=label,
                height=segment_height,
                corner_radius=segment_height // 2,
                border_width=0,
                font=get_fonts()["value"],
                fg_color="transparent",
                hover_color=colors["subtle"],
                text_color=colors["text_muted"],
                command=lambda i=index: self._activate(i),
            )
            pad_left = 4 if index == 0 else 0
            pad_right = 4 if index == len(labels) - 1 else 0
            button.grid(row=0, column=index, sticky="nsew", padx=(pad_left, pad_right), pady=4)
            self._buttons.append(button)
        self.grid_rowconfigure(0, weight=1)
        self.select(selected)

    def select(self, index: int) -> None:
        """Move the selection without firing on_select (phone select() semantics)."""
        colors = self._colors
        self.selected_index = index if 0 <= index < len(self._buttons) else 0
        for position, button in enumerate(self._buttons):
            if position == self.selected_index:
                button.configure(
                    fg_color=colors["accent_soft"],
                    hover_color=colors["accent_soft_pressed"],
                    text_color=colors["accent_pressed"],
                )
            else:
                button.configure(
                    fg_color="transparent",
                    hover_color=colors["subtle"],
                    text_color=colors["text_muted"],
                )

    def _activate(self, index: int) -> None:
        self.select(index)
        if self._on_select is not None:
            self._on_select(index)


# ---- buttons --------------------------------------------------------------------------------

_BUTTON_STYLES = {
    # style: (fill, hover, text, border)
    "primary": ("accent", "accent_hover", "on_accent", None),
    "secondary": ("subtle", "subtle_pressed", "accent", None),
    "quiet": ("card_bg", "quiet_pressed", "text_muted", "card_border"),
    "danger": ("danger", "danger_pressed", "on_danger", None),
}


def styled_button(
    master,
    text: str,
    style: str = "primary",
    command: Callable[[], None] | None = None,
    width: int | None = None,
    height: int = BUTTON_HEIGHT,
    **kwargs,
) -> ctk.CTkButton:
    """Design.button with the phone's PRIMARY / SECONDARY / QUIET / DANGER weights."""
    colors = active_palette()
    fill, hover, text_color, border = _BUTTON_STYLES[style]
    options = dict(
        text=text,
        command=command,
        height=height,
        corner_radius=BUTTON_CORNER_RADIUS,
        border_width=HAIRLINE_WIDTH if border else 0,
        border_color=colors[border] if border else colors["card_border"],
        fg_color=colors[fill],
        hover_color=colors[hover],
        text_color=colors[text_color],
        text_color_disabled=colors["disabled_text"],
        font=get_fonts()["button"],
        border_spacing=16,
    )
    if width is not None:
        options["width"] = width
    options.update(kwargs)
    button = ctk.CTkButton(master, **options)
    button._style_name = style  # type: ignore[attr-defined]
    return button


def apply_button_style(button: ctk.CTkButton, style: str) -> None:
    """Re-weight an existing button (e.g. Start -> Stop) without recreating it."""
    colors = active_palette()
    fill, hover, text_color, border = _BUTTON_STYLES[style]
    button._style_name = style  # type: ignore[attr-defined]
    button.configure(
        fg_color=colors[fill],
        hover_color=colors[hover],
        text_color=colors[text_color],
        border_width=HAIRLINE_WIDTH if border else 0,
        border_color=colors[border] if border else colors["card_border"],
    )


def set_button_disabled(button: ctk.CTkButton, disabled: bool) -> None:
    """Disabled controls take the phone's DISABLED_FILL / DISABLED_TEXT treatment."""
    colors = active_palette()
    style = getattr(button, "_style_name", "primary")
    fill, hover, text_color, border = _BUTTON_STYLES[style]
    if disabled:
        button.configure(
            state="disabled",
            fg_color=colors["disabled_fill"],
            hover_color=colors["disabled_fill"],
            text_color=colors["disabled_text"],
            border_color=colors["card_border"] if border else colors["disabled_fill"],
        )
    else:
        button.configure(
            state="normal",
            fg_color=colors[fill],
            hover_color=colors[hover],
            text_color=colors[text_color],
            border_color=colors[border] if border else colors["card_border"],
        )


# ---- setting rows -----------------------------------------------------------------------------

def setting_row(master, label: str, detail: str, chevron: bool = False):
    """Design.settingRow: overline over a muted detail line, chevron at right.

    Returns (row, detail_label). Pack a control into the row with side="right"
    to replace the chevron with a switch/entry/button.
    """
    colors = active_palette()
    row = ctk.CTkFrame(master, fg_color="transparent")
    column = ctk.CTkFrame(row, fg_color="transparent")
    column.pack(side="left", fill="x", expand=True, pady=14)
    overline_label(column, label).pack(fill="x", anchor="w")
    detail_view = value_label(column, detail, text_color=colors["text_muted"])
    detail_view.pack(fill="x", anchor="w", pady=(3, 0))
    if chevron:
        ctk.CTkLabel(
            row, text="\u203a", font=get_fonts()["chevron"], text_color=colors["text_muted"],
        ).pack(side="right", padx=(12, 0))
    return row, detail_view


# ---- form controls ------------------------------------------------------------------------------

def styled_switch(master, variable=None, text: str = "", command=None) -> ctk.CTkSwitch:
    colors = active_palette()
    return ctk.CTkSwitch(
        master,
        text=text,
        variable=variable,
        command=command,
        font=get_fonts()["body"],
        text_color=colors["text"],
        fg_color=colors["subtle_pressed"],
        progress_color=colors["accent"],
        button_color=colors["text"],
        button_hover_color=colors["text_muted"],
        switch_width=44,
        switch_height=24,
        corner_radius=12,
        border_width=0,
    )


def styled_slider(master, variable=None, command=None, **kwargs) -> ctk.CTkSlider:
    colors = active_palette()
    options = dict(
        variable=variable,
        command=command,
        height=20,
        corner_radius=10,
        button_corner_radius=10,
        button_length=20,
        border_width=0,
        fg_color=colors["subtle"],
        progress_color=colors["accent"],
        button_color=colors["accent"],
        button_hover_color=colors["accent_hover"],
    )
    options.update(kwargs)
    return ctk.CTkSlider(master, **options)


def styled_entry(master, textvariable=None, width: int = 120, **kwargs) -> ctk.CTkEntry:
    colors = active_palette()
    options = dict(
        textvariable=textvariable,
        width=width,
        height=40,
        corner_radius=PANEL_RADIUS,
        border_width=HAIRLINE_WIDTH,
        fg_color=colors["input_bg"],
        border_color=colors["card_border"],
        text_color=colors["text"],
        placeholder_text_color=colors["text_muted"],
        font=get_fonts()["value"],
    )
    options.update(kwargs)
    return ctk.CTkEntry(master, **options)


def styled_textbox(master, height: int = 180, **kwargs) -> ctk.CTkTextbox:
    colors = active_palette()
    options = dict(
        height=height,
        corner_radius=PANEL_RADIUS,
        border_width=HAIRLINE_WIDTH,
        fg_color=colors["surface_alt"],
        border_color=colors["hairline"],
        text_color=colors["text"],
        scrollbar_button_color=colors["subtle_pressed"],
        scrollbar_button_hover_color=colors["card_border"],
        font=get_fonts()["mono_small"],
        wrap="word",
        border_spacing=10,
    )
    options.update(kwargs)
    return ctk.CTkTextbox(master, **options)