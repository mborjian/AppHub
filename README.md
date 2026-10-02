# App Hub

A small Android app for the head unit: a grid of the apps **you** installed,
with their real icons and names — one tap to open, and where the install holds
the platform's close permission or root, one tap to close — without patching
the factory launcher ever again. It is the "plan B" companion to
`../launcher_tool/`: install it once and it always sees every app, including
the ones you add later.

Two of its own screens stand on the board as tiles while their settings
switches are on: a **file manager** over the unit's storage and a **web
browser** built on the unit's own `WebView`. The visual layer — tokens, type,
colour, motion, EN + FA strings — is specified in
[DESIGN_SYSTEM.md](DESIGN_SYSTEM.md); this file is what the app *does*.

## What it does

- **Grid** — every launcher-able app, icon over one line of text (long names
  ellipsize), re-read on every resume, locale-aware sorting (Persian orders
  correctly); columns follow the screen or a fixed 2–6.
- **Tap** launches the app. **Long press** lifts the board: tiles scale, grow
  a badge and become draggable; the badge (or *Edit the page*) opens the app's
  card — *Open*, *Pin to top*, *App info* / *Close*, *Hide from the main
  page*, *Uninstall*, with *Uninstall* only for user-installed apps and
  *Close* only where a real force stop exists.
- **Dot** on an icon: filled for a live process or a task the system still
  holds, hollow for one only used recently (see *What is open*).
- **Pin** — pinned apps keep the front of the grid, in pin order, across
  restarts.
- **Tools pill** — a floating corner button that is always one tap away:
  *Find an app*, *Settings*, *Edit the page*. The board itself carries no
  header and no search row.
- **Files** tile — unit storage plus attached cards, folders first, size and
  date per row. A tap enters a folder or hands a file to another app; the
  row's menu opens, copies, moves, deletes — and installs an APK.
- **Web** tile — one field that takes an address or a search, history and
  bookmarks as sheets, downloads through the platform's downloader into
  *Downloads* (the folder the file manager draws).

## Settings

Stored in `SharedPreferences` (`Prefs`), reached through the tools pill.

| Setting | What it is |
|---|---|
| Show app names | on / off (icon-only grid) |
| Shortcuts | a screen of its own: every installed app on the left, the main page it makes on the right — tick to show, drag the preview to rearrange, filter field remembered, *All* / *None* in one tap |
| Icon size | 48 / 64 / 80 / 96 dp |
| Adapt to screen | scales everything by the screen's width over a phone's 400dp, capped at ×2; the value shows the factor |
| Icon shape | Original · Rounded · Circular · iPhone · Samsung |
| Grid | auto (from the screen), or a fixed 2–6 columns |
| Sort | A–Z / Z–A; pinned stay first, and choosing a sort drops a hand-made order |
| Include system apps | off by default, so only your own apps are listed |
| File manager · Web browser | one switch per tile; the tile's own card writes the same setting |
| Home screen | opens Android's own chooser for the home role; the row disappears once this app answers the intent, read fresh on every resume |
| Theme · Layout direction | System / Light / Dark · System / RTL / LTR |
| Screen margins | left, right, top, bottom in dp from 0 to half the side — wheel or typed — so the unit's own overlay (rail, clock, climate strip) cannot cover this app's content |
| Usage access | shown only while missing; tapping walks a ladder of real settings screens, because a car ROM may not carry the first one |
| Reset settings | back to the defaults; pins are kept |

## Closing an app

`ForceStop.close()` takes the strongest layer that can run and reports which
one answered: root (`am force-stop`), the platform-signed `forceStopPackage`,
`removeTask`, then `killBackgroundProcesses`. On an install with neither the
permission nor root there is no *Close* row at all — a report of "still open"
is not worth offering.

## Uninstalling an app

Only an app the user installed may be removed, and the check runs again at
the moment of removal. Three layers: root (`pm uninstall`),
`PackageInstaller.uninstall` (straight through for the platform-signed
install), and Android's own uninstaller. The toast says which happened; the
row is amber behind a question with the one red yes in the app, and App Hub
never removes itself.

## What is open

One reader, `OpenApps.read`, feeds every list and dot — sources are asked
strongest first, and a source that cannot answer is skipped rather than
believed. On the unit that source is `ProcTable`, which reads `/proc` itself:
real processes per app, memory from `VmRSS`, *Running* for the one in front
and *Open* for the rest. Where the table is hidden the screen says it cannot
see, rather than drawing "nothing open"; *Recently open* is the usage view,
and the banner says so.

## Home screen

The `HOME` intent-filter only *offers* App Hub in the system's chooser; the
settings row is the only thing that asks, and the answer is read fresh rather
than stored. The factory launcher stays installed and reachable either way.

## Build

JDK 21 (the tracked `gradle/gradle-daemon-jvm.properties` asks for the
JetBrains toolchain), Gradle 9.7.1, AGP 9.4.1, `compileSdk`/`targetSdk` 37
(SDK package `platforms;android-37.0`), build-tools 36.0.0, `minSdk` 28.

```bash
./gradlew assembleDebug            # no flags needed, no network
./gradlew --offline assembleDebug  # same, but also forbids any network access
```

