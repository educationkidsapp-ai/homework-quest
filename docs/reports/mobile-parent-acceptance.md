# Mobile parent acceptance — verification pass

**When** 2026-09-19, 18:30–20:00 (Asia/Riyadh).
**Against** QA `https://homework-quest-api-625882725080.me-central1.run.app`, `/health` version `e36c188…`
(the acceptance seed profile: one school *Default school* / `HQ0001`, sections `1A British`, `1B British`,
`1A American`, teachers Maya and Rami, no children).
**App** `androidApp/build/outputs/apk/qa/release/androidApp-qa-release.apk`, built locally with
`./gradlew :androidApp:assembleQaRelease -Pquest.qa.apiBaseUrl=…` (exit 0, ~3 min), running on emulator
`Pixel_8a` (1080×2400, Android 16).
**Scope** verification only — no product code was changed (decision D16 holds).

> The QA flavour installs as package **`app.homeworkquest.qa`**, not `app.homeworkquest`. The QA Cloud Run
> URL is correctly baked into the APK (verified against the dex strings).

## Step table

| # | Step | Expected | Observed | Result | Screenshot |
|---|------|----------|----------|--------|------------|
| 1 | Build QA flavour + install | APK builds, installs, points at QA | exit 0; installed as `app.homeworkquest.qa`; base URL correct | pass | — |
| 2 | Maya: create → upload → analyze | lesson analyzed from `one-page.pdf` | create 0 s, upload 4 s, analyze 21 s → `needs_review`, skill "Counting in 2s" extracted | pass | — |
| 3 | Maya: confirm skills → generate → publish | lesson reaches `review`, publishes to 1A + 1B | generation 33 s → `review`; publish returned two rows — `cb260e24…` (1A) and a **new copy** `251dd049…` (1B) | pass | — |
| 4 | Rami: create → upload → analyze | same on `1A American` / english | create 0 s, upload 1 s, analyze **186 s** → `needs_review` | pass | — |
| 5 | Rami: confirm skills → generate → publish | lesson reaches `review`, publishes to 1A American | L1 165 s, L2 92 s, then **`generate_L3` wedged — "running" for 35+ min, no error, unrecoverable** | **FAIL** | — |
| 6 | Parent registers in the app | new account, lands in the app | registered with a throwaway address; went straight to *Add a child* | pass | `02-register`, `03-after-register` |
| 7 | Join school `HQ0001` | school found and joined | "Default school · Joined · HQ0001"; curriculum/grade options came from the school | pass | `04-addchild-empty`, `05-join-joined` |
| 8 | Add child A (Grade 1 British) | child saved, map opens | saved; map opened on *ChildA's quest* | pass | `06-addchild-a-filled` |
| 9 | Child A sees Maya's lesson | lesson appears (twice is expected while unsectioned) | **"Acceptance maya, 19 Sep" appeared twice** — one per section copy — plus the 3 seed demo lessons | pass | `07-map-childA-duplicate`, `08-map-childA-scrolled` |
| 10 | Play a lesson end to end | 7 stops, finish screen | played all 7 stops (Learn → missing number → socks → odd-one-out → gap → true/false → 3-question check); "The Twos Soup Pot is full!" → certificate, 21/21 stars, new sticker | pass | `09`–`13` |
| 11 | Maya sees the play | play count visible | `GET /teacher/classes` → 1A British `todayStatus=published`, **`playedToday=1`**. `GET /teacher/week` did **not** show it (see F4) | partial | — |
| 12 | Add child B (Grade 1 American) | child saved | saved; child list shows *ChildA British · Grade 1* and *ChildB American · Grade 1* | pass | `15-addchild-b`, `17-child-list-both` |
| 13 | Child B sees Rami's lesson | Rami's english lesson appears | **"No quest today yet"** — blocked by step 5; nothing to show | **FAIL (blocked)** | `16-map-childB-empty` |
| 14 | Attach child A to `1A British` | lesson now appears once | `POST /admin/classes/default%3Abritish%3A1%3A1a%20british/roster/attach` → 200; map shows the lesson **once** (done), other sections' lessons gone | pass | `18-map-childA-after-attach` |

## Failures and who owns them

**F1 — BLOCKER: lesson generation wedges at `generate_L3`, with no timeout and no recovery.**
Rami's lesson `c07ed9f0-b15e-4faf-930b-87a45feeeb2e` (1A American, english, the same `one-page.pdf`):

