# App Hub — Instrument

**A design system for the App Hub head-unit launcher companion.**
Version 1.1 · author: design · status: built, and corrected where building it
disagreed with the specification (see [Part 9](#part-9--what-building-it-changed))

This document is written against the code that exists in `app_hub/` today. Every
claim about the current UI was read out of the source, and every number in the
tables was either measured on the emulator (1024×600 @ 160dpi, the unit's own
shape) or computed by WCAG 2.1 relative luminance. Nothing here needs a new
dependency: the whole system ships inside `appcompat 1.7.0` +
`recyclerview 1.1.0`, because this machine cannot reach `dl.google.com` and the
pinned offline graph is the app's hardest architectural constraint.

---

## Part 1 — Discovery Report

### 1.1 The app

| | |
|---|---|
| Name / package | **App Hub** · `com.mimskydo.apphub` |
| Version | `versionCode 2`, `versionName 1.1` |
| Built with | AGP 8.8.0 / Kotlin 2.1.10, Gradle 8.10.2 |
| SDK | `compileSdk 35`, `minSdk 28`, `targetSdk 34` |
| UI toolkit | **Android Views + XML layouts + Kotlin.** No Compose, no Material, no DataBinding |
| Dependencies | `androidx.appcompat 1.7.0`, `androidx.recyclerview 1.1.0` — that is the entire graph |
| Signature | platform key `c8a2e9bc…92ab8`, installable as a privileged system app |
| Languages | English + **full Persian (fa) parity** — every string exists in `values-fa` |

> Documentation drift worth fixing: `build.gradle.kts` says `minSdk 28` and
> `versionName 1.1`, while `README.md` says "minSdk 29" and the `Chery Omoda
> C5.md` handoff calls the unit Android 10 / API 29. The unit is API 28/29 class;
> the design below targets **API 28** to be safe.

### 1.2 The domain

Not a launcher: a **companion board**. The factory launcher (`com.chery.launcher`,
a platform-signed system app on a 1024×600 160dpi panel) is where the unit boots
to. App Hub is a second surface the driver opens to see *their* apps, launch one
with one tap, close what is still running, and — for the two features that need
it — push an app's window into a rectangle using root.

So the category is **automotive utility / device command surface**, and its
context of use is the whole design brief:

| Context fact | Design consequence |
|---|---|
| 10" panel, 1024×600, 160dpi, **~60–90cm viewing distance** | Type floor 12sp, row text 15sp, secondary text never below 13sp |
| Moving vehicle: glance, don't read | One decision per screen region; state visible without tapping |
| **Red means a fault in a car** | Danger red leaves the grid entirely; destructive actions are amber until confirmed |
| Bright sun and full night | Two fully-specified surface ramps; no pure white on pure black at night; no glow, no bright ring spill |
| Touch with a moving target | Primary targets ≥ 88dp (14mm), secondary ≥ 56dp (8.9mm) — today's 44dp back button is 7mm and is **below** the car floor |
| Portrait phone in the workshop, landscape unit in the car | Everything tokenised so the `Adapt to screen` switch (×1→×2) scales one system, not two |
| Persian users, RTL | Every new token and every new layout is `start`/`end`; numbers stay LTR with tabular figures |

### 1.3 The screens and the flow

Four activities, all built row-by-row in code from shared XML rows:

```
MainActivity ────────────────► SettingsActivity ──┬──► ShortcutsActivity
  (the grid)                      (21 rows)        ├──► WindowMarginsActivity ──► WindowMarginService
   │                              │                └──► system Usage-access screen
   │ long-press an app            ├─ close all ──► back to the grid (EXTRA_CLOSE_ALL)
   └─► Sheet (menu)               ├─ margins ────► Sheet → NumberPicker / input
       ├─ Open / Close            └─ window ─────► WindowMarginsActivity[EXTRA_PACKAGE]
       ├─ Pin / App info
       ├─ Window margins ──► WindowMarginsActivity[EXTRA_PACKAGE]
       └─ Hide from the main page
```

Sheets are a **single custom component** (`Sheet` + `dialog_sheet.xml`) used as
menu, choice picker, numeric wheel (`NumberPicker`) and typed entry. That
consolidation is the best engineering decision in the app and the redesign keeps
it — it only gets restyled.

### 1.4 The visual system as it exists today

| Token | Light | Night |
|---|---|---|
| `bg` | `#F2F4F7` | `#0F1115` |
| `card` | `#FFFFFF` | `#1A1D23` |
| `card_stroke` | `#E7EAF0` | `#262B34` |
| `text_primary` | `#101828` | `#F2F4F7` |
| `text_secondary` | `#667085` | `#98A2B3` |
| `primary` | `#2F6FED` | `#7AA2FF` |
| `ripple` | `#1A2F6FED` | `#33FFFFFF` |
| `state_running` | `#0B8A4B` | `#4ADE80` |
| `danger` | `#D92D20` | `#FF7A70` |

There is **no `dimens.xml`, no text-appearance styles, and no shape scale.**
Everything is a literal inside a layout: cell margins 5dp, cell padding 6/12dp,
grid padding 8dp, row start padding 14dp, sheet padding 12/18dp, row corners
14dp, setting-row corners 16dp, cell ripple corners 18dp, sheet corners 24dp,
row text 11/12/13/14/15/16/21sp. Nine radii-and-gaps values where one scale
belongs. That missing layer is the single biggest reason the app reads
"assembled" rather than "designed", and it is exactly what the `Adapt to screen`
switch makes load-bearing: **×2 multiplies dp, so anything not in dp is a bug
waiting for the owner to flip a switch.**

### 1.5 The brand

The mark is real and good: a blue field (`#4B86FF → #2154CC` gradient) holding a
2×2 grid of rounded tiles, the fourth tile drawn in the app's own running-green
(`#4ADE80`). It says *grid of your apps* and *which of them is alive* in one
glance, and it is mask-safe (everything inside the 72dp adaptive zone). The brand
colour `#2F6FED` already matches the mark's mid-tone. **The palette below keeps
the brand hue and changes only the ink, the secondary text and the semantics.**

### 1.6 Friction found (each one is fixed in Part 6)

| # | Friction | Evidence |
|---|---|---|
| 1 | **Every real app action is a long-press secret.** Close, Pin, App info, Window margins, Hide are only in a menu opened by a ~900ms hold. No affordance marks it; nothing on the tile says a hold does anything | `MainActivity.showActions`, `item_app.xml` (no affordance) |
| 2 | **Destructive tiles sit in the icon grid.** *Close* is a red peer of the app icons and quits App Hub with no confirmation; *Settings* is a blue peer | `AppAdapter.StaticCell`, `MainActivity.closeSelf` |
| 3 | **Settings is only reachable at the end of the scroll.** It is the last cell of a grid that holds every installed app | `MainPage.cells` |
| 4 | **Page order can only be changed inside a sub-screen**, in a preview that is a simulation of the grid, not the grid | `ShortcutsActivity`, `Prefs.pageOrder` |
| 5 | **No loading state.** The grid is empty until `AppRepository` answers, so a slow read shows the "no apps" cell first | `MainActivity.reload` → `render` |
| 6 | **No error state.** A `PackageManager` failure becomes `emptyList()` and is rendered as "No apps to show yet" | `MainActivity.reload` (`catch (t: Throwable) { emptyList() }`) |
| 7 | **The running dot is 12dp of colour only** — no shape reinforcement, and it is announced as a separate image node | `item_app.xml#appDot`, `@string/running_marker` |
| 8 | **Off-grid spacing**: 5 / 6 / 8 / 10 / 12 / 14 / 18 / 20 / 24 dp used for the same purpose across screens | all layouts |
| 9 | **Blue as small text fails contrast.** `#2F6FED` on `bg` = **4.13:1** — the *All apps* / *None* buttons and every value row are 13–14sp in that colour | computed |
| 10 | **Secondary text has zero headroom**: `#667085` on `bg` = **4.52:1** at 11–12sp | computed |
| 11 | **The back target is 44dp (7mm)** — under the car floor, and it is the exit of every screen | three layout headers |
| 12 | **No undo.** Hide / Close / pin changes answer with a toast that cannot be acted on | `MainActivity.hideFromMainPage` |
| 13 | **No way to know why there are no dots.** Usage access lives in Settings; the grid never says the dots need an opt-in | `SettingsActivity.usageRow` |
| 14 | **Empty grid has no action** — a sentence, no way into Settings except the tile below it | `item_empty.xml` |
| 15 | **Selection is invisible to a screen reader**: the sheet's check mark is an `ImageView` with `@null` description, and `selected` is never exposed | `Sheet.rowView`, `dialog_sheet_row.xml` |
| 16 | **Screen status sentences are the weakest type on screen** (12sp inside a card) although they carry the app's best idea: telling the truth when the OS refuses | `activity_window_margins.xml#windowStatus` |
| 17 | Three headers are copy-paste (`8dp/10dp/20dp` + 44dp icon + 21sp bold) with no shared layout | `activity_settings`, `activity_shortcuts`, `activity_window_margins` |
| 18 | Docs drift: `minSdk`, version, "API 29" | `build.gradle.kts` vs `README.md` |

**Strengths to protect:** one sheet component for everything; one app list
(`AppRepository`) behind every screen; honest copy ("closed in background",
"the unit only resizes freeform or multi-window tasks"); the full Persian
translation; the shortcuts screen's two-pane layout — it is the right shape for a
wide, short screen and the design keeps it.

---

## Part 2 — Design Philosophy

### One emotion: **calm command.**

The driver looks once and knows what is open, what they can do, and what the
vehicle just refused to do. Nothing pulses for attention, nothing is red that is
not a fault, nothing is hidden that they might need in a moving car.

### One memorable idea: **a board, not a menu.**

The home screen is a physical board of tiles. You can *pick one up* — and picking
one up lifts the whole board. That is the entire interaction model, and it
replaces the app's weakest pattern (the invisible long-press menu) with its
strongest one (a mode you can see).

### One signature interaction: **The Lift.**

```
   hold a tile (400ms)                the board lifts                 do the work
   ┌──────────────────────┐          ┌──────────────────────┐       ┌──────────────────────┐
   │ ▢ ▢ ▢ ▢              │          │ ▢̅ ▢̅ ▢̅ ▢̅   ⋯          │       │ ▢  ▢̅ ▢̅ ▢           │
   │ ▢ ▢ ▢ ▢              │   ──►    │ ▢̅ ▢̅ ▢̅ ▢̅   ⋯          │  ──►  │ ▢  ▢̅ ▢̅ ▢           │
   │ ▢ ▢ ▢ ▢   ● pill     │          │ ▢̅ ▢̅ ▢̅ ▢̅   ⋯          │       │ ▢  ▢̅ ▢̅ ▢           │
   └──────────────────────┘          │  ✕ Done   ⠿ drag      │       │    ✕ Done            │
                                     └──────────────────────┘       └──────────────────────┘
```

- Every tile grows 2% and a soft 2dp lift appears — the board is *off* the
  surface, and the whole grid is now draggable with two thumbs.
- Every tile grows a **⋯ affordance** in its top-end corner: the actions that
  used to be a secret are now a visible button on every tile.
- A slim **action bar** rises from the bottom with `Done` and the drag hint.
- Tapping `⋯` opens the app's **card sheet** (the old long-press menu,
  restyled); tapping empty space or `Done` puts the board back down.
- Nothing else changes: a single tap is still *open the app*, 100% of the time.

### The supporting ideas

| Idea | What it is |
|---|---|
| **The ring** | Running state is a 3dp ring in the tile's own corner language plus a solid dot. Shape + position + colour, never colour alone. |
| **The pill** | Settings, search and "close all" leave the grid. A single 56dp pill floats at the bottom-end and opens the tools sheet, so the grid holds *only* apps and tools are one tap away from anywhere in the scroll. |
| **One system, two scales** | The whole language is expressed in dp/sp tokens, so `Adapt to screen` (×1→×2) multiplies the design instead of breaking it. The car legibility floor is stated in millimetres, not dp. |
| **Say what the vehicle did** | The app's honesty about refused window moves becomes a first-class **status banner** (icon + 13sp + tone), not a sentence in a card. |

---

## Part 3 — Visual System

### 3.1 Colour

Four ramps. Hue kept from the existing mark; ink and semantics fixed.

**Ink (neutrals)** — one cool ramp, Apple-style titanium:

| Token | Light | Night | Use |
|---|---|---|---|
| `hub_ink_900` | `#101828` | — | primary text, light |
| `hub_ink_700` | `#344054` | — | icons on light |
| `hub_ink_600` | `#475467` | — | **secondary text, light** |
| `hub_ink_400` | `#98A2B3` | — | disabled, hairline-ish glyphs |
| `hub_ink_100` | `#F2F4F7` | — | page, light |
| `hub_night_950` | — | `#0F1115` | page, night |
| `hub_night_900` | — | `#171A21` | card, night |
| `hub_night_800` | — | `#242833` | stroke, night |
| `hub_night_100` | — | `#ECEFF5` | primary text, night |
| `hub_night_300` | — | `#A9B2C0` | secondary text, night |

**Blue (brand)** — the mark's own hue, split by job:

| Token | Light | Night | Use |
|---|---|---|---|
| `hub_blue_600` | `#2F6FED` | — | **fills**: primary button, pill, active dot on a filled surface |
| `hub_blue_700` | `#1D4ED8` | — | **blue text on a surface** (values, links) |
| `hub_blue_100` | `#E8EFFE` | — | tint fills, selection wash |
| `hub_blue_300` | — | `#7AA2FF` | blue text on night surfaces |
| `hub_blue_900` | — | `#12234F` | tint fills, night |

**Semantic** — each with a job and a *shape* partner:

| Token | Light | Night | Job |
|---|---|---|---|
| `hub_running` | `#067647` | `#4ADE80` | running: ring + dot + label |
| `hub_recent` | `#98A2B3` | `#A9B2C0` | recent: hollow dot only |
| `hub_warn` | `#B54708` | `#F5B84A` | **the car's "attention, not fault"**: close all, close App Hub, refusal |
| `hub_danger` | `#B42318` | `#FF7A70` | only inside the confirm button of an irreversible action |
| `hub_ok` | `#067647` | `#4ADE80` | "applied" status banner |
| `hub_blocked` | `#475467` | `#A9B2C0` | "root is not available" — blocked, not broken |

**Measured contrast (WCAG 2.1, relative luminance):**

| Pair | Today | Instrument | Verdict |
|---|---|---|---|
| secondary text on page, light | `#667085` on `#F2F4F7` = **4.52:1** | `#475467` on `#F2F4F7` = **6.84:1** | passes AA small text with headroom |
| blue text on page, light | `#2F6FED` on `#F2F4F7` = **4.13:1** (fails) | `#1D4ED8` on `#F2F4F7` = **6.07:1** | fixed |
| white on blue fill | — | `#FFFFFF` on `#2F6FED` = **4.62:1** (≥17sp semibold) | fills restricted to ≥17sp semibold |
| secondary text on page, night | `#98A2B3` on `#0F1115` = **7.34:1** | `#A9B2C0` on `#0F1115` = **8.6:1** | keep-and-improve |
| running indicator on page, light | `#0B8A4B` on `#F2F4F7` = **4.01:1** | `#067647` = **5.1:1** | indicator needs ≥3:1; now comfortable |
| hairline stroke on page | `#E7EAF0` on `#F2F4F7` = 1.1:1 | same, deliberate | strokes are structure, not information |
| night stroke on night card | `#262B34` on `#1A1D23` = 1.2:1 | `#242833` on `#171A21` = 1.25:1 | deliberate |

**Why red moved.** In a vehicle, red is an instrument fault light. A red *Close*
tile in the app grid competes with the car's own warning semantics and it is the
loudest object on a screen whose job is to be calm. Destructive-but-reversible
actions (close an app, hide a tile, close App Hub) are now **amber `hub_warn`
text on a card**, and true `hub_danger` appears in exactly one place: the filled
confirm button of the sheet that has already asked. Red goes back to meaning
*the car has a problem*.

### 3.2 Typography

**Font decision: system Roboto (API 28+).** Inter/SF Pro are unavailable here —
`dl.google.com` is unreachable so downloadable fonts cannot resolve, and the app
is a candidate system app that must stay small, so bundling four weights is the
wrong trade. Roboto is the platform's own grotesque, is already the Persian
reader's fallback, and needs no licence file. Apple-grade polish comes from
**metrics**, not from a font file:

- a strict nine-step scale, no sizes outside it;
- **tabular figures** on every number the app shows (`tnum`), so `200 / 20 / 0 / 0 px`
  and `×2` never jitter as they change;
- letter-spacing on the two ends of the scale only (`-0.01em` above 21sp,
  `+0.01em` at 12–13sp);
- line heights at 1.4–1.5× for reading sizes, 1.15× for display.

| Style | Size / line | Weight | Tracking | Where |
|---|---|---|---|---|
| `Text.Display` | 34 / 40 | 600 | −0.02em | empty-state numeral ("19 apps") |
| `Text.TitleXL` | 28 / 34 | 600 | −0.01em | sheet hero titles (app card) |
| `Text.TitleL` | **21 / 26** | 700 | −0.01em | screen titles (keeps today's 21sp bold) |
| `Text.TitleM` | 17 / 22 | 600 | 0 | sheet titles, section headers, banner leads |
| `Text.Body` | 15 / 21 | 400 | 0 | row titles, tile labels, sheet rows |
| `Text.BodyStrong` | 15 / 21 | 500 | 0 | the leading word of a row, "All apps" |
| `Text.BodyS` | 14 / 19 | 400 | 0 | row subtitles, descriptions |
| `Text.Meta` | 13 / 17 | 500 | +0.01em | values on the right of a row, units, counts |
| `Text.Caption` | 12 / 16 | 400 | +0.01em | the smallest text the app may use — **hard floor** |

Rules: never below 12sp; secondary copy is `Text.BodyS` or larger on a car
screen; numbers are `Text.Meta` + `tnum`; a value that names a unit keeps the
unit in the same style (no superscript, no mixed weight).

### 3.3 Spacing

4dp base, 8dp rhythm. Named tokens in `dimens.xml`:

| Token | dp | Use |
|---|---|---|
| `space_0` | 0 | — |
| `space_1` | 4 | icon/label micro-gap, stroke insets |
| `space_2` | 8 | inside a chip, between tile label and icon |
| `space_3` | 12 | label ↔ subtitle, icon ↔ text |
| `space_4` | 16 | row inner padding, card padding |
| `space_5` | 24 | **screen gutter**, between groups of rows |
| `space_6` | 32 | between sections on Settings |
| `space_7` | 48 | top/bottom breathing room of an empty state |
| `gutter_grid` | 12 | grid outer padding |
| `gap_cell` | 8 | between tiles (replaces today's 5dp margin + 6dp padding) |
| `row_min_height` | 72 | every settings / list row |
| `touch_min` | 56 | smallest tappable target (8.9mm) |
| `touch_car` | 88 | primary targets: pill, tile, primary button (14mm) |

**The car legibility floor, stated honestly:** at 160dpi, 1dp = 1px =
0.159mm. Apple's 44pt ≈ 7mm is a *hand-held* number. In a moving vehicle the
floor is 9mm (`touch_min` 56dp) with primary actions at 14mm (`touch_car` 88dp).
The app's current 44dp back button is 7mm and moves to 56dp; the tiles are
already 116–128dp (18–20mm) and are fine. Because `Adapt to screen` multiplies
the dp space, a ×2 unit gets 28mm targets — **the floor is a minimum, not a
target, and every token above is defined so that ×1 and ×2 are both valid.**

### 3.4 Corner radius

| Token | dp | Use | Note |
|---|---|---|---|
| `radius_xs` | 8 | chips, small switches, value pills | |
| `radius_s` | 12 | sheet rows, input fields, list rows | |
| `radius_m` | 16 | setting cards, banners, buttons (inline) | |
| `radius_l` | 22 | **app tiles** | deliberately equals `IconMasker`'s rounded shape (0.24 × size): the cell's corner and the icon mask's corner become the same language |
| `radius_xl` | 28 | the floating sheet card | |
| `radius_full` | 999 | the pill, the segmented control, count badges | |

That `radius_l ↔ mask 0.24` alignment is the kind of detail that makes the
surface feel drawn by one hand: change `IconShape.ROUNDED`'s factor to `0.24f ×
radius` and the mask and the cell finally share one curve.

### 3.5 Elevation & depth

On a car panel, hard drop shadows are noise and glare. Depth is built from
**tone + stroke + one small shadow**, and only three levels exist:

| Level | Recipe | Used by |
|---|---|---|
| `flat` | page tone, no stroke | the grid background |
| `raised` | `card` tone + 1dp `stroke` + `elevation 1dp` | tiles, rows, banners |
| `floating` | `card` tone + 1dp `stroke` + `elevation 8dp` + 32% scrim | sheets, the pill |

Light: raised = `#FFFFFF` on `#F2F4F7` (tone step). Night: raised = `#171A21`
on `#0F1115` (tone step, never a shadow). A pressed tile does not darken — it
**shrinks 2% at 90ms** and its ripple runs at 12% opacity, so the only movement
on a car screen is the thing being touched.

### 3.6 Iconography

| | |
|---|---|
| Grid | 24 × 24, 2dp optical padding, keyline square 18, circle 20 |
| Weight | **1.75dp stroke**, round cap, round join — outline, not Material-fill |
| Sizes | 24 (rows, headers) · 20 (dense rows, chips) · 40 (status banners) · 48 (empty states) |
| Colour | inherits tint: `ink_700`/`night_100` for structural, `blue_700`/`blue_300` for active, `warn`/`blocked` for status; never two colours in one glyph |
| Set | `back`, `chevron`, `search`, `close`, `gear`, `pin`, `info`, `sliders`, `frame` (window), `eye-off` (hide), `trash` (uninstall), `grid`, `check-circle`, `warning`, `blocked`, `refresh`, `open` (a page with a way out of it: the app card's *Open*, and the file manager's *Open with*), and the file manager's `folder`, `folder-tile` (its own icon), `up`, `page` (a file), `package` (APK), `image`, `note` (music), `film` (video), `box` (archive), `sheets` (copy), `folder-in` (move), and the browser's `web`, `web-tile` (its own icon), `bookmark`, `reload` |
| Running ring | not an icon: a shape drawable (`stroke 3dp`, `radius_l`) drawn behind the tile |

The existing `ic_tune` (filled rectangles) and `ic_back` (Material filled arrow)
are the two glyphs that most date the app; both are redrawn as 1.75dp outlines.
`ic_open` was drawn before the file manager's set existed and kept a 1dp corner
radius; it now carries the set's 2.9 corner, because the file manager's *Open
with* row made it a glyph that has to sit beside `folder`, `folder-in` and
`sheets` rather than on an app card of its own.

The browser's four are the same kind of addition, in the opposite direction:
`web` is a globe at the set's 1.75dp weight, `web-tile` is that globe on the app's
blue sheet at the folder tile's own scale, and `bookmark` and `reload` are the two
verbs the feature has — a kept page, and a page asked for again. All four were
drawn as a sheet and looked at before they were added (96dp and 24dp for the
outlines, the tile at 96, 64 and 48): a globe is exactly the shape that meets
itself at row size, where the meridian and the equator cross.

---

## Part 4 — Layout & Composition

Screen grid: 12 columns, `space_5` (24dp) gutters, content width 976dp at 1024.
Two columns of content on Settings for wide rows (advanced), single column for
everything else. Safe areas: the unit's own overlay rail is why `Screen margins`
exists — the design treats the margin band as **untouchable** and never assumes
the full 1024 is ours.

### 4.1 The Grid (MainActivity) — "the board"

```
 ┌──────────────────────────────────────────────────────────────────────────────┐
 │                                                                              │
 │   ╭───────────╮  ╭───────────╮  ╭───────────╮  ╭───────────╮  ╭───────────╮   │  tile 176×176
 │   │  ▢    ●   │  │    ▢      │  │    ▢      │  │    ▢      │  │    ▢      │   │  (at ×2: 352)
 │   │           │  │           │  │           │  │           │  │           │   │
 │   ╰───────────╯  ╰───────────╯  ╰───────────╯  ╰───────────╯  ╰───────────╯   │
 │     Chrome          Maps                Clock          Radio        Files    │  label: Text.Body
 │                                                                              │
 │   ╭───────────╮  ╭───────────╮  ╭───────────╮  ╭───────────╮   ╭──────────╮   │
 │   │    ▢      │  │    ▢      │  │    ▢      │  │    ▢      │   │  ⚙︎ search │   │  the pill: 56dp tall,
 │   ╰───────────╯  ╰───────────╯  ╰───────────╯  ╰───────────╯   ╰──────────╯   │  bottom-end, floating
 │     Camera        Notes              Settings       …                        │
 └──────────────────────────────────────────────────────────────────────────────┘
```

- **Only apps.** The Settings and Close cells are gone from the grid (Part 6 #2,
  #3); the pill carries those tools and floats above the scroll, so it is
  reachable without scrolling to the end.
- Tile: icon (`Prefs.iconSize` dp) + `space_2` + one label line, `space_3`
  padding, `radius_l`, `raised`. Cell pitch = icon + 2·`space_3` + `gap_cell`.
- **Auto columns** keep following the icon size and the margins (no change to
  `Prefs.columnCount`), but the formula's cell overhead becomes
  `2·space_3 + gap_cell + label allowance` instead of the hard-coded `+50dp`.
- **Running** = ring (`hub_running`, 3dp) behind the tile + 8dp dot at the
  top-end. **Recent** = hollow dot only. Both are announced in the tile's
  `contentDescription`; the dot's own node stops announcing itself.
- **Empty** (Part 4.9) is a real panel with a primary button, not a cell.
- Label is `Text.Body` (15sp, up from 13sp) with `ellipsize=end`, one line.
  Names are secondary information; the icon is the primary target.
- **Scroll**: vertical, with `clipToPadding=false`, `paddingBottom` =
  `touch_car + space_4` so the pill never covers a tile.

### 4.2 Edit mode — "The Lift" (new state of the grid)

```
 ┌──────────────────────────────────────────────────────────────────────────────┐
 │   ╭───────────╮  ╭───────────╮  ╭───────────╮  ╭───────────╮                  │
 │   │  ▢      ⋯ │  │  ▢      ⋯ │  │  ▢      ⋯ │  │  ▢      ⋯ │   tiles lifted │
 │   ╰───────────╯  ╰───────────╯  ╰───────────╯  ╰───────────╯   2% + 2dp      │
 │     Chrome          Maps                Clock          Radio                 │
 │   ╭───────────╮  ╭───────────╮  ╭───────────╮                                │
 │   │  ▢      ⋯ │  │  ▢      ⋯ │  │  ▢      ⋯ │                                │
 │   ╰───────────╯  ╰───────────╯  ╰───────────╯                                │
 │  ┌────────────────────────────────────────────────────────────────────────┐  │
 │  │   Hold a tile to move it                       ⟲ Reset order    Done   │  │
 │  └────────────────────────────────────────────────────────────────────────┘  │
 └──────────────────────────────────────────────────────────────────────────────┘
```

| | |
|---|---|
| Enter | hold any tile **400ms** (shorter than the old 900ms) — haptic `LONG_PRESS` on entry |
| Tile | `scaleX/Y 1.0 → 1.02` + `elevation 1 → 2`, 240ms, `emphasized` curve, 30ms stagger by column |
| `⋯` | 40dp button, top-end of every tile, `blue_100`/`blue_900` wash, opens the app card sheet |
| Bar | rises 320ms from `y+24` behind the pill's own position, `floating`, `radius_xl`; holds the drag hint, an optional **Reset order**, and `Done` (56dp min) |
| Drag | long-press inside edit mode, or drag the lifted tile directly; reorder writes `Prefs.pageOrder` on drop (the existing `Prefs.pageOrder` + `MainPage.arrange` already support this — see Part 6 #4) |
| Exit | `Done`, back, or a tap on empty board space; the pill returns |

Why a mode instead of a menu: in a car, a hidden verb is a dangerous verb. The
mode makes the destructive verbs *visible, named, and dismissible*, and it gives
the app its one piece of personality that a driver will remember.

### 4.3 The hub sheet (new) — "tools"

```
 ┌────────────────────────────────────────────────────────────┐
 │                    ┌──────────────────────────────┐        │
 │                    │  ┌────────────────────────┐  │        │
 │                    │  │ ⌕  Find an app         │  │ 56dp   │
 │                    │  ├────────────────────────┤  │        │
 │                    │  │ ⚙︎  Settings          › │  │ 72dp   │
 │                    │  ├────────────────────────┤  │        │
 │                    │  │ ⧉  Edit the page       │  │        │
 │                    │  ├────────────────────────┤  │        │
 │                    │  │ ⊘  Close all apps      │  │ amber  │
 │                    │  ├────────────────────────┤  │        │
 │                    │  │ ⏻  Close App Hub       │  │ amber  │
 │                    │  ├────────────────────────┤  │        │
 │                    │  │        Cancel          │  │        │
 │                    │  └────────────────────────┘  │        │
 │                    └──────────────────────────────┘        │
 │  ░░░░ scrim 32%, tap anywhere to dismiss ░░░░░░░░░░░░░░░░░░ │
 └────────────────────────────────────────────────────────────┘
```

Bottom-anchored (not centred like today's sheets), `radius_xl` only on the top
corners at the screen edge, slide-up 320ms `standard`, drag-down-to-dismiss on
the header (phase 3), back dismisses. The grid dims but stays visible — the
Apple move: the sheet belongs to the board, it does not replace it.

### 4.4 The app card sheet (restyled old long-press menu)

```
 ┌────────────────────────────────────────────────────────────┐
 │  ┌──────────────────────────────────────────────────────┐  │
 │  │   ╭────╮   Chrome                                    │  │  56dp icon, TitleXL,
 │  │   │ ▢  │   com.android.chrome · running              │  │  state as a word
 │  │   ╰────╯                                             │  │
 │  ├──────────────────────────────────────────────────────┤  │
 │  │ ↗ Open                       primary, blue_700 text  │  │
 │  │ ⧉ Window margins                                     │  │
 │  │ ⚲ Pin to top                                         │  │
 │  │ ⓘ App info                                           │  │
 │  ├──────────────────────────────────────────────────────┤  │
 │  │ ⊘ Close                                     amber    │  │  grouped apart
 │  │ ⊘ Hide from the main page                   amber    │  │
 │  │ ⌫ Uninstall                                 amber    │  │  user apps only
 │  ├──────────────────────────────────────────────────────┤  │
 │  │                     Cancel                           │  │
 │  └──────────────────────────────────────────────────────┘  │
 └────────────────────────────────────────────────────────────┘
```

Grouping is the fix for friction #1: *do* the thing (Open), *arrange* it
(Window margins / Pin / App info), *remove* it (Close / Hide / Uninstall —
amber, last, with a hairline separator above). Today all five are one
undifferentiated list.

*Uninstall* is drawn only for an app the user installed, which is also the only
card that reaches seven rows: the sheet's cap scrolls its row area by the last
few dp on a 600dp-tall panel rather than letting the card grow past the screen.
The row opens a question — the one removal that re-opening cannot bring back —
and the red yes-row inside it is the first use of the `destroy` variant:
`danger` stays amber for everything the card itself says, red exists only to
answer a question that was already asked.

### 4.5 Search (new, in-place)

```
 ┌──────────────────────────────────────────────────────────────────────────────┐
 │  ┌────────────────────────────────────────────────────────────────────────┐  │
 │  │ ⌕  map                                                             ✕   │  │  56dp field,
 │  └────────────────────────────────────────────────────────────────────────┘  │  appears in place
 │   ╭───────────╮  ╭───────────╮                                              │
 │   │    ▢      │  │    ▢      │   Organic Maps · Maps                        │
 │   ╰───────────╯  ╰───────────╯                                              │
 │     Organic Maps     Maps                                                    │
 │                                                                              │
 │              No app matches "zzz"                                            │
 │              Try a shorter word.                                             │
 └──────────────────────────────────────────────────────────────────────────────┘
```

The field rises from under the pill's row (240ms), the grid filters with a
`RecyclerView` diff (not a rebuild), and the empty result is a **two-line**
state — the query and a next step — never a bare sentence. Same `squash()`
matcher the shortcuts screen already uses, so `apphub` finds *App Hub*.

### 4.6 Settings — grouped, tokenised, scroll-aware

```
 ┌──────────────────────────────────────────────────────────────────────────────┐
 │  ‹   Settings                                                               │  title 21/26 bold
 ├──────────────────────────────────────────────────────────────────────────────┤
 │  LOOK                                                          Text.Meta caps │
 │  ┌────────────────────────────────────────────────────────────────────────┐  │
 │  │ Show app names                                        [ ●———]          │  │ 72dp rows
 │  ├────────────────────────────────────────────────────────────────────────┤  │
 │  │ Icon size                                            Large         ›    │  │
 │  ├────────────────────────────────────────────────────────────────────────┤  │
 │  │ Adapt to screen                                      ×2            ›    │  │  value in tnum
 │  ├────────────────────────────────────────────────────────────────────────┤  │
 │  │ Icon shape                                           Rounded       ›    │  │
 │  ├────────────────────────────────────────────────────────────────────────┤  │
 │  │ Theme                                                System        ›    │  │
 │  └────────────────────────────────────────────────────────────────────────┘  │
 │                                                                              │
 │  LAYOUT                                                                      │
 │  ┌────────────────────────────────────────────────────────────────────────┐  │
 │  │ Shortcuts                                    All 19 apps          ›    │  │
 │  │ Grid                                              Auto (8 columns) ›    │  │
 │  │ Sort                                              Name (A–Z)       ›    │  │
 │  │ Screen margins                                     0 / 0 / 0 / 0 dp ›   │  │
 │  │ Layout direction                                    System         ›    │  │
 │  └────────────────────────────────────────────────────────────────────────┘  │
 │                                                                              │
 │  BEHAVIOUR                                                                   │
 │  ┌────────────────────────────────────────────────────────────────────────┐  │
 │  │ ⚠ Turn on the running dots                            Enable           │  │  banner row,
 │  │   Usage access is off, so nothing shows as running.                    │  │  only while missing
 │  ├────────────────────────────────────────────────────────────────────────┤  │
 │  │ Include system apps                                   [ ———●]          │  │
 │  │ App window margins                                       2 apps    ›    │  │
 │  └────────────────────────────────────────────────────────────────────────┘  │
 │                                                                              │
 │  MAINTENANCE                                                                 │
 │  ┌────────────────────────────────────────────────────────────────────────┐  │
 │  │ Close all apps                                            Run          │  │  amber
 │  │ Reset settings                                                        │  │  amber, confirm sheet
 │  └────────────────────────────────────────────────────────────────────────┘  │
 └──────────────────────────────────────────────────────────────────────────────┘
```

Section headers convert a 21-row list into four scannable groups; a sticky
header keeps the current section named while scrolling; `Back` as a row is
dropped (the 56dp arrow and the system gesture are enough, and it duplicated
navigation).

### 4.7 Shortcuts — keep the two panes, fix the hierarchy

```
 ┌──────────────────────────────────────────────────────────────────────────────┐
 │  ‹   Shortcuts                                              All 19 apps      │
 ├──────────────────────────────────────────────────────────────────────────────┤
 │  ┌──────────────────────────────┐  ┌───────────────────────────────────────┐ │
 │  │ ⌕ Filter apps            ✕   │  │  Main page            Hold to move    │ │
 │  ├──────────────────────────────┤  ├───────────────────────────────────────┤ │
 │  │            ┌───────────────┐ │  │  ╭────╮ ╭────╮ ╭────╮ ╭────╮          │ │
 │  │  ▢ Chrome  │  All  │  None │ │  │  │ ▢  │ │ ▢  │ │ ▢  │ │ ▢  │          │ │
 │  │            └───────────────┘ │  │  ╰────╯ ╰────╯ ╰────╯ ╰────╯          │ │
 │  │  ▢ Maps                      │  │   Chrome   Maps  Clock  Radio         │ │
 │  │  ▢ Clock                     │  │                                       │ │
 │  │  ▢ Radio                     │  │  the live page, mirroring the grid    │ │
 │  └──────────────────────────────┘  └───────────────────────────────────────┘ │
 └──────────────────────────────────────────────────────────────────────────────┘
```

All/None become a **segmented control** (one pill, two halves, `radius_full`)
instead of two independent blue-text buttons that fail contrast; the count is a
badge in the title bar; the preview pane gets `raised` treatment and the real
grid's tile metrics so what you see is what the board will be.

### 4.8 App window margins — the honest screen

```
 ┌──────────────────────────────────────────────────────────────────────────────┐
 │  ‹   App window margins                                        2 apps        │
 ├──────────────────────────────────────────────────────────────────────────────┤
 │  ┌────────────────────────────────────────────────────────────────────────┐  │
 │  │ Apply window margins                                   [ ●———]         │  │
 │  │ Watch these apps and move the one that comes to the front              │  │
 │  └────────────────────────────────────────────────────────────────────────┘  │
 │  ┌────────────────────────────────────────────────────────────────────────┐  │
 │  │ ⚠ Charger opens fullscreen, and this unit only resizes freeform or     │  │  status banner:
 │  │   multi-window tasks — nothing was moved.                              │  │  icon + 13sp + tone
 │  └────────────────────────────────────────────────────────────────────────┘  │
 │  ┌────────────────────────────────────────────────────────────────────────┐  │
 │  │  ▢ Organic Maps                       200 / 20 / 0 / 0 px    [ ●———]   │  │
 │  │    app.organicmaps                                                     │  │
 │  ├────────────────────────────────────────────────────────────────────────┤  │
 │  │  ▢ Charger                                  Off              [ ———●]   │  │
 │  └────────────────────────────────────────────────────────────────────────┘  │
 └──────────────────────────────────────────────────────────────────────────────┘
```

The status sentence becomes a **banner** with a tone-matched icon
(`hub_ok` / `hub_warn` / `hub_blocked`), the lead clause in `Text.BodyStrong`,
the explanation in `Text.BodyS` — and the row values go tabular so
`200 / 20 / 0 / 0 px` stops reflowing while a wheel is turned.

### 4.9 States — the four the app is missing

| State | Today | Instrument |
|---|---|---|
| **Loading** | empty grid, then tiles pop in | 8 **skeleton tiles** at the real tile metrics, `raised` tone at 55% opacity, pulsing 1000ms ease-in-out, no shimmer sweep (glare) |
| **Error** | swallowed into "No apps to show yet" | full-width panel: `⚠ Could not read the app list.` + `Try again` (primary) — and `MainActivity.reload` keeps the throwable to decide this |
| **Empty (first run)** | one sentence, no action | `Text.Display` numeral (`0 apps`), a sentence, and **`Open settings`** as a primary button — the old design's second reason for the Settings cell, done properly |
| **Filtered empty** | one line | query echoed back + a next step (`Try a shorter word.`) |
| **Permission missing** | a row in Settings | the same offer as a dismissible **banner on the grid**, once, with `Enable` — the driver learns why there are no dots without hunting |

### 4.10 Responsive behaviour

| Width class | Layout |
|---|---|
| `< 600dp` (phone in the workshop) | grid 4 auto columns, Settings single column, Shortcuts **stacks** (list above, preview below), sheets full-width minus 16dp |
| `600–900dp` | 6–8 columns, Shortcuts two panes, sheets 420dp max |
| `> 900dp` (the unit, 1024dp) | 8–10 columns at Medium, Settings can go two-column for groups, sheets 520dp, pill at the bottom-end |

`Adapt to screen` ×2 moves the unit to the `<600dp` class *in dp terms* while
keeping 1024 physical px — which is exactly why the sheet widths, gutters and
targets above are tokens: the same rule produces 4 columns of 2×-sized tiles
instead of 8 cramped ones, and nothing needs a special case.

### 4.11 The file manager — a folder browser, five verbs

The fourth surface, and the first one that is a tool rather than a list of apps.
It stands on the board as a tile of its own: the app's own blue sheet with a
folder mark on it, drawn edge to edge and clipped by the icon-shape setting
exactly like the icons beside it, so it is one of the tiles and not the one tile
drawn differently. The switch that puts it there is *Settings → Behaviour → File
manager*, and the tile's own card writes the same setting.

```
 ┌──────────────────────────────────────────────────────────────────────────────┐
 │  ←   Dashcam                              12.4 GB free                      │  header: the folder's own name, the volume's free space
 ├──────────────────────────────────────────────────────────────────────────────┤
 │  ▤  Internal storage                                                   ›     │  the location row: `raised`, a settings row, 72dp
 │     Dashcam/2026-09-30                                                      │  ... and the sheet behind it is the places sheet
 ├──────────────────────────────────────────────────────────────────────────────┤
 │  ↑  Internal storage                                                        │  the way up: always the first row, named after where it leads
 │  ▤  Private                                                             ⋯   │  a folder: accent glyph, no meta line
 │  ▫  dashcam-0930.mp4                    412 MB · 30 Sep 2026            ⋯   │  a file: 24dp glyph inside a 40dp box, `Text.Meta`, 56dp `⋯`
 │  ▫  notes.txt                             2 KB · 12 Sep 2026            ⋯   │
 ├──────────────────────────────────────────────────────────────────────────────┤
 │     ⟨ Holding “dashcam-0930.mp4” to copy        Paste here   ✕ ⟩             │  the carry bar: `bg_undo`, floating, no timeout
 └──────────────────────────────────────────────────────────────────────────────┘
```

* **The location row is a settings row on purpose** — same tone, same height,
same chevron — because "where this is" and "which value this has" are the same
kind of statement, and the sheet behind it is the same sheet. It holds the
volumes first (each with its free space, the current one checked) and then every
folder between here and that volume's root, so climbing is one tap per step.
* **Four empty states, not one** (4.9's rule, applied to a folder): no storage at
all, a folder that is gone, a folder the platform keeps shut, and a folder that
is simply empty. All four use the task manager's `listEmpty` block.
* **The way up is a plain arrow**, and always the first row. It was drawn as a
folder with an arrow inside it until the glyphs were drawn as a sheet and looked
at: *Move* is a folder with an arrow inside it too, and two rows that mean
different things must not be read twice.
* **The carry bar is the undo bar's twin** — the app's one inverted surface —
with the one difference that matters: it does not time out, because there is a
file in it and a file does not expire. So it carries its own dismiss, which the
undo bar never needed.
* **A row's tap is the one obvious verb; the `⋯` is everything else.** A folder
opens, a file is handed over, and a package opens the menu - the one row whose tap
leads to the sheet, because *Install* is the only thing to do with a package and
putting an app on the unit is the one act here a driver has to have gone looking
for. A file that no app on the unit opens leads to that same sheet - the one place
a subtitle is carried into a menu rather than into a question: the sentence that
would have been the toast, over the verbs, because a tap that ends on a toast has
gone nowhere. The `⋯` keeps its own 56dp target, so the row's tap and its menu
never fight for the same finger.
* **Folders take the accent, files take `ink_600`.** One tone per glyph, and the
tone answers the one question a column of rows raises: which of these are places.
* **`Open with` is the one verb that leaves the app, and it is drawn as leaving**:
a page with its top-right corner open and an arrow going out through the gap - the
shape every other screen on the unit uses for it. A folder's *Open* keeps the
folder glyph, because that one stays in here.
* **`Install` is a row and not a shape**: the one file the platform installs by
itself gets the verb that matches, and no *Open with* beside it - two rows leading
to one installer is one too many - while every other file gets *Open with* first
and then the three it takes.
* **A file leaves as itself and not as a copy of itself.** *Open with* hands the
receiving app this app's own URI for that one file, a token minted for the row the
driver picked, so a film on a card is not written into the app's cache on its way
to a player. Never a `FileProvider` over storage: that provider is the door the
update provider's single path exists to keep shut.
* Rows are `Text.Body` + `Text.Meta`, targets are `touch_min`, and the list's
`paddingBottom` is `touch_car` so the bar can never cover the last row — the same
rules as every other list in the app.

### 4.12 The web browser — a toolbar, a page, and a start block

The fifth surface, and the second tool standing on the board: the unit's own
`WebView` under this app's chrome, drawn as the app's blue sheet with a globe on
it, beside **Files**. The switch is *Settings → Behaviour → Web browser*, and the
tile's own card writes the same setting.

```
 ┌──────────────────────────────────────────────────────────────────────────────┐
 │  ←   The Weather Channel                     weather.com                ⋯   │  header: the page's title, its host
 ├──────────────────────────────────────────────────────────────────────────────┤
 │  ‹   ›   ⌕  weather.com                                        ⟳             │  toolbar: one row, `raised`, 56dp targets
 │  ▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔▔                                              │  progress: `ring_width` bar, `primary_text`
 ├──────────────────────────────────────────────────────────────────────────────┤
 │                                                                              │
 │                             the page itself                                  │
 │                                                                              │
 └──────────────────────────────────────────────────────────────────────────────┘
```

* **The toolbar is a row, not a chrome bar.** It is built from the same `raised`
  surface and `touch_min` targets as every `row_setting`, because the one thing
  the screen has to teach is where the address is: back, forward, an address
  *pill* (`bg_pill` + `search`, `imeOptions=actionGo`) and one shared target on the
  right that is `reload` while a page is loading and `close` while it is not.
  Nothing else competes with the page for the width.
* **The address field is the app's only text input, and it is inside a pill.**
  `refresh` never rewrites it while it still has focus: a driver mid-word does not
  want the field changed under the keyboard.
* **The start block is the empty state, drawn with both hands.** The `listEmpty`
  block's own shape — 48dp glyph, `Text.TitleL`, `Text.Body` — plus two pills out
  of the sheet rows (*Bookmarks*, *History*), so the screen does something before
  the first address rather than standing blank.
* **The page sits on the app's page colour.** `hub_page` is set before the first
  load, so a dark screen is not met by a white flash at night, and the software
  layer (`LAYER_TYPE_SOFTWARE`) keeps the unit's GPU driver out of a `WebView` it
  was never tested against.
* **A failure is a banner, not a page.** A main-frame error and a refused
  certificate both come up as `row_banner` over the page, with *reload* as the
  action and a dismiss; the certificate's wording says the load was stopped,
  because there is no *proceed anyway* in this app to offer.
* **History and bookmarks are sheets, not screens.** One column of addresses and
  nothing else, over the page the driver is already looking at — a list that wants
  a screen of its own has stopped being about the page. Both cap at 40 rows
  (`SHEET_ROWS`) and both fall back to the `listEmpty` block when empty, the same
  block the task manager and every folder in the file manager use.
* **The menu is one sheet with three kinds of row**: the page (*Keep this page* /
  *Drop it*, and *Copy address*), the two lists, *Show in Files* — the way back to
  the folder the platform's downloader writes into, which is the folder the file
  manager already draws, so the two features meet in one row — and `Clear history`
  in the sheet's hazard group behind a question that says the bookmarks are not
  going with it. It is the action card's grammar — do / arrange / remove, amber
  last — in a screen that has fewer verbs to group.
* **A failed download is said out loud, once.** The downloader names this app as
  the receiver of its own completion broadcast; the landing needs no second voice
  (the platform's notification said it), and the failure is the one ending a
  driver would otherwise find out about by looking for a file that is not there.
  The receiver's answer comes from the downloader's own record — a record it does
  not hold is a *forgotten*, not a *failed*.

---

## Part 5 — Motion & Micro-interactions

### 5.1 Tokens

| Token | Value | Curve |
|---|---|---|
| `motion_instant` | 90ms | `linear` — press feedback |
| `motion_fast` | 180ms | `standard` — ripples, value changes, dot fade |
| `motion_medium` | 240ms | `standard` — tiles lifting, search field, filter |
| `motion_slow` | 320ms | `emphasized` — sheets, screens, the edit bar |
| `motion_deliberate` | 420ms | `emphasized` — first-run hint, undo pill |
| `standard` | — | `cubic-bezier(0.2, 0, 0, 1)` |
| `emphasized` | — | `cubic-bezier(0.05, 0.7, 0.1, 1)` |
| `exit` | — | `cubic-bezier(0.3, 0, 1, 1)` |

In code, `androidx.core.view.animation.PathInterpolatorCompat.create(...)` — it
is already on the classpath through `androidx.core:core`. All durations are
tokenised in `values/integers.xml` so the whole app slows together if it ever
needs to.

### 5.2 Where each one is used

| Moment | Motion |
|---|---|
| Screen push (Settings, Shortcuts) | content fades 0→1 + slides 12dp from `end`, 320ms `emphasized`; the header title **shares** the element (a `TitleL` view morphing to the Settings title) |
| Sheet open / close | card: `y +32dp → 0` + fade, 320ms `emphasized` in / 240ms `exit` out; scrim 0→32% |
| Tile tap | scale `1 → 0.98 → 1`, 90ms each way, then the launch |
| Tile hold → Lift | 400ms hold → one `LONG_PRESS` haptic, then tiles scale to 1.02 with a 30ms column stagger, 240ms `emphasized` |
| Tile drag | the dragged tile lifts to `elevation 8`, follows the finger 1:1, others reflow 180ms `standard`; on drop the order is written and a single `KEYBOARD_TAP` haptic fires |
| Running detection | the ring and dot **fade in** 180ms `fast` when a process appears — never a pop, never a bounce (a car screen must not twitch) |
| Close an app | row closes: the tile dims to 55% for 180ms, then fades out over 240ms; the toast is replaced by the undo pill (below) |
| Close all | the confirm sheet, then tiles **cascade** out at 20ms each; a determinate count (`7 / 19`) is shown if the batch outlasts 400ms |
| Number wheel | each turn updates the value text in 90ms with no layout shift (tabular figures); the value row behind is live |
| Value change (switch, choice) | the value text cross-fades 180ms; the switch itself uses the platform animation, unmodified |
| Error / refused | the banner slides down 240ms with a single `LONG_PRESS` haptic; it is **never** red |
| Undo pill | slides up 420ms, holds 5s, exits 240ms; a swipe-down dismisses it early |

### 5.3 Haptics (API 28-safe map)

| Event | Constant | Fallback |
|---|---|---|
| Tile hold / Lift entry | `LONG_PRESS` | `VIRTUAL_KEY` |
| Drag drop / order written | `KEYBOARD_TAP` | `CLOCK_TICK` |
| Sheet opened | *(none — a car should not buzz for a menu)* | — |
| Destructive confirm | `LONG_PRESS` twice, 80ms apart | `VIRTUAL_KEY` |
| Refusal / error banner | `LONG_PRESS` | `VIRTUAL_KEY` |

`HapticFeedbackConstants.CONFIRM` is API 30, so it is never used. The unit may
have no vibrator at all: every haptic is a courtesy, never the only feedback.

### 5.4 Skeleton vs spinner

- **Skeleton** when the shape of the answer is known: the grid, the shortcuts
  list, the window-margins list. Slick animations on a car screen are called
  *skeletons* not *shimmers*, and they pulse opacity only.
- **Spinner** when the duration is unknown and the shape is not: `Close all`
  beyond 400ms, the root probes on the window screen.
- **Never a blocking spinner.** The board is always usable; a slow read shows
  skeletons the driver can ignore.

---

## Part 6 — UX Fixes

| # | Before | After | Why |
|---|---|---|---|
| 1 | Close / Pin / App info / Window / Hide are behind an invisible long-press | **Edit mode (The Lift)** + a `⋯` button on every lifted tile; tap opens the app card sheet | A hidden verb in a moving vehicle is a safety problem. The mode is visible, named and escapable |
| 2 | *Close App Hub* is a red tile in the icon grid | Moved to the hub sheet, amber, with a confirm sheet | Quitting the surface you are looking at is not a peer of "open Maps" |
| 3 | Settings is the last cell of the grid | The floating **pill** (bottom-end, always reachable) | Reaching the tools must not require scrolling past 40 apps |
| 4 | The page order is editable only in Settings → Shortcuts, in a mock grid | The **real grid** is draggable in edit mode; the shortcuts preview stays as a second, larger place to do it | Editing a simulation of a thing is a design smell |
| 5 | No loading state (a false empty, then 19 tiles popping in) | Skeleton tiles at the real tile metrics | A false empty state teaches the wrong lesson on first run |
| 6 | Errors render as "No apps to show yet" | Retryable error panel; `reload` keeps the throwable | "Nothing here" and "I failed" are different sentences |
| 7 | 12dp dot, colour only | 3dp ring + 8dp dot + the state named in the tile's description | Shape reinforces state; colour-only fails for ~8% of men |
| 8 | Off-grid spacing across 14 layouts | `space_*` tokens, one 4/8dp rhythm | The grid stops looking assembled |
| 9 | blue small text at 4.13:1 | `blue_700` = 6.07:1 for text; `blue_600` for fills with ≥17sp white | Fails AA today on every value row |
| 10 | secondary text at 4.52:1, 11–12sp | `#475467` at 6.84:1, minimum 12sp, 14sp for descriptions | Zero headroom becomes comfortable |
| 11 | 44dp back target (7mm) | 56dp (8.9mm), glyph unchanged at 24dp | Under the car floor for a moving vehicle |
| 12 | Destructive actions answer with a dead toast | **Undo pill** (5s) for Hide / Pin / Close, on the grid itself | Reversible by default, which is what the app's own honesty implies |
| 13 | No explanation for missing dots | Dismissible grid banner, once, with `Enable` | The permission is a *concept* the driver must be told, not a settings row to find |
| 14 | Empty grid = a sentence, no action | Empty panel with a primary `Open settings` | An empty state must offer the next step |
| 15 | Sheet selection invisible to TalkBack | `contentDescription` includes "selected"; the check node stays decorative; rows expose `isSelected` | Real screen-reader gap |
| 16 | Status sentences are the weakest type on screen | Tone-matched banners with an icon, 2-line hierarchy | The app's best idea (telling the truth) deserves the second-strongest element on the screen |
| 17 | Three copy-pasted headers | One `view_header.xml` + `Text.TitleL` | The screen chrome stops drifting per screen |
| 18 | `Back` duplicated as a settings row | Removed; 56dp arrow + system gesture | Redundant navigation costs a row and a scroll |
| 19 | Docs drift (minSdk 28 vs 29, version) | Fix the README/build comment while implementing | The doc is the contract |

**Accessibility pass, concretely:** every tile's `contentDescription` becomes
`"Chrome, running"` / `"Maps"`; the dot loses its own description (double
announcement today); the pill is `"Tools"`; sheet rows are focusable with
`isSelected` exposed; the edit bar's `⋯` buttons read `"Chrome, more actions"`;
`importantForAccessibility` is dropped from decorative rings; touch targets are
tokenised at ≥56dp; focus order follows the grid row-major; the undo pill is
announced with `announceForAccessibility`. RTL: the pill, dot, ring and edit bar
mirror through `start`/`end`; numeric values get `textDirection="ltr"` +
`tnum` so Persian digits never reorder a fraction.

---

## Part 7 — The Apple Touch

The turns of the screw that separate "clean" from "designed":

1. **The board lifts.** Every interaction after that reads as physical: pick up,
   move, put down, nothing teleports.
2. **Depth by tone, not by shadow.** White on grey, `#171A21` on `#0F1115` — the
   surfaces separate the way Apple's do, without a single hard shadow on a panel
   that would make it obvious.
3. **Two users, one grid.** The light ramp is a calm daylight surface; the night
   ramp never puts `#FFFFFF` on `#000000`, so a night drive does not halate.
4. **Numbers don't move.** `tnum` everywhere a value can change under a finger.
   `200 / 20 / 0 / 0 px` stays perfectly still while the wheel turns behind it.
5. **The corner of the tile is the corner of the icon.** `radius_l` = `0.24 ×
   size` = the masker's own rounding factor.
6. **Amber, not red.** The palette respects what red means in a car.
7. **Restraint in motion.** One signature transition (the lift), everything else
   ≤320ms and monochrome-consistent. Nothing bounces.
8. **Whitespace as hierarchy.** `space_6` between groups; a section header means
   the eye never has to count rows.
9. **The undo pill.** The cheapest luxury in the app: it converts every
   destructive action into a reversible one and disappears.
10. **Copy that tells the truth and stops.** `Charger opens fullscreen, and this
    unit only resizes freeform or multi-window tasks — nothing was moved.`
    Sentence case, consequence named, no blame, no exclamation mark.
11. **The pill gets out of the way.** It fades to 40% while the board scrolls and
    returns in 240ms — present when needed, invisible when reading.
12. **One first-run breath.** On the very first launch only, the pill pulses once
    (420ms) with the label `Settings & search` — discovery without a tutorial.

---

## Part 8 — Implementation

### 8.1 New token layer

`res/values/dimens.xml` — the file the app is missing:

```xml
<resources>
    <!-- spacing: 4dp base, 8dp rhythm -->
    <dimen name="space_1">4dp</dimen>
    <dimen name="space_2">8dp</dimen>
    <dimen name="space_3">12dp</dimen>
    <dimen name="space_4">16dp</dimen>
    <dimen name="space_5">24dp</dimen>   <!-- screen gutter -->
    <dimen name="space_6">32dp</dimen>   <!-- between sections -->
    <dimen name="space_7">48dp</dimen>   <!-- empty-state breathing -->

    <!-- grid -->
    <dimen name="gutter_grid">12dp</dimen>
    <dimen name="gap_cell">8dp</dimen>
    <dimen name="cell_padding">12dp</dimen>
    <dimen name="label_allowance">34dp</dimen>   <!-- one Text.Body line + gap -->

    <!-- targets -->
    <dimen name="touch_min">56dp</dimen>
    <dimen name="touch_car">88dp</dimen>
    <dimen name="row_min_height">72dp</dimen>
    <dimen name="pill_height">56dp</dimen>
    <dimen name="icon_row">24dp</dimen>
    <dimen name="icon_dense">20dp</dimen>
    <dimen name="icon_banner">40dp</dimen>
    <dimen name="icon_empty">48dp</dimen>
    <dimen name="stroke_hairline">1dp</dimen>
    <dimen name="ring_width">3dp</dimen>

    <!-- radii -->
    <dimen name="radius_xs">8dp</dimen>
    <dimen name="radius_s">12dp</dimen>
    <dimen name="radius_m">16dp</dimen>
    <dimen name="radius_l">22dp</dimen>
    <dimen name="radius_xl">28dp</dimen>
    <dimen name="radius_full">999dp</dimen>

    <!-- sheets -->
    <dimen name="sheet_max_width">520dp</dimen>
    <dimen name="sheet_min_width">320dp</dimen>
    <dimen name="sheet_edge_inset">16dp</dimen>

    <!-- elevation -->
    <dimen name="elev_raised">1dp</dimen>
    <dimen name="elev_floating">8dp</dimen>
    <dimen name="elev_dragging">12dp</dimen>
</resources>
```

`res/values/colors.xml` (night overrides in `values-night`, exactly as today):

```xml
<resources>
    <!-- surfaces -->
    <color name="hub_page">#F2F4F7</color>
    <color name="hub_surface">#FFFFFF</color>
    <color name="hub_stroke">#E7EAF0</color>
    <color name="hub_scrim">#520B0F19</color>

    <!-- ink -->
    <color name="hub_ink_900">#101828</color>
    <color name="hub_ink_700">#344054</color>
    <color name="hub_ink_600">#475467</color>   <!-- secondary text: 6.84:1 on hub_page -->
    <color name="hub_ink_400">#98A2B3</color>

    <!-- brand: 600 fills, 700 text (see the contrast table) -->
    <color name="hub_blue_600">#2F6FED</color>
    <color name="hub_blue_700">#1D4ED8</color>
    <color name="hub_blue_100">#E8EFFE</color>

    <!-- semantics -->
    <color name="hub_running">#067647</color>
    <color name="hub_recent">#98A2B3</color>
    <color name="hub_warn">#B54708</color>
    <color name="hub_danger">#B42318</color>
    <color name="hub_ok">#067647</color>
    <color name="hub_blocked">#475467</color>
    <color name="hub_ripple">#1A2F6FED</color>

    <!-- legacy aliases so nothing breaks mid-refactor -->
    <color name="bg">@color/hub_page</color>
    <color name="card">@color/hub_surface</color>
    <color name="card_stroke">@color/hub_stroke</color>
    <color name="text_primary">@color/hub_ink_900</color>
    <color name="text_secondary">@color/hub_ink_600</color>
    <color name="primary">@color/hub_blue_600</color>
    <color name="primary_text">@color/hub_blue_700</color>
    <color name="state_running">@color/hub_running</color>
    <color name="danger">@color/hub_danger</color>
    <color name="warn">@color/hub_warn</color>
    <color name="ripple">@color/hub_ripple</color>
</resources>
```

```xml
<!-- values-night/colors.xml -->
<resources>
    <color name="hub_page">#0F1115</color>
    <color name="hub_surface">#171A21</color>
    <color name="hub_stroke">#242833</color>
    <color name="hub_scrim">#70000000</color>

    <color name="hub_ink_900">#ECEFF5</color>
    <color name="hub_ink_700">#C6CEDA</color>
    <color name="hub_ink_600">#A9B2C0</color>
    <color name="hub_ink_400">#6C7686</color>

    <color name="hub_blue_600">#3B7BF5</color>
    <color name="hub_blue_700">#7AA2FF</color>
    <color name="hub_blue_100">#12234F</color>

    <color name="hub_running">#4ADE80</color>
    <color name="hub_recent">#A9B2C0</color>
    <color name="hub_warn">#F5B84A</color>
    <color name="hub_danger">#FF7A70</color>
    <color name="hub_ok">#4ADE80</color>
    <color name="hub_blocked">#A9B2C0</color>
    <color name="hub_ripple">#33FFFFFF</color>

    <color name="bg">@color/hub_page</color>
    <color name="card">@color/hub_surface</color>
    <color name="card_stroke">@color/hub_stroke</color>
    <color name="text_primary">@color/hub_ink_900</color>
    <color name="text_secondary">@color/hub_ink_600</color>
    <color name="primary">@color/hub_blue_600</color>
    <color name="primary_text">@color/hub_blue_700</color>
    <color name="state_running">@color/hub_running</color>
    <color name="danger">@color/hub_danger</color>
    <color name="warn">@color/hub_warn</color>
    <color name="ripple">@color/hub_ripple</color>
</resources>
```

`res/values/type.xml` — the scale as text appearances:

```xml
<resources>
    <style name="Text" parent="android:Widget.TextView" />

    <style name="Text.Display">
        <item name="android:textSize">34sp</item>
        <item name="android:lineHeight">40dp</item>   <!-- API 28+; minSdk is 28 -->
        <item name="android:textStyle">bold</item>
        <item name="android:letterSpacing">-0.02</item>
        <item name="android:textColor">@color/text_primary</item>
    </style>

    <style name="Text.TitleL">
        <item name="android:textSize">21sp</item>
        <item name="android:textStyle">bold</item>
        <item name="android:letterSpacing">-0.01</item>
        <item name="android:textColor">@color/text_primary</item>
    </style>

    <style name="Text.TitleM">
        <item name="android:textSize">17sp</item>
        <item name="android:textStyle">bold</item>
        <item name="android:textColor">@color/text_primary</item>
    </style>

    <style name="Text.Body">
        <item name="android:textSize">15sp</item>
        <item name="android:textColor">@color/text_primary</item>
    </style>

    <style name="Text.BodyS">
        <item name="android:textSize">14sp</item>
        <item name="android:textColor">@color/text_secondary</item>
    </style>

    <style name="Text.Meta">
        <item name="android:textSize">13sp</item>
        <item name="android:textColor">@color/text_secondary</item>
        <item name="android:letterSpacing">0.01</item>
        <item name="android:fontFeatureSettings">tnum</item>
        <item name="android:textDirection">ltr</item>   <!-- values stay LTR in RTL -->
    </style>

    <style name="Text.Caption">
        <item name="android:textSize">12sp</item>
        <item name="android:textColor">@color/text_secondary</item>
    </style>

    <!-- section headers on Settings: a small caps-ish label, not a title -->
    <style name="Text.Section">
        <item name="android:textSize">13sp</item>
        <item name="android:textStyle">bold</item>
        <item name="android:letterSpacing">0.08</item>
        <item name="android:textAllCaps">true</item>
        <item name="android:textColor">@color/text_secondary</item>
    </style>
</resources>
```

`res/values/integers.xml` + `Motion.kt`:

```xml
<resources>
    <integer name="motion_instant">90</integer>
    <integer name="motion_fast">180</integer>
    <integer name="motion_medium">240</integer>
    <integer name="motion_slow">320</integer>
    <integer name="motion_deliberate">420</integer>
</resources>
```

```kotlin
/**
 * Instrument: the app's motion + haptic vocabulary in one place.
 *
 * The curves are the system's three: standard for things that answer a touch,
 * emphasized for things that arrive, exit for things that leave. Durations come
 * from integers.xml so the whole surface can be slowed together.
 */
object Motion {
    fun standard(context: Context): Interpolator =
        PathInterpolatorCompat.create(0.2f, 0f, 0f, 1f)

    fun emphasized(context: Context): Interpolator =
        PathInterpolatorCompat.create(0.05f, 0.7f, 0.1f, 1f)

    fun exit(context: Context): Interpolator =
        PathInterpolatorCompat.create(0.3f, 0f, 1f, 1f)

    fun duration(context: Context, @IntegerRes token: Int): Long =
        context.resources.getInteger(token).toLong()

    /** A courtesy, never a requirement: the unit may have no vibrator. */
    fun tap(view: View, kind: Kind = Kind.KEY) {
        val constant = when (kind) {
            Kind.HOLD -> HapticFeedbackConstants.LONG_PRESS
            Kind.KEY -> HapticFeedbackConstants.KEYBOARD_TAP
        }
        view.performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
    }

    enum class Kind { HOLD, KEY }
}
```

The existing `IconShape.ROUNDED` factor changes from `0.24f` to
`radius_l / sizePx` so tile and mask share one curve:

```kotlin
IconShape.ROUNDED -> {
    val r = radiusPxFor(size)   // = size * (22f / 96f) at the reference tile
    path.addRoundRect(0f, 0f, size, size, r, r, Path.Direction.CW)
}
```

### 8.2 The pieces that carry the redesign

| Piece | File | Content |
|---|---|---|
| Header | **new** `view_header.xml` | 56dp back target, `Text.TitleL`, optional trailing count/action — included by all three inner screens (kills #17) |
| Tile | `item_app.xml` | `radius_l` background, `cell_padding`, ring `View` behind the icon (`ring_width`, `radius_l`, `hub_running`), `⋯` button (`visibility=gone` outside edit mode), `Text.Body` label |
| Ring | **new** `bg_tile_ring.xml` | `<shape>` rectangle, `radius_l`, `<stroke stroke_width="@dimen/ring_width" color="@color/hub_running"/>` — drawn behind the icon, sized `iconPx + 2*space_1` |
| Pill | **new** `bg_pill.xml` + `view_hub_pill.xml` | `radius_full`, `hub_blue_600`, `elev_floating`, `touch_car` wide, opens `HubSheet` |
| Hub sheet | **new** `dialog_hub_sheet.xml` | bottom-anchored `radius_xl`, rows in the `SheetRow` shape, `sheet_max_width` |
| Banners | **new** `bg_banner.xml` + `view_banner.xml` | `radius_m`, tone wash (`hub_blue_100`/`warn` 8%), 40dp icon, `Text.BodyStrong` lead + `Text.BodyS` body |
| Undo pill | **new** `view_undo.xml` | `radius_full`, `elev_floating`, message + `Undo`, 5s life |
| Sheet | `dialog_sheet.xml`, `dialog_sheet_row.xml` | `radius_xl`, 56dp rows, `Text.Body`/`Text.BodyS`, `radius_s` ripples, check → `check-circle` 20dp `blue_700` |
| Settings | `activity_settings.xml`, `row_setting.xml` | 72dp rows, groups with `Text.Section` headers, values as `Text.Meta` + `tnum`, 56dp switches, the back row removed |
| Shortcuts | `activity_shortcuts.xml`, `row_filter.xml`, `row_shortcut.xml` | segmented All/None, badge count, `raised` preview pane at real tile metrics |
| Window margins | `activity_window_margins.xml`, `row_window_app.xml` | master row, status **banner**, rows at 72dp with `Text.Meta` values |
| Grid | `activity_main.xml` | `gutter_grid`, `paddingBottom = touch_car + space_4`, the pill as a sibling of the RecyclerView |
| Skeletons | **new** `view_skeleton_tile.xml` | tile-shaped, `hub_surface` at 55%, 1000ms opacity pulse |

### 8.3 Behaviour changes in Kotlin

| Change | Where | Size |
|---|---|---|
| `adaptToScreen` etc. unchanged — the token layer now scales with it | `Prefs.kt` | none |
| Tile hold (400ms) enters **edit mode**; `⋯` and drag only exist there | `MainActivity`, `AppAdapter` | ~120 lines |
| Grid drag writes `Prefs.pageOrder` (reuse the shortcuts screen's `ItemTouchHelper`) | `MainActivity`, `AppAdapter` | ~60 lines |
| The pill + hub sheet replace the Settings/Close cells | `MainPage.cells` loses two entries; new `HubSheet` | ~80 lines |
| Loading / error / empty states | `MainActivity.reload` keeps the throwable; three new item types in `AppAdapter` | ~70 lines |
| Running ring + dot + description | `AppAdapter.AppCell.bind` | ~25 lines |
| Undo pill for Hide / Pin / Close | `MainActivity` + new `UndoBar.kt` | ~70 lines |
| Search | MainActivity + a `FilterAdapter` reusing `ShortcutAdapter.squash` | ~80 lines |
| Banner for missing usage access | `MainActivity` first-run flag in `Prefs` | ~30 lines |
| Section headers + sticky behaviour | `SettingsActivity` (rows are already built in code) | ~40 lines |
| Selected state exposed to a11y | `Sheet.rowView` | ~10 lines |

### 8.4 Phasing (each phase ships on its own)

| Phase | Content | Why first |
|---|---|---|
| **0** | `dimens.xml`, `colors.xml`, `type.xml`, `integers.xml`, legacy aliases, `Motion.kt` | Zero behaviour change, every later phase depends on it, and it is what makes `Adapt to screen` ×2 correct |
| **1** | Tokens applied to the four existing screens + `view_header.xml` + contrast fixes (#8–11, #17, #18) | The app instantly looks drawn by one hand; no new interaction to test |
| **2** | Loading / error / empty states (#5, #6, #14) and the a11y pass (#7, #15) | Correctness before flourish |
| **3** | The pill + hub sheet (#2, #3), the grid becomes apps-only | Removes the dangerous tile with the smallest diff |
| **4** | The Lift: edit mode, `⋯`, drag-to-reorder on the real grid (#1, #4) | The signature interaction; needs phase 3's pill to hold `Done` |
| **5** | Search, undo pill, running ring, usage-access banner | The delight layer |
| **6** | Motion + haptics polish, first-run breath, banner tones | The last 5% that reads as the first 50% |

### 8.5 Before → after, screen by screen

**Grid.** Before: 8 columns of 116dp tiles with 5dp margins, a 13sp clipped label,
a 12dp green dot, and a red *Close* peer at the end of the scroll. After: 8
columns at Medium of tiles with `radius_l` and `gap_cell`, 15sp labels, a running
ring plus dot, no peer tiles at all, and a floating pill that opens tools from
anywhere — the board is only apps, and the tools are one tap away.

**Actions.** Before: a 900ms hold, an undifferentiated five-row sheet, no
visual difference between *App info* and *Hide from the main page*. After: a
400ms hold lifts the whole board, every tile shows `⋯`, and the sheet groups
*do* / *arrange* / *remove* with amber last.

**Settings.** Before: 21 identical rows, seven different type sizes, and a
`Close all apps` row as prominent as `Show app names`. After: four named groups,
one type scale, values in tabular figures on `Text.Meta`, the destructive two at
the bottom behind a section header, and a sticky group label while scrolling.

**Shortcuts.** Before: two blue-text buttons at 4.13:1, a hint at 12sp, and a
preview that lays out differently from the grid. After: a segmented control, a
count badge, `raised` preview panes built from the grid's own metrics.

**Window margins.** Before: the app's best sentence at 12sp in a card. After: a
tone-matched banner with an icon, and the same sentence in two type sizes.

**Sheets.** Before: centred cards at `radius 24dp` with 13dp row padding and no
grouping. After: bottom-anchored `radius_xl` cards, 56dp rows, grouping
separators, `check-circle` selection that TalkBack can read, and the grid still
visible behind a 32% scrim.

### 8.6 Copy — English and Persian

Sentence case, second person, consequence named. New strings:

| Key | EN | FA |
|---|---|---|
| `tools` | Tools | ابزارها |
| `search_apps` | Find an app | جستوجوی برنامه |
| `edit_page` | Edit the page | ویرایش صفحه |
| `done` | Done | تمام |
| `drag_hint` | Hold a tile to move it | برای جابهجایی، کاشی را نگه دارید |
| `reset_order` | Reset order | بازگرداندن ترتیب |
| `more_actions` | %1$s, more actions | %1$s، کارهای بیشتر |
| `close_hub_title` | Close App Hub? | مرکز برنامهها بسته شود؟ |
| `close_hub_message` | The apps you have open keep running. | برنامههای باز همچنان اجرا میشوند. |
| `undo` | Undo | بازگرداندن |
| `hidden_undo` | %1$s hidden from the main page | %1$s از صفحه اصلی پنهان شد |
| `loading_apps` | Reading your apps… | در حال خواندن برنامهها… |
| `load_failed` | Could not read the app list. | فهرست برنامهها خوانده نشد. |
| `try_again` | Try again | تلاش دوباره |
| `empty_title` | No apps yet | هنوز برنامهای نیست |
| `empty_message` | Apps you install on this unit appear here. | برنامههایی که روی این دستگاه نصب میکنید اینجا نمایش داده میشوند. |
| `open_settings` | Open settings | باز کردن تنظیمات |
| `no_match` | No app matches “%1$s” | برنامهای با «%1$s» پیدا نشد |
| `no_match_hint` | Try a shorter word. | یک واژه کوتاهتر امتحان کنید. |
| `dots_off_title` | Running apps aren’t marked | برنامههای باز نشانهگذاری نمیشوند |
| `dots_off_message` | Usage access is off, so nothing can show as running. | دسترسی استفاده خاموش است، پس هیچ برنامهای بهعنوان باز نشان داده نمیشود. |
| `enable` | Enable | فعالکردن |
| `close_all_confirm` | Close all %1$d apps? | همه %1$d برنامه بسته شوند؟ |
| `close_all_consequence` | Apps with unsaved work may lose it. | ممکن است کار ذخیرهنشده برنامهها از بین برود. |
| `selected` | selected | انتخابشده |
| `running_state` | running | در حال اجرا |
| `status_applied` | %1$s is running inside its rectangle. | %1$s اکنون داخل مستطیل خود اجرا میشود. |
| `status_refused` | The unit refused to move %1$s, so nothing was changed. | دستگاه از جابهجایی %1$s خودداری کرد، بنابراین چیزی تغییر نکرد. |
| `status_no_root` | Root access is not available, so no window can be moved. | دسترسی روت در دسترس نیست، بنابراین هیچ پنجرهای جابهجا نمیشود. |

Reworded (the honest voice, tightened):

| Key | Before | After (EN) |
|---|---|---|
| `close_all_message` | `Close %1$d apps?` | `Close all %1$d apps? Apps with unsaved work may lose it.` |
| `empty` | `No apps to show yet` | `No apps yet` + `Apps you install on this unit appear here.` + `Open settings` |
| `closed_toast` | `%1$s closed` (dead) | the undo pill, no toast |

### 8.7 Docs and build housekeeping

- `README.md`: add a line pointing at this document; correct `minSdk 28` and the
  version; correct the "API 29" claim where it contradicts the manifest.
- `build.gradle.kts`: `versionCode 3`, `versionName 1.2`.
- `minSdk` stays **28** in code — every API used above exists at 28
  (`PathInterpolatorCompat`, `HapticFeedbackConstants.KEYBOARD_TAP`,
  `WindowInsetsCompat`, `NumberPicker`, `fontFeatureSettings`, `lineHeight`).
  `HapticFeedbackConstants.CONFIRM` (30), `MaterialShapeDrawable` and Material
  components are deliberately **not** used: the dependency graph must not grow.

---

## Part 9 — What building it changed

A specification written against a running app is still a specification. Applying
this one to `app_hub/` turned up thirteen places where the document, the layouts
and the code disagreed — not one of them a matter of taste. They are recorded
here because the number is the point: a design that cannot survive contact with
its own implementation is a drawing.

### 9.1 The screen that never opened

`Shortcuts` had become a two-line panel in the layouts (`listEmptyTitle` plus a
body line) while the screen still held the same view as a `TextView`. The cast
threw inside `onCreate`, so the screen did not open at all — the redesign had
shipped a dead half of the app that no amount of styling would have revealed.
Lesson, now applied to every retyped view: the layout and the field that holds it
are one change, not two.

### 9.2 The badge had to be re-derived, not placed

The document said the lifted tile's `⋯` sits *in the tile's own corner*. Built
that way, it is a 40dp circle drawn across 60% of a 64dp icon's art — legible,
and wrong. What a badge on an icon actually is (iOS has drawn it this way for
fifteen years) is a small disc whose **centre sits on the artwork's corner**: half
over the picture, half in the whitespace beside it.

Stating it that way also fixes it at every icon size. The badge is drawn inside a
box the adapter *sizes* — the icon's own box — and carried out of it by half its
own width and height (`badge_nudge_x` / `badge_nudge_y`, 28dp each). The offset is
therefore the same number at Small and at Extra large, because the thing it hangs
off is the icon rather than the tile. Two consequences the specification had not
considered:

* Every ancestor between the tile and the badge has to stop clipping
  (`clipChildren="false"` **and** `clipToPadding="false"`; the padding box is what
  caught it first). A clipping ancestor does not draw a small circle badly, it
  draws a quarter of one.
* The **running ring** used to enlarge its own view (`icon + 8dp`), which moved
  the box the badge was anchored to whenever an app was running. It now paints
  outside the icon's box by a negative inset (`ring_outset`), so the box and the
  badge are one geometry.

### 9.3 Six verbs have to fit one panel

Sheet rows went to a car-sized 72dp and the app card's six rows immediately ran
off the bottom of the unit's 1024×600 panel. The document's rule ("a verb the
driver cannot see is a verb that is not there") and its own target ("14mm for
anything primary") are in tension at 6 rows × 72dp, and the resolution has to be
arithmetic rather than a preference:

```
6 rows × 64dp + 2 hairlines (34dp) + header (56dp) + padding (40dp) = 528px
window height (600 − status bar)                                     = 576px
```

Hence `row_sheet_height` 64dp (10.2mm — between the 9mm secondary floor and the
14mm primary one) and `MAX_ROWS_HEIGHT` 0.72. The numbers are now stated as a
constraint the way the grid's targets are: **the longest menu in the app must fit
the unit's panel without scrolling.**

### 9.4 Grouping existed and was unused

`SheetRow.groupStart` and `dialog_sheet_divider.xml` were both in the code and
neither was used, so the app card was a flat column of six equal verbs. Three
groups are now visible — *do it* / *arrange it* / *remove it* — as is the pair in
the tools sheet and the rectangle-versus-switch split in an app's edge sheet. A
hairline is the cheapest hierarchy there is.

### 9.5 A count is a quantity

`%1$d apps` read **1 apps** on the window screen's header. `<plurals>` now, in
both languages, with the Persian form deliberately single-item: the language, not
the app, decides what a quantity looks like.

### 9.6 One app, one icon

Four screens draw the same icon — the board, the shortcuts list, the window list,
the app card — at three different sizes, and only one of them was clipping it
into the chosen shape. They now share one `IconCache` keyed by package, size and
shape, which is both cheaper and more correct: the *Icon shape* setting reaches
every screen that shows an icon, and the app card's header icon is the tile's own
drawing rather than a second rendering of it.

Under it, a real bug: `IconMasker` drew an adaptive icon by re-binding *the app's
own* `AdaptiveIconDrawable`'s layers to a raster size. One instance is shared by
every view that shows that icon, so the shortcuts list was left drawing a
stretched icon on a black square while the same icon was perfect on the board two
screens away. The masker now draws copies (`constantState.newDrawable().mutate()`).
**A drawable you did not create is not yours to set bounds on.**

### 9.7 One widget stays the platform's

Three attempts to give the app its own switch — a style, inline `android:track` /
`android:thumb`, and the control's own tint attributes — were all overridden by
the widget's internal tint, which repaints the track from the theme whether or not
a drawable is supplied. The switch therefore stays the platform's widget wearing
the app's accent, and it is the **one** deliberate exception to "nothing here is
Material". It is defensible on its own terms: a switch is a *control*, not
furniture, its shape is a gesture a driver has already learned, and a surface that
fights its framework on the one widget that carries state is a maintenance debt
with no design gain. Everything the system can own, it owns; this it does not.

### 9.8 A choice has to be visible where it was made

A user's report, in three parts, and two of them were the same class of bug twice.

**The setting did not show its own result.** Choosing *Circular* in the shape
picker wrote the preference and closed the sheet; the row still read *Rounded*
until the screen was left and re-opened, which reads as a setting that did not
take. The cause was one missing call: `refresh()` existed, `onResume` called it,
and nothing called it when a choice was applied. It is now called from the one
place every picker goes through, so the row is re-read the moment the value is
written — and so are the rows that only make sense as a *derived* value. That
second half is what the report did not mention and the system depended on:
`Icon size` is written by the same row type, and the Grid row's value
(`Auto (8 columns)`) is computed from it, so it too had to be re-read. Verified
through the UI: *Extra large* turns the Grid row into `Auto (7 columns)` in place.
**A value that is derived from another setting is the same bug waiting for
someone to notice it** — the audit after the report checked every row on every
screen that shows stored state, and `WindowMarginsActivity` and
`ShortcutsActivity` already did this correctly.

**The badge was never the size the document drew it at.** 9.2 settled where the
badge's centre goes and left its size to a literal; the literal drifted. The
circle had grown to 28dp while the *glyph* inside it was still being drawn at the
whole 56dp of its view — three dots 40dp wide inside a 28dp circle, bulging out
of it. That is what "the `⋯` is way too big" was about, and no amount of
re-measuring the circle alone would have found it. Both are now one decision:
the circle is a fraction of the icon (24dp at the reference 64dp icon, its
glyph 20dp inside that), and the fraction has a **ceiling** rather than being a
multiplier —

```
circle = min(icon × 24/64, 24dp)      the badge is an affordance: never heavier
                                      than the badge a driver recognises
dot    = max(icon × 12/64, 12dp)      the dot is a reading: never smaller than
                                      legible from the seat
```

— because the two marks have opposite obligations. The badge may shrink with a
smaller icon (at Small the same 24dp circle covered half the artwork) but must not
grow with a larger one; the dot may grow with a larger icon but must not shrink
below legibility. The XML keeps describing the drawing at the reference size and
`AppAdapter.badge` scales that one description by mutating the insets of its own
layers, so the pile of detail — wash, hairline, ripple, target — stays in one
place. Measured on the emulator at 48/64/96dp icons: 18px, 25px, 25px.

**An empty container still costs a strip of card.** The tools menu wore ~100px of
nothing above its first row: `sheetHeader` is a row with `minHeight` of one touch
target, and a menu has no title to put in it. It is now `GONE` when it has no icon,
no title and no subtitle (and `sheetMessage` is a sibling of it, so a message-only
sheet still shows its message). 29px is what a card with no header should measure,
and all five pickers plus the app card were re-checked for the same mistake.

The same pass also removed the last values living outside the token system: the
search field's 17sp and the numeric sheet's 21sp were literals that happened to
match `TitleM` / `TitleL` while being neither (they are `Text.Field` and
`Text.Number` now), the tile icon and the skeleton were 56dp and 64dp literals
(`icon_tile`), the running dot was a 12dp literal (`dot_state`), the wheel's column
was a bare `200dp` (`number_column`), and `ic_more.xml`'s comment still claimed a
28dp glyph on a 48dp target.

### 9.9 What building the browser round changed

* **The tests found the address the rule missed.** The first JVM test source set
  pinned the address-or-search rule as a table, and two rows failed: `192.168.1.1`
  — the router-and-dashcam address the README names as a core use — went to the
  search engine, because its last dot stands before a one-letter group and the
  suffix rule wanted letters; and `localhost:8080` failed for the same reason a
  port is not letters. Both are fixed in the rule now, and both are rows in the
  table: the test that catches a bug is the one that keeps it caught.
* **A gate that never ran against its failure is a decoration.** The smoke
  script's browser probe was proven both ways before it shipped — a stub `adb`
  answered as a browser that loads the page and as one that never does, and the
  probe exits 0 for the first and 1 with a named error for the second. That is
  the hand-off check's lesson, applied again.

### 9.10 What this leaves open

* The **haptic map** (Part 5) is implemented for the lift, the drag commit and the
  sheet rows; the undo bar's confirmation tap is not, and on a unit with no
  vibrator the calls are silent no-ops either way.
* **Shared-element transitions** between the board and the app card are still the
  dialog's plain scale-up. Doing it properly needs `ActivityOptionsCompat` +
  a matched view, and the card is a `Dialog`, so the honest version of this is a
  `DialogFragment` and a real shared-element pair — worth doing, not worth
  half-doing.
* Part 6's **skeleton shimmer** is opacity-only breathing (the document argued for
  exactly this, and it is what shipped); a true skeleton would need the real
  layout's shape per cell, which is a per-app-layout query on the UI thread.

### 9.11 What the unit's own platform forced

* **A trust store is a snapshot, and a car never updates it.** The update screen
  could not reach GitHub at all - *Chain validation failed* - because `github.com`
  is served by a Sectigo E46 chain and the release assets by a Let's Encrypt "YR"
  chain, neither of which existed when the unit was built. The fix is additive,
  and that is the only shape worth having: the platform's trust manager is still
  asked first and keeps the last word, and three self-signed roots in
  `res/raw/github_roots.pem` - each verified against the live chains with
  `openssl verify` before it was added - are heard only after it refuses. No
  check was relaxed, and there is still no *continue anyway* anywhere in this app.
* **A list of processes beats a list of memories, and it needed a door nobody had
  tried.** The usage view answers *used lately*, which is not the question, and
  the screen that could grant the better answer does not exist on that ROM.
  `/proc` is readable by a plain install on the unit's release, so the task
  manager walks it: the process name names the app, the uid finds the package,
  `oom_score_adj` decides between *Running* and *Open*, and `VmRSS` gives a row
  the memory figure the list never had. The reader has to find somebody else's
  process before it is believed, and says nothing at all when it cannot - the
  difference between a screen that is empty and a screen that is lying.
* **A toast is not a destination.** Tapping *Enable* on the offer banner ended on
  "that settings screen is not available", because the ROM carries no
  usage-access screen. The row now walks a ladder - the platform's screen, this
  app's own details page, the top of Settings - and says in one log line where it
  got to. The last rung is not the screen that was asked for, and the copy does
  not pretend it is; it is simply better than nothing happening.
