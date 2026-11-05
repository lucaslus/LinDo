#!/usr/bin/env python3
"""Read-only UI regression suite using adb + Android UIAutomator; preserves login data."""
import argparse
from datetime import datetime
import json
from pathlib import Path
import re
import subprocess
import time
import traceback
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "do_.linux.lindo"
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--device", default="emulator-5554")
parser.add_argument("--output", type=Path, default=ROOT / "build/ui-smoke")
parser.add_argument("--case", action="append", help="Run only the named case (repeatable)")
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
ADB = ["adb", "-s", args.device]
results = []


def adb(*command, timeout=35, check=True):
    return subprocess.run(ADB + list(command), capture_output=True, text=True, timeout=timeout, check=check).stdout


def screen(name):
    raw = subprocess.run(ADB + ["exec-out", "screencap", "-p"], capture_output=True, check=True).stdout
    path = args.output / f"{name}.png"
    path.write_bytes(raw)
    return str(path)


def tree():
    for attempt in range(3):
        try:
            adb("shell", "uiautomator", "dump", "/sdcard/lindo-ui-smoke.xml", timeout=15)
            return ET.fromstring(adb("shell", "cat", "/sdcard/lindo-ui-smoke.xml"))
        except (ET.ParseError, subprocess.SubprocessError):
            if attempt == 2:
                raise
            time.sleep(0.5)


def bounds(node):
    return tuple(map(int, re.findall(r"\d+", node.get("bounds", ""))))


def find(label, root=None):
    for node in (root if root is not None else tree()).iter("node"):
        if label in (node.get("text"), node.get("content-desc")):
            return node
    raise AssertionError(f"UI element not found: {label}")


def wait_for(label, timeout=22):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            return find(label)
        except (AssertionError, subprocess.SubprocessError):
            time.sleep(0.5)
    raise AssertionError(f"Timed out waiting for: {label}")


def tap(label):
    left, top, right, bottom = bounds(find(label))
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))


def start(scene=None, cold=True):
    command = ["shell", "am", "start"]
    if cold:
        command.append("-S")
    command += ["-W", "-n", f"{PACKAGE}/.MainActivity"]
    if scene == "gallery":
        command += ["--ez", "lindo.brand_preview", "true"]
    elif scene:
        command += ["--es", "lindo.preview", scene]
    adb(*command)


def stats():
    text = adb("shell", "dumpsys", "gfxinfo", PACKAGE)
    result = {}
    for key, pattern in {
        "frames": r"Total frames rendered:\s*(\d+)",
        "janky_frames": r"Janky frames:\s*(\d+)",
        "p50_ms": r"50th percentile:\s*(\d+)ms",
        "p90_ms": r"90th percentile:\s*(\d+)ms",
        "p95_ms": r"95th percentile:\s*(\d+)ms",
        "p99_ms": r"99th percentile:\s*(\d+)ms",
    }.items():
        match = re.search(pattern, text)
        if match:
            result[key] = int(match[1])
    return result


def memory():
    text = adb("shell", "dumpsys", "meminfo", PACKAGE)
    result = {}
    for key, pattern in {"pss_kib": r"TOTAL PSS:\s*(\d+)", "webviews": r"WebViews:\s*(\d+)", "activities": r"Activities:\s*(\d+)"}.items():
        match = re.search(pattern, text)
        if match:
            result[key] = int(match[1])
    return result


def case(name, function):
    if args.case and name not in args.case:
        return
    began = time.monotonic()
    row = {"name": name}
    try:
        detail = function()
        row.update(status="passed", detail=detail)
    except Exception as error:
        row.update(status="failed", error=str(error), traceback=traceback.format_exc())
        try:
            row["screenshot"] = screen(f"failure-{name}")
        except subprocess.SubprocessError:
            pass
    row["duration_seconds"] = round(time.monotonic() - began, 2)
    results.append(row)
    (args.output / "results.json").write_text(json.dumps({"device": args.device, "results": results}, ensure_ascii=False, indent=2))
    print(f"{row['status'].upper()}: {name} ({row['duration_seconds']}s)", flush=True)


