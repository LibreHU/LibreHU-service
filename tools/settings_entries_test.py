#!/usr/bin/env python3
"""LibreHU's entries in Android's Settings (systemsettings/SystemSettings) on an Android 9 emulator (CI:
.github/workflows/settings-entries-test.yml). The service is put in /system/app, as in the LineageOS ROM (Settings
shows the entries of system apps only), then each entry is looked for in its page of Settings with its summary, opened
(the right page of the service must show, back must return to Settings), and the summary provider is called as root
(answer) and as the shell (null). Last, the same in French. Screenshots and UI dumps in the output folder; exit 1 on a
failure.

    python3 tools/settings_entries_test.py <debug APK or its folder> out [ViPER4Android LibreHU APK]

With the ViPER4Android LibreHU APK, it goes to /system/app as well and its entry of the Sound page is checked too.
"""
import glob
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zipfile

APK_IN, OUT = sys.argv[1:3]
VIPER_APK = sys.argv[3] if len(sys.argv) > 3 else None
VIPER = "com.llsl.viper4android"
PKG = "org.librehu.service"
SYSTEM_DIR = "/system/app/LibreHUService"
URI = "content://org.librehu.service.systemsettings/"
SETTINGS = "com.android.settings"
ABI = "x86_64"

# Per language: page of Settings (intent), title and summary of the entry (summary: one of), text of the service page
# it opens (None: entry not opened).
EN = {
    "home": ("-a android.settings.SETTINGS", "LibreHU", ["Vehicle, audio, display, Bluetooth"], "Head unit link"),
    "display": ("-a android.settings.DISPLAY_SETTINGS", "Screen and lights", ["Dark mode follows the headlights"], "Android dark mode"),
    "sound": ("-a android.settings.SOUND_SETTINGS", "Head unit audio", ["Tone, balance, outputs, source"], "Audio processor"),
    "bluetooth": (
        "-n com.android.settings/.Settings\\$ConnectedDeviceDashboardActivity",
        "Car Bluetooth",
        ["Bluetooth off", "No phone connected", "Phone, calls, music"],
        "No phone connected",
    ),
    "android_auto": (
        "-n com.android.settings/.Settings\\$ConnectedDeviceDashboardActivity",
        "Android Auto",
        ["Open Headunit not installed"],
        "Android Auto (Open Headunit)",
    ),
    "system": (
        "-n com.android.settings/.Settings\\$SystemDashboardActivity",
        "Head unit",
        ["MCU not reachable", "MCU link stopped", "Connected to the MCU", "Waiting: Jancar ivi-services owns the MCU"],
        "Root",
    ),
}
FR = {
    "home": ("-a android.settings.SETTINGS", "LibreHU", ["Véhicule, audio, écran, Bluetooth"], None),
    "display": ("-a android.settings.DISPLAY_SETTINGS", "Écran et lumières", ["Mode sombre selon les feux"], None),
}

failures = []
results = []


