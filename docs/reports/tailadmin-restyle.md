# The TailAdmin restyle — what shipped, what was checked, what is still open

Four packages against `docs/prompts/tailadmin-spec.md`: **T1** the foundations (#79, `7393ed4`),
**T2** the shell (#80, `5c13cdf`), **T3** the kit and the teacher screens (#81, `c9fe6f7`), and
**T4** this verification. Nothing in T4 restyles anything; it walks the result as a teacher
does, measures it, and writes down what it found.

---

## 1. Files changed, by what they were for

From `git log --stat` of the three merges, screenshots omitted (they are ~300 of the 329 files).

### T1 — foundations (#79, 37 files, +1 399 / −76)

| Purpose | Files |
| --- | --- |
| The colour, type, radius, shadow and spacing roles | `src/styles/_theme.scss` (**+323**, the package's centre), `src/styles.scss`, `src/styles/_mixins.scss` |
| Their guards | `src/styles/tokens.spec.ts`, `src/styles/fonts.spec.ts` |
| Archivo → Outfit | `src/assets/fonts/outfit_variable.woff2` (Archivo deleted), `fonts/Outfit[wght].ttf`, `fonts/OFL.txt`, `tools/fonts.mjs`, `src/index.html` |
| Dark mode, applied before the first paint | `src/app/core/theme/dark-mode.service.ts` + spec, `src/main.ts`, `src/app/shell/shell-header.component.ts` (the toggle) |
| Kit pieces that held a literal colour | `ui/band`, `ui/button`, `ui/dialog`, `ui/phone-frame`, `ui/shortcuts-dialog`, `core/tour` |
| Feature stylesheets, one `@use` line each | `admin/classes`, `admin/teachers`, `lessons/lesson`, `lessons/lessons`, `week/week` |
| The guide, and the spec it is read against | `src/app/styleguide/*` (html, scss, ts), `docs/prompts/tailadmin-spec.md` (**+235**) |
| Tests | `e2e/styleguide.spec.ts`, `e2e/local/env.ts`, `e2e/local/lesson-editor.spec.ts`, `e2e/local/lesson-publish.spec.ts` |

### T2 — the shell (#80, 21 files, +1 824 / −297)

| Purpose | Files |
| --- | --- |
| The 290/90 px sidebar and the drawer under 1024 px | `ui/nav/nav.component.ts` (**+476**), `ui/nav/nav-icons.ts`, `core/shell/sidebar.service.ts` + spec |
| The header: burger, language, scheme, account dropdown | `shell/shell-header.component.ts` (**+371**) + spec |
| The content well the screens sit in | `shell/shell.component.ts` + spec, `ui/page/page.component.ts`, `features/auth/auth-layout.component.ts` |
| Shell-level styles | `src/styles.scss`, `src/styles/_mixins.scss`, `src/styles/_theme.scss` |
| Its acceptance | `e2e/local/theme-shell.spec.ts` (**+224**), `e2e/local/env.ts` (`setLanguage`), `e2e/local/README.md` |

### T3 — the kit and the teacher screens (#81, 44 files, +1 488 / −628)

| Purpose | Files |
| --- | --- |
| Every kit component given §3's box | `ui/`: `band`, `button`, `card`, `checkbox`, `dialog`, `empty-state`, `input`, `progress-bar`, `select`, `shortcuts-dialog`, `skeleton`, `step-strip`, `table`, `tabs`, `textarea`, `toggle`, `undo-strip` |
| The things a component could not own — badge, cell tile, link-button, menu | `src/styles.scss` (**+157**), `src/styles/_mixins.scss` (+59), `src/styles/_theme.scss` (+37) |
| The teacher's screens | `classes/`: `class-calendar`, `class-children`, `class.page`, `my-classes.page`; `lessons/`: `lesson.page`, `lessons.page`, `new-lesson.page`; `week/`: `week.page`, `status-square`; `home/home.page`; `profile/profile.page` |
| Its acceptance | `e2e/local/theme-kit.spec.ts` (**+228**), `e2e/local/README.md` |

### T4 — this package

| Purpose | Files |
| --- | --- |
| The console/NG0 gate, on every spec in `e2e/local` | `dashboard/e2e/local/env.ts`, and the import line of all ten existing specs |
| Fail-fast sign-in against a deployment | `dashboard/e2e/global-setup.ts` |
| The flow itself | `dashboard/e2e/local/theme-flow.spec.ts` (new) |
| The kit's variants in the guide, and their keys | `src/app/styleguide/styleguide.page.{html,scss,ts}`, `src/assets/i18n/{en,ar}.json`, `dashboard/e2e/styleguide.spec.ts` |
| One CSS fix, called out in §4 | `src/styles/_mixins.scss` |
| How to run it all | `dashboard/e2e/local/README.md`, this report |

---

## 2. The teacher flow, page by page

`docs/screenshots/theme-t4/` — every screen at 1366 × 768 in English and Arabic, light and dark
(32 frames), plus an English light frame at 768 and 375 (16). Written by
`e2e/local/theme-flow.spec.ts`, which also asserts, per screen and per size: a level-1 heading,
**no sideways page scroll**, the screen's main action visible and inside the viewport, and — in
dark mode — the contrast of the body text against the surface under it, composited and computed
in the page.

**There is no separate teacher Home.** `core/nav/screens.ts:111` redirects `/teacher` to `week`:
"Her Home *is* This week". The eight screens below are the eight a teacher actually has.

| Screen | Light | Dark | What was checked beyond the frame |
| --- | --- | --- | --- |
| This week (her Home) | [en](../screenshots/theme-t4/week-en-light-1366.png) · [ar](../screenshots/theme-t4/week-ar-light-1366.png) | [en](../screenshots/theme-t4/week-en-dark-1366.png) · [ar](../screenshots/theme-t4/week-ar-dark-1366.png) | `/teacher` redirects here; the grid is her two sections; the rail holds two items and no Admin area; dark body text **11.21:1** |
| My classes | [en](../screenshots/theme-t4/my-classes-en-light-1366.png) · [ar](../screenshots/theme-t4/my-classes-ar-light-1366.png) | [en](../screenshots/theme-t4/my-classes-en-dark-1366.png) · [ar](../screenshots/theme-t4/my-classes-ar-dark-1366.png) | a card per assignment; the primary link-button is on screen at all three widths; **12.16:1** |
| Class · Calendar | [en](../screenshots/theme-t4/class-calendar-en-light-1366.png) · [ar](../screenshots/theme-t4/class-calendar-ar-light-1366.png) | [en](../screenshots/theme-t4/class-calendar-en-dark-1366.png) · [ar](../screenshots/theme-t4/class-calendar-ar-dark-1366.png) | the month scrolls **inside its card** at 768 and 375, the page never does (T3's fix, still holding); **11.21:1** |
| Class · Children | [en](../screenshots/theme-t4/class-children-en-light-1366.png) · [ar](../screenshots/theme-t4/class-children-ar-light-1366.png) | [en](../screenshots/theme-t4/class-children-en-dark-1366.png) · [ar](../screenshots/theme-t4/class-children-ar-dark-1366.png) | the roster table scrolls inside its own card; 40 body-text elements measured, worst **13.54:1** |
| All lessons | [en](../screenshots/theme-t4/lessons-en-light-1366.png) · [ar](../screenshots/theme-t4/lessons-ar-light-1366.png) | [en](../screenshots/theme-t4/lessons-en-dark-1366.png) · [ar](../screenshots/theme-t4/lessons-ar-dark-1366.png) | the chooser card, the table, the primary "New lesson"; **13.54:1** |
| New lesson | [en](../screenshots/theme-t4/new-lesson-en-light-1366.png) · [ar](../screenshots/theme-t4/new-lesson-ar-light-1366.png) | [en](../screenshots/theme-t4/new-lesson-en-dark-1366.png) · [ar](../screenshots/theme-t4/new-lesson-ar-dark-1366.png) | **submitted**: title + "Write it yourself" + create, which is the lesson every frame below is taken on; **13.54:1** |
| Lesson editor | [en](../screenshots/theme-t4/lesson-en-light-1366.png) · [ar](../screenshots/theme-t4/lesson-ar-light-1366.png) | [en](../screenshots/theme-t4/lesson-en-dark-1366.png) · [ar](../screenshots/theme-t4/lesson-ar-dark-1366.png) | **four submits**: a stop saved in the stop editor and echoed on the pinned phone, the parent panel saved, the day moved and the move survives a reload, the publish sheet confirmed to 1A **and** 1B; 77 elements measured, worst **12.16:1** |
| Profile | [en](../screenshots/theme-t4/profile-en-light-1366.png) · [ar](../screenshots/theme-t4/profile-ar-light-1366.png) | [en](../screenshots/theme-t4/profile-en-dark-1366.png) · [ar](../screenshots/theme-t4/profile-ar-dark-1366.png) | **submitted**: the language select, the only control here that writes anything (§4.10); then sign out returns to the sign-in screen; **13.54:1** |

Narrow frames: `*-en-light-768.png` and `*-en-light-375.png` for each of the eight.
The styleguide's own pair is [light](../screenshots/theme-t4/styleguide-en-light.png) ·
[dark](../screenshots/theme-t4/styleguide-en-dark.png).

**Dark mode passes AA everywhere, with room.** `--hq-color-ink` resolves to
`rgba(255,255,255,0.9)` over a `#171f2e` card, which composites to **13.54:1**; the lowest
reading on any screen is 11.21:1, on the week grid's tinted cells. The floor asserted is 4.5.

---

## 3. Runs

All on this Mac unless stated. Durations are wall clock.

| Command | Result | Time |
| --- | --- | --- |
| `pnpm lint` | clean | 8 s |
| `pnpm test` | **63 files / 460 tests passed** | 7.4 s |
| `pnpm build --configuration=production` | success, **initial total 489.55 kB** (102.98 kB transfer) — unchanged by T4, since the styleguide is file-replaced out of the production configuration | 26 s |
| `pnpm e2e` (styleguide, `ng serve`) | **10 passed** — including the focus-ring test that had been failing, see §4.3 | 9 s |
| `pnpm e2e:local` — run 1 | **65 passed, 1 skipped** | 5.5 min |
| `pnpm e2e:local` — run 2 | **65 passed, 1 skipped** | 5.5 min |
| `pnpm e2e:qa` against QA on `c9fe6f7` | **52 passed, 3 failed, 6 skipped** — see §3.1 and §4.8 | 8.4 min |
| `pnpm e2e:qa theme-flow theme-shell my-classes` (after the one fix in §4.8) | **10 passed, 2 failed** — `theme-flow` green throughout, the two in §4.8 still red | 4.2 min |

The local runs are on the H2 one-school seed (`e2e/local/README.md`), `LLM_PROVIDER=fake`. The
one skip is `lesson-retry.spec.ts`, which needs a server started with
`-Dquest.pipeline.fail-once-at=…`; `admin-classes-teachers.spec.ts` runs locally and skips
itself against a deployment. Run 2 was against the database run 1 had already written to, which
is the idempotence check: every title carries a per-run tag and every file takes back what it
made.

### 3.1 QA, and the secret that is wrong

QA served `c9fe6f7` from 07:5x. **The deploy's own e2e job failed** (run `35420916875`):

```
Error: sara.al-harbi@school.test could not sign in against https://…run.app (HTTP 401)
       — is E2E_STAFF_PASSWORD the seeded staff password?
```

and then every teacher spec spent its full 30-second waits and both retries discovering the same
thing. The same mismatch cancelled the `5c13cdf` job at the 20-minute cap, which uploads no
report at all.

Sara signs in against that same deployment from here, with the value in the local QA env file —
so **the server is right and the `qa` GitHub environment's `E2E_STAFF_PASSWORD` is stale**. It
needs `gh secret set E2E_STAFF_PASSWORD --env qa` with the value the last deploy seeded
(`SEED_STAFF_PASSWORD`). That is an owner action; nothing in the code can fix it.

T4 makes the failure cheap rather than invisible: `e2e/global-setup.ts` now posts one
`/auth/sign-in` as Sara after the health poll and aborts the whole run on anything but 200,
naming the variable and telling a 401 (wrong password) from a 429 (the throttle). Seconds, with
a first line that says what to do, instead of twenty minutes and a cancelled job.

---

## 4. Open issues

### 4.1 Page crops never load — the dashboard shows no lesson images at all

- `server/src/main/java/quest/server/content/LessonStore.java:76` builds an **absolute**
  `"<publicUrl>/media/pages/<id>"`.
- `dashboard/src/app/ui/phone-preview/preview-images.ts:23` hands that URL to the template,
  which puts it in an `<img src>`.
- `server/src/main/java/quest/server/files/MediaController.java:44` has required a bearer token
  since P1.9 — and an `<img>` sends no `Authorization` header.

So every page image in the lesson editor answers **401** and nothing is drawn. `webAdmin` fetched
the bytes with its JWT (`RemoteAdminApi.imageBytes`, named in that controller's own javadoc); the
dashboard never gained the equivalent. This is not a restyle regression — it predates all four
packages — but it is the reason the editor's screenshots have no crops in them, and the console
gate is what finally surfaced it.

**Suggested fix** (backend + dashboard): either a short-lived signed URL on the lesson payload,
so an `<img>` can carry its own authority, or a small loader in the dashboard that fetches with
the token and hands the component an object URL. The first is cheaper for caching; the second
needs no contract change. Until then the 401 is declared in
`dashboard/e2e/local/lesson-editor.spec.ts`'s `beforeEach`, which says to delete it with the fix.

### 4.2 `theme-t3/home-*.png` are pictures of the not-found screen

`dashboard/e2e/local/theme-kit.spec.ts:96` opens `teacher/home`. That is not a route — a
teacher's Home is This week (`dashboard/src/app/core/nav/screens.ts:111`) — so the six
`home-*-1366.png` frames merged in #81 are the "Nothing here" page. The spec's barrier there is
`getByRole('heading', { level: 1 })`, which that page satisfies, so nothing objected.

**Suggested fix**: point that entry at `teacher/week` and rename the frames, or drop it, since
This week is already in the set. `theme-flow.spec.ts` photographs This week and asserts the
redirect; the T3 file is left alone here so its own package can correct its own screenshots.

### 4.3 The focus ring lost half of itself on inputs — **fixed here**

§4 asks for two things on a focused control: the outline, and a soft `rgba(70,95,255,0.1)` halo.
The halo comes from the global `:focus-visible` in `styles.scss:159` as a `box-shadow`. T3 gave
`m.control` a resting `box-shadow: var(--hq-shadow-xs)` — same specificity, component stylesheet
emitted later — so on a focused **input, select or textarea** the halo was replaced by the
resting elevation. `e2e/styleguide.spec.ts`'s "gives a focused control both halves of the ring"
has been failing on `develop` ever since, and **that suite runs in no CI workflow**, so nothing
said so.

This is the one product change in T4, and it is one rule: a `&:focus-visible { box-shadow:
var(--hq-focus-ring); }` inside `m.control` in `dashboard/src/styles/_mixins.scss:102`. Scoped
to that mixin because those three components are its only callers; every other focusable element
already gets the halo from the global rule. Called out because it is a T3 file, and it was the
difference between that test passing and failing.

**Also worth the owner's decision** (the T3 reviewer raised it and it is untouched here): the
outline is `var(--hq-size-focus-ring) solid var(--hq-color-focus)` (`_mixins.scss:72`) rather
than §3's literal `border-color:#9cb9ff`, and `--hq-color-focus-border` is wired but used only by
checkbox and toggle. The repo-wide outline is the more accessible of the two; the spec's is the
prettier. It needs a ruling, not a patch.

### 4.4 The styleguide's progress bars were 0 px wide — **fixed here**

`.sg__stack` is `align-items: flex-start`, which collapses a `display: block` child with no
intrinsic width to nothing. The two bars in the step-strip card have been invisible since P1.0
and no assertion ever measured them. Fixed with a `.sg__bars` wrapper in
`styleguide.page.scss`; the new `e2e/styleguide.spec.ts` asserts all four tones are visible, so
it cannot come back.

### 4.5 Three colour roles are under AA in dark mode

Measured in the page on the deployed palette, against the `#171f2e` card:

| Role | Dark value | On card | Verdict |
| --- | --- | --- | --- |
| `--hq-color-ink` / `-strong` | `rgba(255,255,255,0.9)` | **13.54:1** | fine |
| `--hq-color-ink-soft` | `#98a2b3` | **6.41:1** | fine |
| `--hq-color-ink-muted` (`_theme.scss:310`) | `#667085` | **3.32:1** | under 4.5 |
| `--hq-color-error-ink` (`_theme.scss:332`) | `#f04438` | **4.39:1** | just under 4.5 |
| the school's accent, as link text (`_theme.scss:290`) | `#cc2a0f` on the seeded school | **3.07:1** | under 4.5 |

`ink-muted` is placeholders and disabled text, where WCAG is lenient — but it is also `.hq-muted`
body copy, where it is not. `error-ink` misses by 0.11. The accent is the interesting one: §5
deliberately lets a school's accent survive into dark mode, and a school picked that colour
against a white page, so the result is whatever they chose — here, 3.07:1 for every link.

**Suggested fix**: move `ink-muted` to `gray-400` in the dark block and let `ink-soft` take
`gray-300`; take `error-ink` to `error-400`; and for the accent, either lighten it in dark mode
the way `--hq-color-accent-strong` already does (`_theme.scss:320` uses `brand-400`) or accept it
as the school's own choice and say so in the spec. The body text itself is comfortably clear, so
none of this blocks the restyle.

### 4.6 The reviewer's T3 notes, still open

- **Component-style budgets.** `features/week/week.page.scss` is 6.38 kB and
  `features/lessons/lesson.page.scss` 7.58 kB against a 6.00 kB budget; both print a build
  warning on every production build. Both are pre-existing and both got *smaller* in T3. T4 adds
  nothing to either. Suggested fix: lift the shared bits (the card grid, the status colours) into
  `_mixins.scss`, or raise the budget once, deliberately, with a comment saying why.
- **`statusTone()` returns `string`** at `features/lessons/lessons.page.ts:491` and
  `features/classes/my-classes.page.ts:114`. A `'primary' | 'success' | 'error' | 'warning' |
  'light'` union would let the compiler catch a typo in a badge class name that today just
  renders the neutral tone.
- **Calendar indentation.** `features/classes/class-calendar.component.ts:57` — the block wrapped
  in `.cal__scroll` was never re-indented, and the comment at `:164` sits one level out from the
  `position: relative` it explains at `:172`. Cosmetic, one commit.

### 4.7 Smaller things found while walking

- **`btn--icon` has no caller.** `ui/button/button.component.ts:4` offers an `icon` variant and
  nothing in `src/app/features/**` or `src/app/shell/**` uses it; the header rolls its own
  `.header__icon`. It is now drawn in the styleguide, so at least it is photographed. Either
  adopt it in the header or drop the variant.
- **Profile cannot save anything.** `features/profile/profile.page.ts:24` explains why: the
  contract has `PATCH /admin/users/{id}` (an Admin editing someone) and **no self-edit**, so name
  and photo are shown disabled. That is the honest presentation, but it means the "profile save"
  on the acceptance list does not exist to exercise — the language select is the only control
  here that writes. A `PATCH /me` would close it.
- **Home and Profile have no primary action.** Every other screen has one `.btn--primary` or
  `.hq-linkbutton--primary`; Home's "Add today's lesson" (`features/home/home.page.ts:106`) wears
  the plain link-button box and Profile's only button is secondary (`profile.page.ts:80`). On
  Profile that is arguably right. On Home — which teachers do not see, since their Home is This
  week — it is moot until the Admin's Home is reviewed.
- **The styleguide suite runs in no CI workflow.** Only `deploy-qa.yml` runs Playwright, and only
  `pnpm e2e:qa`, whose `testDir` is `e2e/local`. `e2e/styleguide.spec.ts` is therefore run by
  hand or not at all, which is how §4.3 went unnoticed for two packages. Suggested fix: a
  `pnpm e2e` step in the dashboard CI workflow — it needs `ng serve` and takes nine seconds.
- **The local `PUBLIC_URL` in the README was wrong** — fixed here. It said
  `PUBLIC_URL=http://localhost:18080` while the browser is on `:4300`, so every media link was
  cross-origin plain http and `serve.mjs`'s `img-src 'self' https: data:` blocked all of them.
  Now `:4300`, which is both what the container does and what makes §4.1's 401 visible.

---

### 4.8 Two specs fail on QA and pass locally — the shared database, not the restyle

Both are deterministic (they failed their retry too) and both predate T4. Neither has been seen
before, because the QA job has not completed a teacher spec since `4f67dc2`: `5c13cdf`'s run was
cancelled at the cap and `c9fe6f7`'s died at sign-in (§3.1).

**`dashboard/e2e/local/my-classes.spec.ts:99`** says, in a comment, "The seed leaves 1B with no
lesson at all, so the card offers the action rather than a status." That is true of the local H2
seed and false of QA, which carries two published `Untitled lesson` rows dated today. So
`my-classes.page.html:53`'s `@if (card.status === 'none')` is false, the "Add today's lesson"
button is correctly not drawn, and the test waits 15 s for it. **Suggested fix**: pick the class
to act on by reading the week first and choosing one whose status *is* `none`, or skip the test
against a deployment the way `admin-classes-teachers.spec.ts` does. The screen is behaving
correctly in both places.

The same data caught `theme-flow.spec.ts` on its first QA run, for the same reason, and is fixed
here: My classes' "main action" is now the class link (`.my-classes__link`), which is always
there, rather than the conditional primary button.

**`dashboard/e2e/local/theme-shell.spec.ts:176`** cannot take the class frames at 768 and below.
Playwright's captured page snapshot shows the browser is on a **lesson editor** ("Untitled
lesson · 1A British · Sep 13, 2026", a seed row) rather than on the class page, so
`getByRole('grid')` matches nothing. The helper at `theme-shell.spec.ts:48` reaches the class by
clicking `hq-card.first().getByRole('link').first()`, which resolves to a different link against
QA's data than against the local seed. **Suggested fix**: navigate to the class by its id, the
way `theme-flow.spec.ts` does, instead of by "the first link in the first card"; or assert the
URL after the click so the failure names the cause rather than the missing grid 30 s later.

Until one of these is done the post-deploy QA job stays red on two tests, independently of
anything in this package.

## 5. Lighthouse

From the `c9fe6f7` deploy (`deploy-qa.yml`'s "Lighthouse CI (dashboard sign-in)", reported on
PR #81), against the dashboard's sign-in page with a threshold of 90:

| | Score |
| --- | --- |
| Performance | **97** |
| Accessibility | **100** |

Only the sign-in page is audited. The eight screens behind it are covered by the console gate and
by the contrast arithmetic in §2 instead, neither of which Lighthouse would reach without a
signed-in session.
