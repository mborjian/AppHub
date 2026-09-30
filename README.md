# App Hub

A tiny Android app that lists the apps **you** installed on the head unit, with
their real icons and names, so you can open them with one tap and close them
again — without patching the factory launcher ever again.

This is the "plan B" companion to `../launcher_tool/`: instead of adding a
shortcut per app into the launcher's icon array, you install this once and it
always sees every app, including ones you install later.

The surface itself is specified in [DESIGN_SYSTEM.md](DESIGN_SYSTEM.md) - the
token layer (spacing, type, colour, radii, motion) that this README's settings
are drawn with, the reasoning behind it for a 10" panel in a moving vehicle, and
the redesign it is the foundation for. Everything below is what the app *does*;
that document is what it *looks like* and why.

## What it does

| | |
|---|---|
| **Grid** | every launcher-able app, icon on top and one line of text under it (long names ellipsize), re-read on every resume, sorted with a locale-aware collator (Persian names order correctly). Column count follows the screen, or is fixed in the settings |
| **Tap** | launches that app (exact `package` + activity, with a `getLaunchIntentForPackage` fallback) |
| **Long press** | the board **lifts**: every tile scales slightly and grows a badge in its icon's corner, and the page's tiles become draggable. The badge (or a tap through the tools sheet's *Edit the page*) opens one app's card: **Open**, **Window margins**, **Pin to top**, **App info** / **Close**, **Hide from the main page** - grouped, with a hairline between the three kinds of verb |
| **Dot** | a small dot on the icon of an app that is open: filled for a process or a task the system still holds, hollow for one that was only used recently (see [What is open, and how App Hub knows](#what-is-open-and-how-app-hub-knows)) |
| **Pin** | long press → *Pin to top*; pinned apps keep their place at the front, in the order you pinned them, and the choice survives restarts |
| **Window margins** | long press → *Window margins*: give one app its own rectangle (**left / right / top / bottom**, in pixels) and it is moved inside it the moment it comes to the front - however it was started, from this grid or from the vehicle's own launcher. See [App window margins](#app-window-margins-one-rectangle-per-app) |
| **Task manager** | the open apps, one row each with its own **Close**, and each row saying how it got on the list: *Running* (a process), *Open* (a task the system still holds) or *Recently open* (the usage view). Where the install cannot read the task list the screen says so, and where a close did not work it says that too, because in both cases the list is the news (see [What is open, and how App Hub knows](#what-is-open-and-how-app-hub-knows)) |
| **Tools pill** | a floating button in the bottom corner, always one tap away whatever the grid is scrolled to: **Find an app**, **Settings**, **Edit the page**, **Task manager**, **Close all**, **Close App Hub** - the last two behind a confirmation, the last one being the only thing the close layers cannot do for the app you are looking at |

There is no header, no search row and no hint strip: the board is only the grid,
and the two things that are *not* apps - the tools and the search - float over it
as a pill in the corner. What used to end the grid (a gear cell and a red *Close*
cell) is inside that pill now, because a red "quit App Hub" sitting among the app
icons is one mis-tap away from the wrong app, and reaching the settings by
scrolling past every installed app is a long walk on a car screen. The settings
screens themselves do have a header - a back target and a title - since there the
title is what says which list you are looking at.

## Settings

A **Tools** pill floating over the board opens a sheet whose *Settings* row leads
to it; every choice is stored in `SharedPreferences` (`Prefs`).

| Setting | Options |
|---|---|
| Show app names | on / off (off gives an icon-only grid) |
| Shortcuts | a **screen of its own with two panes** (see below): the **list of every installed app** on the left, the **main page** it adds up to on the right. Ticked = that app has a cell, unticked = it does not; the sheet-free list stays put while you tick and the preview redraws as it changes. **All apps** / **None** do the whole page in one tap. A **filter field** narrows the list as you type (name or package, spaces and case ignored) and is **remembered**, so reopening lands where the last session left off; an ✕ clears it. Dragging a tile in the preview **rearranges the page for real**. Stored as the *hidden* ones, so an app installed later appears on its own and uninstalling one leaves nothing to clean up. Unticking is not uninstalling: the app is only left out of the grid, and the long-press menu's *Hide from the main page* is the same switch from the grid. The row's value counts what is left (`All 19 apps`, `18 of 19 apps`) |
| Icon size | Small 48dp · Medium 64dp · Large 80dp · Extra large 96dp — the grid fits fewer columns as icons grow |
| Adapt to screen | a switch, not a fifth icon size: everything this app draws — icons, text, spacing, the rows of every screen — is scaled by the screen's own width over a phone's (400dp), capped at **×2**. The row's right-hand value says the factor it is drawing at, `×1` to `×2` |
| Icon shape | Original · Rounded · Circular · iPhone style · Samsung style |
| Grid | Auto (computed from the screen) or a fixed 2–6 columns |
| Sort | Name A–Z / Z–A; pinned apps always stay first. Choosing one re-sorts the page, so it drops a hand-arranged order (and says so in the README rather than in the UI) |
| Include system apps | off by default, so only your own apps are listed |
| Theme | System / Light / Dark |
| Layout direction | System / Right-to-left / Left-to-right |
| Screen margins | **left / right / top / bottom**, each dialled on a wheel from 0 to half that side of the screen (512 dp across on this unit's 1024dp width, 284 dp down in its 568dp-tall window) — any whole value, not a list of steps — or **typed on the number keyboard** from the sheet's *Type the value* row. Moves this app's content away from the screen edges so a launcher overlay (shortcut rail, clock, climate strip) cannot cover it. Applied to the grid **and** to this settings screen itself, so the screen follows the value while it is still being chosen |
| App window margins | a **screen of its own** (the grid's own app list, one row per app): a switch on each row turns that app's profile on or off, and the row opens its four numbers — left / right / top / bottom, in **pixels**, dialled on the same wheel as the screen margins or typed on the same keyboard. An app with a profile is moved into its rectangle whenever it comes to the front, from anywhere. The row's value reads `200 / 20 / 0 / 0 px`, or `Off` |
| Usage access | shown **only while it is missing**; tapping it opens the system screen. Useful but not the same thing as the task list: it shows what was used recently, and on a device where it cannot be switched on at all (the unit) `adb shell appops set com.mimskydo.apphub GET_USAGE_STATS allow` does the same job from a PC |
| Close all apps | closes the apps that are open where an exact view knows them, and every app in the list where none does - because on the unit this is a real force stop now, not a call the platform ignored |
| Reset settings | back to the defaults; pinned apps are kept |
| Back | the last row of the list; leaves the settings screen (same as the arrow in the header) |

Two implementation notes worth keeping:

* **Shapes need the layers.** An `AdaptiveIconDrawable` masks itself and draws
  its art inset to the middle two thirds of its bounds, so clipping *around* it
  changes nothing — every shape came out pixel-identical. `IconMasker` draws the
  background and foreground layers separately instead, so the tile really fills
  the cell and the chosen shape is visible.
* **Direction is not locale.** Forcing RTL sets the `screenLayout` direction
  bits of the activity `Configuration` and leaves `locale` alone, so the grid
  mirrors while the strings keep following the device language.
* **Margins are physical, and there is one implementation.** `Left` / `Right`
  are screen edges, not `start`/`end`: the thing hiding part of the screen is a
  rail on one side of the display, and it does not swap sides when the layout
  direction is flipped. `BaseActivity.applyMargins()` pads the activity's
  content root, and both screens call it - the settings screen calls it again
  on every change, which is what makes the preview live. The margin band shows
  the window background, which is the same colour as the layouts, so no seam
  appears as the values grow.
* **The margins are a wheel, not a list** (`Sheet.showNumber`, a `NumberPicker`
  inside the usual rounded card). A value in dp has no useful set of round steps
  to offer, so every whole number in range is reachable — by dragging the
  column, by the two buttons, or by typing it in. The range comes from the live
  configuration rather than a constant (`Prefs.maxMargin` = half the width for
  left/right, half the height for top/bottom), so the bound is the screen the app
  is actually running on and it follows the unit's density. Descendant focus is
  blocked so a stray tap cannot turn the wheel into a text field, and the value
  is written on every turn — the sheet stays open, which is what makes it a
  preview.
* **The wheel has a keyboard beside it.** `Sheet.showNumber` draws the rotator;
  `Sheet.showInput` is the same value typed, opened from the sheet's *Type the
  value* row. The field is numeric, comes up focused with its content selected
  and the keyboard raised, and Enter is accepted three ways because keyboards
  differ — the IME's Done action, a bare Enter on a single-line field (which
  arrives as `IME_NULL`), and a hardware `KEYCODE_ENTER` — and all of them close
  the sheet. Junk is not a value: anything that does not parse leaves the stored
  one alone. Values are clamped by `Prefs.setMargin`, so `9999` lands on the
  limit rather than being refused.
* **Auto columns follow the margins**, because the grid has less width to work
  with once an edge is moved in (`Prefs.columnCount` subtracts left + right).
  A fixed column count is never overridden.
* **The adaptation is one lever, not one per dimension** (`Prefs.layoutContext`,
  `Prefs.adaptDensity`). What it changes is the *dp space* the app is inflated
  in: the configuration's density is multiplied, and because a dp, an sp (the
  scaled density follows the density), a padding and a cell width are all
  written in that same space, the icons, the text and the spacing grow together
  and nothing can be left behind at the old size. Six hand-written multipliers
  would have been six places to forget.
* **The rule is the screen's width in dp over a phone's** (`screenScale`,
  400dp, capped at ×2). The app is written in dp, and a dp is a fixed fraction
  of nothing: on the unit's 1024dp-wide panel the 64dp icon that fills a third
  of a phone covers a sixteenth of the screen, seen from further away than any
  phone is. Normalising the canvas means an icon keeps the *share of the
  display* it would have on a phone - and the cap is there because a 4K panel
  should not be turned into a phone. The width is read from the application's
  configuration, never from a screen's, or a second pass would compound the
  first.
* **The screen's own size in dp is recomputed with the density**
  (`adaptDensity`). It is a function of the density and the framework does not
  recompute it for a configuration an app overrides - the grid would still lay
  its cells out for the old, much wider screen and draw eight columns into the
  room of four. The small/normal/large/xlarge class is brought in line with it
  for the same reason.
* **A density cannot be changed on a screen that already exists**, so
  `BaseActivity` remembers the scale it was built at and builds the screen again
  if the setting no longer matches it (compare in `onResume`). That is the
  ordinary case, not an edge one: the grid is sitting behind the settings screen
  while the switch is turned on - and it is also what makes the switch's own
  screen show its result, since it is recreated at the moment it is flipped.
* **The margins stay physical, and that is deliberate.** A screen margin keeps
  an overlay the *unit* draws - a shortcut rail, a clock, a climate strip - off
  this app's content, and that overlay does not grow when the icons do. So
  `BaseActivity.applyMargins` converts the stored dp with the unit's own density
  (`Prefs.deviceDensity`) rather than the adapted one, and a value keeps meaning
  the same pixels whichever way the switch is set. The app window margins are
  already in pixels and are untouched by all of this.
* **Shortcuts is a screen, not a sheet** (`ShortcutsActivity`). A sheet answers a
  question and closes; this one is a place where two things are watched at once -
  tick an app, watch it arrive on the page - so the two halves sit side by side.
  The sheet machinery is for menus and one-value pickers again, and the rounded
  card is untouched by this screen. `Sheet.show` still closes on a tap because its
  rows are one choice among several.
* **The app list is the grid's own list.** The list is read with `AppRepository`
  on a background thread, exactly like the grid, so it offers what the grid could
  show - including the factory apps once *Include system apps* is on, which is why
  that switch drops the cached list and reads it again. Each row carries the app's
  own icon in its own colours, where every row of a sheet is a monochrome glyph
  drawn in the secondary text colour.
* **The list is filtered, not rebuilt** (`ShortcutAdapter.filter`). A field above
  the rows hides the rows that do not match instead of replacing them, so a tick
  survives any amount of filtering (`squash()` compares letters and digits only,
  lower-cased, so `apphub` finds *App Hub* and `organicmaps` finds
  `app.organicmaps`; the empty list says *No app matches* rather than looking
  broken). The field keeps what was typed, because working through the apps that
  start with `ca` is a session, not a keystroke, and the ✕ is one tap away. The
  count in the header counts the *page*, not the filter, so a narrowed list never
  reads as a page that lost its apps.
* **The filter is the only thing on that screen that is remembered.** It is a
  stored value like a tick, so typing writes it through (`Prefs.shortcutsFilter`)
  rather than saving it at some later moment. It is in *Reset settings* with the
  rest.
* **All / None apply to the whole list, not to what is showing.** Filtering hides
  rows; it never decides what the page holds, so *None* with `cam` typed still
  clears the page rather than clearing the two matches. `Prefs.setHiddenAll`
  writes the set once.
* **One implementation of the main page** (`MainPage.arrange`, `MainPage.cells`).
  The grid and the preview beside it both have to agree about what the page is to
  the pixel, so neither arranges it itself. `arrange` puts an explicit page order
  first if there is one, and otherwise pins the pinned apps to the front exactly
  as the grid always has; `cells` is the apps plus the gear and *Close*, with the
  empty-state cell spanning the row when there is nothing to show.
* **A drag writes the page's own order** (`Prefs.pageOrder`, package names in a
  newline-joined string, the way `PinnedApps` stores its list). It is written when
  the tile is let go, and from then on the page follows it instead of the name
  sort. Anything with no place in it - an app installed later, or one ticked back
  on - comes after the arranged tiles and can simply be dragged in. Package names
  rather than positions mean an app that is uninstalled or hidden and comes back
  lands where it was left.
* **A sort and an arrangement cannot both decide**, so choosing a sort drops the
  arrangement (`SettingsActivity`, the Sort row). The alternative - leaving the
  arrangement to win silently - would make that row look broken, and the row's
  stored value would then describe something the page does not do. *Pin to top*
  has the same problem from the other side: with an arrangement in place the pin
  ranking decides nothing, so pinning moves the app to the front of the
  arrangement instead of quietly doing nothing.
* **The preview measures its own pane.** It lays the page out with the same rule
  the grid uses, at the width it has: `Prefs.columnCount(paneWidth)`, the user's
  icon size - capped to what a column of that pane can hold, so a fixed 6 columns
  at Extra large shrinks the tiles rather than overflowing - and the same icon
  shape and *Show app names* as the grid. A pane half the width of the screen
  therefore breaks the page into fewer columns than the page does; what is
  identical is what the drag is about, which is the order and the cells.
* **In the preview nothing is tappable.** No cell launches anything, the gear and
  *Close* lead nowhere, and no cell ripples: the hold belongs to the drag, and a
  ripple with nothing behind it promises an action the preview does not have
  (`AppAdapter` takes every callback as optional for exactly this).
* **Only the app cells move.** ItemTouchHelper refuses to pick up the gear or
  *Close* and refuses to drop a tile on them, so the page's two fixed cells stay
  where they are however the tiles are shuffled.
* **A sheet longer than the screen scrolls, and the row that closes it does not.**
  The rows live in a `ScrollView` (`dialog_sheet.xml`) whose height is capped the
  moment the sheet is measured (`Sheet.clampRows`, `MAX_ROWS_HEIGHT`), with the row
  that closes the sheet in a footer below it, so a long list never hides the way
  out at the bottom of it.
* **The keyboard takes room from the rows, not from the card.** A dialog window is
  not shrunk around the IME, so a sheet the height of the screen used to end up
  half under the keypad — with the field visible and the rows and the OK row gone.
  `Sheet.clampRows` now reads the IME inset (`ViewCompat` + `WindowInsetsCompat`),
  caps the rows to the space the keypad leaves, and moves the card to the top of
  the screen while the keyboard is up (`Gravity.TOP`, back to `CENTER` when it
  closes, gated on `insets.isVisible(ime)` so the two states cannot ping-pong).
  The shortcuts screen has the same problem from the other side and solves it the
  plain way: it asks for `adjustResize`, so the panes shrink and the field, the
  rows and the preview all stay above the keys.
* **What is hidden is stored, not what is shown.** `Prefs` keeps the *hidden*
  package names, so a later install is on the main page without anyone ticking it
  and an uninstall leaves no stale entry. It is part of *Reset settings*, and it is
  applied in `MainActivity.render()` rather than in the repository - so the pinned
  order and *Close all apps* still know the app exists, and the latter still only
  closes what the screen is showing.
* **A switch is a view of what is stored, not a second copy of it.** All rows
  share one `settingSwitch` id, so Android saves a single position for the
  screen and restores it into *every* switch on a recreation — and the theme,
  direction and **Reset** rows all recreate this screen. That used to put the
  just-cleared values straight back after a Reset, which is how it was found
  (with `run-as … cat shared_prefs/apphub.xml`). `onRestoreInstanceState` now runs
  inside the same guard as `refresh()`, so a restored position is shown and never
  written, and the listener additionally ignores any change that already matches
  storage.

## Closing an app: four layers

`ForceStop.close()` tries the strongest mechanism available and always falls
back, so the feature degrades instead of breaking — and it now reports what it
*got*, not what it tried, because three of the four layers can be refused:

1. **root** — `su -c am force-stop <pkg>`. Works where `su` lets the app's uid
   in (Magisk-style su does; AOSP's `su` only allows root and shell, so a plain
   app is refused there and we move on). The unit's `su` is AOSP's, so this
   layer never answers there.
2. **`FORCE_STOP_PACKAGES`** — the hidden `ActivityManager.forceStopPackage()`,
   which needs `android.permission.FORCE_STOP_PACKAGES`: `signature|privileged`.
   A platform-signed install holds it *by signature*, with no root at runtime
   and no `/system` write; `/system/priv-app` is the other way in — see
   [Release & system install](#release--system-install).
3. **`REMOVE_TASKS`** — the hidden `ActivityManager.removeTask(taskId)`, which
   closes one task the way the recents screen does. Only reachable where that
   permission is granted (the recents role holds it), so in practice this lives
   next to layer 2; it is also the layer that keeps `Close` able to take a
   *window* away where a force stop is not allowed.
4. **`killBackgroundProcesses()`** — and the honest size of it. This used to be
   described here as "enough for the hub's main flow". It is not any more:
   **since Android 14 the platform can kill only the caller's own background
   processes through this call**, whichever app makes it. Measured on an
   Android 15 emulator as a controlled pair — the same cached app, killed by
   `adb shell am kill` and *not* killed by App Hub's own call a moment before —
   and documented in Android 14's behaviour changes ("the API can kill only the
   background processes of your own app"). So the layer still runs where it can
   still do something (Android 13 and older, i.e. the unit), and from Android 14
   the app stops claiming a close it cannot make.

A foreground process can never be killed by itself in *any* layer — that is a
platform guarantee, not a gap in this app.

The report follows the same rule. The layer that answered is returned, and
where the source that listed the app is exact (a task, a process) the list is
read back afterwards and the toast is the *verified* one: `closed`, or **still
open** when the layers did not reach it. One line goes to logcat either way,
because a car is a place where the answer has to be readable after the fact:

```bash
adb logcat -s AppHub        # close <pkg> task=<id> layer=<method> stillOpen=<bool> source=<source>
```

## What is open, and how App Hub knows

Knowing what is "open" is one question with four answers, best first, and the
screen always says which one it is showing. The reader is `OpenApps`, and the
board's dots and the task manager share it — they cannot disagree about which
apps exist.

| | Source | Says what | Needs |
|---|---|---|---|
| 1 | `ActivityManager.getRecentTasks()` | **exactly what is open**: every task the system still holds, with its id | `REAL_GET_TASKS` — `signature\|privileged`, so a platform-signed install or `/system/priv-app` |
| 2 | `getRunningAppProcesses()` | apps with a **process** right now | the same permission, or root |
| 3 | `su -c ps -A -o NAME` | the same, without asking the framework | a usable `su` |
| 4 | `UsageStats` events | apps brought to the front in the last 30 minutes | the one-time *Usage access* opt-in |

Three things about that table are worth keeping.

**Why layer 1 is the one that matters.** `getRecentTasks()` is deprecated, and
it is still the only non-root door to the task list: the replacement its
deprecation note points at is a launcher-side API (`LauncherApps`), which needs
the default-launcher *role* rather than a permission. Deprecated is not the same
as removed — and this is the difference between a list of apps that are open and
a list of apps that were opened.

**A source that cannot answer is detected, not believed.** An app that is
refused sees only its own processes, and one that may not read tasks sees at
least its own task; both would otherwise read as "nothing is open on this
device". So each cheap source has to prove itself — a foreign package for the
process table, a task at all (its own included) for the recents list — and a
source that cannot prove anything is skipped rather than reported as an empty
device. Where no source can answer at all, the board simply shows no dots and
the task manager says *cannot see open apps* and names the two ways out.

**Usage access is a view of the past, and is labelled as one.**
`ACTIVITY_RESUMED` is the whole signal. The first version of this removed a
package again on `ACTIVITY_PAUSED`, which reads like "it left" but is not: an
app is paused *every* time it is covered — including by this hub — so with the
hub in front the answer was always empty, and a phone with usage access was told
nothing was open while six apps had a task each. `PAUSED` and `STOPPED` arrive
both when an app is left and when its task is taken away, so neither can decide
that it is gone; only "was resumed recently" is left. Those rows are drawn as
*Recently open* (a hollow dot on the board), and the task manager puts a banner
over them saying what they are.

The window is 30 minutes (`OpenApps.RECENT_WINDOW_MILLIS`) rather than the 15
this started with: it is a filter against ancient history, not a stopwatch.

## Making the open-apps feature work

A plain **debug** install can never read the task list (the permission is
`signature|privileged`, and `adb shell pm grant` refuses it — verified on an
Android 15 emulator: *"not a changeable permission type"*; `FORCE_STOP_PACKAGES`
is refused the same way, and `REMOVE_TASKS` is *"managed by role"*). What works,
per device:

**The unit (Android 10, platform key in hand).** Install the platform-signed
build. It costs no root at runtime and writes nothing to `/system`:

```bash
python tools/release.py --install --reinstall
```

The signature match is what grants `REAL_GET_TASKS` and `FORCE_STOP_PACKAGES`,
and it is not a guess: the factory launcher shares `android.uid.system`, which
the platform only allows for an APK signed with the framework's own certificate
— the same `c8a2e9bc…92ab8` this repository already signs with. Check it after
installing:

```bash
adb shell dumpsys package com.mimskydo.apphub | grep -E "REAL_GET_TASKS|FORCE_STOP_PACKAGES"
```

Both lines must read `granted=true`. With them the list is exact and *Close* is
a real force stop.

**Before any of that: try the grant.** The unit is `userdebug`
(`ro.debuggable=1`), and a build that is willing to hand a signature permission
to `pm` would save the reinstall entirely:

```bash
adb shell pm grant com.mimskydo.apphub android.permission.REAL_GET_TASKS
adb shell pm grant com.mimskydo.apphub android.permission.FORCE_STOP_PACKAGES
```

On the Android 15 emulator used here both are refused (*"is not a changeable
permission type"*) because the protection level carries no `development` flag —
the grant is the cheap experiment, and the platform-signed install is the one
that is known to work.

**The unit, still on a debug build.** One adb command opens the fallback view
without any Settings screen on the car (which the unit does not offer):

```bash
adb shell appops set com.mimskydo.apphub GET_USAGE_STATS allow
```

That lists *recently open* apps, and on Android 10 `killBackgroundProcesses()`
still closes their background processes — so the close button does something
there even unsigned. It cannot force-stop and it cannot see a task that was
never brought to the front in the window.

**A phone.** Android leaves two options and no third: root (Magisk-style `su`,
which the app uses when it finds one), or an install the platform is willing to
trust. Since Android 14, `killBackgroundProcesses()` reaches only the caller's
own processes, so on a modern unrooted phone **nothing** can close another app —
not this app, not `adb grant`, not a task-killer. Usage access still gives the
list of what was used recently, and the phone's own recents screen is the tool
that closes. The app says so rather than pretending, which is the point.

**A privileged install.** `/system/priv-app` is the other way to hold the same
permissions: `python tools/release.py --system`. `--system` refuses on a unit
whose `ro.control_privapp_permissions` is `enforce` unless the permission is
allow-listed (`/system/etc/permissions/privapp-permissions-*.xml`), because an
unlisted privileged permission can stop the unit from booting. The normal
platform-signed install avoids that path entirely and is the recommended one.

## App window margins: one rectangle per app

Some apps do not fit the unit's screen: a phone-shaped app on a 1024×600 landscape display, a
screen whose controls end up under the factory launcher's own overlay. This feature gives one
app its own rectangle, and the app runs **inside** it:

```text
Left: 200 px   ┌──────────────────────────────┐   Right: 20 px
Top: 0 px      │       the app's window       │   Bottom: 0 px
               └──────────────────────────────┘
```

The four numbers are **pixels**, not dp, unlike the screen margins: what is being set is not
padding but the bounds of a window, and the window manager measures those in pixels - which is
also how the example that started this feature is written.

**Where it lives.** The apps are the ones the grid shows: the same `AppRepository` query, the
same *Include system apps* switch, the same sort, the same names and icons. There is one idea of
"the installed apps" in this app, not two. A profile is one line in `SharedPreferences`
(`window_profiles`, keyed by package) holding the switch and the four numbers; *Reset settings*
does not touch it, exactly like the pins.

**What applies it.** A profile is deliberately *not* attached to a launch: this app never sees
the intent the vehicle's launcher sends, and an app started from it has to be moved as well. So
`WindowMarginService` keeps an eye on which app is **in front** and, the moment a configured one
appears, moves that app's task once:

```text
su -c "dumpsys activity activities | grep …"    which app is in front, and its task id
su -c "am stack list"                          that task's current bounds, to compare against
su -c "am task resize <id> <l> <t> <r> <b>"    the one task - and nothing else
su -c "am stack list"                          read it back: did the window actually move?
```

The last step is what makes the feature honest. `am task resize` is **the narrowest mechanism
Android has** for this - one task's bounds, no global state - but the window manager only honours
it for a task in a resizing-capable windowing mode (freeform, or multi-window). A fullscreen task
is pinned to the display: the command returns without complaint and the window does not move. So
the service reads the task back and compares - bounds that match (or at least changed) are
*Applied*, bounds that did not move are *Refused* - and the screen says so rather than pretending:
*"…opens fullscreen, and this unit only resizes freeform or multi-window tasks - nothing was
moved."* The same read-back is the periodic drift check: the app in front has its rectangle
re-read every few seconds and put back if something moved it.

**What it deliberately never does.** No `wm size`, no `wm density`, no `wm overscan`, no
`settings put`, no `am stack move-task`, no file on the system partition, nothing that changes the
unit for everybody else: when the window cannot be controlled, the app says so and stops. It also
refuses to touch three packages whatever the list offers - App Hub itself, the launcher the unit
is actually running (resolved at runtime, because the factory app is not the only one a unit may
be running instead), and SystemUI - and those rows are inert and say so.

**Why a foreground service, and why it costs nothing when unused.** "This app came to the front"
has to be noticed while *other* apps are on screen, which is what a foreground service is for. It
is started when the first profile is switched on, stops itself the moment the last one is
switched off (or the master switch is), and is started again after a reboot (`BOOT_COMPLETED`),
because the launcher - not App Hub - is the first thing on screen after a boot. With no profile
enabled there is no service, no poll and no notification at all.

The poll is paced rather than tight, because each look at the system is a root command: the app
in front is read about once a second, a change of app is followed up quickly, and the bounds of
the task that is already in front are read back only now and then.

## Build

Requirements: JDK 21 — the tracked `gradle/gradle-daemon-jvm.properties` asks the
wrapper for a JetBrains 21 toolchain, which on this machine is
`C:/Users/mahdi/.jdks/jbr-21.0.11`. The build itself is Gradle 9.7.1 with AGP
9.4.1 (`compileSdk` 37 → SDK package `platforms;android-37.0`) and build-tools
36.0.0.

```bash
./gradlew assembleDebug          # works with no flags, and no network
./gradlew --offline assembleDebug   # same, but also forbids any network access
```

No build flags are needed. `app/build.gradle.kts` pins the two AndroidX modules
(`appcompat:1.8.0`, `recyclerview:1.4.0`) to versions the local Gradle cache
holds, because `dl.google.com` is unreachable from this machine (every request
404s, even for artifacts that do exist); resolution goes through the Aliyun
mirror that `settings.gradle.kts` puts first. A version that is neither cached
nor on that mirror cannot be added — the build dies with a wall of
`Could not find …` task failures instead.

**Adding a new dependency:** check what versions the cache actually holds, then
pin that version in the same block, or the build will fail the same way.

```bash
ls ~/.gradle/caches/modules-2/files-2.1/androidx.core/core
```

The same block applies to SDK packages: `gradle.properties` turns
`android.builder.sdkDownload` off, and a missing package is installed from the
mirror with `tools/sdkget.py`:

```bash
python tools/sdkget.py --list platforms           # what the mirror carries
python tools/sdkget.py "platforms;android-37.0"   # install / update one
```

Output: `app/build/outputs/apk/debug/app-debug.apk` → `adb install -r <apk>`.

`.github/workflows/android-debug.yml` runs this same build on every push and
pull request, so a dependency that stops resolving cannot slip through: it
installs JDK 21 (the JetBrains build the wrapper asks for) plus the SDK
packages above, then builds the debug APK and uploads it as an artifact. A
`vX.Y.Z` tag runs `.github/workflows/release.yml` instead; see *Releasing from a
tag* below.

**Debugging over Wi-Fi.** Android Studio's *Pair devices using Wi-Fi* dialog
refuses to open while the adb server is older than platform-tools 37.0.0: it
reports *"ADB Version Too Low"* even though `adb pair` itself would work. Check
what the server says, and update it from the mirror if it is older:

```bash
adb version                              # "Version 37.0.1-…" is fine
python tools/sdkget.py platform-tools    # installs the current one; then: adb kill-server
```

Pair an Android 11+ device with `adb pair <ip>:<pair-port>` (code from
Developer options → Wireless debugging) and then `adb connect <ip>:<port>` —
pairing and connecting use different ports. Devices older than 11 have no
pairing UI and need one USB session of `adb tcpip 5555` first.

## Release & system install

`tools/release.py` builds the release APK, zipaligns it and signs it with the
unit's **platform key** (the certificate every factory app uses,
`c8a2e9bc…92ab8`), then verifies the digest:

```bash
python tools/release.py                 # build + sign + verify only
python tools/release.py --install       # ... then adb install -r
python tools/release.py --install --reinstall   # after a debug-key build
python tools/release.py --system        # privileged app in /system/priv-app
python tools/release.py --online        # let Gradle use the network
```

Without `--online` the release build runs `gradle assembleRelease --offline`;
the version pins above are always in effect either way.

Key lookup: `--pk8`/`--pem`, then `PLATFORM_PK8`/`PLATFORM_PEM`, then
`tools/signing.properties`, then the usual `C:/chery-launcher/keys/`.

`--system` runs the same sequence the launcher rollouts use: `adb root` →
`adb remount` → confirm `/` is `rw` → push + sha256 check → uninstall the
user-data copy → `mkdir /system/priv-app/AppHub` → `cp` → `chown root:root` →
`chmod 0644` → `restorecon` → verify the installed hash → reboot.

> **Read before using `--system`.** A privileged app that requests a privileged
> permission which is *not* in `/system/etc/permissions/privapp-permissions-*.xml`
> is merely denied it in `log` mode, but can stop the unit from booting in
> `enforce` mode. The script reads `ro.control_privapp_permissions` and refuses
> to continue on `enforce` unless you pass `--force`. Installing the
> platform-signed APK normally (`--install`) is always safe and gives you
> layers 1 and 3; only layer 2 needs `/system/priv-app`.

### Releasing from a tag

`.github/workflows/release.yml` runs when a `vX.Y.Z` tag is pushed and
publishes a signed APK on the GitHub release: **one universal build**, because
the app ships no native libraries and no density-specific resources, so a split
by ABI or screen density could not leave anything out.

The keystore never enters the repository. It lives in four repository secrets,
and the job decodes it into `$RUNNER_TEMP` for that run only:

| Secret | What it holds |
| --- | --- |
| `RELEASE_KEYSTORE_BASE64` | the `.jks` file, base64-encoded on one line |
| `RELEASE_KEYSTORE_PASSWORD` | the store password |
| `RELEASE_KEY_ALIAS` | the alias inside it |
| `RELEASE_KEY_PASSWORD` | the key's password (often the store one) |

```bash
# from wherever the keystore is kept - do not put it in the repository
base64 -w0 apphub-release.jks > /tmp/apphub.b64
gh secret set RELEASE_KEYSTORE_BASE64 < /tmp/apphub.b64 && rm /tmp/apphub.b64
gh secret set RELEASE_KEYSTORE_PASSWORD   # prompts, so it stays out of the history
gh secret set RELEASE_KEY_ALIAS
gh secret set RELEASE_KEY_PASSWORD
```

`*.jks` and `*.keystore` are ignored, but keeping the file outside the working
tree is still the better habit.

A release is then one tag, and the tag has to match what the APK will say:

```bash
# bump versionName and versionCode in app/build.gradle.kts first
git tag v2.0.0 && git push origin v2.0.0
```

The job refuses to build when the tag and `versionName` disagree - the current
`2.0` counts as `2.0.0`, so `v2.0.0` is the first tag that can match - and it
checks the signature with `apksigner verify` before publishing
`AppHub-<version>-universal.apk` plus its `.sha256`.

A signed build on this machine uses the same four variables as the workflow:

```bash
APPHUB_KEYSTORE=C:/keys/apphub-release.jks APPHUB_KEYSTORE_PASSWORD=... \
APPHUB_KEY_ALIAS=... APPHUB_KEY_PASSWORD=... ./gradlew assembleRelease
```

With none of them set, `assembleRelease` still works and leaves the APK
unsigned, which is what `tools/release.py` expects for the platform-key path.

## Project layout

```
app_hub/
├── app/src/main/
│   ├── AndroidManifest.xml          permissions + the single activity
│   ├── java/com/mimskydo/apphub/
│   │   ├── BaseActivity.kt          applies theme + forced direction before views
│   │   ├── MainActivity.kt          the grid (apps + the settings cell)
│   │   ├── MainPage.kt              the page's order and cells, shared by grid + preview
│   │   ├── SettingsActivity.kt      every option, rows built in code
│   │   ├── ShortcutsActivity.kt     apps on the left, the page they make on the right
│   │   ├── Prefs.kt                 the settings model (enums + SharedPreferences)
│   │   ├── Sheet.kt                 the rounded dialog behind every menu/picker
│   │   ├── IconShape.kt             icon masking (circle, squircles, rounded)
│   │   ├── AppRepository.kt         PackageManager query (off the UI thread)
│   │   ├── AppEntry.kt              cell model + AppState (running/recent)
│   │   ├── AppAdapter.kt            grid adapter, icon cache, settings cell
│   │   ├── PinnedApps.kt            ordered pins in SharedPreferences
│   │   ├── OpenApps.kt              what is open: tasks, processes, usage + the access report
│   │   ├── RunningApps.kt           process-based detection (privileged/root)
│   │   ├── ActivityStats.kt         usage-stats recency + access check
│   │   ├── ForceStop.kt             the four close layers + the verified report
│   │   ├── WindowProfiles.kt        per-app window rectangles (one per package)
│   │   ├── WindowControl.kt         the root side: foreground, tasks, `am task resize`
│   │   ├── WindowMarginService.kt   the watcher that applies them, + boot receiver
│   │   ├── WindowMarginsActivity.kt the screen that configures them
│   │   └── RootShell.kt             optional su, probes `-c` and `<uid>` forms
│   └── res/                         layouts, drawables, theme, EN + FA strings
├── tools/release.py                 platform-signed release + optional install
├── build.gradle.kts                 AGP 9.4.1 (built-in Kotlin, no Kotlin plugin)
├── .github/workflows/               debug APK build on every push
└── gradle/wrapper/                  Gradle 9.7.1 wrapper
```

Dependencies are deliberately tiny — `androidx.appcompat` (theme +
`AppCompatActivity`) and `androidx.recyclerview` (the list). No Material, no
Compose, no icon packs; the app icon itself is a vector.

## How this was checked

The UI was driven on an **emulator** (Pixel-class system image, 1024×600 at
density 160 — deliberately the same shape as the head unit) with `uiautomator`
dumps for the geometry and `screencap` for the pixels. This machine has no
`avdmanager`, so the AVD is two hand-written files under `../work/avd/`
(`apphub.ini` plus `apphub.avd/config.ini`: x86_64, `android-35`
`google_apis_playstore`, `hw.lcd.width=1024`, `hw.lcd.height=600`,
`hw.lcd.density=160`, `hw.keyboard=yes`) started with
`ANDROID_AVD_HOME=C:\wamp\www\fx\work\avd emulator -avd apphub -no-window`:

| Check | Result |
|---|---|
| empty install | the message cell spans the grid and the **Settings cell is still reachable** — before the fix the grid was hidden with nothing installed, so there was no way into the settings at all |
| populated grid | 8 columns at Medium on a 1024dp screen, one text line per cell, running/`recent` dot on the icon |
| long press | rounded sheet, app icon + name + package in the header, four rows |
| settings screen | every setting row plus the **Back** row at the end, with live values (`Medium`, `Rounded`, `Auto (8 columns)`, `Name (A–Z)`, `All 19 apps`, `0 / 0 / 0 / 0 dp`) |
| margin wheel | `Left` opens a wheel reading **Any value from 0 to 512 dp** (half the 1024dp width) and `Top` one reading **0 to 284 dp** (half the 568dp-tall window) — so the bound is the live screen, not a constant. Three turns of the wheel: 0 → 3, one step per turn, no wrapping; six turns down at 0 stay at 0; on a value of 96, five turns gave 101 |
| margin by keyboard | *Type the value* opens a numeric field pre-filled with the stored dp (250) and selected. Typing `250` and pressing Enter applied `250 / 0 / 0 / 0 dp` and moved the row to x 32 → 282; `9999` landed on **512**, the limit; clearing the field and pressing Enter left the stored 512 alone; typing `96` and tapping **OK** gave `96`, and **Cancel** after typing `7` changed nothing |
| margins are live | with the wheel still open, the settings text behind it moved **65 → 111 px** for a 30 → 76 dp turn, i.e. the screen follows the value as it is dialled (measured from `screencap` pixels, since only the dialog is in the a11y tree) |
| screen margins | summary read `3 / 0 / 0 / 0 dp` after a 3 dp turn (row title x 32 → 35); with left 76 / top 9 the content box started at `(89, 46)` instead of `(13, 37)` and the 1024×568 window was inset by exactly those two numbers |
| margins reach the grid | after picking them and pressing **Back**, the grid carried the same inset: the `Settings` tile's text moved 19,222 → 51,246 (+32/+24) |
| the keypad gets its room | in the margins sheet, with the keyboard up the card moved to the top of the screen and shrank to `[314,36][710,284]`, the rows to y 284 and the **OK** row to y 244–265 — all of it above the keypad, which starts at y 296. Before the fix the same state left the card's white pixels only from y 38 to **295** (the keypad was drawn over everything below) while OK sat at y 531, under the keys. Tapping a live key typed into the field, and OK pressed with the keyboard open closed the sheet and left the row reading `18 of 19 apps` |
| hide from the grid | long-pressing Camera offers five rows, the last being **Hide from the main page**; tapping it drops the cell immediately (`hidden_apps` = `com.android.camera2`), shows a toast, and the app is back through the shortcuts screen alone |
| reset clears it | *Reset settings* empties the prefs file (`<map />`) and the count returns to `All 19 apps` with every box ticked — Calendar is back on the grid, which then holds 19 apps + Settings + Close = 21 cells |
| include system apps reloads | with the factory apps switched on the row went from `None` to `All 19 apps` by itself: the switch drops the cached list and reads it again on the background thread |
| reset really resets | after *Reset settings* → *Reset*, `shared_prefs/apphub.xml` is **empty** and the switches read on / on / on; before the fix the same run left the switched-off settings behind, because the restore after the recreation wrote the pre-restore switch positions back |
| back row | last row of the list; tapping it returns to the grid and the grid re-applies the margins |
| shortcuts, two panes | at 1024×600 the list is `[14,84][505,556]` and the page `[519,84][1010,556]`, with `All 19 apps` at `[930,47][1004,65]` and the hint at `[888,86][1010,103]` — nothing runs off the screen. 19 apps + gear + *Close* lay out as 113px cells at x 524/647/770/892 |
| the filter is remembered | typing `cam` left one row in the list and stored `shortcuts_filter`; closing the screen with **Back** and opening it again from the settings row came back with `cam` in the field and the list still narrowed. The ✕ cleared the field *and* the stored value |
| the filter does not decide the page | with `cam` typed the header still read `All 19 apps` and the preview still held every app: filtering hides rows, it does not tick anything |
| All / None | *None* left `0 of 19 apps` and 19 packages in `hidden_apps`, and the preview became the empty page — *No apps to show yet* spanning the whole width (`[524,116][1005,207]`), with the gear and *Close* still cells; *All apps* put all 19 back |
| reorder writes the page | long-pressing the third tile and sliding it left moved Chrome from 3rd to 2nd and stored `page_order` = calendar, **chrome**, camera2, …; the pane showed the new order at once and the grid drew `Calendar, Chrome, Camera, Clock` in its first row when it came back |
| a sort drops the arrangement | the *Sort* sheet → *Name (A–Z)* removed `page_order` and the grid went back to name order |
| the preview is not a launcher | its cells report `clickable=false`, and the gear and *Close* in it report `clickable=false` too — while the app's own cells in the grid stay clickable |
| icon size | measured in the screenshot: 64px tile at Medium, 88px at Extra large |
| adapt to screen, off | the row is on the settings screen between *Icon size* and *Icon shape* and reads **×1**; the grid measures 8 columns, 128px cells, 64px icons and a 20px-tall label at Medium |
| adapt to screen, on | tapping the row took the screen from a 29px header to a 57px one, a 21px row title to 41px, the header's **own glyphs from 19px of ink to 40px**, the row's own value to **×2** and the *Grid* row from `Auto (8 columns)` to **`Auto (4 columns)`** - so the grid really does lay itself out for a narrower screen, rather than drawing the old eight columns into the room of four |
| what it does to the grid | 8 columns of 128px cells, 64px icons and 104x20px labels became **4 columns of 248px cells, 128px icons and 204x36px labels** - icons, text and spacing all exactly double, on the unit's own 1024x600 @ 160dpi shape |
| it reaches the screen behind it | the switch was flipped on the settings screen; pressing Back put the **already-running grid** back at ×2 (the density is fixed when a screen is created, so it is built again when it no longer matches) |
| the icons stay crisp | the drawn art fills both boxes identically - 89% of the tile in the icon's own green, art bounding box the full 64x64 and the full 128x128 - and the 3px of antialiased edge at 64px became 4px at 128px rather than the 8px a 2x upscale of the small bitmap would have left |
| margins are not scaled with it | `margin_left` = 50dp put the content box at x 63 with the switch off **and at x 76 with it on**: the 13px of cell margin doubled with the density, the 50px of screen margin did not. The wheel's range is the unit's too: *Any value from 0 to 512 dp* with the switch on |
| the other screens | at ×2 the sheets, the shortcuts screen (list, filter, page preview) and the app window margins screen all lay out inside 1024x600; a long-press sheet is taller than the screen and **scrolls**, so *Window margins* and *Hide from the main page* are still reachable, and the pixel values there read the same as before (`200 / 0 / 0 / 0 px`) |
| reset clears it | *Reset settings* → *Reset* left `shared_prefs/apphub.xml` empty, the row reading **×1** and the grid back to `Auto (8 columns)`, i.e. back to the size the unit's own density gives |
| icon shape | Circular vs Samsung style are visibly different silhouettes; before the fix all five shapes were **pixel-identical** |
| theme | Dark flips the whole screen; the pref round-trips through `SharedPreferences` |
| direction | RTL mirrors the grid (first app moves to the right edge) while strings stay in the device language |
| usage access | row is present, and **gone** after `appops set … GET_USAGE_STATS allow` |
| window margins screen | reached from the settings row (*Screen margins* → *App window margins*, value `None`), then from an app's long-press menu; the header, the master switch, the status sentence and the app rows all lay out at 1024×600, the app row showing `Off` with its switch off |
| window margin profile | long-pressing the row opens the four edges (`Left 0 px` … `Bottom 0 px`) plus *Turn the profile on*; *Left* → *Type the value* → `200` → **Enter** stored `com.mengbo.electronicmanual=1\|200\|0\|0\|0` in `shared_prefs/window_profiles.xml` and the row redrew as `200 / 0 / 0 / 0 px` with its switch on |
| the watcher starts, and stops when unused | with one profile on, `dumpsys activity services` showed `WindowMarginService isForeground=true … types=0x40000000` and its `window_margins` notification; with the master switch off it simply is not there |
| no root, no changes | on an emulator without `su` the screen reads *"Root access is not available, so no window can be moved"* and the app does nothing else - no crash, no config touched |
| the patterns on the device's own engine | `../work/WindowRegexTest.java` compiles `WindowControl`'s five patterns with Android's ICU regex (a bare `}` is a syntax error there, which is how the one real bug of this feature was found) and runs them over real `dumpsys`/`am stack list` output: `topResumedActivity`, the older `mResumedActivity` wording, `mFocusedApp`, both `Stack id=` and `RootTask id=` task blocks, `mWindowingMode`, and the `taskId=…: unknown bounds=…` line that must not match |
| the apply/verify flow on a real window manager | `../work/WindowFlowTest.java` replays the service's flow on the emulator: a **fullscreen** app came back `Refused (nothing changed)` - `before=[0,0][1024,600] mode=fullscreen`, `after=[0,0][1024,600]` - while a **freeform** one (`am start --windowingMode 5`, with freeform enabled on the emulator only, never on the unit) went `before=[382,51][642,541] mode=freeform` → `after=[200,100][824,500] mode=freeform`, i.e. the app really did end up inside exactly the rectangle |

The open-apps work was driven the same way, on an **Android 15 emulator**
(Pixel-class system image, 1024×600 at density 160, `ro.build.type=user`, so
`adb root` is unavailable - which is what makes it a fair stand-in for a phone):

| Check | Result |
|---|---|
| which permissions `adb` can hand over | `pm grant` accepts `PACKAGE_USAGE_STATS`, `DUMP` and `WRITE_SECURE_SETTINGS` (the `development` flag), and refuses the three this feature needs: `REAL_GET_TASKS` and `FORCE_STOP_PACKAGES` with *"is not a changeable permission type"*, `REMOVE_TASKS` with *"is managed by role"* (`dumpsys package permissions`: `REAL_GET_TASKS` = `signature\|privileged`, `REMOVE_TASKS` = `signature\|recents\|role`) |
| the list, before | with usage access granted and three apps just opened and backgrounded, the screen said **"No open apps"** - the bug being fixed: `ACTIVITY_PAUSED` removed a package the moment it was covered, and the hub is always the app doing the covering |
| the list, after | `adb shell appops set com.mimskydo.apphub GET_USAGE_STATS allow` → Chrome, Photos, Settings and the hub's own neighbour app listed as *Recently open*, count `4 apps`, and the board's tile for the last one carried a hollow dot |
| the banner says what it is | the task manager shows *Recently used, not open* with *"Usage access cannot tell an app that is open from one used a while ago…"*, and it is absent when an exact source answers |
| the dots and the list agree | the board and the task manager are drawn from one read (`OpenApps`), so no tile can be marked while its row is missing |
| a close that cannot work, reported honestly | on Android 15 (no root, no platform signature) tapping *Close* logs `layer=NONE` and toasts *"…is still open"*, instead of the old `killBackgroundProcesses()` call reported as a close |
| the platform is the reason | controlled pair on one cached app: `adb shell am kill com.android.settings` killed pid 8349; App Hub's own `killBackgroundProcesses("com.android.settings")` returned normally and **pid 8349 was still there** a moment later. App Hub's own call is a no-op from Android 14 on, exactly as the release notes say |
| one real bug found this way | the first version of the new fallback asked `checkPermission(KILL_BACKGROUND_PROCESSES, <the app being closed>)` - the *target's* permission, which no normal app holds - so every close reported `layer=NONE` while looking like a close that had run, and nothing was ever called |

The checks that need the unit itself could not be run here: nothing was installed
on the vehicle, and no Android 10 test device is attached to this machine. What
is *not* verified is therefore exactly two things, both on the unit: that the
platform-signed install is granted `REAL_GET_TASKS` and `FORCE_STOP_PACKAGES`
(one `dumpsys` line, in [Making the open-apps feature work](#making-the-open-apps-feature-work)),
and that Android 10's `getRecentTasks()` answers a permission-holder with every
task. The second is the framework's documented behaviour for the permission and
is why the source is tried first; the implementation does not depend on it,
because a source that answers nothing falls through to the next one.

The design pass was driven the same way, screen by screen, on the build that is
installed (the emulator is named explicitly in every `adb` call: a phone can be
attached to this machine at the same time, and nothing here should ever be able
to touch it):

| Check | Result |
|---|---|
| every screen still opens | the board, the tools sheet, the settings screen, the shortcuts screen, the window margins screen, one app's edges sheet and the way back were all reached through the real UI: **7 of 7, 0 crashes** from `com.mimskydo.apphub` |
| the shortcut screen used to throw | its empty-state panel had become a two-line `LinearLayout` in the layouts while the screen still held it as a `TextView`, so `onCreate` threw a `ClassCastException` and the screen never opened at all (`ShortcutsActivity.kt:73`). Fixed, and it opens |
| the badge is on the icon's corner, not over it | at Medium the badge's 28dp circle is centred on the icon's top-right corner - it covers the art's corner quarter and hangs into the tile's whitespace - and it is placed against a box the adapter sizes, so the same is true at Small and at Extra large. Before the fix a 40dp circle sat across 60% of a 64dp icon, and at 72dp rows the card's six verbs ran off the bottom of the panel |
| a clipped badge is a quarter of a circle | the badge deliberately paints outside its own view, so the tile, its inner column and the icon's box all had to stop clipping (`clipChildren` and `clipToPadding`): a screenshot of the lifted board is what showed the quarter |
| the running ring no longer moves the badge | the ring paints outside the icon's box by a negative inset instead of making its own view bigger, so an app that is running no longer shifts the geometry its tile's badge is placed against |
| the action card fits the panel | six 64dp rows (`row_sheet_height`) plus two hairlines and the header come to 528px of the 576px window, with the dividers after the second row (do / arrange) and before *Close* (remove). At 72dp the same card ran past the bottom edge |
| a count is a quantity | the empty board, the settings row and the window screen's header read `1 app` for one app via `<plurals>`; they used to read `1 apps` |
| one app, one drawing | the board, both lists and the app card now share one icon cache keyed by package, size and shape. The masker draws *copies* of an adaptive icon's layers: re-binding the app's own instance to a raster size had left the shortcuts list drawing a stretched icon on a black square while the same icon was fine on the board |
| dark mode | the night palette measures exactly as specified on the settings screen: **62.8%** `#171A21` (surfaces), **31.9%** `#0F1115` (page), **1.8%** `#242833` (hairlines) - 95.6% of the screen in three tones |
| a setting shows its own result | choosing *Circular* writes the row's value and re-reads it in place; the number of pickers that only read on `onResume` is now zero. *Icon size* → *Extra large* turns the Grid row from `Auto (8 columns)` into `Auto (7 columns)` on the same screen, because that value is derived from it |
| the `⋯` is a mark, not a button | the circle is 24dp at the reference 64dp icon and scales down with it (18px/25px/25px measured at 48/64/96dp) rather than growing, and the glyph is inset *twice* — inside the circle, and inside that for the dots. Before, a 40dp-wide glyph was being drawn inside a 28dp circle and bulged out of it |
| a card with nothing at the top has nothing at the top | the tools menu's first row sits 29px below the card's top edge (card padding + the row's own centring); it wore ~100px of empty header because `sheetHeader` keeps a touch target's `minHeight` whether or not it holds anything. All five pickers and the app card were re-measured after the fix |
| the switch | stays the platform's own widget. Three attempts at a custom track and thumb were overridden by the control's own tint, and it is the one widget whose shape a finger has already been taught; the app's colour reaches it through the theme's accent instead |

Not checked: the head unit itself. Nothing here was installed on the vehicle,
and the launcher tooling in `../launcher_tool/` was not touched by this work.

## Honest limitations

* **Window margins need root.** Without a usable `su` every call answers null, the screen says
  *"Root access is not available, so no window can be moved"* and nothing else happens - the
  rest of the hub works exactly as before.
* **The unit decides whether a task may be moved.** On Android 10 the window manager only
  resizes a task that is in freeform or multi-window mode; one started fullscreen stays
  fullscreen, and the screen says which app and which mode instead of quietly doing half the
  job. Nothing global is changed to work around it.
* **Three packages can never be given a rectangle**: App Hub itself, the launcher the unit is
  actually running, and SystemUI. Their rows say so.
* **System apps are excluded by default** (`FLAG_SYSTEM` /
  `FLAG_UPDATED_SYSTEM_APP` are filtered out) — this hub is for what the user
  installed. *Include system apps* in the settings lists them too.
* **No exact "open" view without privileges.** On a plain install you get the
  `recent` state (needs the usage-access opt-in) or nothing; only the
  platform-signed install, a `/system/priv-app` install or root gives the real
  task and process view.
* **A modern phone cannot close other apps at all.** Android 14 restricts
  `killBackgroundProcesses()` to the caller's own processes, so on an unrooted
  phone the app shows the *Recently open* list and its Close says *still open*
  instead of pretending. The platform-signed install, a priv-app install, or
  root is what changes that — not an `adb grant`, which the platform refuses for
  these three permissions.
* **The dot is the only status shown** — the grid deliberately carries no
  package names, badges or pin markers.
* **Adapting to the screen is one fixed rule, not a knob.** The factor is the
  screen's width over 400dp, capped at ×2 - there is no slider behind it, and no
  way to ask for ×1.5 on a screen the rule says is ×2 (the icon size is still
  there to fine-tune the tiles on top of it).
* **At ×2 the screens scroll more**, which is the price of everything being
  twice as big on a 600px-tall display: four grid rows instead of eight on the
  settings screen, and a long-press sheet that has to be scrolled to reach its
  last two rows. The layouts themselves lay out inside 1024x600 unchanged.
* **An icon is scaled from whatever the app ships.** An adaptive or vector icon
  redraws at the bigger size; a legacy app whose icon is a small raster PNG is
  upscaled from that bitmap, exactly as it already was at Extra large.
* **The adaptation follows the window's own width, not the display's.** The
  scale is computed from the application's configuration, so a screen narrower
  than the display (a split window) is scaled as if it were the whole panel. That
  is not a case this unit produces, and one source for the number is what keeps
  it from compounding.
* `QUERY_ALL_PACKAGES` is declared so the list keeps working on Android 11+;
  on the Android 10 head unit it is ignored.
* The app targets SDK 34 but runs on the unit's Android 9/10 (API 28/29) — that
  is the point of `minSdk 28`.
