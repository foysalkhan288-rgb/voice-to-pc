"""Generate every SpeakType icon from one drawing.

Run from the repository root:

    windows/.venv/Scripts/python.exe tools/make_icons.py

Outputs
    windows/assets/speaktype.ico    Windows exe, window and shortcut icon (16-256 px)
    windows/assets/speaktype.png    Tray + window bitmap (256 px)
    windows/assets/speaktype-512.png
    docs/logo.png                   README wordmark
    android-app/app/src/main/res/mipmap-*/ic_launcher.png        legacy launcher
    android-app/app/src/main/res/mipmap-*/ic_launcher_round.png  legacy round
    android-app/app/src/main/res/drawable-nodpi/ic_launcher_foreground.png
"""
from __future__ import annotations

import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent

GREEN_TOP = (0x36, 0xA6, 0x7E)
GREEN_BOTTOM = (0x1B, 0x6B, 0x4E)
WHITE = (255, 255, 255, 255)

# Drawing grid. The microphone is laid out once in this space and then cropped to
# MIC_BBOX, which keeps every output centred on the same artwork.
CANVAS = 1024
SS = 2  # supersample before the first downscale

CAPSULE = (382, 176, 642, 576)
CAPSULE_RADIUS = 130
CRADLE_CENTER = (512, 528)
CRADLE_RADIUS = 200
CRADLE_STROKE = 44
CRADLE_START = 12   # degrees, clockwise from 3 o'clock; a full 0-180 makes the tips stick out
CRADLE_END = 168
STEM = (490, 736, 534, 824)
STEM_RADIUS = 10
BASE = (416, 824, 608, 870)
BASE_RADIUS = 22

