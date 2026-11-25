#!/usr/bin/env python3
"""Render OS download badge matrix for GitHub Release notes, latest.json, and README.md."""

from __future__ import annotations

import argparse
import datetime as dt
import json
import pathlib
import re
from typing import Iterable

START_MARKER = "<!-- LINDO_DOWNLOAD_MATRIX_START -->"
END_MARKER = "<!-- LINDO_DOWNLOAD_MATRIX_END -->"


def find_asset(names: list[str], predicate) -> str | None:
    for name in sorted(names):
        if predicate(name.lower()):
            return name
    return None


def resolve_asset_urls(
    repo: str,
    tag: str,
    asset_names: Iterable[str],
    strict_existing: bool = False,
) -> dict[str, str]:
    names = list(asset_names)
    dl_prefix = f"https://github.com/{repo}/releases/download/{tag}"
    fallback = "" if (strict_existing and names) else f"https://github.com/{repo}/releases/latest"

    def url_for(pred) -> str:
        hit = find_asset(names, pred)
        return f"{dl_prefix}/{hit}" if hit else fallback

    return {
        "apk_arm64": url_for(lambda n: n.endswith(".apk")),
        "win_setup": url_for(lambda n: n.endswith("-setup.exe") or (n.endswith(".exe") and "windows" in n)),
        "win_msi": url_for(lambda n: n.endswith(".msi")),
        "mac_arm64": url_for(lambda n: n.endswith(".dmg") and ("arm64" in n or "aarch64" in n)),
        "mac_x64": url_for(lambda n: n.endswith(".dmg") and ("x64" in n or "x86_64" in n)),
        "linux_appimage": url_for(lambda n: n.endswith(".appimage")),
        "linux_deb": url_for(lambda n: n.endswith(".deb")),
        "linux_rpm": url_for(lambda n: n.endswith(".rpm")),
    }


def render_os_table(urls: dict[str, str]) -> str:
    spec_rows = [
        (
            "Android",
            [
                ("apk_arm64", "APK-ARMv8", "https://img.shields.io/badge/APK-ARMv8-2ea043?logo=android&logoColor=white"),
            ],
        ),
        (
            "Windows",
            [
                ("win_setup", "Setup-x64", "https://img.shields.io/badge/Setup-x64-0078D4"),
                ("win_msi", "MSI-x64", "https://img.shields.io/badge/MSI-x64-4B9CD3"),
            ],
        ),
        (
            "macOS",
            [
                ("mac_arm64", "DMG-Apple Silicon", "https://img.shields.io/badge/DMG-Apple_Silicon-181717?logo=apple&logoColor=white"),
                ("mac_x64", "DMG-Intel X64", "https://img.shields.io/badge/DMG-Intel_X64-0071C5?logo=apple&logoColor=white"),
            ],
        ),
        (
            "Linux",
            [
                ("linux_appimage", "AppImage-x64", "https://img.shields.io/badge/AppImage-x64-D94625?logo=linux&logoColor=white"),
                ("linux_deb", "DebPackage-x64", "https://img.shields.io/badge/DebPackage-x64-E95420?logo=debian&logoColor=white"),
                ("linux_rpm", "RpmPackage-x64", "https://img.shields.io/badge/RpmPackage-x64-D97706?logo=redhat&logoColor=white"),
            ],
        ),
    ]
    lines = [
        "| OS | Download |",
        "| :--- | :--- |",
    ]
    for os_name, items in spec_rows:
        badges = [
            f"[![{alt}]({badge_img})]({urls[key]})"
            for key, alt, badge_img in items
            if urls.get(key)
        ]
        if badges:
            lines.append(f"| **{os_name}** | {'<br/>'.join(badges)} |")
    return "\n".join(lines)


def clean_bullet_lines(raw_notes: str) -> list[str]:
    out: list[str] = []
    for raw in raw_notes.splitlines():
        s = raw.strip()
        if not s:
            continue
        s = re.sub(r"^[-*•]\s+", "", s).strip()
        if s and s not in out:
            out.append(s)
    return out or ["常规体验优化与问题修复"]


