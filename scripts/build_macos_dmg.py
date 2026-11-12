#!/usr/bin/env python3
"""
Best-Practice macOS DMG Builder for Lindo.

Produces a clean, native Apple-HIG drag-to-install macOS disk image containing:
  - Lindo.app                          at (148, 148)
  - Applications -> /Applications link at (392, 148)
  - Retina HiDPI multi-resolution .background.tiff (72dpi + 144dpi)
  - Pixel-accurate Finder window frame accounting for macOS's 29pt titlebar
  - ULMO (LZMA level 9) high-ratio disk image compression (macOS 10.15+)

Usage (via uv, zero osascript / TCC permissions required):
  uv run --with dmgbuild,pillow python3 scripts/build_macos_dmg.py \
      --app composeApp/build/compose/binaries/main/app/Lindo.app \
      --icon composeApp/src/desktopMain/resources/icon.icns \
      --out composeApp/build/compose/binaries/main/dmg/Lindo-1.0.29.dmg \
      --preview-out build/dmg_installer_preview.png
"""

from __future__ import annotations

import argparse
import glob
import os
import shutil
import subprocess
import tempfile
from pathlib import Path
from typing import Optional

from PIL import Image, ImageDraw, ImageFont


# Visible content canvas dimensions (pt).
# macOS Finder's `window_rect` specifies the OUTER window frame including the 29pt titlebar,
# so `window_rect` height MUST be CONTENT_H_PT + FINDER_TITLEBAR_PT to prevent bottom clipping.
CONTENT_W_PT = 540
CONTENT_H_PT = 320
FINDER_TITLEBAR_PT = 29

ICON_SIZE_PT = 116
APP_ICON_POS = (148, 138)
APPLICATIONS_POS = (392, 138)


