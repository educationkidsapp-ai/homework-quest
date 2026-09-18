# Teacher-first v1 — prompt

Paste everything below this line into Claude Code. This prompt **narrows the plan**: build the teacher flow first, on the Angular dashboard and the Spring backend, and defer the multi-school layer. It overrides `docs/dashboard-prompt.md` where the two differ; §B of that prompt (planner + worker agents) and §A (dynamic product name, default **Schools Dashboard**) still apply. Update `docs/plan.md` first, then build.

---

## 1. Scope of v1

Build only this: **Admin creates classes and teachers. A teacher signs in, sees only the classes she teaches, opens one, adds a lesson (upload or manual), reviews it, and publishes it to that class. Children in that class see it on their map.**

Deferred (keep the flags and tables, hide the screens): schools/tenancy, themes, managerial role, complaints, announcements, platform settings UI, teacher questions to students, self-registration, invite emails.

## 2. Model

- **Class** = curriculum × grade × section, created by Admin. `Class(id, curriculum: american|british, grade: 1|2|3, name: "1A", joinCode, active)`. Grade 1 British may have classes 1A, 1B, 1C.
- **Teacher** = a dashboard user with a profile: `Teacher(userId, fullName, photoUrl, subjects[]: math|english|science|…, curriculum, active)`.
- **TeachingAssignment(teacherId, classId, subject)** with a unique constraint on `(classId, subject)`: **one teacher per subject per class**, and a teacher may teach the same subject in many classes (1A, 1B, 2C). The grades a teacher "teaches" are derived from her assignments, not stored.
- **Child** belongs to one class: `Child(…, classId)`. The parent joins a class in the app by entering the class join code (or picking curriculum → grade → class from a list if `class.joinCode` is disabled by Admin). Curriculum and grade are then taken from the class, not chosen by the parent.
- **Lesson(id, classId, subject, date, teacherId, status: draft|ready|published, source, version, …)**; the map for a child = published lessons of the child's class, all subjects, by date. A teacher may publish one lesson to several of her classes in one action: the server copies the lesson per class (same plays, separate ids and results), so results and edits stay per class.
- Validation: a teacher can only create or publish a lesson for `(classId, subject)` pairs in her assignments; the server rejects anything else with 403 even if the UI is bypassed.

Migrations: add `Class`, `Teacher`, `TeachingAssignment`; move `Child.curriculum/grade` to `Child.classId` (data migration creates a default class per existing curriculum+grade and moves children into it); add `Lesson.classId` and `Lesson.teacherId`; drop the old `Course` table. Keep `School` columns nullable and unused for now.

## 3. Admin (minimal)

Admin signs in to the same dashboard and sees three screens:

1. **Classes** — table by curriculum and grade; create a class (curriculum, grade, name), regenerate its join code, print a join-code card (PDF) for parents, deactivate.
2. **Teachers** — create a teacher (full name, email, temporary password shown once, subjects, curriculum, photo); assign classes per subject with a picker that blocks a class already taken for that subject and says by whom; reset password; deactivate. A seed script `server/src/main/resources/seed/teachers.csv` + `classes.csv` loads the same data on `qa` for demos.
3. **All lessons** — read-only across classes, with the existing pipeline pages for debugging and the cache page.

## 4. Teacher — screens

1. **Sign in** — email + password; first login forces a password change.
2. **My classes** — one card per assignment, grouped by grade: "1A · Math · British", today's status (`No lesson yet` with a one-tap **Add today's lesson**, or `Published · 12 of 18 played`), children count. Only her assignments, nothing else exists in her navigation.
3. **Class page** — header "1A · Math"; a month calendar where each date shows the lesson status (none / draft / ready / published) and a **Results** column; a **New lesson** button pre-set to this class and subject; a **Children** tab listing the class's children with stars this week, level reached and weak skills.
4. **Lesson editor** — the existing pipeline with class and subject fixed: choose source (PDF, slides, images, manual), date, optional notes; step strip with retry and continue; Review skills; Review plays (three levels, inline edit, add stop, attach image, phone preview); Parent panel. Everything from the earlier lesson-pipeline prompts applies unchanged.
5. **Publish** — a sheet: "Publish to 1A on 18 Sep 2026" with a checklist of her other classes of the same grade and subject ("Also publish to 1B, 1C"); on confirm the lesson appears on those children's maps and the class calendar cell turns to published. Unpublish and re-publish (new `version`) available from the class page.
6. **Results** (per lesson) — who played, stars per stop, level reached, weakest stops across the class, and the saved retells/drawings for open stops; export CSV.
7. **This week** — one calendar across **all** her classes. Columns are the days of the current week (school week from Admin settings, default Sun–Thu), rows are her assignments ("1A · Math", "1B · Math", "2C · Math"). Each cell shows that day's item as a small card: lesson title with a status square (none / draft / ready / published), or an exam with a ribbon and its window, plus a played count ("12/18") once children start. Empty cells show a faint **+** that opens the lesson editor pre-set to that class, subject and date. Drag a card to another day of the same row to move an unpublished lesson; drop a card onto another row of the same grade and subject to copy it to that class (uses the multi-class publish rules). Previous/next week arrows, a **Today** button, and a week summary strip: lessons published, gaps ("1B has no lesson Tuesday"), exams closing this week, open stops waiting for marks. This is the teacher's landing page after sign-in; My classes stays one tap away.
8. **Profile** — name, photo, password, language (EN/AR).

