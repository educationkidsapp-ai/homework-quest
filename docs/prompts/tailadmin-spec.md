# Containers, shapes and components — TailAdmin spec

Everything below is lifted from `TailAdmin/free-angular-tailwind-dashboard@main` (`src/styles.css` and the component templates) and written as literal values, so it can be rebuilt without Tailwind.

---

## 1. Foundations

### Typography

Family: **Outfit** (`https://fonts.googleapis.com/css2?family=Outfit:wght@100..900`). Body weight 400.

| Token | Size / line-height | Used for |
|---|---|---|
| `title-2xl` | 72 / 90 | — |
| `title-xl` | 60 / 72 | — |
| `title-lg` | 48 / 60 | — |
| `title-md` | 36 / 44 | — |
| `title-sm` | **30 / 38** | metric values, big numbers |
| `theme-xl` | 20 / 30 | — |
| `theme-sm` | **14 / 20** | body, table cells, nav labels, buttons |
| `theme-xs` | **12 / 18** | table headers, captions, badges |

Other sizes in use: page title 24/600, card title 18/600, card sub 14/400, metric label 14/400.

### Colour

**Brand** — 25 `#f2f7ff` · 50 `#ecf3ff` · 100 `#dde9ff` · 200 `#c2d6ff` · 300 `#9cb9ff` · 400 `#7592ff` · **500 `#465fff`** · 600 `#3641f5` · 700 `#2a31d8` · 800 `#252dae` · 900 `#262e89` · 950 `#161950`

**Gray** — 25 `#fcfcfd` · 50 `#f9fafb` · 100 `#f2f4f7` · 200 `#e4e7ec` · 300 `#d0d5dd` · 400 `#98a2b3` · 500 `#667085` · 600 `#475467` · 700 `#344054` · 800 `#1d2939` · 900 `#101828` · 950 `#0c111d`

**Success** — 50 `#ecfdf3` · 100 `#d1fadf` · 500 `#12b76a` · 600 `#039855` · 700 `#027a48`
**Error** — 50 `#fef3f2` · 100 `#fee4e2` · 200 `#fecdca` · 300 `#fda29b` · 500 `#f04438` · 600 `#d92d20` · 700 `#b42318`
**Warning** — 50 `#fffaeb` · 100 `#fef0c7` · 300 `#fec84b` · 400 `#fdb022` · 500 `#f79009` · 600 `#dc6803` · 700 `#b54708`

Page background `#f9fafb`. Surface `#ffffff`. Default border `#e4e7ec`. Inner divider `#f2f4f7`. Primary text `#1d2939`, secondary `#667085`, muted `#98a2b3`.

### Shape

Radius is the system's main tell — nothing is sharp, nothing is a pill except badges.

| Radius | Where |
|---|---|
| `4px` | chart bar caps |
| `8px` | buttons, inputs, nav items, small icon tiles, dropdown rows |
| `12px` | metric icon tiles, inner panels, avatars-as-squares |
| **`16px`** | **every card container** |
| `9999px` | badges, status pills, avatars, progress tracks, notification dot |

### Elevation

| Token | Value |
|---|---|
| `shadow-theme-xs` | `0px 1px 2px 0px rgba(16,24,40,0.05)` |
| `shadow-theme-sm` | `0px 1px 3px 0px rgba(16,24,40,0.1), 0px 1px 2px 0px rgba(16,24,40,0.06)` |
| `shadow-theme-md` | `0px 4px 8px -2px rgba(16,24,40,0.1), 0px 2px 4px -2px rgba(16,24,40,0.06)` |
| `shadow-theme-lg` | `0px 12px 16px -4px rgba(16,24,40,0.08), 0px 4px 6px -2px rgba(16,24,40,0.03)` |
| `shadow-theme-xl` | `0px 20px 24px -4px rgba(16,24,40,0.08), 0px 8px 8px -4px rgba(16,24,40,0.03)` |

Cards do **not** carry a shadow — they are defined by a 1px `#e4e7ec` border on a `#f9fafb` ground. Shadows belong to controls (`xs`), dropdowns (`lg`) and drawers (`xl`).

### Spacing rhythm

Grid gap `24px`. Card padding `24px` (`20px 24px` when a header and body are split). Content well `padding: 24px`, centred, `max-width: 1536px`. Control height `44px`. Button padding `11px 16px`. Table cell padding `14px 24px` (edge) / `14px 12px` (inner).

---

## 2. Containers

### App shell

```
[ sidebar 290px ][ main ]
```