The version is not written in the build file: `versionName` is the newest
reachable `vX.Y.Z` git tag and `versionCode` is packed from it (1.0.0 →
10000, 1.1.0 → 10100). A checkout with no tags builds `1.0.0`.

`dl.google.com` is unreachable from this machine (every request 404s), so the
two AndroidX modules (`appcompat`, `recyclerview`) are pinned to versions the
local Gradle cache holds and resolution falls back to the mirror
`settings.gradle.kts` keeps last — canonical repositories first. A version
that is neither cached nor mirrored cannot be added:

```bash
ls ~/.gradle/caches/modules-2/files-2.1/androidx.core/core   # what is cached
python tools/sdkget.py --list platforms           # what the mirror carries
python tools/sdkget.py "platforms;android-37.0"   # install one; sdkDownload is off
```

Output: `app/build/outputs/apk/debug/app-debug.apk` → `adb install -r <apk>`.

CI: `.github/workflows/android-debug.yml` builds the debug APK and runs the
JVM tests on every push, taking its JDK and SDK pins from
`.github/actions/android-setup/action.yml`; a `vX.Y.Z` tag runs
`.github/workflows/release.yml` instead (see below). For Wi-Fi debugging,
Android Studio's pairing dialog needs an adb **server** of platform-tools
≥ 37.0.0 — `adb version` to check, `python tools/sdkget.py platform-tools`
then `adb kill-server` if it is older.

## Release & system install

`tools/release.py` builds the release APK, zipaligns it and signs it with the
unit's **platform key** (`c8a2e9bc…92ab8`), then verifies the digest:

```bash
python tools/release.py                 # build + sign + verify
python tools/release.py --install       # ... then adb install -r
python tools/release.py --install --reinstall   # after a debug-key build
python tools/release.py --system        # privileged app in /system/priv-app
python tools/release.py --online        # let Gradle use the network
```

Key lookup: `--pk8`/`--pem`, then `PLATFORM_PK8`/`PLATFORM_PEM`, then
`tools/signing.properties`, then the usual `C:/chery-launcher/keys/`.

`--system` runs root → remount → push + sha256 check → install under
`/system/priv-app` → reboot. Read its warning first: a privileged permission
that is missing from `privapp-permissions-*.xml` is only denied in `log`
mode, but can stop the unit booting in `enforce` mode — the script reads
`ro.control_privapp_permissions` and refuses on `enforce` without `--force`.
Installing the platform-signed APK with `--install` is always safe.

### Releasing from a tag

A release is one `vX.Y.Z` tag and nothing else — the tag *is* the version:

```bash
git tag v1.0.0 && git push origin v1.0.0
```

The keystore never enters the repository. It lives in four repository secrets,
decoded into `$RUNNER_TEMP` for that run only:

| Secret | What it holds |
|---|---|
| `RELEASE_KEYSTORE_BASE64` | the `.jks` file, base64 on one line |
| `RELEASE_KEYSTORE_PASSWORD` | the store password |
| `RELEASE_KEY_ALIAS` | the alias inside it |
| `RELEASE_KEY_PASSWORD` | the key's password |

```bash
# from wherever the keystore is kept - do not put it in the repository
base64 -w0 apphub-release.jks > /tmp/apphub.b64
gh secret set RELEASE_KEYSTORE_BASE64 < /tmp/apphub.b64 && rm /tmp/apphub.b64
gh secret set RELEASE_KEYSTORE_PASSWORD   # prompts, so it stays out of the history
gh secret set RELEASE_KEY_ALIAS
gh secret set RELEASE_KEY_PASSWORD
```

The job strips a byte-order mark, CRLF wrapping or `certutil`'s `BEGIN/END`
lines before decoding, opens the keystore with `keytool` before building,
refuses a tag that is not a plain `vX.Y.Z`, checks the APK's own `versionName`
against it, verifies the signature, and requires the file manager's hand-off
provider to be unexported and per-intent. Then — before publishing anything —
it installs the signed APK on a headless emulator and requires
`com.mimskydo.apphub/.MainActivity` to be the resumed activity with a clean
crash buffer, and hands the provider a token nobody minted, which has to come
back refused. Only then does it publish `AppHub-<version>-universal.apk` plus
its `.sha256`. The same checks by hand:

```bash
sh tools/smoke-test-apk.sh app/build/outputs/apk/release/app-release.apk
```

A signed build on this machine uses the same four variables as the workflow
(`APPHUB_KEYSTORE`, `APPHUB_KEYSTORE_PASSWORD`, `APPHUB_KEY_ALIAS`,
`APPHUB_KEY_PASSWORD`). With none set, `assembleRelease` still works and
leaves the APK unsigned, which is what `tools/release.py` expects.

### Updating from the unit itself

The *Updates* row in the settings shows the running build, asks this
repository's `releases/latest`, and installs nothing until all four hold:

| Check | What it rules out |
|---|---|
| signed with the release certificate, pinned as a SHA-256 digest in `Updater.kt` | anything not published from this project |
| the package name is this app's | a file that is not this app at all |
| `versionCode` newer than the installed one | going backwards |
| matching the `.sha256` published beside it | a truncated or substituted transfer |