Navigation for a teacher: This week · My classes · (selected class) · Profile. No other menu items render, and routes outside her scope redirect to My classes.

## 5. API (additions and changes)

Teacher: `GET /me` (profile + assignments), `GET /teacher/week?start=` (all assignments × days with lesson/exam cards and played counts, one query), `PATCH /teacher/lessons/{id}` (move date while unpublished), `POST /teacher/lessons/{id}/copy` (to another class), `GET /teacher/classes`, `GET /teacher/classes/{id}/calendar?month=`, `GET /teacher/classes/{id}/children`, `POST /teacher/lessons` (classId, subject, date, source), `POST /teacher/lessons/{id}/publish` (body: `classIds[]` ⊆ her classes with the same subject and grade), `POST /teacher/lessons/{id}/unpublish`, `GET /teacher/lessons/{id}/results`, `GET /teacher/lessons/{id}/results.csv`.
Admin: `POST/GET/PATCH /admin/classes`, `POST /admin/classes/{id}/join-code`, `POST/GET/PATCH /admin/teachers`, `PUT /admin/teachers/{id}/assignments`.
App: `POST /children` now takes `joinCode` (or `classId`); `GET /classes/lookup?code=` returns class name, grade and curriculum for the confirmation screen; the map endpoint filters by `classId`.
All teacher endpoints are scoped by the caller's assignments in the service layer; an ArchUnit test asserts every `/teacher/**` controller method calls the assignment check.

## 6. Mobile app changes (small)

- Add child: replace the curriculum/grade chooser with **Enter your class code** → shows "1A · Grade 1 · British" → confirm. Keep the manual curriculum → grade → class list behind a flag `join.byList`.
- The map reads the class's published lessons; the parent calendar shows the teacher's name and photo per lesson.
- Nothing else changes.


## 7. Homework scores and the child's level

Every published lesson is homework. Its results already exist as stars and attempts per stop; v1 turns them into a **gradebook** the teacher can see and adjust.

- **Automatic score per child per lesson.** The server computes `HomeworkScore(childId, lessonId, autoScore 0–100, level reached 1–3, starsTotal, completion %, computedAt)` from attempts: single-answer stops score by first-try correctness, multi-answer/match/order stops by mistakes (3 stars = 100, 2 = 70, 1 = 40), open stops count as complete but unscored until the teacher marks them. Recomputed whenever new attempts arrive.
- **Teacher adjustments.** On the lesson Results page and on the child's page the teacher can set `teacherScore` (overrides auto, keeps auto visible), mark each open stop (retell, drawing, open answer) with 1–3 stars and a one-line comment, and add a lesson comment to the parent. All of it in `TeacherMark(childId, lessonId, stopId?, stars?, score?, comment, markedAt)`.
- **Child level.** Per child per subject, a rolling `ChildLevel(childId, subject, band: emerging|developing|secure|exceeding, trend: up|flat|down, computedAt)` from the last 10 homework scores (weighted toward recent), the levels reached, and the weak-skill list. Thresholds are constants in `server/.../grading/Bands.java`, editable by Admin later.
- **Teacher screens.**
  - *Class gradebook* (Class page → Gradebook tab): a grid of children × lessons for the month, each cell the score with a coloured square glyph (green/amber/red bands), a per-child average column, a per-lesson class average row, sortable; click a cell to open that child's attempt detail; **Mark open stops** filter shows only what still needs the teacher.
  - *Child page*: level band and trend per subject, a line chart of scores over time, skills (going well / needs another look), the teacher's comments, and the saved open-stop work with the marking controls.
  - Export the gradebook as CSV/XLSX.
- **Parent sees** the score and the teacher's comment for each lesson in the app's calendar and progress report, only after the lesson's results are **released** (teacher toggle per lesson, default on for homework). The child never sees a number: child mode keeps stars only.

## 8. Exams

A teacher can create an **exam** for a class: a lesson with `type = exam` and its own rules.

