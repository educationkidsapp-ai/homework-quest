# N4.5 — gradebook and exams, verified

**Package** `test/gradebook-exams-e2e` (plan row N4.5) · **base** `develop` at `bc4fbfa` · **run** 20 Sep 2026
**Acceptance** `docs/teacher-flow.md` §10 steps 5–8 · **suites** `dashboard/e2e/local/n4-flow.spec.ts`,
`dashboard/e2e/local/n4-timing.spec.ts`, helpers in `dashboard/e2e/local/n4-api.ts`
**Screenshots** `docs/screenshots/n4.5/` (1366 × 768, EN light, EN dark, AR)

## How it was run

Local H2 with the `full` seed and no model calls, exactly as `dashboard/e2e/local/README.md` describes:

```bash
export JAVA_HOME=…/jdk-21…
./gradlew :shared-api:publishToMavenLocal -Pquest.serverOnly=true
(cd server && ./mvnw -q -B package -DskipTests)

SPRING_PROFILES_ACTIVE=h2 SEED_SCHOOL=true SEED_PROFILE=full \
  SEED_STAFF_PASSWORD="$E2E_STAFF_PASSWORD" ADMIN_EMAIL="$E2E_ADMIN_EMAIL" ADMIN_PASSWORD="$E2E_ADMIN_PASSWORD" \
  LLM_PROVIDER=fake PORT=18080 PUBLIC_URL=http://localhost:4300 DASHBOARD_URL=http://localhost:4300 \
  java -jar server/target/server.jar &

cd dashboard && pnpm build --configuration=production
pnpm e2e:local n4-flow                 # the walkthrough
E2E_TIMING=1 pnpm e2e:local n4-timing  # the numbers below
```

`gradebook`, `openStopMarking`, `exams` and `teacher.rosterEdit` are flipped on for the school through
`PUT /admin/schools/{id}/flags/{key}` in `beforeAll` and put back in `afterAll` (the one-school seed ships all four
off, and `my-classes.spec.ts` asserts that shape).

**The child plays over the API.** There is no web player (N3 is not built; the owner tests the child's side on the
mobile app), so the child's half of steps 5 and 7 is driven through the endpoints the app itself uses — a parent
bearer under `quest.auth.fake`, `GET /children/{id}/map`, `GET /lessons/{id}?childId=…` for the paper, and
`POST /children/{id}/attempts`. The seeded roster children have a `parentEmail` but no `parent_id` and no endpoint
hands one to an account, so the suite walks the app's own path instead: a parent creates her child with the class's
**join code** (`ChildService.create`). Nothing is written into the database behind the product's back.

## Step table — §10 steps 5 to 8

| # | Step | Expected | Observed | Result | Frame |
| --- | --- | --- | --- | --- | --- |
| 5a | Publish a homework to 1A | published and released to parents without being asked | `GET /teacher/lessons/{id}/results` → `released: true`, `played: 0`; the child's map carries the island | **pass** | — |
| 5b | A child plays it; results show the attempt within 5 s | her row carries the score within 5 s of the upload | 5 attempts uploaded, page reloaded, score `100` on screen — **133 ms** measured from the `POST` and logged by the run (assertion: < 5 000 ms) | **pass** | `results-1366-en.png` |
| 5c | Sara marks the retell 2★ from the Results page → score updates | 100 → 94 | score stays **100**: the mark is saved against the *top level's* stop, not the one the child answered — see defect **D1**. Kept in the suite as `test.fail()` | **fail (D1)** | `results-1366-en.png` |
| 5d | The score moves when the retell is marked on the stop she answered | 100 → 94, band `exceeding` | 94 on the Results page; `PUT /teacher/marks` accepted | **pass** | — |
| 5e | The parent sees the released score and the comment | `GET /children/{id}/progress` → score 94, band, comment | `{score: 94, band: "exceeding", comment: "Lovely retelling — <run>"}` | **pass** | — |
| 5f | Withdraw the release → the parent loses it | the red band asks, then the result leaves the parent | confirmation shown, toggle `aria-checked=false`, parent's `results` no longer names the lesson; releasing again returns it with nothing to confirm | **pass** | — |
| 6a | The 1A gradebook shows the score and the band | a cell with 94 and its band square | cell `94`, `data-band="exceeding"` | **pass** | `gradebook-1366-en.png` |
| 6b | The child page shows the band and the chart point | band badge and the lesson on the score chart | band badge visible; the chart's table row names the lesson | **pass** | `child-1366-en.png` |
| 7a | An exam with a 30-minute window, published | the island exists only inside the window and carries it | island present with `examWindow`; the paper says `type: exam`, `hintsOff: true`, `numbersOff: true` | **pass** | — |
| 7b | The child sits it once; an interrupted sitting resumes | two answers, then three more, one sitting | after the first batch `sat: 1`, `submitted: 0`; after the second, `submitted: 1` | **pass** | — |
| 7c | A second sitting is refused | 409 `exam_already_taken` | **409 `exam_already_taken`** | **pass** | — |
| 7d | Results: score, percent, band, time taken | 13 / 15 stars, 75 %, `secure`, a real time | `13`, `75%`, `data-band="secure"`, `0:02`, "Handed in" | **pass** | `exam-results-1366-en.png` |
| 7e | Distribution and per-question difficulty | one child in `secure`; a row per question | distribution table: `Se = 1` (others 0); "Question by question" has a row per paper stop, the wrong one at 100 % missed | **pass** | `exam-results-1366-en.png` |
| 7f | CSV, XLSX and the per-child PDF | all three download non-empty | `.csv` 1 023 B, `.xlsx` 4 822 B, `.pdf` 1 205 B on disk, all asserted > 0 | **pass** | — |
| 7g | An absent child is re-opened once; a second is refused | confirmation, then 409 | "may sit it again until …"; second `POST …/reopen/{childId}` → **409 `exam_already_reopened`** | **pass** | — |
| 8 | Another teacher's section is refused | 403/404 by API, nothing by URL | Omar: results, gradebook and exam results all refused; `PUT /teacher/marks` and `reopen` refused; the dashboard draws neither page for him | **pass** | — |