def welcome():
    start("welcome")
    wait_for("你好，佬友。")
    root = tree()
    label = find("使用 LINUX DO 登录", root)
    parent = {child: node for node in root.iter() for child in node}
    button = label
    while button.get("clickable") != "true" and button in parent:
        button = parent[button]
    left, top, right, bottom = bounds(button)
    density_text = adb("shell", "wm", "density")
    density = int(re.findall(r"\d+", density_text)[-1]) / 160
    assert (bottom - top) / density >= 48, "Login touch target smaller than 48dp"
    screen("welcome-light")
    tap("切换至深色模式")
    wait_for("切换至浅色模式")
    screen("welcome-dark")
    return {"login_touch_height_dp": (bottom - top) / density}


def large_fonts(scale):
    adb("shell", "settings", "put", "system", "font_scale", str(scale))
    start("welcome")
    wait_for("使用 LINUX DO 登录")
    root = tree()
    width, height = map(int, re.findall(r"(\d+)x(\d+)", adb("shell", "wm", "size"))[-1])
    for label in ("使用 LINUX DO 登录", "使用系统浏览器授权"):
        left, top, right, bottom = bounds(find(label, root))
        assert 0 <= left < right <= width and 0 <= top < bottom <= height, f"Clipped action: {label}"
    screen(f"welcome-font-{scale}")
    return {"font_scale": scale}


def gallery():
    adb("shell", "settings", "put", "system", "font_scale", "1.0")
    start("gallery")
    wait_for("零度娘 · SVG 状态提示")
    root = tree()
    for label in ("零度娘正在整理内容", "正在同步最新内容", "连接暂时中断了", "这里还很安静", "已经准备好啦", "已经看到这里啦"):
        find(label, root)
    screen("states-light")
    tap("深色")
    wait_for("浅色")
    screen("states-dark")
    return {"states": 6, "themes": 2}


def animation_switch():
    # Run after the gallery case; the checkbox toggles only the debug preview's local preference.
    def rendered_frames():
        adb("shell", "dumpsys", "gfxinfo", PACKAGE, "reset")
        time.sleep(1.4)
        return stats().get("frames", 0)

    moving = rendered_frames()
    switch = next(node for node in tree().iter("node") if node.get("checkable") == "true")
    left, top, right, bottom = bounds(switch)
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
    time.sleep(0.6)
    still = rendered_frames()
    assert moving > 10, "Animation is not producing frames"
    assert still < moving / 3, "Motion continues while disabled"
    screen("states-motion-disabled")
    return {"moving_frames_in_1_4s": moving, "disabled_frames_in_1_4s": still}


def fullscreen_web():
    start("web")
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        root = tree()
        views = [node for node in root.iter("node") if node.get("class") == "android.webkit.WebView"]
        if views:
            break
        time.sleep(0.5)
    assert views, "No native WebView"
    rect = bounds(views[0])
    width, height = map(int, re.findall(r"(\d+)x(\d+)", adb("shell", "wm", "size"))[-1])
    assert rect[2] - rect[0] >= width * 0.98, "WebView is wrapped in horizontal padding"
    assert rect[3] - rect[1] >= height * 0.88, "Custom header or transition reduces WebView viewport"
    assert not any("登录成功后自动同步 Connect" in node.get("text", "") for node in root.iter("node"))
    screen("webview-fullscreen")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    wait_for("首页")
    return {"webview_bounds": rect, "screen": [width, height]}


def web_lifecycle():
    start()
    wait_for("首页")
    time.sleep(2)
    baseline = memory()
    samples = []
    for _ in range(4):
        start("web", cold=False)
        time.sleep(0.6)
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        wait_for("首页")
        samples.append(memory())
    # Object counts are evidence, not a proof that every possible heap leak is absent.
    assert samples[-1].get("webviews", 0) <= baseline.get("webviews", 0) + 1, "Native WebView instances grow after closing"
    assert samples[-1].get("activities", 0) <= baseline.get("activities", 0) + 1, "Activity instances grow during modal navigation"
    return {"baseline": baseline, "after_each_close": samples}


