#!/usr/bin/env python3
"""
Canonical Cross-Platform Icon Synchronizer for Lindo (Mobile KMP + Desktop Tauri)

Extracts the 1:1 vector icon definitions from Mobile's `LinDoIcons.kt` (both 24x24
Lucide-grade round-stroke icons and official LINUX DO FontAwesome 6 solid icons),
merges shared supplementary 24x24 round-stroke icons, writes the single canonical
manifest to `shared/icons/lindo-icons.json`, and generates `desktop-tauri/src/components/LinDoIcons.tsx`.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KT_PATH = ROOT / "composeApp/src/commonMain/kotlin/do_/linux/lindo/ui/components/LinDoIcons.kt"
JSON_PATH = ROOT / "shared/icons/lindo-icons.json"
TSX_PATH = ROOT / "desktop-tauri/src/components/LinDoIcons.tsx"


def fmt_num(val: float) -> str:
    if abs(val - round(val)) < 1e-6:
        return str(int(round(val)))
    s = f"{val:.4f}".rstrip("0").rstrip(".")
    return s


def parse_commands_to_svg_d(body: str) -> str:
    parts: list[str] = []
    cmd_re = re.compile(
        r"(moveTo|lineTo|horizontalLineTo|verticalLineTo|curveTo|close)\s*\(([^)]*)\)"
    )
    for m in cmd_re.finditer(body):
        cmd = m.group(1)
        raw_args = m.group(2).strip()
        nums = [
            fmt_num(float(x.strip().rstrip("fF")))
            for x in raw_args.split(",")
            if x.strip()
        ]
        if cmd == "moveTo":
            parts.append(f"M{nums[0]} {nums[1]}")
        elif cmd == "lineTo":
            parts.append(f"L{nums[0]} {nums[1]}")
        elif cmd == "horizontalLineTo":
            parts.append(f"H{nums[0]}")
        elif cmd == "verticalLineTo":
            parts.append(f"V{nums[0]}")
        elif cmd == "curveTo":
            parts.append(
                f"C{nums[0]} {nums[1]} {nums[2]} {nums[3]} {nums[4]} {nums[5]}"
            )
        elif cmd == "close":
            parts.append("Z")
    return " ".join(parts)


def extract_balanced_block(text: str, open_brace_idx: int) -> tuple[str, int]:
    depth = 0
    i = open_brace_idx
    while i < len(text):
        ch = text[i]
        if ch == "{":
            depth += 1
        elif ch == "}":
            depth -= 1
            if depth == 0:
                return text[open_brace_idx + 1 : i], i + 1
        i += 1
    raise ValueError(f"Unbalanced brace starting at {open_brace_idx}")


def extract_balanced_parens(text: str, open_paren_idx: int) -> tuple[str, int]:
    depth = 0
    i = open_paren_idx
    while i < len(text):
        ch = text[i]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                return text[open_paren_idx + 1 : i], i + 1
        i += 1
    raise ValueError(f"Unbalanced paren starting at {open_paren_idx}")


def parse_kotlin_icons(kt_source: str) -> dict[str, dict]:
    icons: dict[str, dict] = {}

    # 1. Parse lucideIcon("Name") { ... } definitions
    val_re = re.compile(
        r"val\s+([A-Za-z0-9_]+)\s*:\s*ImageVector\s+by\s+lazy\s*\{"
    )
    pos = 0
    while True:
        m = val_re.search(kt_source, pos)
        if not m:
            break
        prop_name = m.group(1)
        brace_open = kt_source.find("{", m.end() - 1)
        lazy_body, next_pos = extract_balanced_block(kt_source, brace_open)
        pos = next_pos

        fa_m = re.search(
            r'faSolidIcon\(\s*"([^"]+)"\s*,\s*([0-9.]+)[fF]\s*,\s*([0-9.]+)[fF]\s*,\s*"([^"]+)"\s*\)',
            lazy_body,
        )
        if fa_m:
            vb_w = float(fa_m.group(2))
            vb_h = float(fa_m.group(3))
            side = max(vb_w, vb_h, 512.0)
            dx = (side - vb_w) / 2.0
            dy = (side - vb_h) / 2.0
            path_entry: dict = {
                "d": fa_m.group(4),
                "fill": "currentColor",
                "stroke": "none",
            }
            if dx != 0 or dy != 0:
                path_entry["transform"] = f"translate({fmt_num(dx)} {fmt_num(dy)})"
            icons[prop_name] = {
                "viewBox": f"0 0 {fmt_num(side)} {fmt_num(side)}",
                "category": "fontawesome-official",
                "paths": [path_entry],
            }
            continue

        lucide_m = re.search(r'lucideIcon\(\s*"([^"]+)"', lazy_body)
        if not lucide_m:
            continue
        lucide_brace = lazy_body.find("{", lucide_m.end())
        icon_body, _ = extract_balanced_block(lazy_body, lucide_brace)

        paths: list[dict] = []
        sub_pos = 0
        sub_kw_re = re.compile(r"\b(strokePath|path)\b")
        while True:
            sm = sub_kw_re.search(icon_body, sub_pos)
            if not sm:
                break
            kind = sm.group(1)
            cursor = sm.end()
            while cursor < len(icon_body) and icon_body[cursor].isspace():
                cursor += 1
            args_str = ""
            if cursor < len(icon_body) and icon_body[cursor] == "(":
                args_str, cursor = extract_balanced_parens(icon_body, cursor)
                args_str = args_str.strip()
            while cursor < len(icon_body) and icon_body[cursor].isspace():
                cursor += 1
            if cursor >= len(icon_body) or icon_body[cursor] != "{":
                sub_pos = cursor
                continue
            p_body, sub_pos = extract_balanced_block(icon_body, cursor)
            d_str = parse_commands_to_svg_d(p_body)
            if not d_str:
                continue

            if kind == "strokePath":
                sw = 1.8
                if args_str:
                    sw = float(args_str.rstrip("fF"))
                paths.append(
                    {
                        "d": d_str,
                        "fill": "none",
                        "stroke": "currentColor",
                        "strokeWidth": round(sw, 2),
                        "strokeLinecap": "round",
                        "strokeLinejoin": "round",
                    }
                )
            else:
                has_fill = "fill = SolidColor" in args_str
                alpha_m = re.search(r"alpha\s*=\s*([0-9.]+)[fF]", args_str)
                has_stroke = "stroke = SolidColor" in args_str
                sw_m = re.search(r"strokeLineWidth\s*=\s*([0-9.]+)[fF]", args_str)
                even_odd = "PathFillType.EvenOdd" in args_str

                entry: dict = {
                    "d": d_str,
                    "fill": "currentColor" if has_fill else "none",
                    "stroke": "currentColor" if has_stroke else "none",
                }
                if alpha_m:
                    entry["fillOpacity"] = round(float(alpha_m.group(1)), 3)
                if even_odd:
                    entry["fillRule"] = "evenodd"
                    entry["clipRule"] = "evenodd"
                if has_stroke:
                    entry["strokeWidth"] = (
                        round(float(sw_m.group(1)), 2) if sw_m else 1.8
                    )
                    entry["strokeLinecap"] = "round"
                    entry["strokeLinejoin"] = "round"
                paths.append(entry)

        icons[prop_name] = {
            "viewBox": "0 0 24 24",
            "category": "lindo-line",
            "paths": paths,
        }

    return icons


# Supplementary icons following the exact same 24x24 · 1.8dp round-cap/join design spec
SUPPLEMENTARY_ICONS: dict[str, dict] = {
    "ChevronLeft": {
        "viewBox": "0 0 24 24",
        "category": "lindo-line",
        "paths": [
            {
                "d": "M15 18 L9 12 L15 6",
                "fill": "none",
                "stroke": "currentColor",
                "strokeWidth": 1.85,
                "strokeLinecap": "round",
                "strokeLinejoin": "round",
            }
        ],
    },
    "Pin": {
        "viewBox": "0 0 24 24",
        "category": "lindo-line",
        "paths": [
            {
                "d": "M12 17 V22 M9 10.76 A2 2 0 0 1 7.89 12.55 L6.1 13.45 C5.42 13.79 5 14.48 5 15.24 V17 H19 V15.24 C19 14.48 18.58 13.79 17.9 13.45 L16.11 12.55 A2 2 0 0 1 15 10.76 V6 H16 A1 1 0 0 0 17 5 V4 A1 1 0 0 0 16 3 H8 A1 1 0 0 0 7 4 V5 A1 1 0 0 0 8 6 H9 Z",
                "fill": "none",
                "stroke": "currentColor",
                "strokeWidth": 1.8,
                "strokeLinecap": "round",
                "strokeLinejoin": "round",
            }
        ],
    },
    "PinFilled": {
        "viewBox": "0 0 24 24",
        "category": "lindo-line",
        "paths": [
            {
                "d": "M9 10.76 A2 2 0 0 1 7.89 12.55 L6.1 13.45 C5.42 13.79 5 14.48 5 15.24 V17 H19 V15.24 C19 14.48 18.58 13.79 17.9 13.45 L16.11 12.55 A2 2 0 0 1 15 10.76 V6 H16 A1 1 0 0 0 17 5 V4 A1 1 0 0 0 16 3 H8 A1 1 0 0 0 7 4 V5 A1 1 0 0 0 8 6 H9 Z",
                "fill": "currentColor",
                "stroke": "currentColor",
                "strokeWidth": 1.6,
                "strokeLinecap": "round",
                "strokeLinejoin": "round",
            },
            {
                "d": "M12 17 V22",
                "fill": "none",
                "stroke": "currentColor",
                "strokeWidth": 1.85,
                "strokeLinecap": "round",
                "strokeLinejoin": "round",
            },
        ],
    },
    "CheckCircle": {
        "viewBox": "0 0 24 24",
        "category": "lindo-line",
        "paths": [
            {
                "d": "M21 12 C21 16.97 16.97 21 12 21 C7.03 21 3 16.97 3 12 C3 7.03 7.03 3 12 3 C16.97 3 21 7.03 21 12 Z M8.5 12.3 L10.9 14.7 L15.8 9.6",
                "fill": "none",
                "stroke": "currentColor",
                "strokeWidth": 1.85,
                "strokeLinecap": "round",
                "strokeLinejoin": "round",
            }
        ],
    },
    "CheckCircleFilled": {
        "viewBox": "0 0 24 24",
        "category": "lindo-line",
        "paths": [
            {
                "d": "M12 2 C6.48 2 2 6.48 2 12 C2 17.52 6.48 22 12 22 C17.52 22 22 17.52 22 12 C22 6.48 17.52 2 12 2 Z M10.6 16.2 L6.9 12.5 A0.9 0.9 0 0 1 8.17 11.23 L10.6 13.66 L15.83 8.43 A0.9 0.9 0 0 1 17.1 9.7 L10.6 16.2 Z",
                "fill": "currentColor",
                "fillRule": "evenodd",
                "clipRule": "evenodd",
                "stroke": "none",
            }
        ],
    },
    "Archive": {
        "viewBox": "0 0 24 24",
        "category": "lindo-line",
        "paths": [
            {
                "d": "M3 4 H21 V8 H3 Z M5 8 V19 C5 20.1 5.9 21 7 21 H17 C18.1 21 19 20.1 19 19 V8 M10 12 H14",
                "fill": "none",
                "stroke": "currentColor",
                "strokeWidth": 1.8,
                "strokeLinecap": "round",
                "strokeLinejoin": "round",
            }
        ],
    },
    "Mail": {
        "viewBox": "0 0 24 24",
        "category": "lindo-line",
        "paths": [
            {
                "d": "M4 5 H20 C21.1 5 22 5.9 22 7 V17 C22 18.1 21.1 19 20 19 H4 C2.9 19 2 18.1 2 17 V7 C2 5.9 2.9 5 4 5 Z M22 7 L12 13 L2 7",
                "fill": "none",
                "stroke": "currentColor",
                "strokeWidth": 1.8,
                "strokeLinecap": "round",
                "strokeLinejoin": "round",
            }
        ],
    },
    "UserPlus": {
        "viewBox": "0 0 24 24",
        "category": "lindo-line",
        "paths": [
            {
                "d": "M16 21 V19 C16 16.79 14.21 15 12 15 H6 C3.79 15 2 16.79 2 19 V21 M9 11 C11.21 11 13 9.21 13 7 C13 4.79 11.21 3 9 3 C6.79 3 5 4.79 5 7 C5 9.21 6.79 11 9 11 Z M19 8 V14 M16 11 H22",
                "fill": "none",
                "stroke": "currentColor",
                "strokeWidth": 1.85,
                "strokeLinecap": "round",
                "strokeLinejoin": "round",
            }
        ],
    },
    "Copy": {
        "viewBox": "0 0 24 24",
        "category": "lindo-line",
        "paths": [
            {
                "d": "M9 9 H19 C20.1 9 21 9.9 21 11 V19 C21 20.1 20.1 21 19 21 H9 C7.9 21 7 20.1 7 19 V11 C7 9.9 7.9 9 9 9 Z M5 15 H4 C2.9 15 2 14.1 2 13 V5 C2 3.9 2.9 3 4 3 H13 C14.1 3 15 3.9 15 5 V6",
                "fill": "none",
                "stroke": "currentColor",
                "strokeWidth": 1.8,
                "strokeLinecap": "round",
                "strokeLinejoin": "round",
            }
        ],
    },
    "TypeAa": {
        "viewBox": "0 0 24 24",
        "category": "lindo-line",
        "paths": [
            {
                "d": "M3.5 19 L8.5 6 L13.5 19 M5.3 14.5 H11.7 M16.2 11.5 C16.9 10.7 17.9 10.3 19 10.3 C20.6 10.3 21.5 11.3 21.5 12.9 V19 M21.5 14.5 H18.6 C16.9 14.5 15.8 15.3 15.8 16.8 C15.8 18.1 16.8 19 18.3 19 C19.7 19 20.9 18.2 21.5 17.1",
                "fill": "none",
                "stroke": "currentColor",
                "strokeWidth": 1.8,
                "strokeLinecap": "round",
                "strokeLinejoin": "round",
            }
        ],
    },
    "DesktopMonitor": {
        "viewBox": "0 0 24 24",
        "category": "desktop-platform",
        "paths": [
            {
                "d": "M4 4 H20 C21.1 4 22 4.9 22 6 V15 C22 16.1 21.1 17 20 17 H4 C2.9 17 2 16.1 2 15 V6 C2 4.9 2.9 4 4 4 Z M8 21 H16 M12 17 V21",
                "fill": "none",
                "stroke": "currentColor",
                "strokeWidth": 1.8,
                "strokeLinecap": "round",
                "strokeLinejoin": "round",
            }
        ],
    },
    "ZoomIn": {
        "viewBox": "0 0 24 24",
        "category": "desktop-platform",
        "paths": [
            {
                "d": "M11 19 C15.42 19 19 15.42 19 11 C19 6.58 15.42 3 11 3 C6.58 3 3 6.58 3 11 C3 15.42 6.58 19 11 19 Z M21 21 L16.65 16.65 M11 8 V14 M8 11 H14",
                "fill": "none",
                "stroke": "currentColor",
                "strokeWidth": 1.8,
                "strokeLinecap": "round",
                "strokeLinejoin": "round",
            }
        ],
    },
    "ZoomOut": {
        "viewBox": "0 0 24 24",
        "category": "desktop-platform",
        "paths": [
            {
                "d": "M11 19 C15.42 19 19 15.42 19 11 C19 6.58 15.42 3 11 3 C6.58 3 3 6.58 3 11 C3 15.42 6.58 19 11 19 Z M21 21 L16.65 16.65 M8 11 H14",
                "fill": "none",
                "stroke": "currentColor",
                "strokeWidth": 1.8,
                "strokeLinecap": "round",
                "strokeLinejoin": "round",
            }
        ],
    },
    "Maximize": {
        "viewBox": "0 0 24 24",
        "category": "desktop-platform",
        "paths": [
            {
                "d": "M15 3 H21 V9 M9 21 H3 V15 M21 3 L14 10 M3 21 L10 14",
                "fill": "none",
                "stroke": "currentColor",
                "strokeWidth": 1.85,
                "strokeLinecap": "round",
                "strokeLinejoin": "round",
            }
        ],
    },
    "Minimize": {
        "viewBox": "0 0 24 24",
        "category": "desktop-platform",
        "paths": [
            {
                "d": "M4 14 H10 V20 M20 10 H14 V4 M14 10 L21 3 M3 21 L10 14",
                "fill": "none",
                "stroke": "currentColor",
                "strokeWidth": 1.85,
                "strokeLinecap": "round",
                "strokeLinejoin": "round",
            }
        ],
    },
    "ColumnsSplit": {
        "viewBox": "0 0 24 24",
        "category": "desktop-platform",
        "paths": [
            {
                "d": "M4 4 H20 C21.1 4 22 4.9 22 6 V18 C22 19.1 21.1 20 20 20 H4 C2.9 20 2 19.1 2 18 V6 C2 4.9 2.9 4 4 4 Z M12 4 V20",
                "fill": "none",
                "stroke": "currentColor",
                "strokeWidth": 1.8,
                "strokeLinecap": "round",
                "strokeLinejoin": "round",
            }
        ],
    },
}


def generate_tsx(manifest: dict[str, dict]) -> str:
    lines: list[str] = [
        "/* AUTO-GENERATED FROM shared/icons/lindo-icons.json BY scripts/sync-shared-icons.py */",
        "/* Single source of truth shared 1:1 with composeApp/.../ui/components/LinDoIcons.kt */",
        'import React from "react";',
        "",
        "export interface LinDoIconProps extends Omit<React.SVGProps<SVGSVGElement>, 'children'> {",
        "  size?: number | string;",
        '  title?: string;',
        "}",
        "",
        "interface IconPathSpec {",
        "  d: string;",
        "  fill: string;",
        "  fillOpacity?: number;",
        '  fillRule?: "nonzero" | "evenodd";',
        '  clipRule?: "nonzero" | "evenodd";',
        "  stroke: string;",
        "  strokeWidth?: number;",
        '  strokeLinecap?: "round" | "butt" | "square";',
        '  strokeLinejoin?: "round" | "miter" | "bevel";',
        "  transform?: string;",
        "}",
        "",
        "interface IconDef {",
        "  viewBox: string;",
        "  category: string;",
        "  paths: readonly IconPathSpec[];",
        "}",
        "",
        f"export const LINDO_ICON_DEFS = {json.dumps(manifest, ensure_ascii=False, indent=2)} as const satisfies Record<string, IconDef>;",
        "",
        "export type LinDoIconName = keyof typeof LINDO_ICON_DEFS;",
        "",
        "function createLinDoIcon(name: LinDoIconName): React.FC<LinDoIconProps> {",
        "  const def: IconDef = LINDO_ICON_DEFS[name];",
        "  const Component: React.FC<LinDoIconProps> = React.memo(({ size = 16, title, className = '', style, ...rest }) => (",
        "    <svg",
        "      width={size}",
        "      height={size}",
        "      viewBox={def.viewBox}",
        '      fill="none"',
        '      xmlns="http://www.w3.org/2000/svg"',
        '      aria-hidden={title ? undefined : "true"}',
        "      className={`inline-block shrink-0 select-none ${className}`}",
        "      style={style}",
        "      {...rest}",
        "    >",
        "      {title ? <title>{title}</title> : null}",
        "      {def.paths.map((p, idx) => (",
        "        <path",
        "          key={idx}",
        "          d={p.d}",
        "          fill={p.fill}",
        "          fillOpacity={p.fillOpacity}",
        "          fillRule={p.fillRule}",
        "          clipRule={p.clipRule}",
        "          stroke={p.stroke}",
        "          strokeWidth={p.strokeWidth}",
        "          strokeLinecap={p.strokeLinecap}",
        "          strokeLinejoin={p.strokeLinejoin}",
        "          transform={p.transform}",
        "        />",
        "      ))}",
        "    </svg>",
        "  ));",
        "  Component.displayName = `LinDoIcon(${name})`;",
        "  return Component;",
        "}",
        "",
        "export const LinDoIcons = {",
    ]

    for icon_name in manifest.keys():
        lines.append(f'  {icon_name}: createLinDoIcon("{icon_name}"),')

    lines.extend(
        [
            "} as const;",
            "",
            "/* =========================================================================",
            " * Shared Official LINUX DO Category & Tag Visual Resolvers",
            " * 1:1 aligned with Mobile `LinDoApp.kt` (`resolveOfficialCategoryVisual` / `resolveOfficialTagVisual`)",
            " * ========================================================================= */",
            "",
            "export interface OfficialCategoryVisual {",
            "  Icon: React.FC<LinDoIconProps>;",
            "  color: string;",
            "  restricted: boolean;",
            "  displayLabel: string;",
            "}",
            "",
            "export function resolveOfficialCategoryVisual(",
            "  categoryName?: string | null,",
            "  fallbackColor?: string | null,",
            "  iconNameOverride?: string | null,",
            "  restrictedOverride?: boolean | null",
            "): OfficialCategoryVisual {",
            '  const raw = (categoryName || "").trim();',
            '  const displayLabel = raw.replace(/,\\s*/g, " · ").trim();',
            '  const base = raw.split(",")[0].trim();',
            '  const iconKey = (iconNameOverride || "").toLowerCase();',
            "  const restricted =",
            "    restrictedOverride ??",
            '    (/lv\\s*[123]/i.test(raw) || base.includes("深海") || base.includes("幽域") || base.includes("虫洞"));',
            "",
            "  let Icon: React.FC<LinDoIconProps> = LinDoIcons.FaCode;",
            '  let color = fallbackColor || "#32C3C3";',
            "",
            '  if (iconKey === "code" || base.includes("开发")) {',
            "    Icon = LinDoIcons.FaCode;",
            '    color = "#32C3C3";',
            '  } else if (iconKey === "droplet" || base.includes("搞七") || base.includes("日常")) {',
            "    Icon = LinDoIcons.FaDroplet;",
            '    color = "#3AB54A";',
            '  } else if (iconKey === "piggy-bank" || base.includes("福利") || base.includes("羊毛")) {',
            "    Icon = LinDoIcons.FaPiggyBank;",
            '    color = "#E45735";',
            '  } else if (iconKey === "square-share-nodes" || base.includes("资源荟萃")) {',
            "    Icon = LinDoIcons.FaShareNodes;",
            '    color = "#12A89D";',
            '  } else if (iconKey === "hard-drive" || base.includes("网盘")) {',
            "    Icon = LinDoIcons.FaHardDrive;",
            '    color = "#16B176";',
            '  } else if (iconKey === "book" || base.includes("文档")) {',
            "    Icon = LinDoIcons.FaBook;",
            '    color = "#9CB6C4";',
            '  } else if (iconKey === "briefcase" || base.includes("非我") || base.includes("招聘")) {',
            "    Icon = LinDoIcons.FaBriefcase;",
            '    color = "#A8C6FE";',
            '  } else if (iconKey === "book-open-reader" || base.includes("读书")) {',
            "    Icon = LinDoIcons.FaBookOpenReader;",
            '    color = "#E0D900";',
            '  } else if (iconKey === "newspaper" || base.includes("快讯") || base.includes("前沿")) {',
            "    Icon = LinDoIcons.FaNewspaper;",
            '    color = "#BB8FCE";',
            '  } else if (iconKey === "rss" || base.includes("记忆")) {',
            "    Icon = LinDoIcons.FaRss;",
            '    color = "#F7941D";',
            '  } else if (iconKey === "seedling" || base.includes("国产")) {',
            "    Icon = LinDoIcons.FaSeedling;",
            '    color = "#D12C25";',
            '  } else if (iconKey === "hurricane" || base.includes("虫洞") || base.includes("广场")) {',
            "    Icon = LinDoIcons.FaHurricane;",
            '    color = "#FF00F7";',
            '  } else if (iconKey === "comments" || base.includes("运营") || base.includes("反馈")) {',
            "    Icon = LinDoIcons.FaComments;",
            '    color = "#808281";',
            '  } else if (iconKey === "bullhorn" || base.includes("公告")) {',
            "    Icon = LinDoIcons.FaBullhorn;",
            '    color = "#F1592A";',
            '  } else if (iconKey === "award" || base.includes("悬赏")) {',
            "    Icon = LinDoIcons.FaAward;",
            '    color = "#C4B512";',
            '  } else if (iconKey === "users" || base.includes("活动")) {',
            "    Icon = LinDoIcons.FaUsers;",
            '    color = "#38571A";',
            '  } else if (iconKey === "clone" || base.includes("模板")) {',
            "    Icon = LinDoIcons.FaClone;",
            '    color = "#04A407";',
            '  } else if (iconKey === "store" || base.includes("跳蚤") || base.includes("市场")) {',
            "    Icon = LinDoIcons.FaStore;",
            '    color = "#ED207B";',
            '  } else if (iconKey === "rocket" || base.includes("扬帆") || base.includes("起航")) {',
            "    Icon = LinDoIcons.FaRocket;",
            '    color = "#F7941D";',
            '  } else if (iconKey === "coins" || base.includes("积分") || base.includes("乐园")) {',
            "    Icon = LinDoIcons.FaCoins;",
            '    color = "#E0D900";',
            '  } else if (iconKey === "lightbulb" || base.includes("孵化")) {',
            "    Icon = LinDoIcons.FaLightbulb;",
            '    color = "#F7941D";',
            '  } else if (iconKey === "water" || base.includes("深海") || base.includes("幽域")) {',
            "    Icon = LinDoIcons.FaWater;",
            '    color = "#12A89D";',
            "  }",
            "",
            "  return { Icon, color, restricted, displayLabel };",
            "}",
            "",
            "const AI_TAG_SET = new Set([",
            '  "ai",',
            '  "chatgpt",',
            '  "claude",',
            '  "claudecode",',
            '  "gemini",',
            '  "openai",',
            '  "deepseek",',
            '  "codex",',
            '  "cursor",',
            '  "mcp",',
            '  "antigravity",',
            "]);",
            "",
            "export function resolveOfficialTagVisual(",
            "  tagName: string",
            "): { Icon: React.FC<LinDoIconProps>; color: string } | null {",
            '  const clean = tagName.trim().replace(/^#+/, "").trim();',
            "  const lower = clean.toLowerCase();",
            '  if (clean === "纯水" || clean.includes("水")) {',
            '    return { Icon: LinDoIcons.FaFaucet, color: "#F7941D" };',
            "  }",
            '  if (clean === "快问快答" || clean.includes("问答") || clean.includes("求助")) {',
            '    return { Icon: LinDoIcons.FaCircleQuestion, color: "#32C3C3" };',
            "  }",
            '  if (clean === "人工智能" || AI_TAG_SET.has(lower)) {',
            '    return { Icon: LinDoIcons.FaRobot, color: "#A8C6FE" };',
            "  }",
            '  if (clean === "公告" || clean.includes("站务")) {',
            '    return { Icon: LinDoIcons.FaBullhorn, color: "#F1592A" };',
            "  }",
            '  if (clean === "原创") {',
            '    return { Icon: LinDoIcons.FaFeatherPointed, color: "#3AB54A" };',
            "  }",
            "  if (",
            '    clean === "抽奖" ||',
            '    clean.includes("福利") ||',
            '    clean.includes("公益") ||',
            '    clean.includes("羊毛")',
            "  ) {",
            '    return { Icon: LinDoIcons.FaGift, color: "#E45735" };',
            "  }",
            '  if (clean === "精华神帖" || clean.includes("精华")) {',
            '    return { Icon: LinDoIcons.FaFire, color: "#F59E0B" };',
            "  }",
            '  if (clean === "集中帖" || clean.includes("合集")) {',
            '    return { Icon: LinDoIcons.FaLayerGroup, color: "#12A89D" };',
            "  }",
            '  if (clean === "树洞" || clean === "情感" || clean === "病友") {',
            '    return { Icon: LinDoIcons.FaTree, color: "#16B176" };',
            "  }",
            '  if (clean.includes("网盘") || clean.includes("夸克") || clean.includes("云盘")) {',
            '    return { Icon: LinDoIcons.FaCloud, color: "#38BDF8" };',
            "  }",
            "  if (",
            '    clean === "软件开发" ||',
            '    clean === "配置优化" ||',
            '    clean.includes("开源") ||',
            '    clean === "教程" ||',
            '    clean === "软件调试" ||',
            '    lower === "linux"',
            "  ) {",
            '    return { Icon: LinDoIcons.FaCode, color: "#32C3C3" };',
            "  }",
            '  if (clean === "网络安全") {',
            '    return { Icon: LinDoIcons.FaShieldHalved, color: "#10B981" };',
            "  }",
            '  if (["vps", "nas", "pt", "docker"].includes(lower) || clean.includes("服务")) {',
            '    return { Icon: LinDoIcons.FaServer, color: "#60A5FA" };',
            "  }",
            '  if (["影视", "电影", "动漫"].includes(clean)) {',
            '    return { Icon: LinDoIcons.FaFilm, color: "#BB8FCE" };',
            "  }",
            '  if (clean === "游戏") {',
            '    return { Icon: LinDoIcons.FaGamepad, color: "#F472B6" };',
            "  }",
            '  if (["读书", "书籍"].includes(clean)) {',
            '    return { Icon: LinDoIcons.FaBook, color: "#E0D900" };',
            "  }",
            '  if (["职场", "求职"].includes(clean)) {',
            '    return { Icon: LinDoIcons.FaBriefcase, color: "#A8C6FE" };',
            "  }",
            '  if (clean === "金融经济" || lower === "aff") {',
            '    return { Icon: LinDoIcons.FaCoins, color: "#FADB14" };',
            "  }",
            "  return null;",
            "}",
            "",
            "export const LinDoCategoryBadge: React.FC<{",
            "  name?: string | null;",
            "  fallbackColor?: string | null;",
            "  iconName?: string | null;",
            "  restricted?: boolean | null;",
            "  iconSize?: number;",
            "  className?: string;",
            "}> = React.memo(({ name, fallbackColor, iconName, restricted, iconSize = 12.5, className = '' }) => {",
            "  if (!name) return null;",
            "  const vis = resolveOfficialCategoryVisual(name, fallbackColor, iconName, restricted);",
            "  const CatIcon = vis.Icon;",
            "  return (",
            "    <span",
            "      className={`inline-flex shrink-0 items-center gap-[4px] font-normal leading-none ${className}`}",
            '      style={{ color: "var(--ink-secondary)" }}',
            "    >",
            "      <CatIcon size={iconSize} style={{ color: vis.color }} />",
            "      {vis.restricted ? (",
            "        <LinDoIcons.FaLock",
            "          size={Math.round(iconSize * 0.85 * 10) / 10}",
            '          style={{ color: "var(--ink-tertiary)" }}',
            "        />",
            "      ) : null}",
            "      <span>{vis.displayLabel}</span>",
            "    </span>",
            "  );",
            "});",
            "",
            "export const LinDoTopicTagChip: React.FC<{",
            "  tag: string;",
            "  compact?: boolean;",
            "  onClick?: (e: React.MouseEvent) => void;",
            "}> = React.memo(({ tag, compact = false, onClick }) => {",
            '  const clean = tag.trim().replace(/^#+/, "").trim();',
            "  if (!clean) return null;",
            "  const vis = resolveOfficialTagVisual(clean);",
            "  const TagIcon = vis ? vis.Icon : LinDoIcons.FaTag;",
            "  const iconSize = compact ? (vis ? 10 : 8.8) : vis ? 10.5 : 9.2;",
            "  return (",
            "    <span",
            "      onClick={onClick}",
            "      className={`inline-flex shrink-0 items-center gap-[3.5px] rounded-[4px] ${",
            '        compact ? "h-[19px] px-[5px] text-[10.8px]" : "h-[21px] px-[6px] text-[11.2px]"',
            '      } font-normal leading-none ${onClick ? "cursor-pointer hover:opacity-80" : ""}`}',
            "      style={{",
            '        backgroundColor: "var(--tonal-pill)",',
            '        color: "var(--ink-secondary)",',
            "      }}",
            "    >",
            "      <TagIcon",
            "        size={iconSize}",
            '        style={{ color: vis ? vis.color : "var(--ink-tertiary)" }}',
            "      />",
            "      <span className=\"truncate max-w-[112px]\">{clean}</span>",
            "    </span>",
            "  );",
            "});",
            "",
        ]
    )

    return "\n".join(lines)


def main() -> int:
    kt_source = KT_PATH.read_text(encoding="utf-8")
    extracted = parse_kotlin_icons(kt_source)
    if len(extracted) < 90:
        print(f"ERROR: Expected >= 90 icons from LinDoIcons.kt, got {len(extracted)}", file=sys.stderr)
        return 1

    merged = dict(extracted)
    for k, v in SUPPLEMENTARY_ICONS.items():
        if k not in merged:
            merged[k] = v

    JSON_PATH.parent.mkdir(parents=True, exist_ok=True)
    JSON_PATH.write_text(
        json.dumps(merged, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )

    tsx_code = generate_tsx(merged)
    TSX_PATH.write_text(tsx_code, encoding="utf-8")

    print(
        f"Synced {len(extracted)} icons from LinDoIcons.kt + {len(merged) - len(extracted)} supplementary icons "
        f"-> {len(merged)} canonical icons in {JSON_PATH.relative_to(ROOT)} & {TSX_PATH.relative_to(ROOT)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
