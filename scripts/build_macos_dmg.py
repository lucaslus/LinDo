#!/usr/bin/env python3
"""
Best-Practice macOS DMG Builder for Lindo.

Produces a standard drag-to-install macOS disk image containing:
  - Lindo.app                 at (180, 200)
  - Applications -> /Applications symlink at (480, 200)
  - Retina HiDPI multi-resolution .background/background.tiff (72dpi + 144dpi)
  - Custom volume icon (.VolumeIcon.icns)
  - Clean Finder Icon View (.DS_Store) with hidden toolbar/sidebar/status bar

Usage (via uv, zero osascript / TCC permissions required):
  uv run --with dmgbuild,pillow python3 scripts/build_macos_dmg.py \
      --app composeApp/build/compose/binaries/main/app/Lindo.app \
      --icon composeApp/src/desktopMain/resources/icon.icns \
      --mark design/brand-v3/sources/mark-transparent.png \
      --out composeApp/build/compose/binaries/main/dmg/Lindo-1.0.27-macos-x64.dmg \
      --preview-out build/dmg_installer_preview.png
"""

from __future__ import annotations

import argparse
import math
import os
import shutil
import subprocess
import tempfile
from pathlib import Path
from typing import Optional

from PIL import Image, ImageDraw, ImageFilter, ImageFont


WINDOW_W_PT = 660
WINDOW_H_PT = 420
APP_ICON_POS = (185, 196)
APPLICATIONS_POS = (475, 196)


