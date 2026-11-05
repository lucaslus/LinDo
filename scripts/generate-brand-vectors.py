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


def build(source, launcher=False):
    svg = ET.parse(source).getroot()
    width, height = float(svg.get("width")), float(svg.get("height"))
    vector = ET.Element("vector", {
        attribute("width"): "108dp" if launcher else f"{width:g}dp",
        attribute("height"): "108dp" if launcher else f"{height:g}dp",
        attribute("viewportWidth"): "192" if launcher else f"{width:g}",
        attribute("viewportHeight"): "192" if launcher else f"{height:g}",
    })
    destination = vector
    if launcher:
        destination = ET.SubElement(vector, "group", {
            attribute("translateX"): "32", attribute("translateY"): "32",
            attribute("scaleX"): f"{128 / width:.8f}", attribute("scaleY"): f"{128 / height:.8f}",
        })
    for contour in svg:
        if not contour.tag.endswith("path"):
            raise ValueError(f"{source}: flatten non-path shapes before export")
        data = contour.get("d", "")
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


if __name__ == "__main__":
    sources = ROOT / "design/brand-v3"
    resources = ROOT / "composeApp/src/commonMain/composeResources/drawable"
    for name, resource in (("lindo-mark", "lindo_brand_mark"), ("lingdu-welcome", "lindo_mascot_welcome"), ("lingdu-connecting", "lindo_mascot_connecting")):
        (resources / f"{resource}.xml").write_text(build(sources / f"{name}.svg"))
    launcher = ROOT / "composeApp/src/androidMain/res/drawable/ic_lindo_foreground.xml"
    launcher.write_text(build(sources / "lindo-mark.svg", launcher=True))
    print("Exported 3 SVG resources and Android adaptive icon foreground; all paths below AAPT limit.")
