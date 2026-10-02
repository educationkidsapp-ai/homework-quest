# MySchool palette

The dashboard's colours are the MySchool logo's. This page is the published table: the mobile app adopts these values
as they are written here. The source is `dashboard/src/styles/_theme.scss`; `dashboard/src/styles/palette.spec.ts`
fails when a ramp step in that file is not on this page with the same value, and measures every text/background pair
below against WCAG AA with the same arithmetic as the server's `Contrast`.

Sampled from the logo artwork (median of each colour region): light blue `#089CDF`, deep blue `#0754B7`, magenta
`#B8047A`, orange `#FB9B0A`. The vector mark is `dashboard/src/assets/brand/myschool-mark.svg` (and
`myschool-mark-mono.svg`, which paints in `currentColor`).

## Ramps

Blue leads. Magenta and orange are accents — fills, chips, chart series — and are text only through the `-ink` roles.

### Primary — blue (`--hq-color-brand-*`)

| Token | Hex | Note |
|---|---|---|
| `brand-25` | `#F3FAFD` |  |
| `brand-50` | `#E6F5FC` |  |
| `brand-100` | `#CEEBF9` |  |
| `brand-200` | `#A1D9F3` |  |
| `brand-300` | `#66C2EB` |  |
| `brand-400` | `#089CDF` | logo light blue — gradient start |
| `brand-500` | `#0774C9` | first step white text clears AA (4.83:1) — text-bearing gradient start |
| `brand-600` | `#0762BF` | default accent (light) |
| `brand-700` | `#0754B7` | logo deep blue — gradient end, accent hover |
| `brand-800` | `#064192` |  |
| `brand-900` | `#05306E` |  |
| `brand-950` | `#052150` |  |

### Secondary — magenta (`--hq-color-magenta-*`)

| Token | Hex | Note |
|---|---|---|
| `magenta-50` | `#FBF0F7` |  |
| `magenta-100` | `#F6DEEE` |  |
| `magenta-200` | `#EDBEDC` |  |
| `magenta-300` | `#E091C4` |  |
| `magenta-400` | `#D25EAA` |  |
| `magenta-500` | `#C32C8F` |  |
| `magenta-600` | `#B8047A` | logo magenta — secondary |
| `magenta-700` | `#9C0368` | secondary as text (light) |
| `magenta-800` | `#810356` |  |
| `magenta-900` | `#650244` |  |
| `magenta-950` | `#4A0132` |  |

### Accent — orange (`--hq-color-orange-*`)

| Token | Hex | Note |
|---|---|---|
| `orange-50` | `#FFF6E9` |  |
| `orange-100` | `#FEEBCE` |  |
| `orange-200` | `#FDD79D` |  |
| `orange-300` | `#FCBE60` |  |
| `orange-400` | `#FB9B0A` | logo orange — accent |
| `orange-500` | `#E48B09` |  |
| `orange-600` | `#C17307` |  |
| `orange-700` | `#A25E05` |  |
| `orange-800` | `#874B04` | orange as text (light) |
| `orange-900` | `#703B03` |  |
| `orange-950` | `#592B02` |  |

### Warning — yellow (`--hq-color-warning-*`)

Warning was an orange one step from the logo's; it is yellow now so that it never reads as the brand accent.

| Token | Hex | Note |
|---|---|---|
| `warning-50` | `#FFFBE0` |  |
| `warning-100` | `#FFF5B8` |  |
| `warning-300` | `#FFE24D` |  |
| `warning-400` | `#FBD400` |  |
| `warning-500` | `#E6BF00` |  |
| `warning-600` | `#B39500` |  |
| `warning-700` | `#7D6500` |  |

Unchanged: `gray-*` neutrals, `success-*` (`#12B76A` / `#039855` / `#027A48`) and `error-*` (`#F04438` / `#D92D20` /
`#B42318`). Error is an orange-red about 45° of hue from the magenta, and every status is carried by a label or an
icon as well as its colour.