Every frame above also exists in dark and in Arabic (`-en-dark`, `-ar`).

## Timings

`n4-timing.spec.ts`, local H2, the fixture built through the API: **30 children** created by one parent with 1B's
join code, **20 hand-written homeworks** on the 20 most recent teaching days, one upload per child (3 000 attempts),
plus an exam the whole class sat. Measured over the grid the server really answers with — 53 children (the seed's 23
plus this run's 30) × 22 lessons, 631 played cells — 20 calls each, nearest-rank p95:

| Read | p95 | median | Target |
| --- | --- | --- | --- |
| `GET /teacher/classes/{id}/gradebook` | **21 ms** | 18 ms | < 1 s |
| `GET /teacher/exams/{id}/results` (30 sittings) | **7 ms** | 6 ms | < 1 s |

Both are two orders of magnitude inside the target, and neither grows per row in a way a 20-call sample can see.
**These are in-memory H2 on the dev Mac**, not Cloud Run against PostgreSQL: they say there is no N+1 and no missing
index in the read path, and they do not stand in for the QA p95 the reviewer measures after a deploy.

## QA probes (read-only)

`GET /health` reported `bc4fbfacb00c4dea41436bf84c45231329301ec6` before the probes, so QA is on this base. QA is the
owner's acceptance environment (`acceptance` seed: Maya and Rami, no children), and **nothing was written**: no child,
no parent, no lesson, no flag. Three calls each, warm instance.

| As | Call | Status | Latency | Note |
| --- | --- | --- | --- | --- |
| Maya | `GET /teacher/classes` | 200 | 0.25 s | 1A and 1B British math, 0 children |
| Maya | `GET /teacher/classes/{1A}/gradebook` | 200 | 0.21 / 0.23 / 0.34 s | `lessons: 0, children: 0` — an empty class, which is the acceptance seed |
| Maya | `GET /teacher/classes/{1A}/exams` | 200 | 0.20 / 0.20 / 0.28 s | `[]` |
| Maya | `GET /teacher/children/{id}` | — | — | **skipped**: the roster is empty, so there is no child to read |
| Admin | `GET /admin/flags` | 200 | 0.18 / 0.19 / 0.26 s | the definitions read back |
| — | `GET /schools/default/flags` | 200 | — | `exams`, `gradebook`, `openStopMarking`, `teacher.rosterEdit` all **on**; `webPlayer` and `multiSchool` off |

## Defects

### D1 — marking from the Results page never moves the score (major, §10 step 5)

**What happens.** A child plays a published lesson, the teacher marks her retell 2★ on the Results page, saves, and
her score does not change. Every per-stop cell on that page also reads "not attempted" for a child who played the
whole lesson.

**Why.** `GET /teacher/lessons/{id}/results` builds the column list from the lesson's **top** level —
`server/src/main/java/quest/server/grading/GradingService.java:96-100`
(`stopsByLevel.getOrDefault(top, List.of())`) — while each child's own stops come from the level she actually played
(`server/src/main/java/quest/server/grading/Scoring.java:69-82`, `scoredLevel`). The dashboard joins the two **by stop
id** (`dashboard/src/app/features/results/results.models.ts:74` and `:90`), so on a lesson with more than one level
nothing matches: the cells fall back to "not attempted", and the mark panel — whose open stops are the row's
(`dashboard/src/app/features/results/mark-panel.component.ts:119`) — offers the **top level's** retell.
`PUT /teacher/marks` then stores the stars against a stop the child never answered, answers 200, and the scorer
ignores them.

**Why it matters everywhere.** Publishing a manual (or generated) lesson creates Levels 2 and 3, so a published
lesson always has a top level of 3 while children play Level 1. Observed on this run: the child answered
`…:1:0:…`, the columns were `…:3:0:…`, the saved mark was `…:3:0:mb17c1d`, and the score stayed 100 where §7's
arithmetic says 94.

**Why nothing caught it.** The only marking covered until now is `results.spec.ts` (N4.2) on `seed/attempts.csv`,
whose homework has exactly one level — there the top level and the scored level are the same play.

**In the suite.** `n4-flow.spec.ts` keeps the screen-level assertion as `test.fail()` with the analysis beside it, so
the run stays green today and turns red the moment the fix lands (an unexpected pass). The rest of the chain is
driven by marking the stop the child answered, so steps 5d–6b are proved on correct data.

**Owner.** `backend` for the contract (columns and the child's stops have to be the same level, or the row has to
carry its own columns), `dashboard` for the join. One of the two, not both.

### D2 — the exam create contract and its validator disagree (minor)

`ExamDto.CreateExamRequest` declares `@Min(3) @Max(20) Integer practiceLength`
(`server/src/main/java/quest/server/exams/ExamDto.java:66`), and `server/openapi.json` publishes that range, but the
create path enforces 5–12 (`server/src/main/java/quest/server/admin/AdminLessonService.java:141`, "Practice length
must be 5–12 stops."). `POST /teacher/classes/{id}/exams` with `practiceLength: 3` — legal by the contract — is a 400.
Owner: `backend`.

### D3 — a re-opened child counts as having sat the exam (minor, §8's metric cards)

`reopen` writes a sitting row in state `STARTED` for a child who has answered nothing
(`server/src/main/java/quest/server/exams/ExamService.java:177-190`), and the results pass counts every non-absent
child into `sat` (`ExamService.java:228`). After re-opening one absent child the page reads **"Sat it 2 of 19"** with
"Handed in 1" and "Absent 17" while only one child has ever answered a question — the metric card contradicts its own
table, where her row reads "Re-opened" with an em dash for a score. Visible in `docs/screenshots/n4.5/exam-results-1366-en.png`.
Owner: `backend`.

### D4 — the exam paper still carries its hints (observation)

`ExamPlays.decorate` sets `hintsOff: true` and `numbersOff: true` but leaves each single-answer stop's `hint` string in
the body the child's device downloads (`server/src/main/java/quest/server/exams/ExamPlays.java`, `decorate`). §8's "no
hints" is therefore a switch the client is trusted to honour rather than content that is not sent. That is defensible
for the mobile app; it will not be for the web player, where the payload is one devtools tab away. Worth a decision
before N3 ships. Owner: `backend` (and `mobile` for honouring it today).

### D5 — an exam's percent while the child is still inside the paper (observation)

Mid-sitting, `ExamChildResult.percent` is computed over the stops she has answered, so a child two questions into a
five-question paper with both right reports `percent: 100`, `band: exceeding` over the API. The results page hides it
(`handed(row)` gates the score, the percent and the band on `submitted`), so only an API consumer — an export, a
future app screen — can be misled. Owner: `backend`, if anything.

### D6 — a red spec already on `develop`: the roster table overflows its card (pre-existing)

Not this package's, and not caused by it: on a freshly seeded server, running only that file,
`roster-place.spec.ts:180` ("the table fits its card at every desktop width") fails on `bc4fbfa` with **"the roster
table overflows its card by 62px at 1024px"**. The 1280 and 1366 widths pass. The spec exists because the verbs moved
into a menu when the table was wider than its card, so this is the same problem coming back at the narrowest desktop
width. Owner: `dashboard` (N2.6). Reported here because the local suite is not green without it and the next worker
should not spend the morning on it twice.

## What still needs the app

Nothing below is a defect; it is the part of §10 no browser can reach today.

- **The child sitting an exam.** There is no web player (`webPlayer` is off on QA and N3 is not built), so the island,
  the resumable sitting, the "one attempt" message and the absence of hints, numbers and a timer on screen are still
  only testable in the mobile app. This suite proves the **server's** half of all four.
- **Hints and numbers off.** `hintsOff` / `numbersOff` are honoured by the player, not by the payload (D4): what the
  child actually sees has to be checked in the app.
- **Results for parents.** The parent's side is asserted on `GET /children/{id}/progress` — the released score, band
  and comment, appearing and disappearing with the release. How it is drawn in the app (parent mode only, never a
  number in front of the child, §6) is the app's own acceptance.
- **A managerial account.** The one-school seed has none, so "results read-only for MANAGERIAL" is still uncovered
  here.

## Suite health

`n4-flow.spec.ts` is idempotent on a shared database: every title and the child carry a per-run tag, the child is
created by this run's own parent, and `afterAll` unpublishes and deletes the lesson and the exam, removes the child
and restores all four flags. Both files skip themselves when `E2E_BASE_URL` is set — they write, and QA is read-only
for this package — and `n4-timing.spec.ts` additionally waits for `E2E_TIMING`, so the everyday local run stays at
about four minutes.
