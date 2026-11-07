#!/usr/bin/env python3
"""Export canonical SVG paths to Compose and Android VectorDrawable resources."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", ANDROID)
PATH_BYTE_LIMIT = 30_000  # Stay below AAPT's per-string UTF-8 limit with a margin.


def attribute(name):
    return f"{{{ANDROID}}}{name}"


def build(source, launcher=False, legacy=False):
    svg = ET.parse(source).getroot()
    width, height = float(svg.get("width")), float(svg.get("height"))
    canvas = 48 if legacy else 108
    vector = ET.Element("vector", {
        attribute("width"): f"{canvas}dp" if launcher else f"{width:g}dp",
        attribute("height"): f"{canvas}dp" if launcher else f"{height:g}dp",
        attribute("viewportWidth"): str(canvas) if launcher else f"{width:g}",
        attribute("viewportHeight"): str(canvas) if launcher else f"{height:g}",
    })
    destination = vector
    if launcher:
        span = 42 if legacy else 60
        padding = str((canvas - span) // 2)
        destination = ET.SubElement(vector, "group", {
            attribute("translateX"): padding, attribute("translateY"): padding,
            attribute("scaleX"): f"{span / width:.8f}", attribute("scaleY"): f"{span / height:.8f}",
        })
    for contour in svg:
        if not contour.tag.endswith("path"):
            raise ValueError(f"{source}: flatten non-path shapes before export")
        data = contour.get("d", "").strip()
        if not data:
            continue  # Android PathParser throws on empty paths; Compose can silently tolerate them.
        if len(data.encode("utf-8")) > PATH_BYTE_LIMIT:
            raise ValueError(f"{source}: optimize path data before Android export (AAPT string limit)")
        parent = destination
        transform = contour.get("transform", "")
        if transform:
            match = re.fullmatch(r"translate\(([-\d.]+)[ ,]+([-\d.]+)\)", transform)
            if not match:
                raise ValueError(f"{source}: unsupported transform: {transform}")
            parent = ET.SubElement(destination, "group", {
                attribute("translateX"): match[1], attribute("translateY"): match[2],
            })
        ET.SubElement(parent, "path", {
            attribute("fillColor"): contour.get("fill", "#000000"),
            attribute("pathData"): data,
            attribute("fillType"): "evenOdd" if contour.get("fill-rule") == "evenodd" else "nonZero",
        })
    ET.indent(vector)
    return ET.tostring(vector, encoding="unicode") + "\n"


def launcher_artwork(resources, name, layers):
    items = "\n".join(f'    <item android:drawable="@drawable/{layer}"/>' for layer in layers)
    (resources / "drawable" / f"ic_lindo_art_{name}.xml").write_text(
        f'<layer-list xmlns:android="{ANDROID}">\n{items}\n</layer-list>\n'
    )
    legacy = resources / "mipmap-anydpi"
    legacy.mkdir(exist_ok=True)
    legacy_items = "\n".join(f'    <item android:drawable="@drawable/{layer}_legacy"/>' for layer in layers)
    for background in ("white", "black"):
        filename = f"ic_lindo_{name}_{background}.xml"
        color = f"@color/ic_launcher_{background}"
        artwork = f"@drawable/ic_lindo_art_{name}"
        (resources / "mipmap-anydpi-v26" / filename).write_text(f'''<adaptive-icon xmlns:android="{ANDROID}">
    <background android:drawable="{color}"/>
    <foreground android:drawable="{artwork}"/>
</adaptive-icon>
''')
        (legacy / filename).write_text(f'''<layer-list xmlns:android="{ANDROID}">
    <item>
        <shape>
            <size android:width="48dp" android:height="48dp"/>
            <solid android:color="{color}"/>
            <corners android:radius="12dp"/>
        </shape>
    </item>
{legacy_items}
</layer-list>
''')


def launcher_vectors(resources, name, source):
    (resources / "drawable" / f"{name}.xml").write_text(build(source, launcher=True))
    (resources / "drawable" / f"{name}_legacy.xml").write_text(build(source, launcher=True, legacy=True))


if __name__ == "__main__":
    sources = ROOT / "design/brand-v3"
    resources = ROOT / "composeApp/src/commonMain/composeResources/drawable"
    for name, resource in (("lindo-mark", "lindo_brand_mark"), ("lingdu-welcome", "lindo_mascot_welcome"), ("lingdu-connecting", "lindo_mascot_connecting")):
        (resources / f"{resource}.xml").write_text(build(sources / f"{name}.svg"))
    android = ROOT / "composeApp/src/androidMain/res"
    base = "ic_lindo_foreground"
    launcher_vectors(android, base, sources / "lindo-mark.svg")
    launcher_artwork(android, "classic", [base])
    for expression in sorted((sources / "expressions").glob("*.svg")):
        name = expression.stem
        overlay = f"ic_lindo_expression_{name}"
        (resources / f"lindo_expression_{name}.xml").write_text(build(expression))
        launcher_vectors(android, overlay, expression)
        launcher_artwork(android, name, [base, overlay])
    official = sources / "linuxdo-mark.svg"
    (resources / "lindo_linuxdo_mark.xml").write_text(build(official))
    launcher_vectors(android, "ic_lindo_official", official)
    launcher_artwork(android, "official", ["ic_lindo_official"])
    print("Exported brand artwork and launcher icons. All vector paths are below the AAPT limit.")