- **Sidebar** — `width:290px` expanded, `90px` collapsed, `transition:width .3s ease`. `position:sticky; top:0; height:100vh`. Background `#ffffff`, `border-right:1px solid #e4e7ec`, `padding:0 20px`. Logo block `padding:32px 0`. Collapsed, labels are hidden and icons centre.
- **Main** — `flex:1; min-width:0`, column.
- **Header** — `position:sticky; top:0; z-index:40`, `#ffffff`, `border-bottom:1px solid #e4e7ec`, inner `padding:0 24px`, row `padding:16px 0`.
- **Content well** — `padding:24px; margin:0 auto; width:100%; max-width:1536px`.

### Card (the base container)

```html
<div style="border-radius:16px;border:1px solid #e4e7ec;background:#ffffff;padding:24px"></div>
```

### Card with header (component-card)

Header `padding:20px 24px` — title 18/600 `#1d2939`, optional sub 14/400 `#667085`. Body separated by `border-top:1px solid #f2f4f7`, `padding:24px`.

### Table card

`border-radius:16px; border:1px solid #e4e7ec; background:#ffffff; overflow:hidden`. Header row `padding:20px 24px`. The table itself is flush to the card edges; the toolbar sits above a `#f2f4f7` divider.

### Nested / two-tone card