def _load_sf_font(size_px: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    for sf_path in ["/System/Library/Fonts/SFNS.ttf", "/System/Library/Fonts/HelveticaNeue.ttc"]:
        if os.path.exists(sf_path):
            try:
                return ImageFont.truetype(sf_path, size_px)
            except Exception:
                continue
    return _load_cjk_font(size_px, bold=False)


def _load_cjk_font(size_px: int, bold: bool = False) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    # Locate genuine PingFang SC across both macOS 13/14 (/System/Library/Fonts) and macOS 15+ (AssetsV2)
    pingfang_index = 7 if bold else 3  # 3 = PingFang SC Regular, 7 = PingFang SC Medium
    pingfang_paths = ["/System/Library/Fonts/PingFang.ttc"] + sorted(
        glob.glob("/System/Library/AssetsV2/com_apple_MobileAsset_Font*/*.asset/AssetData/PingFang.ttc")
    )
    candidates = [(p, pingfang_index) for p in pingfang_paths] + [
        ("/System/Library/Fonts/Hiragino Sans GB.ttc", 0),
        ("/System/Library/Fonts/Supplemental/Arial Unicode.ttf", 0),
    ]
    for font_path, index in candidates:
        if os.path.exists(font_path):
            try:
                return ImageFont.truetype(font_path, size_px, index=index)
            except Exception:
                try:
                    return ImageFont.truetype(font_path, size_px)
                except Exception:
                    continue
    return ImageFont.load_default()


def _draw_supersampled_arrow(canvas: Image.Image, center_x_pt: float, center_y_pt: float, scale: int) -> None:
    """Render a razor-sharp minimal rounded arrow using 4x supersampling."""
    ss = 4 * scale
    box_w_pt = 80
    box_h_pt = 40
    bw = box_w_pt * ss
    bh = box_h_pt * ss

    layer = Image.new("RGBA", (bw, bh), (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)

    cy = bh // 2
    cx = bw // 2
    # Crisp modern arrow: 38pt shaft + 9pt 45-degree rounded chevron head
    half_len = int(19 * ss)
    head_len = int(8.5 * ss)
    stroke = max(2, int(2.4 * ss))
    color = (140, 146, 158, 235)

    x_start = cx - half_len
    x_tip = cx + half_len

    # Shaft with rounded caps (simulated with endpoint circles + line)
    r_cap = stroke / 2.0
    draw.line([(x_start, cy), (x_tip - int(1.5 * ss), cy)], fill=color, width=stroke)
    draw.ellipse([x_start - r_cap, cy - r_cap, x_start + r_cap, cy + r_cap], fill=color)

    # Upper & lower chevron wings with rounded joins/caps
    wing_top = (x_tip - head_len, cy - head_len)
    wing_bot = (x_tip - head_len, cy + head_len)
    tip_pt = (x_tip, cy)

    draw.line([wing_top, tip_pt], fill=color, width=stroke)
    draw.line([wing_bot, tip_pt], fill=color, width=stroke)
    for px, py in [wing_top, wing_bot, tip_pt]:
        draw.ellipse([px - r_cap, py - r_cap, px + r_cap, py + r_cap], fill=color)

    target_w = box_w_pt * scale
    target_h = box_h_pt * scale
    smooth = layer.resize((target_w, target_h), Image.Resampling.LANCZOS)

    paste_x = int(round(center_x_pt * scale)) - target_w // 2
    paste_y = int(round(center_y_pt * scale)) - target_h // 2
    canvas.alpha_composite(smooth, (paste_x, paste_y))


def render_dmg_background(scale: int, mark_path: Optional[Path] = None) -> Image.Image:
    w = CONTENT_W_PT * scale
    h = CONTENT_H_PT * scale

    # Clean, unified Apple-native studio surface (high luminance guarantees crisp black Finder labels
    # in both macOS Light Mode and Dark Mode, with zero nested cards, rings, or fake buttons).
    img = Image.new("RGBA", (w, h), (245, 246, 248, 255))
    grad = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    gdraw = ImageDraw.Draw(grad)
    for y in range(h):
        t = y / max(1, h - 1)
        r = int(round(248 - 9 * t))
        g = int(round(249 - 9 * t))
        b = int(round(251 - 8 * t))
        gdraw.line([(0, y), (w, y)], fill=(r, g, b, 255))
    img = Image.alpha_composite(img, grad)

    # Crisp minimal vector arrow optically centered between Lindo.app and Applications
    arrow_center_x = (APP_ICON_POS[0] + APPLICATIONS_POS[0]) / 2.0
    arrow_center_y = APP_ICON_POS[1] - 1.0
    _draw_supersampled_arrow(img, arrow_center_x, arrow_center_y, scale)

    # Single understated, frameless hint caption with generous bottom margin (52pt above window bottom)
    draw = ImageDraw.Draw(img)
    hint_font = _load_cjk_font(int(12 * scale), bold=False)
    hint_text = "将 Lindo 拖拽到 Applications 文件夹完成安装"
    hb = draw.textbbox((0, 0), hint_text, font=hint_font)
    hw = hb[2] - hb[0]
    draw.text(
        ((w - hw) // 2, int(266 * scale) - hb[1]),
        hint_text,
        font=hint_font,
        fill=(140, 145, 156, 255),
    )

    return img.convert("RGB")


def _extract_png_from_icns(icns_path: Path, out_png: Path, size_px: int = 256) -> bool:
    if not icns_path.exists():
        return False
    sips = shutil.which("sips")
    if sips:
        res = subprocess.run(
            [sips, "-s", "format", "png", "-z", str(size_px), str(size_px), str(icns_path), "--out", str(out_png)],
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
        )
        if res.returncode == 0 and out_png.exists():
            return True
    return False


def render_dmg_preview(
    bg_2x: Image.Image,
    app_icns: Path,
    preview_out: Path,
) -> None:
    """Render a pixel-accurate 2x Retina preview matching macOS Dark Mode Finder chrome."""
    scale = 2
    titlebar_h = FINDER_TITLEBAR_PT * scale
    canvas = Image.new("RGBA", (bg_2x.width, bg_2x.height + titlebar_h), (245, 246, 248, 255))
    tdraw = ImageDraw.Draw(canvas)

    # Authentic macOS Dark Mode Finder titlebar (#323336) to test contrast just like user's Mac
    tdraw.rectangle([0, 0, canvas.width, titlebar_h], fill=(50, 51, 54, 255))
    tdraw.line([(0, titlebar_h - 1), (canvas.width, titlebar_h - 1)], fill=(34, 35, 38, 255), width=2)

    for idx, color in enumerate([(255, 95, 87), (254, 188, 46), (40, 200, 64)]):
        cx = int((20 + idx * 20) * scale)
        cy = titlebar_h // 2
        r = int(6 * scale)
        tdraw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color)

    title_font = _load_sf_font(int(13 * scale))
    tb = tdraw.textbbox((0, 0), "Lindo", font=title_font)
    tdraw.text(
        ((canvas.width - (tb[2] - tb[0])) // 2, (titlebar_h - (tb[3] - tb[1])) // 2 - tb[1]),
        "Lindo",
        font=title_font,
        fill=(226, 228, 233, 255),
    )

    canvas.paste(bg_2x.convert("RGBA"), (0, titlebar_h))

    icon_px = ICON_SIZE_PT * scale
    with tempfile.TemporaryDirectory() as tmp:
        tmp_dir = Path(tmp)
        app_png = tmp_dir / "app.png"
        if _extract_png_from_icns(app_icns, app_png, icon_px):
            app_img = Image.open(app_png).convert("RGBA").resize((icon_px, icon_px), Image.Resampling.LANCZOS)
            ax = APP_ICON_POS[0] * scale - icon_px // 2
            ay = titlebar_h + APP_ICON_POS[1] * scale - icon_px // 2
            canvas.alpha_composite(app_img, (ax, ay))

        apps_icns_candidates = [
            Path("/System/Library/CoreServices/CoreTypes.bundle/Contents/Resources/ApplicationsFolderIcon.icns"),
            Path("/System/Library/CoreServices/CoreTypes.bundle/Contents/Resources/GenericFolderIcon.icns"),
        ]
        apps_png = tmp_dir / "apps.png"
        for cand in apps_icns_candidates:
            if _extract_png_from_icns(cand, apps_png, icon_px):
                apps_img = Image.open(apps_png).convert("RGBA").resize((icon_px, icon_px), Image.Resampling.LANCZOS)
                bx = APPLICATIONS_POS[0] * scale - icon_px // 2
                by = titlebar_h + APPLICATIONS_POS[1] * scale - icon_px // 2
                canvas.alpha_composite(apps_img, (bx, by))
                break

    label_font = _load_sf_font(int(13 * scale))
    ldraw = ImageDraw.Draw(canvas)
    for label, pos_pt in [("Lindo", APP_ICON_POS), ("Applications", APPLICATIONS_POS)]:
        lb = ldraw.textbbox((0, 0), label, font=label_font)
        lw = lb[2] - lb[0]
        lx = pos_pt[0] * scale - lw // 2
        ly = titlebar_h + (pos_pt[1] + 70) * scale - lb[1]
        ldraw.text((lx, ly), label, font=label_font, fill=(29, 29, 31, 255))

    preview_out.parent.mkdir(parents=True, exist_ok=True)
    canvas.convert("RGB").save(preview_out, format="PNG", optimize=True)


def build_dmg(
    app_path: Path,
    icon_path: Path,
    out_dmg: Path,
    volume_name: str = "Lindo",
    mark_path: Optional[Path] = None,
    preview_out: Optional[Path] = None,
    dmg_format: str = "ULMO",
) -> None:
    import dmgbuild

    if not app_path.exists():
        raise FileNotFoundError(f"App bundle not found: {app_path}")

    out_dmg.parent.mkdir(parents=True, exist_ok=True)
    if out_dmg.exists():
        out_dmg.unlink()

    with tempfile.TemporaryDirectory(prefix="lindo_dmg_") as tmp:
        tmp_dir = Path(tmp)
        bg_1x = render_dmg_background(scale=1, mark_path=mark_path)
        bg_2x = render_dmg_background(scale=2, mark_path=mark_path)

        bg_1x_path = tmp_dir / "background.png"
        bg_2x_path = tmp_dir / "background@2x.png"
        bg_tiff_path = tmp_dir / "background.tiff"

        bg_1x.save(bg_1x_path, format="PNG", dpi=(72, 72))
        bg_2x.save(bg_2x_path, format="PNG", dpi=(144, 144))

        tiffutil = shutil.which("tiffutil")
        bg_asset_path = bg_1x_path
        if tiffutil:
            res = subprocess.run(
                [tiffutil, "-cathidpicheck", str(bg_1x_path), str(bg_2x_path), "-out", str(bg_tiff_path)],
                capture_output=True,
                text=True,
            )
            if res.returncode == 0 and bg_tiff_path.exists():
                bg_asset_path = bg_tiff_path

        if preview_out is not None:
            render_dmg_preview(bg_2x=bg_2x, app_icns=icon_path, preview_out=preview_out)

        settings = {
            "filename": str(out_dmg),
            "volume_name": volume_name,
            "format": dmg_format,
            "compression_level": 9,
            "files": [str(app_path)],
            "symlinks": {"Applications": "/Applications"},
            "badge_icon": str(icon_path) if icon_path.exists() else None,
            "background": str(bg_asset_path),
            "window_rect": ((260, 180), (CONTENT_W_PT, CONTENT_H_PT + FINDER_TITLEBAR_PT)),
            "icon_size": ICON_SIZE_PT,
            "text_size": 13,
            "icon_locations": {
                app_path.name: APP_ICON_POS,
                "Applications": APPLICATIONS_POS,
            },
            "show_status_bar": False,
            "show_tab_view": False,
            "show_toolbar": False,
            "show_pathbar": False,
            "show_sidebar": False,
            "sidebar_width": 180,
        }
        if settings["badge_icon"] is None:
            del settings["badge_icon"]

        dmgbuild.build_dmg(
            filename=str(out_dmg),
            volume_name=volume_name,
            settings=settings,
        )


def main() -> None:
    parser = argparse.ArgumentParser(description="Build best-practice macOS DMG for Lindo")
    parser.add_argument("--app", required=True, type=Path, help="Path to Lindo.app")
    parser.add_argument("--icon", required=True, type=Path, help="Path to icon.icns")
    parser.add_argument("--out", required=True, type=Path, help="Output .dmg path")
    parser.add_argument("--volume-name", default="Lindo", help="Mounted volume name")
    parser.add_argument("--mark", default=None, type=Path, help="Optional brand mark PNG")
    parser.add_argument("--preview-out", default=None, type=Path, help="Optional PNG preview output")
    parser.add_argument(
        "--format",
        default="ULMO",
        choices=["ULMO", "ULFO", "UDBZ", "UDZO"],
        help="macOS disk image compression format (default: ULMO / LZMA for macOS 10.15+)",
    )
    args = parser.parse_args()

    build_dmg(
        app_path=args.app.resolve(),
        icon_path=args.icon.resolve(),
        out_dmg=args.out.resolve(),
        volume_name=args.volume_name,
        mark_path=args.mark.resolve() if args.mark else None,
        preview_out=args.preview_out.resolve() if args.preview_out else None,
        dmg_format=args.format,
    )
    print(f"Built macOS DMG: {args.out.resolve()} ({args.out.stat().st_size / (1024 * 1024):.1f} MB)")


if __name__ == "__main__":
    main()