def _load_cjk_font(size_px: int, bold: bool = False) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    candidates = [
        ("/System/Library/Fonts/PingFang.ttc", 2 if bold else 0),
        ("/System/Library/Fonts/STHeiti Medium.ttc", 0),
        ("/System/Library/Fonts/Hiragino Sans GB.ttc", 0),
        ("/System/Library/Fonts/Supplemental/Arial Unicode.ttf", 0),
        ("/System/Library/Fonts/HelveticaNeue.ttc", 1 if bold else 0),
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


def render_dmg_background(scale: int, mark_path: Optional[Path] = None) -> Image.Image:
    w = WINDOW_W_PT * scale
    h = WINDOW_H_PT * scale

    # Clean Apple-HIG frosted alabaster gradient (ensures Finder's dark filename labels stay 100% legible)
    img = Image.new("RGBA", (w, h), (246, 247, 250, 255))
    grad = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    gdraw = ImageDraw.Draw(grad)
    for y in range(h):
        t = y / max(1, h - 1)
        r = int(248 - 11 * t)
        g = int(249 - 11 * t)
        b = int(252 - 9 * t)
        gdraw.line([(0, y), (w, y)], fill=(r, g, b, 255))
    img = Image.alpha_composite(img, grad)

    # Subtle warm brand radial glow in upper center
    glow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    glow_draw = ImageDraw.Draw(glow)
    cx, cy = w // 2, int(h * 0.44)
    rx, ry = int(220 * scale), int(125 * scale)
    glow_draw.ellipse(
        [cx - rx, cy - ry, cx + rx, cy + ry],
        fill=(245, 190, 72, 24),
    )
    glow = glow.filter(ImageFilter.GaussianBlur(radius=36 * scale))
    img = Image.alpha_composite(img, glow)

    card_layer = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    cdraw = ImageDraw.Draw(card_layer)

    # Soft elevated card container behind the two icons
    pad_x = int(58 * scale)
    top_y = int(84 * scale)
    bot_y = int(314 * scale)
    radius = int(24 * scale)

    shadow = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    sdraw = ImageDraw.Draw(shadow)
    sdraw.rounded_rectangle(
        [pad_x, top_y + int(5 * scale), w - pad_x, bot_y + int(5 * scale)],
        radius=radius,
        fill=(20, 24, 36, 18),
    )
    shadow = shadow.filter(ImageFilter.GaussianBlur(radius=12 * scale))
    img = Image.alpha_composite(img, shadow)

    cdraw.rounded_rectangle(
        [pad_x, top_y, w - pad_x, bot_y],
        radius=radius,
        fill=(255, 255, 255, 212),
        outline=(220, 224, 232, 255),
        width=max(1, scale),
    )

    # Subtle circular drop-zone halos behind Lindo.app and Applications
    for ix_pt, iy_pt, is_target in [
        (APP_ICON_POS[0], APP_ICON_POS[1], False),
        (APPLICATIONS_POS[0], APPLICATIONS_POS[1], True),
    ]:
        ix = int(ix_pt * scale)
        iy = int((iy_pt - 8) * scale)
        halo_r = int(56 * scale)
        if is_target:
            # Dashed / accented target ring around Applications
            cdraw.ellipse(
                [ix - halo_r, iy - halo_r, ix + halo_r, iy + halo_r],
                fill=(244, 247, 253, 220),
                outline=(212, 172, 68, 130),
                width=max(1, int(1.5 * scale)),
            )
        else:
            cdraw.ellipse(
                [ix - halo_r, iy - halo_r, ix + halo_r, iy + halo_r],
                fill=(250, 248, 242, 210),
                outline=(232, 228, 218, 180),
                width=max(1, scale),
            )

    # Center animated-look chevron flow between (185, 188) and (475, 188)
    arrow_y = int(188 * scale)
    center_x = w // 2
    dot_offsets = [-46, -24, 0, 24, 46]
    for idx, dx_pt in enumerate(dot_offsets):
        px = center_x + int(dx_pt * scale)
        if idx < len(dot_offsets) - 1:
            r_dot = int((2.6 if idx < 2 else 3.2) * scale)
            alpha = 95 + idx * 36
            cdraw.ellipse(
                [px - r_dot, arrow_y - r_dot, px + r_dot, arrow_y + r_dot],
                fill=(217, 155, 38, alpha),
            )
        else:
            # Crisp rounded chevron arrowhead
            chev_w = int(9 * scale)
            chev_h = int(9 * scale)
            stroke_w = max(2, int(3.0 * scale))
            cdraw.line(
                [(px - chev_w, arrow_y - chev_h), (px, arrow_y), (px - chev_w, arrow_y + chev_h)],
                fill=(212, 148, 28, 245),
                width=stroke_w,
                joint="curve",
            )

    img = Image.alpha_composite(img, card_layer)
    draw = ImageDraw.Draw(img)

    # Header brand title
    title_font = _load_cjk_font(int(16 * scale), bold=True)
    sub_font = _load_cjk_font(int(13 * scale), bold=False)
    caption_font = _load_cjk_font(int(12 * scale), bold=False)

    header_text = "安装 Lindo 到 macOS"
    hb = draw.textbbox((0, 0), header_text, font=title_font)
    hw = hb[2] - hb[0]
    draw.text(
        ((w - hw) // 2, int(34 * scale)),
        header_text,
        font=title_font,
        fill=(32, 35, 42, 255),
    )

    # Bottom instruction pill
    instr_text = "将左侧 Lindo 图标拖拽到右侧 Applications 文件夹完成安装"
    ib = draw.textbbox((0, 0), instr_text, font=sub_font)
    iw = ib[2] - ib[0]
    ih = ib[3] - ib[1]
    pill_pad_x = int(18 * scale)
    pill_pad_y = int(8 * scale)
    pill_y = int(344 * scale)
    pill_box = [
        (w - iw) // 2 - pill_pad_x,
        pill_y,
        (w + iw) // 2 + pill_pad_x,
        pill_y + ih + pill_pad_y * 2,
    ]
    draw.rounded_rectangle(
        pill_box,
        radius=int(16 * scale),
        fill=(255, 255, 255, 215),
        outline=(222, 226, 234, 255),
        width=max(1, scale),
    )
    draw.text(
        ((w - iw) // 2, pill_y + pill_pad_y - ib[1]),
        instr_text,
        font=sub_font,
        fill=(68, 73, 84, 255),
    )

    footer_text = "LINUX DO 社区原生高刷桌面客户端"
    fb = draw.textbbox((0, 0), footer_text, font=caption_font)
    fw = fb[2] - fb[0]
    draw.text(
        ((w - fw) // 2, int(386 * scale)),
        footer_text,
        font=caption_font,
        fill=(142, 147, 158, 255),
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
    """Render an accurate 2x Retina preview of how the mounted DMG window looks in macOS Finder."""
    scale = 2
    # Add macOS Finder window titlebar chrome on top for clear preview context
    titlebar_h = 38 * scale
    canvas = Image.new("RGBA", (bg_2x.width, bg_2x.height + titlebar_h), (246, 247, 250, 255))
    tdraw = ImageDraw.Draw(canvas)
    tdraw.rectangle([0, 0, canvas.width, titlebar_h], fill=(242, 243, 246, 255))
    tdraw.line([(0, titlebar_h - 1), (canvas.width, titlebar_h - 1)], fill=(216, 219, 226, 255), width=2)

    # Traffic lights
    for idx, color in enumerate([(255, 95, 87), (254, 188, 46), (40, 200, 64)]):
        cx = int((20 + idx * 20) * scale)
        cy = titlebar_h // 2
        r = int(6 * scale)
        tdraw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=color)

    title_font = _load_cjk_font(int(13 * scale), bold=True)
    tb = tdraw.textbbox((0, 0), "Lindo", font=title_font)
    tdraw.text(
        ((canvas.width - (tb[2] - tb[0])) // 2, (titlebar_h - (tb[3] - tb[1])) // 2 - tb[1]),
        "Lindo",
        font=title_font,
        fill=(52, 55, 62, 255),
    )

    canvas.paste(bg_2x.convert("RGBA"), (0, titlebar_h))

    icon_px = 96 * scale
    with tempfile.TemporaryDirectory() as tmp:
        tmp_dir = Path(tmp)
        app_png = tmp_dir / "app.png"
        if _extract_png_from_icns(app_icns, app_png, icon_px):
            app_img = Image.open(app_png).convert("RGBA").resize((icon_px, icon_px), Image.Resampling.LANCZOS)
            ax = APP_ICON_POS[0] * scale - icon_px // 2
            ay = titlebar_h + (APP_ICON_POS[1] - 8) * scale - icon_px // 2
            canvas.alpha_composite(app_img, (ax, ay))

        # Extract authentic macOS Applications folder icon if present
        apps_icns_candidates = [
            Path("/System/Library/CoreServices/CoreTypes.bundle/Contents/Resources/ApplicationsFolderIcon.icns"),
            Path("/System/Library/CoreServices/CoreTypes.bundle/Contents/Resources/GenericFolderIcon.icns"),
        ]
        apps_png = tmp_dir / "apps.png"
        placed_apps = False
        for cand in apps_icns_candidates:
            if _extract_png_from_icns(cand, apps_png, icon_px):
                apps_img = Image.open(apps_png).convert("RGBA").resize((icon_px, icon_px), Image.Resampling.LANCZOS)
                bx = APPLICATIONS_POS[0] * scale - icon_px // 2
                by = titlebar_h + (APPLICATIONS_POS[1] - 8) * scale - icon_px // 2
                canvas.alpha_composite(apps_img, (bx, by))
                placed_apps = True
                break
        if not placed_apps:
            fdraw = ImageDraw.Draw(canvas)
            bx = APPLICATIONS_POS[0] * scale
            by = titlebar_h + (APPLICATIONS_POS[1] - 8) * scale
            fdraw.rounded_rectangle(
                [bx - 76, by - 56, bx + 76, by + 60],
                radius=18,
                fill=(78, 168, 246, 255),
            )

    label_font = _load_cjk_font(int(12.5 * scale), bold=False)
    ldraw = ImageDraw.Draw(canvas)
    for label, pos_pt in [("Lindo", APP_ICON_POS), ("Applications", APPLICATIONS_POS)]:
        lb = ldraw.textbbox((0, 0), label, font=label_font)
        lw = lb[2] - lb[0]
        lx = pos_pt[0] * scale - lw // 2
        ly = titlebar_h + (pos_pt[1] + 50) * scale
        ldraw.text((lx, ly), label, font=label_font, fill=(28, 30, 36, 255))

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
            "window_rect": ((220, 160), (WINDOW_W_PT, WINDOW_H_PT)),
            "icon_size": 96,
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