def offline():
    try:
        adb("shell", "svc", "wifi", "disable")
        adb("shell", "svc", "data", "disable")
        start("web")
        wait_for("暂时连接不上 LINUX DO", timeout=25)
        find("重新连接")
        screen("offline-error")
    finally:
        adb("shell", "svc", "wifi", "enable")
        adb("shell", "svc", "data", "enable")
    return {"retry_visible": True, "network_restored": True}


def feed_scroll():
    start()
    wait_for("首页")
    time.sleep(4)
    adb("shell", "dumpsys", "gfxinfo", PACKAGE, "reset")
    for _ in range(8):
        adb("shell", "input", "swipe", "360", "1260", "360", "620", "280")
        time.sleep(0.12)
    data = stats()
    assert data.get("frames", 0) > 20
    assert adb("shell", "pidof", PACKAGE).strip(), "App exited while scrolling"
    screen("feed-after-scroll")
    return {"debug_build_framestats": data, "memory": memory(), "note": "Emulator/debug sample, not a release-device 120Hz certification"}


def renderer_recovery():
    # Establish the app PID before triggering the renderer crash. Reading pidof
    # immediately after `am start -S` can still include the dying previous PID.
    start()
    wait_for("首页")
    process_before = adb("shell", "pidof", PACKAGE).strip()
    assert process_before, "Application process was not running before the renderer crash"
    start("web_crash", cold=False)
    wait_for("网页已停止运行", timeout=22)
    assert process_before == adb("shell", "pidof", PACKAGE).strip(), "WebView renderer death killed the application process"
    screen("renderer-recovery")
    adb("shell", "input", "keyevent", "KEYCODE_BACK")
    wait_for("首页")
    return {"application_process_survived": True, "returned_to_native_home": True,
            "pid_before": process_before, "pid_after": adb("shell", "pidof", PACKAGE).strip()}



def installed_icon():
    start("icon")
    wait_for("系统图标加载成功：AdaptiveIconDrawable")
    screen("installed-icon")
    return {"native_android_inflater": "AdaptiveIconDrawable", "version": "0.1.1"}


def rich_content():
    fixture = ROOT / "composeApp/src/commonTest/resources/rich-content.html"
    subprocess.run(ADB + ["shell", f"run-as {PACKAGE} sh -c 'cat > files/lindo-rich-preview.html'"],
                   input=fixture.read_bytes(), capture_output=True, check=True)
    start("rich")
    wait_for("富文本回归标题")
    tap("▸ 折叠测试")
    root = tree()
    text = " ".join(n.get("text", "") for n in root.iter("node"))
    assert "格式文本" in text, "Expanded rich content is missing"
    assert "二级列表" in text, "Nested list is missing"
    screen("rich-content-top")
    found = set()
    for _ in range(7):
        text = " ".join(n.get("text", "") for n in tree().iter("node"))
        for marker in ("表头 A", "单元格一", "配图测试", "复制代码", "投票选项测试", "在原网页查看完整内容", "打开视频或网页"):
            if marker in text:
                found.add(marker)
        adb("shell", "input", "swipe", "360", "1260", "360", "550", "280")
    screen("rich-content-bottom")
    assert len(found) == 7, f"Missing rendered content: {found}"
    return {"visible_content_markers": sorted(found)}


def rich_link_navigation():
    fixture = ROOT / "composeApp/src/commonTest/resources/rich-content.html"
    subprocess.run(ADB + ["shell", f"run-as {PACKAGE} sh -c 'cat > files/lindo-rich-preview.html'"],
                   input=fixture.read_bytes(), capture_output=True, check=True)
    start("rich")
    wait_for("链接点击测试")
    tap("链接点击测试")
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        if any(n.get("class") == "android.webkit.WebView" for n in tree().iter("node")):
            break
        time.sleep(.5)
    else:
        raise AssertionError("Rich link did not open the in-app WebView")
    screen("rich-link-webview")
    for _ in range(3):
        adb("shell", "input", "keyevent", "KEYCODE_BACK")
        try:
            find("富文本回归标题")
            break
        except AssertionError:
            pass
    wait_for("富文本回归标题")
    return {"clicked_text_link": True, "in_app_browser": True, "back_to_post": True}