- **Create**: from the Class page → **New exam**. Same editor as a lesson (upload a PDF/slides/images and let the pipeline generate, or add stops manually), plus exam settings: title, open date/time and close date/time, one level only (teacher picks 1, 2 or 3, or "mixed" = the teacher assembles stops from the three generated levels), `attempts = 1`, hints off, "Again/Harder" off, results release: automatic on close or manual.
- **Child experience** (child-mode rules still apply): the exam appears on the map as a distinct island with a ribbon, only between open and close; Pip explains "This one is a test — do your best, no hints today"; no red X, no timer on screen, no score shown; a "well done" screen at the end with the sticker, and no replay. If the child leaves mid-exam the attempt resumes where it stopped until the close time.
- **Scoring**: same rules as §7, all stops must be answered; open stops marked by the teacher; a `ExamResult(childId, examId, score, maxScore, percent, band, submittedAt, releasedAt)` row per child. Children who did not sit it are listed as *absent* with a **Re-open for this child** action (extends the window for one child).
- **Teacher screens**: *Exam results* page — per-child table (score, percent, band, time taken, submitted at, marks pending), class average and distribution chart, per-stop difficulty (which questions most children missed), release button, export CSV/XLSX and a printable per-child result sheet (PDF) for the school file. The gradebook shows exams as separate, highlighted columns and includes them in the child's level with a higher weight (constant in `Bands.java`).
- **Parent sees** the exam result and band in the app after release, with the teacher's comment; a push/email notification on release.
- **API**: `POST /teacher/classes/{id}/exams`, `PATCH /teacher/exams/{id}` (settings), `POST /teacher/exams/{id}/publish`, `POST /teacher/exams/{id}/release`, `POST /teacher/exams/{id}/reopen/{childId}`, `GET /teacher/exams/{id}/results`, `.../results.csv`, `.../results/{childId}.pdf`; app: the map endpoint returns exams with `opensAt/closesAt`, `POST /children/{id}/exams/{examId}/attempts` (single attempt enforced server-side, resumable).
- **Data**: `Lesson.type: homework|exam`, `ExamSettings(lessonId, opensAt, closesAt, level, hintsOff, releaseMode)`, `ExamResult`, `TeacherMark` (shared with §7). Flags: `exams`, `gradebook`, `openStopMarking`.

## 9. Build order (planner packages; PRs into `develop`, QA deploy after each)

1. `backend`: migrations, entities, assignment-scoped services, seed CSVs, endpoints, tests (including "teacher B cannot read class of teacher A" and "second teacher for Math in 1A is rejected").
2. `dashboard`: teacher navigation and the eight screens including This week with drag-to-move and drag-to-copy, Admin's three screens, role guards.
3. `mobile`: join-by-code flow and class-based map.
4. `backend` + `dashboard`: gradebook (§7) — score computation, teacher marks, child level, Gradebook tab, Child page, release toggle; app shows released scores in parent mode.
5. `backend` + `dashboard` + `mobile`: exams (§8) — settings, exam island and single resumable attempt in the app, results page, release, exports, PDF sheet.
6. `test`: e2e — Admin creates class 1A and teacher Sara (Math, British, 1A + 1B); Sara logs in, sees two cards, publishes a lesson to both; a child joined to 1A sees it, a child in 2A does not; the child plays, Sara marks the retell, releases, the parent sees the score; Sara creates an exam for 1A with a 30-minute window, the child sits it once, results and PDF export match the attempts.
7. `docs`: update `docs/backend.md`, `docs/dashboard-angular.md`, `docs/android.md`, `docs/plan.md`.

## 10. Acceptance

- After sign-in the teacher lands on This week showing every class she teaches, with the correct cards per day; moving a draft to another day and copying a lesson to a sibling class both work by drag and are reflected in the class calendars; the gap summary names the exact class and day.
- A teacher's dashboard shows only her classes; every attempt to reach another class's data returns 403 from the API and redirects in the UI.
- Creating a second Math teacher for 1A fails with a clear message naming the current teacher.
- Sara publishes one lesson to 1A and 1B in one action; each class gets its own copy with separate results.
- A child with code for 1A sees the lesson on today's island in the QA APK; the parent calendar shows "Ms Sara".
- The gradebook for a class of 25 children and 20 lessons loads in under 1 s from the QA seed; changing a teacher score updates the child's level without a page reload; open stops needing marks are listed and clear once marked.
- An exam cannot be started before `opensAt` or after `closesAt`, allows exactly one attempt per child (a second `POST` returns 409), shows no hints and no numbers to the child, and its results appear to the parent only after release.
- `quality-performance` approves each PR; Lighthouse ≥ 90 on the teacher screens; p95 < 300 ms on `/teacher/classes` and the calendar endpoint with the QA seed (30 classes, 40 teachers, 600 children).