## Brand gradient

| | Value |
|---|---|
| Angle | `135deg` (CSS: towards the bottom-right; Compose: `Brush.linearGradient` from top-start to bottom-end) |
| Stops — decorative (`--hq-gradient-brand`) | `#089CDF` 0 % → `#0754B7` 100 % |
| Stops — under text (`--hq-gradient-brand-fill`) | `#0774C9` 0 % → `#0754B7` 100 % |

White text on `#089CDF` is 3.07:1, so the artwork's own pair is for surfaces with no label on them. Anything that
carries white text (the active nav item, a primary button, a hero card) starts one step in, at `#0774C9` (4.83:1), and
ends on the same deep blue (7.09:1).

A school theme does not change the gradient. `ThemeService` writes a school's six colours as inline custom properties
(`--hq-color-accent`, surface, ink, ground, rule, mascot) and the gradient reads none of them — it is the product's
signature and stays the logo's blues, exactly as the gradient it replaced did. The stops are their own properties
(`--hq-gradient-brand-from`, `--hq-gradient-brand-fill-from`, `--hq-gradient-brand-to`) should that ever change.

## Roles

| Role | Light | Dark |
|---|---|---|
| `accent` (school-overridable) | `brand-600` `#0762BF` | `brand-500` `#0774C9` |
| `accent-strong` (hover, links) | `brand-700` `#0754B7` | `brand-400` `#089CDF` |
| `accent-soft` | `brand-50` `#E6F5FC` | `brand-500` 16 % on the surface |
| `accent-on-soft` | `brand-600` `#0762BF` | `brand-300` `#66C2EB` |
| `accent-ink` | `brand-600` `#0762BF` | `brand-400` `#089CDF` |
| `focus` | `brand-500` `#0774C9` | `brand-400` `#089CDF` |
| `brand-ink` | `brand-600` `#0762BF` | `brand-300` `#66C2EB` |
| `brand-fill` / `brand-fill-hover` | `brand-500` / `brand-600` | same |
| `secondary` | `magenta-600` `#B8047A` | `magenta-500` `#C32C8F` |
| `secondary-soft` / `secondary-ink` | `magenta-50` / `magenta-700` | `magenta-500` 16 % / `magenta-300` |
| `tertiary` | `orange-400` `#FB9B0A` | `orange-400` `#FB9B0A` |
| `tertiary-soft` / `tertiary-ink` | `orange-50` / `orange-800` | `orange-400` 16 % / `orange-300` |
| `info-soft` / `info-ink` | `brand-50` / `brand-700` | `brand-400` 16 % / `brand-300` |
| `on-warning` (text on a yellow fill) | `gray-900` `#101828` | same |
| `warning-soft` / `warning-ink` | `warning-50` / `warning-700` | `warning-500` 15 % / `warning-500` |
| Ground / surface / ink | `#F9FAFB` / `#FFFFFF` / `#1D2939` | `#101828` / `#171F2E` / white 90 % |

Tile gradients that carry a white glyph start at a stop white reaches 3:1 on: orange `#C17307` → `#A25E05`, neutral
`#667085` → `#344054`, blue `#089CDF` → `#0762BF`, magenta `#D25EAA` → `#B8047A`.

Categorical series, in order: `brand-500`, `magenta-600`, `orange-400`, `gray-500`, teal `#0891B2`, `brand-900`
(dark: `brand-400`, `magenta-400`, `orange-400`, `gray-400`, teal `#22D3EE`, `brand-200`).

## Changing it

Edit the ramp in `dashboard/src/styles/_theme.scss`, update the table here, and run
`corepack pnpm --dir dashboard exec ng test --include src/styles/palette.spec.ts`. Literal colours belong in that file
and nowhere else; `design/tokens.json` still carries the parent-mode palette the app's `TokensDriftTest` and the
server's default theme read, and is not where the dashboard's brand colours live.
