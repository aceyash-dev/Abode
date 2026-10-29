#!/usr/bin/env python3
"""Generate every launcher icon from store-assets/icon-master.png.

Run from anywhere:

    python3 tools/make_icons.py

Outputs (all committed, so the app builds without running this):

    app/src/main/res/mipmap-{mdpi..xxxhdpi}/ic_launcher.png          legacy square
    app/src/main/res/mipmap-{mdpi..xxxhdpi}/ic_launcher_round.png    legacy round
    app/src/main/res/mipmap-{mdpi..xxxhdpi}/ic_launcher_foreground.png  adaptive layer
    app/src/main/res/mipmap-{mdpi..xxxhdpi}/ic_launcher_monochrome.png  themed layer
    app/src/main/res/mipmap-anydpi-v26/ic_launcher{,_round}.xml
    app/src/main/res/mipmap-anydpi-v33/ic_launcher{,_round}.xml      adds <monochrome>
    app/src/main/res/values/colors.xml                               background colour
    store-assets/icon-512.png                                        Play listing

Requires Pillow.
"""

import os

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "app", "src", "main")
SRC = os.path.join(ROOT, "store-assets", "icon-master.png")

LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
ADAPTIVE = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

FONT_CANDIDATES = [
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
]


def main() -> None:
    src = Image.open(SRC).convert("RGB")

    # The adaptive background is a flat colour sampled from the artwork's corner,
    # so the system mask never reveals a seam where the artwork's own rounded
    # corners were cropped away.
    background = src.getpixel((6, 6))
    background_hex = "#{:02X}{:02X}{:02X}".format(*background)

    for density, size in LEGACY.items():
        out_dir = os.path.join(APP, "res", f"mipmap-{density}")
        os.makedirs(out_dir, exist_ok=True)

        square = src.resize((size, size), Image.Resampling.LANCZOS)
        square.save(os.path.join(out_dir, "ic_launcher.png"), "PNG", optimize=True)

        rounded = square.convert("RGBA")
        mask = Image.new("L", (size * 4, size * 4), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=255)
        rounded.putalpha(mask.resize((size, size), Image.Resampling.LANCZOS))
        rounded.save(os.path.join(out_dir, "ic_launcher_round.png"), "PNG", optimize=True)

    # Adaptive foreground: the whole artwork, scaled to the 108dp canvas. Only the
    # central ~66dp is ever visible, which frames the 'a' keycap with the
    # surrounding keys and keeps the drawn squircle corners outside the mask.
    for density, size in ADAPTIVE.items():
        out_dir = os.path.join(APP, "res", f"mipmap-{density}")
        os.makedirs(out_dir, exist_ok=True)
        src.resize((size, size), Image.Resampling.LANCZOS).save(
            os.path.join(out_dir, "ic_launcher_foreground.png"), "PNG", optimize=True
        )
        monochrome(size).save(
            os.path.join(out_dir, "ic_launcher_monochrome.png"), "PNG", optimize=True
        )

    write_xml(background_hex)
    write_store_icon(src)
    print(f"icons generated, adaptive background {background_hex}")


def monochrome(size: int) -> Image.Image:
    """Keycap silhouette with the glyph cut out. The system tints by alpha."""
    canvas = Image.new("L", (size, size), 0)
    pad = int(size * 0.16)
    radius = int(size * 0.20)
    ImageDraw.Draw(canvas).rounded_rectangle(
        (pad, pad, size - pad, size - pad), radius=radius, fill=255
    )

    font_path = next((p for p in FONT_CANDIDATES if os.path.exists(p)), None)
    if font_path:
        glyph = Image.new("L", (size, size), 0)
        font = ImageFont.truetype(font_path, int(size * 0.52))
        draw = ImageDraw.Draw(glyph)
        box = draw.textbbox((0, 0), "a", font=font)
        text_w, text_h = box[2] - box[0], box[3] - box[1]
        draw.text(
            ((size - text_w) / 2 - box[0], (size - text_h) / 2 - box[1]),
            "a",
            font=font,
            fill=255,
        )
        canvas = Image.composite(Image.new("L", (size, size), 0), canvas, glyph)

    rgba = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    rgba.putalpha(canvas)
    return rgba


def write_xml(background_hex: str) -> None:
    def adaptive(with_monochrome: bool) -> str:
        mono = (
            '\n    <monochrome android:drawable="@mipmap/ic_launcher_monochrome" />'
            if with_monochrome
            else ""
        )
        return (
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@color/ic_launcher_background" />\n'
            '    <foreground android:drawable="@mipmap/ic_launcher_foreground" />'
            f"{mono}\n"
            "</adaptive-icon>\n"
        )

    for folder, with_mono in (("mipmap-anydpi-v26", False), ("mipmap-anydpi-v33", True)):
        out_dir = os.path.join(APP, "res", folder)
        os.makedirs(out_dir, exist_ok=True)
        for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
            with open(os.path.join(out_dir, name), "w", encoding="utf-8") as handle:
                handle.write(adaptive(with_mono))

    colors = os.path.join(APP, "res", "values", "colors.xml")
    os.makedirs(os.path.dirname(colors), exist_ok=True)
    with open(colors, "w", encoding="utf-8") as handle:
        handle.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            "<resources>\n"
            f'    <color name="ic_launcher_background">{background_hex}</color>\n'
            "</resources>\n"
        )


def write_store_icon(src: Image.Image) -> None:
    store_dir = os.path.join(ROOT, "store-assets")
    os.makedirs(store_dir, exist_ok=True)
    src.resize((512, 512), Image.Resampling.LANCZOS).save(
        os.path.join(store_dir, "icon-512.png"), "PNG", optimize=True
    )


if __name__ == "__main__":
    main()
