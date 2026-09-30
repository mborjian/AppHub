#!/bin/sh
#
# Installs a built APK on the emulator that is already running and proves the
# launcher actually comes up. The release job runs this before it publishes
# anything: an APK that installs but does not start is not a release.
#
#     sh tools/smoke-test-apk.sh <apk> [package/activity]
#
# The screenshot and the activity dump the answer is based on are written to
# $EVIDENCE_DIR (default "smoke-test"), so the workflow can upload them.
set -eu

APK=${1:?usage: smoke-test-apk.sh <apk> [package/activity]}
COMPONENT=${2:-com.mimskydo.apphub/.MainActivity}
PACKAGE=${COMPONENT%%/*}
EVIDENCE_DIR=${EVIDENCE_DIR:-smoke-test}
WAIT_SECONDS=${WAIT_SECONDS:-30}

mkdir -p "$EVIDENCE_DIR"

# Anything that goes wrong reports the crash buffer and the activity state too:
# what is missing from the normal output is usually what explains the failure.
fail() {
    echo "::error::$1"
    echo "--- crash buffer ---"
    adb logcat -d -b crash 2>/dev/null | tail -60 || true
    echo "--- activities ---"
    adb shell dumpsys activity activities 2>/dev/null | head -40 || true
    exit 1
}

command -v adb >/dev/null 2>&1 || fail "adb is not on PATH"

echo "Installing $APK"
adb wait-for-device
adb install -r "$APK" || fail "the APK did not install"

echo "Installed: $(adb shell dumpsys package "$PACKAGE" \
    | sed -n 's/.*versionName=\(.*\)/\1/p' | head -1 | tr -d '\r')"

# A screen that went to sleep puts the keyguard in front instead of the app, and
# this check is about the app, not about the display.
adb shell svc power stayon true || true
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell wm dismiss-keyguard || true

adb logcat -c || true

echo "Starting $COMPONENT"
adb shell am start -W -n "$COMPONENT" | tee "$EVIDENCE_DIR/am-start.txt"

# -W waits for the launch, but it answers "ok" for an activity that dies a moment
# later: by then the home screen is in front again, and only the resumed record
# says which of the two happened.
resumed=""
left=$WAIT_SECONDS
while [ "$left" -gt 0 ]; do
    resumed=$(adb shell dumpsys activity activities \
        | grep -E 'ResumedActivity|mResumedActivity|mFocusedApp' | head -2 || true)
    case "$resumed" in
        *"$PACKAGE/"*) break ;;
    esac
    # Older builds name the activity record differently, and the window that has
    # the focus is the same answer by another route.
    resumed=$(adb shell dumpsys window 2>/dev/null \
        | grep -E 'mCurrentFocus|mFocusedApp' | head -2 || true)
    case "$resumed" in
        *"$PACKAGE/"*) break ;;
    esac
    resumed=""
    sleep 1
    left=$((left - 1))
done

pid=$(adb shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r' || true)
crash=$(adb logcat -d -b crash 2>/dev/null || true)

{
    echo "--- in front: $PACKAGE ---"
    echo "${resumed:-<nothing resumed>}"
    echo
    echo "--- still running, pid: ${pid:-<no process>} ---"
    echo
    echo "--- crash buffer ---"
    echo "${crash:-<empty>}"
} | tee "$EVIDENCE_DIR/launcher.txt"

[ -n "$resumed" ] || fail "nothing of $PACKAGE is the resumed activity after ${WAIT_SECONDS}s - the launcher did not start"
[ -n "$pid" ] || fail "$PACKAGE has no process - it started and died"
case "$crash" in
    *"$PACKAGE"*) fail "the crash buffer mentions $PACKAGE" ;;
esac

adb exec-out screencap -p > "$EVIDENCE_DIR/launcher.png" \
    || echo "screencap failed, keeping no screenshot"

echo "OK: $COMPONENT is in front, pid $pid"
