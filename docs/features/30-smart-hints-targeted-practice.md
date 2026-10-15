# Feature 30 — Smart hints and targeted practice (stretch)

**Status:** Done · **Area:** server, dashboard (Teacher), mobile app · **Depends on:** 24 (AI service layer) ·
**Feature switch:** `smartHints` (camelCase, like the other switches).

**Leftovers:**
- **One parent-only route under `/children` is exempt from the no-AI rule:** feature 28's evening card
  (`/children/{id}/evening-card`). A parent reads it behind the parent gate, and the child's screens never call it. The rule names it, and a
  test checks the route still exists, so the next exemption is a decision rather than an accident.
- **Hint and practice quality needs the real model on QA.** The fake model deliberately writes one bad hint per
  lesson, so the reject, retry and drop path runs on every test build.
- **Hints and practice are not cached.** A rebuild pays for them again, because the cache is keyed to stop ids that
  a regenerate changes.
- **Refusals follow the rest of the teacher's lesson routes:** 403 for a colleague in the same school, 404 for
  another school.
- **The hint and approve request bodies are pre-serialised JSON strings in `openapi.json`,** like the other lesson
  routes. The dashboard builds them by hand.
- **The play schema widened.** Variants 0–5 and 4–9 stops are now allowed, and the level and practice rules moved
  into `SchemaValidator`.

*Spec drafted by the planner from the owner's brief (the owner gave no spec file); review it in the PR.*

**Decision (the owner's own rule):** **generation happens only at lesson build time and is approved by the teacher.**
No child-facing endpoint may reach the AI, now or by accident later, and a test asserts it.

## Goal

Better help for the child without any AI at play time: approved, step-by-step hints on every question, and a short
practice set for each weak skill. Both are written when the lesson is built and are published only after the teacher
has read them.

## Where to look first

- The lesson pipeline and its step ledger (milestone 05): `analysis/LessonPipeline`, `LessonSteps`,
  `PipelineDeadlines`, `StepBudget`, `PipelineWatchdog`.
- Prompt B's play rules and the shared play validator: `analysis/Prompts`, `analysis/LlmJson`,
  `quest.api.validation.SchemaValidator`.
- The one AI loop (feature 24): `ai/AiService`, `ai/AiTask`, and `analysis/SampleLlmClient` for the fake provider.
- The published lesson and the map: `content/LessonStore`, `content/LessonController`, `children/MapService`,
  `children/ProgressService.weakByLesson`, `quest.api.map.MapAssembler`.

## Behaviour

### Smart hints

1. A new pipeline step **`HINTS`** runs after `PANEL`, last in the pipeline, **only when the school's `smartHints`
   flag is on** and **only for homework** — an exam (`type = exam`, `hintsOff`) never gets hints.
2. It asks for **two graded hints for every answerable stop** of Levels 1–3 and the Again variant: a nudge first,
   then a stronger pointer. **Never the answer.**
3. The validator rejects a hint that contains the stop's correct answer (the correct option's words, the number, the
   spoken word, `true`/`false`, the comparison sign) and a hint longer than 90 characters — Prompt B's own limit for
   `hint`. The model is asked again with the errors; a hint that still breaks a rule is **dropped**, and the step
   still succeeds with the hints that are good.
4. Hints are stored unapproved. The teacher reads, edits and approves them per stop or all at once; **editing a hint
   clears its approval** until she approves again.
5. Only **approved** hints reach the published lesson (`PublishedLesson.smartHints: Map<stopId, List<String>>`), at
   the next publish version. The app shows hint 1 after the first wrong try and hint 2 after the second, in place of
   the single `stop.hint`. With no approved hints, today's behaviour stays.

### Targeted practice

1. In the same step, **one short practice play (4–5 stops) per confirmed skill** of the lesson, written with Prompt
   B's play rules and focused on that skill, checked by the same play validator.
2. Stored as an extra play of the lesson — **level 1, variant 2 and up** — with a row naming the skill it is for.
   Unapproved by default.
3. The teacher sees them beside the lesson and **approves or rejects** each one. Only approved ones are published
   (`PublishedLesson.practice: List<PracticePlay(skillId, play)>`).
4. The map's **review island** for a weak skill points to that skill's approved practice play instead of the generic
   "Again" play. With none approved, today's behaviour stays (`lessonId:1:1`).

### Failure never dead-ends a lesson

`HINTS` keeps its own ledger row, deadline, budget and transient retries like every other step. **A failed `HINTS`
step does not fail the lesson:** the row is marked `error` with the usual message, and the lesson still reaches
review and can be published without hints or practice.

## Endpoints

All of them resolve the lesson through `TeacherScope` first (the teacher wrote it, or she holds the assignment on
its class and subject), carry the `smartHints` flag, and answer 404 while the flag is off. A lesson that is not hers
is refused the way the rest of `/teacher/lessons/{id}/**` refuses one: 403 to a colleague of her own school, 404 to
another school's teacher.

| Route | What it does |
|---|---|
| `GET /teacher/lessons/{id}/smart-hints` | the hints of every stop and the practice plays, with their approval state |
| `PUT /teacher/lessons/{id}/smart-hints/{stopId}` | replace one stop's two hints (clears approval) |
| `POST /teacher/lessons/{id}/smart-hints/approve` | approve the named stops, or all of them |
| `POST /teacher/lessons/{id}/practice/{playId}/approve` | publish this practice play |
| `POST /teacher/lessons/{id}/practice/{playId}/reject` | drop it (the play and its row go), `204` |

## Data

`V52__smart_hints.sql`, additive and idempotent:

- the `smartHints` flag, off;
- `stop_hints(stop_id PK, lesson_id, school_id, hints_json, approved_by, approved_at, created_at)`;
- `practice_plays(play_id PK → plays, lesson_id, school_id, skill_id, approved_by, approved_at)`.

Both carry `school_id` for the Hibernate `school` filter, as every tenant table does.

## Out of scope

- Hints at play time written by the AI — the whole point of the feature is that there are none.
- Hints on exams, on open stops and on information stops.
- A practice play for a skill the lesson did not confirm, and practice across lessons.

## Acceptance criteria

- [ ] No AI call is possible from any child-facing endpoint: an ArchUnit rule over the call graph, and a runtime
      test that replaces the provider with a mock and verifies zero interactions.
- [ ] Hints and practice are generated only by the build step, and never by a child's or a parent's request.
- [ ] An exam gets neither hints nor practice.
- [ ] Unapproved hints and unapproved practice never appear in `GET /lessons/{id}`.
- [ ] Approving, then publishing, adds them at the next version; editing a hint clears the approval.
- [ ] A hint containing the answer is rejected, and dropped if the model repeats it.
- [ ] The review island uses the approved practice play and falls back to "Again".
- [ ] A colleague's lesson is 403 and another school's is 404; school isolation; the flag off means no step and 404
      on every route.

## Tests

- `ChildEndpointsHaveNoAiTest` (ArchUnit) and `NoAiFromChildEndpointsTest` (runtime, `@MockitoBean LlmClient`).
- `teacher/SmartHintsApiTest`: the step runs at build only, exams get none, unapproved content is never published,
  approve → published at the next version, editing clears approval, a leaking hint is dropped, the review island,
  another teacher's lesson, the flag off, school isolation.
- `tenancy/IsolationTest`: a case for the two new tables.
- `FlagAdminTest`, `PermissionsTest`, `PreAuthorizeCoverageTest`, `FeatureFlagCoverageTest`, `OpenApiContractTest`.