Outer `border-radius:16px; border:1px solid #e4e7ec; background:#f2f4f7`; inner `background:#ffffff; border-radius:16px; padding:24px`. The exposed grey strip below carries a summary row. (TailAdmin's monthly-target pattern.)

### Drawer

`position:fixed; inset:0; background:rgba(16,24,40,0.45); z-index:60`, panel right-aligned `width:480px; max-width:92vw; height:100%`, `background:#ffffff`, `shadow-theme-xl`, slide-in `.22s ease-out`. Header and footer both `padding:16–20px 24px` with `1px solid #e4e7ec` rules; body scrolls.

### Dropdown panel

`border-radius:16px; border:1px solid #e4e7ec; background:#ffffff; padding:12px`, `shadow-theme-lg`, fade+rise `.18s`. Rows `padding:10px 8px; border-radius:8px`, hover `#f9fafb`.

### Grids

- Metric row: `grid-template-columns:repeat(4,minmax(0,1fr)); gap:24px`
- Chart + side panel: `minmax(0,7fr) minmax(0,5fr); gap:24px`
- Card gallery: `repeat(3,minmax(0,1fr)); gap:24px`

Always `minmax(0,…)` so children can shrink.

---

## 3. Components

### Nav item

`display:flex; align-items:center; gap:12px; padding:8px 12px; border-radius:8px; font-size:14px; line-height:20px; font-weight:500`. Icon 24px.

| State | Background | Text | Icon |
|---|---|---|---|
| inactive | transparent | `#344054` | `#667085` |
| hover | `#f2f4f7` | `#344054` | `#344054` |
| active | `#ecf3ff` | `#465fff` | `#465fff` |

Trailing badge: `border-radius:9999px; padding:2px 10px; font-size:12px; font-weight:500`, `#ecfdf3`/`#027a48` inactive, `#d1fadf`/`#027a48` active.

### Button

Base: `border-radius:8px; padding:11px 16px; font-size:14px; font-weight:500; display:inline-flex; align-items:center; gap:8px`.

| Variant | Normal | Hover |
|---|---|---|
| Primary | `#465fff` bg, `#ffffff` text, `shadow-theme-xs` | `#3641f5` |
| Secondary | `#ffffff` bg, `1px solid #d0d5dd`, `#344054` text, `shadow-theme-xs` | bg `#f9fafb`, text `#1d2939` |
| Danger outline | `#ffffff` bg, `1px solid #fecdca`, `#b42318` text | bg `#fef3f2` |
| Icon button | `44×44`, `1px solid #e4e7ec`, radius `8px` (or `9999px` in the header), icon `#667085` | bg `#f9fafb`, icon `#344054` |

Focus ring everywhere: `border-color:#9cb9ff; box-shadow:0 0 0 3px rgba(70,95,255,0.1)`.

### Input

`height:44px; border-radius:8px; border:1px solid #e4e7ec; background:transparent; padding:10px 16px; font-size:14px; color:#1d2939; shadow-theme-xs`. Placeholder `#98a2b3`. Focus as above. With a leading icon, left padding `48px`; with a trailing keycap, right padding `56px`.

**Keycap** — `border-radius:8px; border:1px solid #e4e7ec; background:#f9fafb; padding:4.5px 7px; font-size:12px; letter-spacing:-0.2px; color:#667085`.

### Badge / status pill

`display:inline-flex; align-items:center; gap:6px; border-radius:9999px; padding:2px 10px; font-size:12px; font-weight:500`. (`md` size uses 14px text.)

| Colour | Light bg / text | Solid bg |
|---|---|---|
| primary | `#ecf3ff` / `#465fff` | `#465fff` |
| success | `#ecfdf3` / `#027a48` | `#12b76a` |
| error | `#fef3f2` / `#b42318` | `#f04438` |
| warning | `#fffaeb` / `#b54708` | `#f79009` |
| light | `#f2f4f7` / `#344054` | `#98a2b3` |

Never carry meaning on colour alone — pair with a word, a glyph or an icon.

### Metric card

Card (24px padding) → a `48×48` icon tile (`border-radius:12px; background:#f2f4f7; color:#344054`) → `margin-top:20px` row, `align-items:flex-end; justify-content:space-between`: label 14/`#667085` over value 30/38/700/`#1d2939`, with a delta badge on the right (success up / error down, arrow rotated 180° when down).

### Data table

- `width:100%; border-collapse:collapse`
- Header: `background:#f9fafb` (or bare with `border-y:1px solid #f2f4f7`), cells `padding:14px 24px; font-size:12px; line-height:18px; font-weight:500; color:#667085; text-align:left`
- Rows: `border-top:1px solid #f2f4f7`, hover `#f9fafb`, selected `#f9fafb`
- Cells: `font-size:14px`, primary text `#1d2939`, secondary `#667085`
- Leading cell pattern: a `40×40` radius-8 tinted icon tile beside a 14/500 title over a 12px `#667085` sub-line
- Wrap in `max-width:100%; overflow-x:auto`

### Filter chip

`padding:8px 14px; border-radius:8px; font-size:14px; font-weight:500; border:1px solid`. Off: `#ffffff` / `#d0d5dd` / `#344054`, count pill `#f2f4f7`/`#667085`. On: `#465fff` fill, white text, count pill `rgba(255,255,255,0.22)`/white.

### Progress bar

Track `height:8px; border-radius:9999px; background:#f2f4f7`; fill same radius, colour by threshold — `#12b76a` ≥70%, `#fdb022` 50–69%, `#f04438` <50%.

### Radial gauge

180° arc, `stroke-width:28`, `stroke-linecap:round`; track `#e4e7ec`, fill `#465fff` via `stroke-dasharray:283` + computed `stroke-dashoffset`. Value 30/38/700 centred, delta badge beneath.

### Stacked bar chart

Column `display:flex; flex-direction:column; justify-content:flex-end; gap:3px`, bars `max-width:38px`, top cap `4px 4px 0 0`, bottom cap `0 0 4px 4px`. Plot height 240px with a `#f2f4f7` baseline; labels 12px `#98a2b3`. Tooltip: `#1d2939` bg, white 12px text, `border-radius:8px; padding:6px 10px`, `shadow-theme-md`.

### Timeline (drawer)

A 22px round step marker (`✓` success / `!` error / `~` warning / `–` skipped, tinted from the matching ramp) with a 2px `#e4e7ec` connector, beside a 14/500 name over a 13px `#667085` note.

### Empty state

Centred, `padding:56px 24px`: a `56×56` radius-12 `#f2f4f7` tile with a `#98a2b3` icon, a 16/500 `#344054` line, a 14/400 `#667085` line, and optionally one secondary button.

---

## 4. Rules

- Every container is `border-radius:16px`, 1px `#e4e7ec`, white, on a `#f9fafb` page. Cards don't cast shadows.
- Two divider weights only: `#e4e7ec` between containers and structural regions, `#f2f4f7` inside a container.
- Controls are 44px tall and radius-8; badges are the only pills.
- Icons are 24px in navigation and metric tiles, 20px in controls and table cells, 18px inside buttons, stroke 1.6–1.8.
- Text ramp is fixed: 24/600 page title, 18/600 card title, 14/400 body, 12/500 table header and badge, 30/700 for numbers.
- Every interactive element needs an explicit hover and a `rgba(70,95,255,0.1)` focus ring — no browser defaults.

---

## 5. Dark mode (planner addendum, lifted from the same repo's `dark:` utilities and `styles.css`)

Toggle: a `.dark` class on `<html>` (`@custom-variant dark (&:is(.dark *))`), persisted in `localStorage['theme']` as `light | dark`, default light.

| Role | Light | Dark |
|---|---|---|
| Page background | `#f9fafb` | `#101828` (gray-900) |
| Sidebar / header surface | `#ffffff` | `#1a2231` (`--color-gray-dark`) |
| Card surface | `#ffffff` | `rgba(255,255,255,0.03)` on the page background |
| Container border | `#e4e7ec` | `#1d2939` (gray-800) |
| Control border | `#d0d5dd` / `#e4e7ec` | `#344054` (gray-700) |
| Inner divider | `#f2f4f7` | `#1d2939` (gray-800) |
| Primary text | `#1d2939` | `rgba(255,255,255,0.9)` |
| Secondary text | `#667085` | `#98a2b3` (gray-400) |
| Muted text / placeholder | `#98a2b3` | `#667085` (gray-500) |
| Row / nav hover | `#f2f4f7` | `rgba(255,255,255,0.05)` |
| Nav active | `#ecf3ff` / `#465fff` | `rgba(70,95,255,0.12)` / `#7592ff` (brand-400) |
| Badge light bg | `<ramp>-50` | `rgba(<ramp>-500, 0.15)` with `<ramp>-500` text |
| Scrollbar thumb | `#e4e7ec` | `#344054` |
| Focus ring | `rgba(70,95,255,0.1)` | same, with border `#7592ff` |

Shadows are unchanged in dark mode; they are near-invisible on dark surfaces by design.

---

## Deviations

Places the dashboard knowingly departs from the above, each with the ruling behind it.

- **CR3 readability (2026-09-19).** The owner's change request of 2026-09-19 overrides §1's type
  ramp and two of its spacing figures; the dashboard's sizes are now, all from
  `src/styles/_theme.scss`: body **16/24** (`--hq-text-theme-sm`, which is also the table cell,
  the nav label, the button and the input), secondary text **14/20**
  (`--hq-text-theme-xs` — meta lines, hints, the timeline sub-line `--hq-text-note`, and
  `--hq-font-label-size`, so a form label is one step under the body), the page title **26/34 at
  600** and the card title **19/28 at 600** (inside the request's 24–28 and 18–20), the empty
  state's lead **18px**. The spec's **12 px** survives as `--hq-text-theme-2xs` and is used only
  where §3 needs a label in a fixed box — the badge, the data-table header row, the keycap, the
  header's `EN`/`AR` and avatar initials, the step-strip marker — and never for a sentence.
  Spacing: card padding **20px** (header `16px 20px`), table cell `12px 20px`, grid gap **24px**,
  page gutter **32px** (`--hq-size-page-padding`, unchanged), and the content well widens from
  §2's **1536px to 1760px** so the grids fill the width at 1366 *and* 1920 instead of being
  cropped back. The 1100 px reading measure (`--hq-size-content-max-width`) is kept for the one
  place that is a row of controls rather than a grid, the lessons page's filter chooser.
  Contrast: `--hq-color-ink` stays `gray-800` (`#1d2939`, **14.70:1**, darker than the request's
  `#1f2937`) and `--hq-color-ink-soft` moves `gray-500` → `gray-600` (`#475467`, **7.69:1**,
  well under the request's `#6b7280` ceiling); dark mode is untouched and
  `e2e/styleguide.spec.ts`'s AA assertion still names `--hq-color-ink-muted` as the only
  exemption.
- **D19 — the focus ring keeps its outline.** §3 asks for `border-color:#9cb9ff` plus the
  `rgba(70,95,255,0.1)` halo. The halo ships as written; the border does not, because `#9cb9ff`
  on white is **1.94:1** and a focus indicator that cannot be seen is not one. Every focusable
  element takes `outline: var(--hq-size-focus-ring) solid var(--hq-color-focus)` over the spec's
  halo instead (`src/styles.scss`, `m.focus-ring`), and `--hq-color-focus-border` carries
  `#9cb9ff` for the controls whose own border is part of the state. `e2e/styleguide.spec.ts`
  asserts both halves.
- **The accent as text is re-derived in dark mode.** §5 lets a school's accent survive into dark
  mode, which is right for a fill and wrong for a word: the seeded school's `#cc2a0f` reads
  3.07:1 on the dark card. `--hq-color-accent-ink` mixes the accent 45 % into white for text
  only, and only here — in light mode the role is the raw accent, governed by the server's
  contrast check on the theme. The accent itself is untouched wherever it is a background. See
  §4.5 of `docs/reports/tailadmin-restyle.md`.
