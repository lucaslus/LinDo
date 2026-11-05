#!/usr/bin/env python3
"""Watch Android/shared sources, incrementally build, install, and restart LinDo."""

import argparse
import fcntl
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time


ROOT = Path(__file__).resolve().parents[1]
COMPONENT = "do_.linux.lindo/.MainActivity"
APK = ROOT / "composeApp/build/outputs/apk/debug/composeApp-debug.apk"


def log(message):
    print(f"[{time.strftime('%H:%M:%S')}] {message}", flush=True)


def snapshot():
    roots = [ROOT / "composeApp/src/commonMain", ROOT / "composeApp/src/androidMain", ROOT / "gradle"]
    files = [ROOT / name for name in ("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "local.properties", "composeApp/build.gradle.kts")]
    for root in roots:
        files.extend(path for path in root.rglob("*") if path.is_file())
    result = {}
    for path in files:
        try:
            stat = path.stat()
            result[str(path.relative_to(ROOT))] = (stat.st_mtime_ns, stat.st_size)
        except FileNotFoundError:
            pass
    return result


def run(command, env):
    process = subprocess.Popen(command, cwd=ROOT, env=env)
    try:
        return process.wait() == 0
    except BaseException:
        process.terminate()
        try:
            process.wait(timeout=5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
        raise


def deploy(adb, device, env):
    started = time.monotonic()
    probe = subprocess.run([adb, "-s", device, "get-state"], capture_output=True, text=True, env=env)
    if probe.returncode or probe.stdout.strip() != "device":
        log(f"Device {device} is unavailable; start/reconnect it, then save a source file to retry.")
        return False
    log("Incremental Android debug build…")
    command = [str(ROOT / "gradlew"), f"-Dorg.gradle.java.home={env['JAVA_HOME']}", ":composeApp:assembleDebug", "--max-workers=3", "--console=plain"]
    if not run(command, env):
        log("Build failed; keeping the installed version. Fix and save to retry.")
        return False
    if not APK.is_file():
        log(f"APK not found: {APK}")
        return False
    if not run([adb, "-s", device, "install", "-r", "-t", str(APK)], env):
        log("Install failed; application data has not been cleared.")
        return False
    if not run([adb, "-s", device, "shell", "am", "start", "-S", "-W", "-n", COMPONENT], env):
        log("Launch failed; check adb logcat.")
        return False
    log(f"Updated {device} in {time.monotonic() - started:.1f}s; persistent application data retained.")
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--device", default="emulator-5554", help="Explicit adb serial (default: emulator-5554)")
    parser.add_argument("--once", action="store_true", help="Build, install, and launch once, then exit")
    parser.add_argument("--skip-initial", action="store_true", help="Watch without rebuilding immediately")
    args = parser.parse_args()
    if args.once and args.skip_initial:
        parser.error("--once and --skip-initial cannot be combined")
    env = os.environ.copy()
    studio_jdk = Path("/Applications/Android Studio.app/Contents/jbr/Contents/Home")
    jdk = studio_jdk if studio_jdk.is_dir() else Path(env.get("JAVA_HOME", "/nonexistent"))
    if not (jdk / "bin/java").is_file():
        parser.error("Set JAVA_HOME to a working JDK (JDK 21 recommended).")
    env["JAVA_HOME"] = str(jdk)
    env["PATH"] = str(jdk / "bin") + os.pathsep + env.get("PATH", "")
    sdk = env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT") or str(Path.home() / "Library/Android/sdk")
    env["ANDROID_HOME"] = sdk
    adb = str(Path(sdk) / "platform-tools/adb")
    if not Path(adb).is_file():
        adb = shutil.which("adb")
    if not adb:
        parser.error("Android SDK platform-tools/adb is required.")
    lock_path = ROOT / ".gradle/lindo-android-dev.lock"
    lock_path.parent.mkdir(parents=True, exist_ok=True)
    with lock_path.open("w") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            parser.error("An Android dev process is already running for this checkout.")
        previous = snapshot()
        if not args.skip_initial:
            success = deploy(adb, args.device, env)
            if args.once:
                return 0 if success else 1
        log(f"Watching shared/Android code, resources, and Gradle config → {args.device}. Ctrl+C to stop.")
        while True:
            time.sleep(0.75)
            current = snapshot()
            if current == previous:
                continue
            # Wait for a quiet save window so multi-file writes form one build.
            while True:
                time.sleep(0.75)
                latest = snapshot()
                if latest == current:
                    break
                current = latest
            changed = sorted(key for key in current.keys() | previous.keys() if current.get(key) != previous.get(key))
            log("Changed: " + ", ".join(changed[:5]) + (" …" if len(changed) > 5 else ""))
            previous = current
            deploy(adb, args.device, env)


if __name__ == "__main__":
    def stop(_signum, _frame):
        raise KeyboardInterrupt

    signal.signal(signal.SIGTERM, stop)
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        log("Android dev watcher stopped.")
        sys.exit(0)