```
upload 19:05:20 | convert 19:05:21 | analyze done 19:08:26 | skills 19:08:45
generate_L1 done 19:11:30 | generate_L2 done 19:13:02 | generate_L3 running since 19:13:02
```

Status stays `generating` indefinitely and no `error` is ever set, so the class card stays
`todayStatus=draft` and the lesson can never be published. The one escape hatch is refused:

```
POST /teacher/lessons/{id}/steps/generate_L3/retry
→ 400 {"code":"bad_request","message":"Wait for the current job to finish."}
```

The teacher has no UI or API route back. Compare Maya's math lesson on the *same* PDF: analysed in 21 s,
generated in 33 s, published inside a minute. Owner: **backend** — the lesson job runner needs a per-step
watchdog (fail the step after N minutes so `retry` becomes possible), `server/…/analysis` job runner.

**F2 — the teacher cannot publish straight after analyze.** After analyze the lesson sits at
`needs_review`; `POST …/publish` returns 400 *"Only a lesson in review can be published."* The teacher must
first `PUT /teacher/lessons/{id}/skills` with the confirmed skills, which kicks generation. Correct by
design, but anyone scripting create → upload → analyze → publish will fail. Owner: docs.

**F3 — `POST /teacher/lessons` field values.** `source` must be one of `pdf | slides | images | manual`
(`upload` is rejected) and `practiceLength` must be 5–12. Owner: docs.

**F4 — `GET /teacher/week` hides today when today is a weekend day.** Today is Sat 2026-09-19; the endpoint
returned `start=2026-09-13` with cells for 09-13…09-17 only (school week SUN–THU, `Asia/Riyadh`), so today's
lesson *and its play* are absent from the week grid even though both exist. The class card does show them.
Owner: **backend** (`GET /teacher/week`) — or accepted behaviour, but the owner testing on a Fri/Sat will see
an empty week screen and conclude publishing failed.

**F5 — Back from the world map lands on the Add-child form, then exits.** After register → add child → map,
Android Back shows *Add a child* pre-filled with the existing child, and Back again leaves the app.
`shared/src/commonMain/kotlin/quest/feature/children/presentation/AddChildScreen.kt` stays on the back stack.
Owner: **mobile** (blocked by D16).

**F6 — the school code must be re-entered for every child.** Adding the second child reopens *Add a child*
with an empty School code although the parent already joined Default school; the join is per-child, not
per-parent (`AddChildScreen.kt:64` `JoinStep`, `:150` `schoolCode.takeIf { … CONFIRMED }`). The owner will
think the join did not stick. Owner: **mobile** (blocked by D16) — or product, if this is intended.

**F7 — a generated stop dropped a term from the source sequence.** The worksheet reads
*"5. Write the missing numbers. 10, 12, __, 16, __, 20"* — two gaps, 14 and 18. The generated stop rendered
*"Fill the gap: 10, 12, __, 16, 20"*, which silently drops the 18 slot and leaves a sequence that is not a
count in 2s. The intended answer (14) is still right, so a child is not marked wrong, but the number line on
screen is incorrect. Owner: backend/prompt (stop generation from `analysis.skills.examples`).

**F8 — `Deploy QA` is red on every develop deploy, and it is not the deploy.** Build image, Terraform,
Cloud Run + migrations and the QA APK all pass; only *"Dashboard e2e (Playwright, against QA)"* fails:

```
Error: sara.al-harbi@school.test could not sign in against <api>/auth/sign-in (HTTP 401).
```

`dashboard/e2e/global-setup.ts:38` hardcodes `sara.al-harbi@school.test`, which the acceptance seed profile
no longer creates — it seeds `maya@test.com` / `rami@test.com` instead. Also referenced at
`dashboard/e2e/local/env.ts:153` and `dashboard/e2e/local/theme-shell.spec.ts:171`;
`server/src/test/java/quest/server/classes/SchoolSeedTest.java:33` still expects Sara for the default profile,
so the fixture needs to be profile-aware rather than simply renamed. Owner: **test** (me) — out of scope for
this verification-only package.

**F9 — an unsectioned child cannot be deactivated or deleted by an admin.**
`PATCH /admin/children/{id} {"active":false}` returns 400 *"That child is not in a class yet."*, and there is
no admin delete-child route; `DELETE /children/{id}` needs the parent's own token. Test children created
through the app can therefore only be cleared by a reseed. Owner: **backend**.