GitHub's chains are younger than a car's trust store (Sectigo E46, Let's
Encrypt's "YR" generation), so `GithubTls` asks the platform's trust manager
first and hears `res/raw/github_roots.pem` — each anchor checked against the
live chain with `openssl verify` when it was added — only after the platform
refuses. A chain neither store accepts is still refused, and the update path
talks to GitHub's names only, redirects included.

The install has two paths: root copies the file to `/data/local/tmp` and runs
`su -c pm install -r`; without root, Android's own installer does it through
the single-path FileProvider. The run that starts an update dies with the
process, so the next board reads `Prefs.pendingUpdate` and says *Updated to
…* or *… did not install*. Two things worth knowing: a platform-signed build
cannot be updated this way (the row says so instead of downloading), and
rotating the release key needs one manual install on every unit.

## Project layout

```
app_hub/
├── app/src/main/
│   ├── AndroidManifest.xml          permissions + the activities
│   ├── java/com/mimskydo/apphub/
│   │   ├── BaseActivity.kt          theme, direction, margins
│   │   ├── MainActivity.kt          the grid and its chrome
│   │   ├── MainPage.kt              the page's order and cells, shared by grid + preview
│   │   ├── SettingsActivity.kt      every option, rows built in code
│   │   ├── ShortcutsActivity.kt     apps on the left, the page they make on the right
│   │   ├── Prefs.kt                 the settings model (enums + SharedPreferences)
│   │   ├── Sheet.kt                 the rounded dialog behind every menu/picker
│   │   ├── IconShape.kt             icon masking (circle, squircles, rounded)
│   │   ├── AppRepository.kt         PackageManager query (off the UI thread)
│   │   ├── Updater.kt               the release check, the verified download, the install
│   │   ├── AppEntry.kt              cell model + tool tiles + app state
│   │   ├── AppAdapter.kt            grid adapter, icon cache
│   │   ├── PinnedApps.kt            ordered pins in SharedPreferences
│   │   ├── OpenApps.kt              what is open: one reader, ordered sources
│   │   ├── ProcTable.kt             /proc table on the unit
│   │   ├── RunningApps.kt           process-based detection (privileged/root)
│   │   ├── ActivityStats.kt         usage-stats recency + access check
│   │   ├── ForceStop.kt             the close layers + the verified report
│   │   ├── Uninstall.kt             the removal layers, user-installed apps only
│   │   ├── Files.kt                 volumes, folders and the copy/move/delete verbs
│   │   ├── Install.kt               an APK into an installer session, + the status receiver
│   │   ├── Handoff.kt               one file out to another app: token, provider, four endings
│   │   ├── FileManagerActivity.kt   the file manager screen
│   │   ├── Web.kt                   the browser's rules: address-or-search, memory, downloads
│   │   ├── BrowserActivity.kt       the browser screen: toolbar, WebView, history + bookmarks
│   │   └── RootShell.kt             optional su, probes `-c` and `<uid>` forms
│   └── res/                         layouts, drawables, theme, EN + FA strings
├── tools/release.py                 platform-signed release + optional install
├── tools/smoke-test-apk.sh          the release job's checks, usable by hand
├── build.gradle.kts                 AGP 9.4.1 (built-in Kotlin, no Kotlin plugin)
├── .github/actions/android-setup/   the JDK + SDK pins, shared by both workflows
├── .github/workflows/               debug APK + JVM tests on push, release on a tag
└── gradle/wrapper/                  Gradle 9.7.1 wrapper
```

Dependencies are deliberately tiny — `androidx.appcompat` and
`androidx.recyclerview`, plus `junit` for the JVM tests. No Material, no
Compose, no icon packs; the app icon itself is a vector.

## Honest limitations

- System apps are excluded by default; *Include system apps* lists them.
- Uninstall is offered only for apps the user installed, and App Hub never
  removes itself.
- The file manager needs full storage access (*All files access* from
  Android 11 on); without it the screen says so instead of reading a folder as
  empty. `Android/data` and `Android/obb` cannot be read from Android 11 on,
  grant or no grant.
- No multi-select, no *new folder*, no built-in viewer or editor: *Open with*
  hands the file to another app by `content://` URI, so an app that only
  opens paths will not be in the list.
- The browser has no tabs, no private mode, no password store and no upload;
  an SSL error always stops the load (there is no *proceed anyway* anywhere
  in this app), and plain `http` stays allowed because the unit's own router
  and dashcam pages have no certificate.
- History and bookmarks (200 visits kept) live on this install and go with
  it; clearing the history leaves the bookmarks alone.
- A real close and a real task view need the platform-signed install, a
  priv-app install or root: Android 14+ restricts `killBackgroundProcesses()`
  to the caller's own processes, so an ordinary install shows *Recently open*
  and draws no *Close* row rather than pretending.
- No device is attached to this machine. What verified all of this is the
  build, the permissions and resources read back out of the built APK, the
  JVM tests, and glyph sheets drawn by hand; the first real copy, close,
  uninstall, download and page load on the unit are the unit's.
