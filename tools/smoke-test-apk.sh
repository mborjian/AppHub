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

# The hand-off is the one door between the file manager's tree and another app,
# and everything behind it is one file: a token this app minted for the row the
# driver picked. The shell is an app that was never handed a URI, so this is the
# one thing about the feature a device can be asked without a driver: a token
# that was never minted has to come back refused, and not as a row of results.
# What the provider is registered as is only evidence here - the manifest of the
# APK itself is checked in the release job.
adb shell dumpsys package "$PACKAGE" \
    | grep -E 'HandoffProvider|\.handoff' \
    | tee "$EVIDENCE_DIR/handoff-provider.txt" \
    || echo "(no hand-off provider line in dumpsys)"

probe=$(adb shell content query \
    --uri "content://${PACKAGE}.handoff/00000000-0000-0000-0000-000000000000/never-handed-out.txt" \
    2>&1 || true)
printf '%s\n' "$probe" | tee "$EVIDENCE_DIR/handoff.txt"
case "$probe" in
    *"Row:"*) fail "the hand-off provider served a token it never minted, to an app it never granted" ;;
esac
echo "OK: the hand-off provider refused an ungranted read of a token it never minted"

# The browser is the one screen that reaches outside the unit, and a release is
# the first time the feature ever meets a page - so it meets one here. The page
# is served by the runner itself and reverse-forwarded, so the check needs no
# network at all: the emulator reaches it as its own localhost, over exactly
# the path a real page takes (the client's scheme gate, the load, the history
# record and the "page done" line the assertion reads). A non-exported activity
# only answers `am start` from root, which the google_apis image allows.
PAGE_DIR="$EVIDENCE_DIR/web"
mkdir -p "$PAGE_DIR"
printf '<!doctype html><html><head><title>AppHub smoke page</title></head><body><h1>smoke</h1></body></html>\n' \
    > "$PAGE_DIR/page.html"

WEB_PORT=${WEB_PORT:-8080}
python3 -m http.server "$WEB_PORT" --bind 127.0.0.1 --directory "$PAGE_DIR" >/dev/null 2>&1 &
WEB_SERVER=$!
cleanup() {
    kill "$WEB_SERVER" 2>/dev/null || true
    adb reverse --remove "tcp:$WEB_PORT" 2>/dev/null || true
}
trap cleanup EXIT

adb root >/dev/null 2>&1 || true
adb wait-for-device
adb reverse "tcp:$WEB_PORT" "tcp:$WEB_PORT" \
    || fail "adb reverse failed - the emulator cannot reach the runner's page server"

adb logcat -c || true
adb shell am start -W -n "$PACKAGE/.BrowserActivity" -d "http://127.0.0.1:$WEB_PORT/page.html" \
    | tee "$EVIDENCE_DIR/browser-start.txt"

page_done=""
left=$WAIT_SECONDS
while [ "$left" -gt 0 ]; do
    page_done=$(adb logcat -d -s AppHub:* 2>/dev/null \
        | grep "page done http://127.0.0.1:$WEB_PORT/page.html" || true)
    [ -n "$page_done" ] && break
    sleep 1
    left=$((left - 1))
done
printf '%s\n' "${page_done:-<no page done line>}" | tee "$EVIDENCE_DIR/browser.txt"
[ -n "$page_done" ] || fail "the browser never finished loading the smoke page"
echo "OK: the browser loaded a page end to end"

adb exec-out screencap -p > "$EVIDENCE_DIR/browser.png" \
    || echo "screencap failed, keeping no browser screenshot"

adb exec-out screencap -p > "$EVIDENCE_DIR/launcher.png" \
    || echo "screencap failed, keeping no screenshot"

echo "OK: $COMPONENT is in front, pid $pid"
