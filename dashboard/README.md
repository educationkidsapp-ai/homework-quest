# dashboard

The Angular workspace behind the schools panel. The API container serves this bundle at
`<api>/panel/`, which is why `baseHref` is `/panel/` and `apiBaseUrl` is `""` (same origin)
in every configuration but development.

Angular 22 (zoneless by default), standalone components, signals, strict TypeScript, pnpm,
Node 22 (`.nvmrc` at the repo root).

## Commands

| Command                                       | What it does                                                                                                                           |
| --------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| `corepack pnpm install`                       | install (pnpm is pinned by `packageManager`)                                                                                           |
| `pnpm start`                                  | dev server on http://localhost:4200/panel/                                                                                             |
| `pnpm styleguide`                             | the same, opened in a browser — `/panel/` redirects to the styleguide                                                                  |
| `pnpm tokens` / `pnpm tokens --check`         | regenerate `src/styles/_tokens.generated.scss` and `src/app/ui/motion/tokens.generated.ts` from `design/tokens.json`, or fail on drift |
| `pnpm fonts` / `node tools/fonts.mjs --check` | rebuild `src/assets/fonts/*.woff2` from the TTFs in `shared-ui`, or fail on drift                                                      |
| `pnpm lint`                                   | ESLint (angular-eslint flat config + the local `hq` rules)                                                                             |
| `pnpm test`                                   | Vitest via the Angular CLI's `unit-test` builder, with Angular Testing Library                                                         |
| `pnpm e2e`                                    | Playwright; writes the styleguide screenshots to `docs/screenshots/dashboard-p1.0/`                                                    |
| `pnpm build --configuration=qa`               | the QA bundle                                                                                                                          |
| `pnpm gen:api`                                | placeholder until `server/openapi.json` exists (P3.1)                                                                                  |

## How it is put together

**Design tokens.** `design/tokens.json` at the repo root is the single source both front-ends
generate from. `pnpm tokens` turns it into CSS custom properties (`--hq-<group>-<name>`) plus an
SCSS map, and into the motion durations TypeScript needs. Nothing outside the generated file may
contain a literal hex or px value — per-school theming is an override of `:root` at runtime, and a
literal is a colour that cannot be themed.

**Components.** `src/app/ui/` — no UI kit. Every component is standalone, signal-based
(`input()`, `output()`, `model()`), `OnPush`, themed only through the custom properties, and
keyboard-reachable with a visible focus ring.

**Motion.** `src/app/ui/motion/` holds the only animations in the codebase: `pageEnter`,
`listStagger`, `rowCollapse`, `countUp`, `shake`, `expandBand`. `@angular/animations` is deprecated
in v22, so each is a CSS class (keyframes in `src/styles/_motion.scss`) applied by a directive.
`MotionService` and the `reduced-motion` mixin are the one place `prefers-reduced-motion` is
decided; the styleguide's "Reduce motion" switch goes through the same path.

**i18n.** Transloco, `en` and `ar` under `src/assets/i18n/`. `LanguageService` sets `lang` and
`dir` on `<html>` and persists the choice; every component uses CSS logical properties, so that
attribute is the whole of RTL support.

**Lint rules.** `eslint/` holds two local rules: `hq/no-product-name-literal` (the product name is
platform data, not a constant) and `hq/feature-flag-reference` (every screen and feature route says
which flag gates it, or opts out with `/* hq-flag: none (shell) — reason */`). `src/app/features/`
is empty in this package on purpose — the rule is in place before the first feature lands.

**Styleguide.** `/panel/styleguide` renders every component in every state, in both languages, with
a reduced-motion switch. The production configuration replaces `styleguide.route.ts` with an empty
routes array, so none of it ships.