def build_release_markdown(
    repo: str,
    tag: str,
    prev_tag: str,
    bullets: list[str],
    urls: dict[str, str],
) -> str:
    bullet_md = "\n".join(f"- {b}" for b in bullets)
    dl_badge = (
        f"![Downloads](https://img.shields.io/github/downloads/{repo}/{tag}/total"
        f"?logo=github&color=2ea043)"
    )
    parts = [
        "### 更新内容",
        "",
        bullet_md,
        "",
        dl_badge,
        "",
        "**按系统选择下载（Download based on your OS）：**",
        "",
        render_os_table(urls),
    ]
    if prev_tag:
        parts.extend(
            [
                "",
                f"**完整变更记录**: [{prev_tag}...{tag}](https://github.com/{repo}/compare/{prev_tag}...{tag})",
            ]
        )
    return "\n".join(parts) + "\n"


def build_readme_block(repo: str, tag: str, urls: dict[str, str]) -> str:
    badges = (
        f"[![Latest Release](https://img.shields.io/github/v/release/{repo}?logo=github&label=latest&color=F59E0B)]"
        f"(https://github.com/{repo}/releases/latest) "
        f"[![Total Downloads](https://img.shields.io/github/downloads/{repo}/total?logo=github&label=downloads&color=2ea043)]"
        f"(https://github.com/{repo}/releases)"
    )
    return "\n".join(
        [
            START_MARKER,
            badges,
            "",
            f"当前最新正式版：**`{tag}`**（点击对应系统徽章即可直接下载安装包，完整校验文件见 [GitHub Releases](https://github.com/{repo}/releases/latest)）：",
            "",
            render_os_table(urls),
            END_MARKER,
        ]
    )


def extract_apk_build_code(asset_names: Iterable[str]) -> int | None:
    for name in asset_names:
        if name.lower().endswith(".apk"):
            m = re.search(r"build[-_.]?(\d+)", name, flags=re.IGNORECASE)
            if m:
                return int(m.group(1))
    return None


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--mode", choices=["release", "update-readme"], required=True)
    ap.add_argument("--repo", default="lucaslus/LinDo")
    ap.add_argument("--tag", required=True)
    ap.add_argument("--prev-tag", default="")
    ap.add_argument("--assets-dir", default="")
    ap.add_argument("--asset-name", action="append", default=[])
    ap.add_argument("--raw-notes-file", default="")
    ap.add_argument("--notes-out", default="")
    ap.add_argument("--manifest-out", default="")
    ap.add_argument("--readme", default="README.md")
    args = ap.parse_args()

    asset_names: list[str] = list(args.asset_name)
    if args.assets_dir:
        p = pathlib.Path(args.assets_dir)
        if p.is_dir():
            asset_names.extend(f.name for f in p.iterdir() if f.is_file())

    urls = resolve_asset_urls(
        args.repo,
        args.tag,
        asset_names,
        strict_existing=(args.mode == "release"),
    )

    if args.mode == "release":
        raw_notes = (
            pathlib.Path(args.raw_notes_file).read_text(encoding="utf-8")
            if args.raw_notes_file and pathlib.Path(args.raw_notes_file).is_file()
            else ""
        )
        bullets = clean_bullet_lines(raw_notes)
        md = build_release_markdown(args.repo, args.tag, args.prev_tag, bullets, urls)
        if args.notes_out:
            pathlib.Path(args.notes_out).write_text(md, encoding="utf-8")
        else:
            print(md)

        if args.manifest_out:
            clean_ver = args.tag.lstrip("vV").split("-")[0]
            apk_code = extract_apk_build_code(asset_names)
            android_obj: dict[str, object] = {
                "versionName": clean_ver,
                "notes": bullets[:6],
            }
            if apk_code is not None:
                android_obj["versionCode"] = apk_code
            manifest = {
                "updatedAt": dt.datetime.now(dt.timezone.utc).strftime("%Y-%m-%d"),
                "desktop": {
                    "version": clean_ver,
                    "notes": bullets[:6],
                },
                "android": android_obj,
            }
            pathlib.Path(args.manifest_out).write_text(
                json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
        return

    readme_path = pathlib.Path(args.readme)
    content = readme_path.read_text(encoding="utf-8")
    new_block = build_readme_block(args.repo, args.tag, urls)
    if START_MARKER in content and END_MARKER in content:
        pattern = re.compile(
            re.escape(START_MARKER) + r".*?" + re.escape(END_MARKER),
            flags=re.DOTALL,
        )
        updated = pattern.sub(lambda _: new_block, content, count=1)
    else:
        raise SystemExit(f"Missing {START_MARKER} / {END_MARKER} in {readme_path}")

    if updated != content:
        readme_path.write_text(updated, encoding="utf-8")


if __name__ == "__main__":
    main()