**Not defects.** The parent sign-in and create-account screens are titled *"Schools Dashboard"* with the
subtitle *"Parents sign in; children just play."* That is the app correctly applying §A branding — QA's
`GET /platform-settings` returns `name: "Schools Dashboard"` — but the owner will open the parent app and see
the dashboard's product name. Change the platform setting if that is not wanted. Separately, an emulator
IME *"Try out your stylus"* tutorial swallowed scripted keystrokes; that is an emulator artifact, fixed with
`settings put secure stylus_handwriting_enabled 0`.

## What the owner will see

Register, join `HQ0001`, add a child and play a lesson all work, and they look good: the map, the soup-pot
progress meter, the worked examples and the end-of-lesson certificate all render correctly, and the generated
maths was on-topic and age-appropriate.

Three things will surprise the owner:

1. **A Grade 1 British child who is not on a section roster sees every section's copy of the same lesson.**
   Maya publishes once to 1A + 1B and the child's map shows *two* identical islands. Attaching the child to
   `1A British` collapses it to one. This is the designed behaviour, but it looks like a duplication bug
   until someone explains it.
2. **The app is called "Schools Dashboard"** on the parent sign-in screen.
3. **Adding a second child asks for the school code again.**

And one thing will stop the owner: **if a lesson's generation wedges at L3 there is no way out** — no error,
no retry, the class just says "draft" forever. That happened on one of the two lessons created for this pass.

## Cleanup — what is left on QA

**Deleted.** Maya's two lessons: `cb260e24…` (1A British) and its 1B copy `251dd049…`. Both needed
`POST /admin/lessons/{id}/unpublish` first — `DELETE` on a published lesson returns
409 *"Unpublish the lesson first"*.

**Detached.** `ChildA` was removed from the `1A British` roster
(`DELETE /admin/classes/{id}/roster/{childId}` → 200), so it will not appear in the owner's teacher views.

**Still there, and why:**

| What | Why it could not be removed |
|------|------------------------------|
| `c07ed9f0…` "Acceptance rami", status `generating` | `DELETE /admin/lessons/{id}` → 409 `{"code":"running","message":"Wait for the current job to finish."}`. The wedged job (F1) also blocks deletion, so this row cannot be cleared until the job runner gets a watchdog or QA is reseeded. It is a `draft` on `1A American`, so no child sees it. |
| `ChildA` `bd172998…`, `ChildB` `97bf0893…` | There is no admin delete-child route, and `PATCH /admin/children/{id} {"active":false}` returns 400 *"That child is not in a class yet."* — deactivation only works for a child already on a roster, so an unsectioned child cannot be deactivated or deleted by an admin at all (**F9**). `DELETE /children/{id}` is parent-scoped and needs the parent's Firebase token. Both are unsectioned, so they are invisible to the teachers. |
| Firebase Auth user `qa-parent+…@test.com` | A DB reseed does not touch Firebase Auth and there is no admin route to delete a user. Remove it from the Firebase console if the owner wants it gone. |
| `lesson-sh-sound` (11 Sep), `lesson-counting-by-2s` (14 Sep), `lesson-hot-soup-1` (14 Sep) | Part of the seed, not of this pass — deliberately left. Note an unsectioned British Grade 1 child sees all three. |

A QA reseed clears everything above except the Firebase user.

## How to reproduce

```sh
./gradlew :androidApp:assembleQaRelease \
  -Pquest.qa.apiBaseUrl=https://homework-quest-api-625882725080.me-central1.run.app --no-daemon
~/Library/Android/sdk/emulator/emulator -avd Pixel_8a -no-snapshot-load &
adb install -r -t androidApp/build/outputs/apk/qa/release/androidApp-qa-release.apk
adb shell settings put secure stylus_handwriting_enabled 0   # or the IME tutorial eats scripted input
adb shell monkey -p app.homeworkquest.qa -c android.intent.category.LAUNCHER 1
```

Teacher content is driven over the API (`SEED_STAFF_PASSWORD` for the seeded staff, `ADMIN_EMAIL` /
`ADMIN_PASSWORD` for the platform admin — never printed): sign in, `POST /teacher/lessons`
(`source: "pdf"`, `practiceLength: 5`), `POST …/files` with `dashboard/e2e/fixtures/one-page.pdf`,
`POST …/analyze`, poll to `needs_review`, `PUT …/skills` with the analysed skills, poll to `review`,
`POST …/publish {"classIds": […]}`.
