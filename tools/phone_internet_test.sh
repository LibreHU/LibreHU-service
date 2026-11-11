#!/bin/bash
# The Internet over the cable on an emulator standing for the phone (CI: .github/workflows/phone-internet-test.yml):
# the probe's key allowed in adbd (adb_keys, as the user's "Always allow" does), the host's adb server stopped so that
# the probe talks to the emulator's adbd itself (port 5555), then core/src/test/.../PhoneProbe.kt: adb connection,
# LibreHU-service's release APK pushed, its relay started with app_process, DNS and HTTP through the relay.
#     tools/phone_internet_test.sh <release apk> <adbkey (PEM)> <out dir>
APK=$1
KEY=$2
OUT=$3
mkdir -p "$OUT"
adb wait-for-device
adb root
sleep 5
adb wait-for-device
adb shell getprop ro.build.version.sdk | tee "$OUT/sdk.txt"
adb shell getprop ro.adb.secure | tee "$OUT/adb-secure.txt"
adb push "$KEY.pub" /data/local/tmp/probe.pub
adb shell 'f=/data/misc/adb/adb_keys; [ -f $f ] || { touch $f; chown system:shell $f; chmod 640 $f; }; cat /data/local/tmp/probe.pub >> $f; restorecon $f; ls -lZ /data/misc/adb/' | tee "$OUT/adb-keys.txt"
adb kill-server
sleep 2
./gradlew --no-daemon -q -PcoreOnly :core:phoneProbe -PprobeArgs="127.0.0.1 5555 $KEY $APK" 2>&1 | tee "$OUT/probe.txt"
rc=${PIPESTATUS[0]}
adb start-server
adb wait-for-device
adb shell cat /data/local/tmp/librehu-relay.log > "$OUT/relay.log" 2>&1
adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>&1
exit $rc