def adb(*args, timeout=180):
    try:
        r = subprocess.run(["adb", *args], text=True, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return "TIMEOUT"
    return r.stdout + r.stderr


def sh(cmd, timeout=180):
    return adb("shell", cmd, timeout=timeout)


def check(ok, what):
    results.append(f"{'ok ' if ok else 'BAD'} {what}")
    if not ok:
        failures.append(what)
    print(results[-1], flush=True)
    return ok


def wait_boot(seconds=300):
    adb("wait-for-device")
    end = time.time() + seconds
    while time.time() < end:
        if sh("getprop sys.boot_completed").strip() == "1" and "package:" in sh("pm path android"):
            return True
        time.sleep(3)
    return False


def dump(name):
    """UI hierarchy of the screen: list of (text, bounds centre), also saved with a screenshot."""
    for _ in range(3):
        out = sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; cat /sdcard/ui.xml", timeout=60)
        start = out.find("<?xml")
        if start >= 0:
            break
        time.sleep(2)
    else:
        return []
    xml = out[start:]
    with open(os.path.join(OUT, name + ".xml"), "w", encoding="utf-8") as f:
        f.write(xml)
    with open(os.path.join(OUT, name + ".png"), "wb") as f:
        f.write(subprocess.run(["adb", "exec-out", "screencap", "-p"], capture_output=True, timeout=60).stdout)
    nodes = []
    try:
        for n in ET.fromstring(xml).iter("node"):
            m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
            if m:
                x1, y1, x2, y2 = map(int, m.groups())
                nodes.append((n.get("text", ""), ((x1 + x2) // 2, (y1 + y2) // 2)))
    except ET.ParseError:
        pass
    return nodes


def find(nodes, text):
    return next((c for t, c in nodes if t == text), None)


def resumed():
    out = sh("dumpsys activity activities | grep -E 'mResumedActivity|ResumedActivity:'")
    return out.strip()


def find_entry(name, title, summaries):
    """Entry of the current page of Settings: scrolls, opens « Advanced », waits for the summary. Nodes and the
    summary found."""
    nodes = []
    for attempt in range(8):
        time.sleep(2)
        nodes = dump(f"{name}-{attempt}")
        texts = [t for t, _ in nodes]
        if title in texts:
            # The summary comes from the provider on a background thread: shown a moment later.
            got = next((s for s in summaries if s in texts), None)
            if got:
                return nodes, got
            continue
        advanced = find(nodes, "Advanced") or find(nodes, "Options avancées")
        if advanced:
            sh(f"input tap {advanced[0]} {advanced[1]}")
        else:
            sh("input swipe 640 600 640 200 400")
    return nodes, None


# Entries of Connected devices: that page of Settings crashes on a device without Bluetooth (Android 9's own bug,
# LocalBluetoothManager null in BluetoothDeviceUpdater), as on the emulator; the head unit has Bluetooth.
CONNECTED = {"bluetooth": "BluetoothEntry", "android_auto": "AndroidAutoEntry"}


def run_without_page(name, spec):
    """Entry of a page that cannot be shown here: its summary from the provider and the page it opens."""
    _, title, summaries, page_text = spec
    results.append(f"--  {name}: page of Settings skipped (no Bluetooth on this device: it crashes there)")
    print(results[-1], flush=True)
    got = sh(f"content call --uri {URI}{name} --method {name}")
    check(any(s in got for s in summaries), f"{name}: summary from the provider: {got.strip()}")
    sh(f"am start -W -n {PKG}/.systemsettings.{CONNECTED[name]}")
    page = []
    for attempt in range(6):
        time.sleep(2)
        page = dump(f"{name}-opened-{attempt}")
        if find(page, page_text):
            break
    now = resumed()
    check(PKG in now, f"{name}: LibreHU opened ({now.splitlines()[0] if now else '?'})")
    check(find(page, page_text) is not None, f"{name}: page of the service with « {page_text} »")
    sh("input keyevent KEYCODE_BACK")
    time.sleep(2)


def run_page(name, spec, open_entry):
    intent, title, summaries, page_text = spec
    if name in CONNECTED and "feature:android.hardware.bluetooth" not in sh("pm list features"):
        run_without_page(name, spec)
        return
    sh(f"am start -W {intent}")
    nodes, got = find_entry(name, title, summaries)
    if not check(find(nodes, title) is not None, f"{name}: entry « {title} » in Settings"):
        return
    check(got is not None, f"{name}: summary {got!r} (expected one of {summaries})")
    if not open_entry or page_text is None:
        return
    x, y = find(nodes, title)
    sh(f"input tap {x} {y}")
    page = []
    for attempt in range(6):
        time.sleep(2)
        page = dump(f"{name}-opened-{attempt}")
        if find(page, page_text):
            break
    now = resumed()
    check(PKG in now, f"{name}: LibreHU opened ({now.splitlines()[0] if now else '?'})")
    check(find(page, page_text) is not None, f"{name}: page of the service with « {page_text} »")
    sh("input keyevent KEYCODE_BACK")
    time.sleep(2)
    now = resumed()
    check(SETTINGS in now, f"{name}: back to Settings ({now.splitlines()[0] if now else '?'})")


def run_viper_open():
    """ViPER4Android's entry opens its main screen, back returns to Settings."""
    sh("am start -W -a android.settings.SOUND_SETTINGS")
    nodes, _ = find_entry("viper-tap", "ViPER4Android", ["Android audio effects: equalizer, bass, surround"])
    pos = find(nodes, "ViPER4Android")
    if not check(pos is not None, "viper: entry found to open"):
        return
    sh(f"input tap {pos[0]} {pos[1]}")
    time.sleep(5)
    dump("viper-opened")
    now = resumed()
    check(VIPER in now, f"viper: ViPER4Android opened ({now.splitlines()[0] if now else '?'})")
    sh("input keyevent KEYCODE_BACK")
    time.sleep(2)
    now = resumed()
    check(SETTINGS in now, f"viper: back to Settings ({now.splitlines()[0] if now else '?'})")


def install_system(apk):
    print(adb("root"), flush=True)
    time.sleep(5)
    adb("wait-for-device")
    out = adb("remount")
    print(out, flush=True)
    if "succeeded" not in out:
        print(sh("mount -o rw,remount /system; mount -o rw,remount /"), flush=True)
    if VIPER_APK:
        # No native library in ViPER4Android's APK (its driver is a separate audio effect library).
        sh("rm -rf /system/app/ViPER4Android; mkdir -p /system/app/ViPER4Android")
        print(adb("push", VIPER_APK, "/system/app/ViPER4Android/ViPER4Android.apk"), flush=True)
        sh("chmod 755 /system/app/ViPER4Android; chmod 644 /system/app/ViPER4Android/ViPER4Android.apk")
    libs = f"{SYSTEM_DIR}/lib/{ABI}"
    sh(f"rm -rf {SYSTEM_DIR}; mkdir -p {libs}")
    print(adb("push", apk, f"{SYSTEM_DIR}/LibreHUService.apk"), flush=True)
    # A system app's native libraries are not extracted by Android: next to the APK, as a ROM build does.
    tmp = os.path.join(OUT, "lib")
    os.makedirs(tmp, exist_ok=True)
    with zipfile.ZipFile(apk) as z:
        for entry in z.namelist():
            if entry.startswith(f"lib/{ABI}/") and entry.endswith(".so"):
                path = os.path.join(tmp, os.path.basename(entry))
                with open(path, "wb") as f:
                    f.write(z.read(entry))
                print(adb("push", path, f"{libs}/"), flush=True)
    sh(f"chmod 755 {SYSTEM_DIR} {SYSTEM_DIR}/lib {libs}; chmod 644 {SYSTEM_DIR}/LibreHUService.apk {libs}/*")
    restart()


def restart():
    """Android restarted (zygote): the packages of /system scanned again, the locale read again."""
    sh("setprop sys.boot_completed 0; stop; start")
    time.sleep(10)
    check(wait_boot(), "Android restarted")
    sh("input keyevent KEYCODE_WAKEUP; wm dismiss-keyguard")
    time.sleep(15)


def main():
    os.makedirs(OUT, exist_ok=True)
    apk = APK_IN if APK_IN.endswith(".apk") else sorted(glob.glob(os.path.join(APK_IN, "*.apk")))[0]
    check(wait_boot(), "emulator booted")
    install_system(apk)
    info = sh(f"dumpsys package {PKG} | grep -E 'codePath=|pkgFlags=' | head -4")
    print(info, flush=True)
    check(SYSTEM_DIR in info and "SYSTEM" in info, "LibreHU-service is a system app")
    found = sh("cmd package query-activities --components -a com.android.settings.action.EXTRA_SETTINGS")
    with open(os.path.join(OUT, "extra-settings.txt"), "w", encoding="utf-8") as f:
        f.write(found)
    for entry in ("HomeEntry", "DisplayEntry", "SoundEntry", "BluetoothEntry", "AndroidAutoEntry", "SystemEntry"):
        check(f"{PKG}/.systemsettings.{entry}" in found, f"{entry} answers EXTRA_SETTINGS")
    # Summary provider: root gets the summary, the shell nothing (null, no exception).
    root = sh(f"content call --uri {URI}display --method display")
    check("com.android.settings.summary=" in root, f"provider as root: {root.strip()}")
    shell = sh(f"su 2000 content call --uri {URI}display --method display")
    check("Result: null" in shell, f"provider as the shell: {shell.strip()}")
    for name, spec in EN.items():
        run_page(name, spec, open_entry=True)
    if VIPER_APK:
        check(f"{VIPER}/.librehu.SoundSettingsEntry" in found, "ViPER4Android answers EXTRA_SETTINGS")
        run_page(
            "viper",
            ("-a android.settings.SOUND_SETTINGS", "ViPER4Android", ["Android audio effects: equalizer, bass, surround"], None),
            open_entry=False,
        )
        run_viper_open()
    # French: the same entries, translated (Settings reads the resources in the system's language).
    sh("setprop persist.sys.locale fr-FR")
    restart()
    for name, spec in FR.items():
        run_page("fr-" + name, spec, open_entry=False)
    logcat = adb("logcat", "-d", "-v", "threadtime", "-b", "all", timeout=300)
    with open(os.path.join(OUT, "logcat.txt"), "w", encoding="utf-8") as f:
        f.write(logcat)
    # Crashes of the service or of Settings only (the emulator's own apps may crash on their own).
    crashes = [
        line
        for line in logcat.splitlines()
        if "AndroidRuntime" in line and (f"Process: {PKG}," in line or f"Process: {SETTINGS}," in line)
    ]
    check(not crashes, f"no crash of LibreHU-service or Settings ({crashes[:2]})")
    summary = "\n".join(results + [f"FAILED: {x}" for x in failures] or ["nothing run"])
    print(summary, flush=True)
    with open(os.path.join(OUT, "summary.txt"), "w", encoding="utf-8") as f:
        f.write(summary + "\n")
    with open(os.environ.get("GITHUB_STEP_SUMMARY", os.devnull), "a", encoding="utf-8") as f:
        f.write("## LibreHU in Android's Settings (Android 9)\n\n```\n" + summary + "\n```\n")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