MIC_BBOX = (
    min(CAPSULE[0], CRADLE_CENTER[0] - CRADLE_RADIUS - CRADLE_STROKE // 2),  # left
    CAPSULE[1],                                                            # top
    max(CAPSULE[2], CRADLE_CENTER[0] + CRADLE_RADIUS + CRADLE_STROKE // 2),  # right
    BASE[3],                                                               # bottom
)


def _gradient(size: int) -> Image.Image:
    strip = Image.new("RGB", (1, size))
    pixels = strip.load()
    for y in range(size):
        t = y / max(1, size - 1)
        pixels[0, y] = tuple(round(a + (b - a) * t) for a, b in zip(GREEN_TOP, GREEN_BOTTOM))
    return strip.resize((size, size), Image.BILINEAR)


def _tile(size: int, round_icon: bool) -> Image.Image:
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    if round_icon:
        draw.ellipse((0, 0, size - 1, size - 1), fill=255)
    else:
        draw.rounded_rectangle((0, 0, size - 1, size - 1), radius=round(size * 0.226), fill=255)
    tile = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    tile.paste(_gradient(size).convert("RGBA"), (0, 0), mask)
    return tile


def _mic_artwork() -> Image.Image:
    """The white microphone, cropped tight to MIC_BBOX, at supersampled resolution."""
    canvas = Image.new("RGBA", (CANVAS * SS, CANVAS * SS), (0, 0, 0, 0))
    draw = ImageDraw.Draw(canvas)

    def box(x0, y0, x1, y1):
        return (x0 * SS, y0 * SS, x1 * SS, y1 * SS)

    stroke = CRADLE_STROKE * SS
    draw.rounded_rectangle(box(*CAPSULE), radius=CAPSULE_RADIUS * SS, fill=WHITE)

    cx, cy = CRADLE_CENTER
    r = CRADLE_RADIUS
    draw.arc(
        box(cx - r, cy - r, cx + r, cy + r),
        start=CRADLE_START,
        end=CRADLE_END,
        fill=WHITE,
        width=int(stroke),
    )
    # Round off the two tips so the cradle does not look sheared.
    for angle in (CRADLE_START, CRADLE_END):
        radians = math.radians(angle)
        tip_x = cx + r * math.cos(radians)
        tip_y = cy + r * math.sin(radians)
        draw.ellipse(
            box(tip_x - CRADLE_STROKE / 2, tip_y - CRADLE_STROKE / 2,
                tip_x + CRADLE_STROKE / 2, tip_y + CRADLE_STROKE / 2),
            fill=WHITE,
        )

    draw.rounded_rectangle(box(*STEM), radius=STEM_RADIUS * SS, fill=WHITE)
    draw.rounded_rectangle(box(*BASE), radius=BASE_RADIUS * SS, fill=WHITE)

    return canvas.crop(tuple(v * SS for v in MIC_BBOX))


def _place(canvas: Image.Image, artwork: Image.Image, height_fraction: float,
           center_y_fraction: float = 0.5) -> None:
    target_h = round(canvas.height * height_fraction)
    scale = target_h / artwork.height
    target_w = max(1, round(artwork.width * scale))
    resized = artwork.resize((target_w, target_h), Image.LANCZOS)
    x = (canvas.width - target_w) // 2
    y = round(canvas.height * center_y_fraction - target_h / 2)
    canvas.alpha_composite(resized, (x, y))


def _mark(size: int, *, round_icon: bool = False, tile: bool = True,
          height_fraction: float | None = None) -> Image.Image:
    # Below 32 px the fine stand disappears, so the glyph is drawn larger to survive.
    if height_fraction is None:
        height_fraction = 0.74 if size <= 32 else 0.64
    big = CANVAS * SS
    canvas = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    if tile:
        canvas.alpha_composite(_tile(big, round_icon))
    _place(canvas, _mic_artwork(), height_fraction)
    return canvas.resize((size, size), Image.LANCZOS)


def _write_ico(path: Path, images: list[Image.Image]) -> None:
    """Write a multi-resolution ICO from separately rendered PNGs.

    PIL's ICO writer downscales one bitmap for every entry, which loses the bolder
    small-size rendering. Windows accepts PNG-compressed entries since Vista.
    """
    import struct

    entries = []
    for image in images:
        sink = _Bytes()
        image.convert("RGBA").save(sink, format="PNG")
        entries.append((image.width, sink.data))

    header = struct.pack("<HHH", 0, 1, len(entries))
    directory = b""
    payload = b""
    offset = 6 + 16 * len(entries)
    for (size, data) in entries:
        dimension = 0 if size >= 256 else size
        directory += struct.pack(
            "<BBBBHHII", dimension, dimension, 0, 0, 1, 32, len(data), offset
        )
        payload += data
        offset += len(data)
    path.write_bytes(header + directory + payload)


def _adaptive_foreground(size: int = 432) -> Image.Image:
    """Mic only, transparent, inside the 66% adaptive safe zone."""
    big = CANVAS * SS
    canvas = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    _place(canvas, _mic_artwork(), 0.52)
    return canvas.resize((size, size), Image.LANCZOS)


class _Bytes:
    """Minimal write-only file object so PIL can encode PNG into memory."""

    def __init__(self) -> None:
        self.data = b""

    def write(self, chunk: bytes) -> int:
        self.data += chunk
        return len(chunk)

    def flush(self) -> None:
        return None

    def seek(self, *args) -> int:
        return 0

    def tell(self) -> int:
        return len(self.data)


def _wordmark(icon: Image.Image, text: str = "SpeakType") -> Image.Image:
    from PIL import ImageFont

    height = 256
    font_path = Path(r"C:/Windows/Fonts/seguisb.ttf")
    font = ImageFont.truetype(str(font_path), 128) if font_path.exists() else ImageFont.load_default()

    probe = ImageDraw.Draw(Image.new("RGBA", (10, 10)))
    bbox = probe.textbbox((0, 0), text, font=font)
    text_w = bbox[2] - bbox[0]

    canvas = Image.new("RGBA", (height + 40 + text_w + 24, height), (0, 0, 0, 0))
    canvas.alpha_composite(icon.resize((height, height), Image.LANCZOS), (0, 0))
    draw = ImageDraw.Draw(canvas)
    draw.text(
        (height + 36 - bbox[0], (height - (bbox[3] - bbox[1])) / 2 - bbox[1]),
        text,
        font=font,
        fill=(0x1B, 0x6B, 0x4E, 255),
    )
    return canvas


def main() -> int:
    windows_assets = ROOT / "windows" / "assets"
    android_res = ROOT / "android-app" / "app" / "src" / "main" / "res"
    docs = ROOT / "docs"
    for directory in (windows_assets, android_res, docs):
        directory.mkdir(parents=True, exist_ok=True)

    _mark(1024).save(windows_assets / "speaktype.png")
    _mark(512).save(windows_assets / "speaktype-512.png")
    # The tray asks for 16 px; a 32 px source downsamples far better than the 256 px one.
    _mark(32).save(windows_assets / "speaktype-tray.png")

    _write_ico(
        windows_assets / "speaktype.ico",
        [_mark(size) for size in (16, 24, 32, 48, 64, 128, 256)],
    )

    _wordmark(_mark(256)).save(docs / "logo.png")

    for name, px in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        folder = android_res / f"mipmap-{name}"
        folder.mkdir(parents=True, exist_ok=True)
        _mark(px).save(folder / "ic_launcher.png")
        _mark(px, round_icon=True).save(folder / "ic_launcher_round.png")

    foreground_dir = android_res / "drawable-nodpi"
    foreground_dir.mkdir(parents=True, exist_ok=True)
    _adaptive_foreground(432).save(foreground_dir / "ic_launcher_foreground.png")

    print("wrote icons:")
    for path in (
        windows_assets / "speaktype.ico",
        windows_assets / "speaktype.png",
        windows_assets / "speaktype-tray.png",
        docs / "logo.png",
        android_res / "mipmap-xxxhdpi" / "ic_launcher.png",
        foreground_dir / "ic_launcher_foreground.png",
    ):
        print("  ", path.relative_to(ROOT))
    return 0


if __name__ == "__main__":
    sys.exit(main())
