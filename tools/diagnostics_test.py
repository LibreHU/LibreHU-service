#!/usr/bin/env python3
"""LibreHU-service's diagnostics on an emulator (CI: .github/workflows/diagnostics-test.yml): each item of its provider
(diag/DiagnosticsProvider) read with adb, as the shell, then LibreHU Helper's report made by Helper's own test
(tools/report_test.py of LibreHU-helper-app), whose zip must hold the section LibreHU-service. Exit 1 on a failure.

    python3 tools/diagnostics_test.py <debug APK or its folder> helper.apk <report_test.py of the Helper> out
"""
import glob
import os
import re
import subprocess
import sys
import time

SERVICE, HELPER, REPORT_TEST, OUT = sys.argv[1:5]
URI = "content://org.librehu.service.diagnostics/"
# First line expected of each item (None: may be empty on a fresh install).
ITEMS = {
    "report": "# LibreHU-service diagnostics",
    "service-log": "# ",
    "standby-journal": None,
    "can": "# LibreHU CAN box log",
}
# Files of the Helper's report and their smallest size (bytes).
REPORT_FILES = {
    "librehu-service/report.txt": 500,
    "librehu-service/service-log.txt": 1,
    "librehu-service/standby-journal.txt": 0,
    "librehu-service/can.txt": 20,
}


def adb(*args, timeout=180):
    try:
        r = subprocess.run(["adb", *args], text=True, capture_output=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return "TIMEOUT"
    return r.stdout + r.stderr


def main():
    os.makedirs(OUT, exist_ok=True)
    apk = SERVICE if SERVICE.endswith(".apk") else sorted(glob.glob(os.path.join(SERVICE, "*.apk")))[0]
    adb("wait-for-device")
    for _ in range(120):
        if adb("shell", "getprop", "sys.boot_completed").strip() == "1":
            break
        time.sleep(2)
    print(adb("install", "-r", "-g", apk), flush=True)
    failures = []
    results = []
    for item, head in ITEMS.items():
        t0 = time.time()
        text = adb("shell", "content", "read", "--uri", URI + item, timeout=120)
        with open(os.path.join(OUT, f"adb-{item}.txt"), "w", encoding="utf-8") as f:
            f.write(text)
        ok = head is None or text.startswith(head)
        results.append(f"adb {item}: {len(text)} chars in {time.time() - t0:.1f} s, {'ok' if ok else 'BAD'}")
        if not ok:
            failures.append(f"adb {item}: {text[:300]!r}")
    # Helper's report, as on its own CI (installs the Helper, exports to its folder, then through the folder picker).
    r = subprocess.run([sys.executable, REPORT_TEST, HELPER, os.path.join(OUT, "helper")], text=True, capture_output=True, timeout=3000)
    with open(os.path.join(OUT, "helper-report-test.txt"), "w", encoding="utf-8") as f:
        f.write(r.stdout + r.stderr)
    lists = sorted(glob.glob(os.path.join(OUT, "helper", "*.list.txt")))
    if not lists:
        failures.append("no report of the Helper (helper-report-test.txt)")
    for path in lists:
        sizes = {}
        with open(path, encoding="utf-8") as f:
            for line in f:
                m = re.match(r"\s*(\d+)\s+(\S+)", line)
                if m:
                    sizes[m.group(2)] = int(m.group(1))
        for name, least in REPORT_FILES.items():
            size = sizes.get(name)
            ok = size is not None and size >= least
            results.append(f"{os.path.basename(path)}: {name} {size} bytes, {'ok' if ok else 'BAD'}")
            if not ok:
                failures.append(f"{os.path.basename(path)}: {name} missing or under {least} bytes")
    logcat = adb("logcat", "-d", "-v", "threadtime", "-b", "all", timeout=300)
    with open(os.path.join(OUT, "logcat.txt"), "w", encoding="utf-8") as f:
        f.write(logcat)
    summary = "\n".join(results + [f"FAILED: {x}" for x in failures] or ["nothing run"])
    print(summary, flush=True)
    with open(os.path.join(OUT, "summary.txt"), "w", encoding="utf-8") as f:
        f.write(summary + "\n")
    with open(os.environ.get("GITHUB_STEP_SUMMARY", os.devnull), "a", encoding="utf-8") as f:
        f.write("## Diagnostics on Android 9\n\n```\n" + summary + "\n```\n")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