def list_offline(scene):
    try:
        adb("shell", "svc", "wifi", "disable")
        adb("shell", "svc", "data", "disable")
        if scene == "inbox":
            start()
            tap("消息")
        else:
            start(scene)
        wait_for("重试", timeout=45)
        text = " ".join(n.get("text", "") for n in tree().iter("node"))
        assert "网络连接失败" in text, text
        assert "暂无" not in text and "草稿箱为空" not in text, "Network error is presented as successful empty content"
        screen(f"offline-{scene}")
        return {"page": scene, "error_visible": True, "false_empty_visible": False}
    finally:
        adb("shell", "svc", "wifi", "enable")
        adb("shell", "svc", "data", "enable")


def list_retry_recovery():
    try:
        adb("shell", "svc", "wifi", "disable")
        adb("shell", "svc", "data", "disable")
        start("bookmarks")
        wait_for("重试", timeout=45)
    finally:
        adb("shell", "svc", "wifi", "enable")
        adb("shell", "svc", "data", "enable")
    time.sleep(2)
    tap("重试")
    deadline = time.monotonic() + 40
    while time.monotonic() < deadline:
        text = " ".join(n.get("text", "") for n in tree().iter("node"))
        if "重试" not in text and "零度娘正在加载" not in text:
            break
        time.sleep(.5)
    assert "重试" not in text, "Online bookmarks did not recover"
    assert "我的书签与收藏" in text
    screen("bookmarks-online-restored")
    return {"network_restored": True, "loaded_without_error": True}


original_font = adb("shell", "settings", "get", "system", "font_scale").strip()
try:
    case("welcome-themes-and-touch-target", welcome)
    case("large-font-1.3", lambda: large_fonts(1.3))
    case("large-font-1.6", lambda: large_fonts(1.6))
    case("six-svg-states-light-dark", gallery)
    case("reduced-motion-stops-animation", animation_switch)
    case("fullscreen-webview-and-back", fullscreen_web)
    case("repeated-webview-release", web_lifecycle)
    case("offline-error-and-retry", offline)
    case("feed-scroll-framestats", feed_scroll)
    case("renderer-process-crash-recovery", renderer_recovery)
    case("installed-launcher-icon", installed_icon)
    case("rich-content-structure-and-expansion", rich_content)
    case("rich-link-navigation", rich_link_navigation)
    for scene in ("bookmarks", "notifications", "drafts", "history", "activity", "messages", "search", "inbox"):
        case(f"network-failure-{scene}", lambda scene=scene: list_offline(scene))
    case("list-online-recovery", list_retry_recovery)
finally:
    adb("shell", "settings", "put", "system", "font_scale", original_font if original_font not in ("null", "") else "1.0")
    # The suite is intended for the dedicated emulator; leave connectivity enabled.
    adb("shell", "svc", "wifi", "enable")
    adb("shell", "svc", "data", "enable")
    start()
    (args.output / "display.txt").write_text(adb("shell", "dumpsys", "display"))
    (args.output / "crash-log.txt").write_text(adb("logcat", "-b", "crash", "-d"))
    report = {"device": args.device, "completed_at": datetime.now().isoformat(), "passed": sum(r["status"] == "passed" for r in results), "failed": sum(r["status"] == "failed" for r in results), "results": results}
    (args.output / "results.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
    print(f"UI suite: {report['passed']} passed, {report['failed']} failed. {args.output / 'results.json'}", flush=True)
raise SystemExit(1 if any(row["status"] == "failed" for row in results) else 0)
