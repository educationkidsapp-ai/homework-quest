# Teacher flow

Reference document for the teacher role in the dashboard. It describes what a teacher can do, in what order, on which screens, and what the system does at each step. Prompts and code should point here instead of restating it. Put it in the repo as `docs/teacher-flow.md`.

Status: specification · as of 18 Sep 2026 · applies to the dashboard-only, one-school build.

---

## 1. Who the teacher is

A teacher is a dashboard user created by Admin. Her profile holds:

| Field | Example | Notes |
|---|---|---|
| Full name | Sara Al Harbi | shown to parents on lessons |
| Email | sara@school.edu | sign-in |
| Subjects | Math | one or more: math, english, science, … |
| Curriculum | British | american or british |
| Photo | | optional |
| Assignments | 1A · Math, 1B · Math, 2C · Math | set by Admin; see §2 |

The grades she teaches are not stored; they are derived from her assignments.

## 2. Classes and assignments

- A **class** is a section inside a curriculum and grade: `1A`, `1B`, `1C` in British Grade 1. Admin creates classes and their join codes.
- A **teaching assignment** links a teacher to a class for one subject. Rule: **one teacher per subject per class**. Sara can teach Math in 1A, 1B and 2C; 1A cannot have a second Math teacher. Admin's assignment picker blocks a taken class and names the current teacher.
- Everything the teacher sees and does is limited to her assignments. Routes outside them redirect to *My classes*; API calls outside them return 403, whether or not the UI was bypassed.

## 3. The flow at a glance

```mermaid
flowchart LR
    A[Sign in] --> B[This week]
    B --> C[Pick a class and day]
    C --> D[Lesson editor]
    D --> D1[Upload PDF / slides / images<br>or add stops manually]
    D1 --> D2[Analyze - cached]
    D2 --> D3[Review skills]
    D3 --> D4[Review plays - 3 levels]
    D4 --> D5[Parent panel EN/AR]
    D5 --> E[Preview as child]
    E --> F[Publish to class(es)]
    F --> G[Children play]
    G --> H[Results]
    H --> I[Mark open stops]
    I --> J[Release to parents]
    J --> K[Gradebook and child level]
    C --> X[New exam]
    X --> D
    K --> B
```

## 4. Step by step

### Step 1 — Sign in
Email and password. First sign-in forces a password change. *Forgot password* sends a reset link by email. After sign-in the teacher lands on **This week**.

### Step 2 — This week
A grid of her classes (rows) by the days of the school week (columns; default Sun–Thu, set by Admin).

- Each cell shows the day's item: a lesson card with a status square (none · draft · ready · published) or an exam card with a ribbon and its open/close window, plus "12/18 played" once children start.
- An empty cell shows a faint **+**; tapping it opens the lesson editor pre-set to that class, subject and date.
- Drag a card to another day in the same row to move an unpublished lesson. Drop it on a sibling row (same grade and subject) to copy it to that class.
- A summary strip lists gaps ("1B has no lesson Tuesday"), exams closing this week and open stops waiting for marks.
- Previous/next week, **Today**.

### Step 3 — My classes and the class page
**My classes**: one card per assignment ("1A · Math · British"), today's status, children count, one-tap **Add today's lesson** when missing.

**Class page** tabs:
- **Calendar** — month view; each date shows lesson status and a results column.
- **Children** — roster with stars this week, level band, weak skills; add/edit children if the `teacher.rosterEdit` flag is on.
- **Gradebook** — see Step 9.
- **Exams** — see Step 10.

### Step 4 — Create a lesson
From This week, the class calendar or **New lesson**. Class and subject are fixed; the teacher sets the date and picks a source:

| Source | What happens |
|---|---|
| PDF | text extracted; picture-only pages sent as images |
| Slides (PPTX) | converted to PDF on the server, then as above |
| Images | each photo is one page |
| Manual | opens Review plays on an empty lesson; the teacher adds stops herself and may press *Generate the other levels* |

Optional notes to the analyzer ("the teacher used a number line").

### Step 5 — Analysis (cached)
The server hashes the file. If the same file was analyzed before, by anyone, the result returns instantly with a badge **Analyzed before · 0 tokens**. Otherwise the pipeline runs:

`upload → analyze → confirm skills → (L1 ‖ L2 ‖ L3) → (variant ‖ parent panel) → ready`

The three levels are written **at the same time** — they read the same analysis and the same confirmed skills — and the Again variant and the parent panel then run together, once all three levels are stored (D25). Each is still its own step with its own deadline, so a failure in one leaves the others done.

A step strip shows each step as pending, running, done or error. On error the teacher sees a plain message and three actions: **Retry and continue**, **Retry this step only**, **Replace file**. Steps that already succeeded are kept; retries never regenerate them. While the pipeline runs the editor polls `GET /teacher/lessons/{id}/status`, which carries just the strip and the counters.

She does not have to watch it: whoever created the lesson gets a bell notification when the skills are waiting for her ("Skills to confirm"), when the questions are ready ("Questions ready") and when the job stops ("Generation stopped"), persisted server-side and pushed live on `/ws/chat` — `docs/runbook.md` "Chat and notifications" (E2, D26).

### Step 6 — Review skills
The skills found in the source, with anything the analyzer was unsure about flagged for a choice. The teacher ticks, renames, removes or adds skills, then **Build the practice**.

### Step 7 — Review plays
Three tabs — **Level 1 Same as the book · Level 2 Think · Level 3 Challenge** — each a list of stops in order. Per stop: title, ingredient, content, correct answers and parent tip (EN/AR), all editable inline; attach an image; reorder by drag; **Regenerate this stop**; **+ Add stop** of any type. A phone preview on the right renders the selected stop exactly as the child will see it.

Then the **Parent panel** tab: objectives, Supported and Challenge ideas, one tip per stop, in English and Arabic.

### Step 8 — Preview and publish
**Preview as child** opens the web player on the lesson in preview mode (attempts are not recorded in the gradebook).

**Publish** opens a sheet: "Publish to 1A on 18 Sep 2026", with checkboxes for her other classes of the same grade and subject ("Also publish to 1B, 1C"). On confirm, each selected class gets its own copy with separate results. The calendar cells turn to published. **Unpublish** and **re-publish** (new version) are available from the class page; draft and error lessons can be deleted.

### Step 9 — Results, marking, release, gradebook
When children play, attempts arrive and the server computes a score per child per lesson (0–100), level reached, stars and completion. Open stops (retell, drawing, open answer) count as complete but unscored until marked.

- **Results** (per lesson): who played, stars per stop, level reached, weakest stops, saved retells/drawings/open answers with 1–3 star marking and a comment, CSV export.
- **Release** toggle: parents see the score and comment only after release (default on for homework).
- **Gradebook** (per class): children × lessons grid, coloured band squares, per-child averages, class averages per lesson, teacher score overrides with the automatic score kept visible, "needs marking" filter, CSV/XLSX export.
- **Child page**: level band (emerging · developing · secure · exceeding) and trend per subject, score chart over time, skills going well / needing another look, comments, saved work.

### Step 10 — Exams
From the class page → **New exam**. Same editor as a lesson, plus settings: title, open and close date/time, one level (1, 2, 3, or *mixed* assembled from the generated levels), one attempt, hints off, results release automatic on close or manual.

- Children see a distinct island only during the window; no hints, no numbers, no timer on screen; the attempt resumes if interrupted; a second attempt is refused by the server.
- **Exam results**: per-child table (score, percent, band, time taken, submitted, marks pending), class average and distribution, per-question difficulty, absent children with **Re-open for this child**, release, CSV/XLSX, printable per-child PDF sheet.
- Exams count with a higher weight in the child's level.

### Step 11 — Profile
Name, photo, password, language (EN/AR; the dashboard mirrors to RTL).

## 5. Navigation

`This week · My classes · [selected class] · Profile`. Nothing else renders for a teacher.

## 6. What the child never sees

Regardless of what the teacher does: no red X, no percentage, no score, no timer. Children see stars, a sticker, ingredients in the pot and a certificate. Numbers belong to the teacher and, after release, the parent.

## 7. Rules the system enforces

| Rule | Where |
|---|---|
| Teacher sees only her assignments | UI guards and API (403) |
| One teacher per subject per class | database unique constraint + Admin picker |
| Publish only to classes of the same grade and subject she teaches | API validation |
| Same file analyzed once, forever | SHA-256 keyed cache, never expired |
| Retries never regenerate completed steps | step-based pipeline |
| Preview attempts never enter the gradebook | `previewOf` marker |
| One attempt per child per exam, inside the window | server-side check (409 on second) |
| Scores reach parents only after release | release flag per lesson/exam |

## 8. API used by the teacher (summary)

`GET /me` · `GET /teacher/week` · `GET /teacher/classes` · `GET /teacher/classes/{id}/calendar` · `GET /teacher/classes/{id}/children` · `POST /teacher/lessons` · `PATCH /teacher/lessons/{id}` · `GET /teacher/lessons/{id}/status` · `POST /teacher/lessons/{id}/copy` · `POST /teacher/lessons/{id}/retry` · `PUT /teacher/lessons/{id}/skills` · `PUT /teacher/stops/{id}` · `POST /teacher/stops/{id}/regenerate` · `POST /teacher/lessons/{id}/publish` · `POST /teacher/lessons/{id}/unpublish` · `DELETE /teacher/lessons/{id}` · `GET /teacher/lessons/{id}/results` · `PUT /teacher/marks` · `POST /teacher/lessons/{id}/release` · `GET /teacher/classes/{id}/gradebook` · `GET /teacher/children/{id}` · `POST /teacher/classes/{id}/exams` · `PATCH /teacher/exams/{id}` · `POST /teacher/exams/{id}/publish` · `POST /teacher/exams/{id}/release` · `POST /teacher/exams/{id}/reopen/{childId}` · `GET /teacher/exams/{id}/results` · exports `.csv`, `.xlsx`, `/results/{childId}.pdf`.

## 9. Feature flags touching this flow

`lessons.pdf` · `lessons.slides` · `lessons.images` · `lessons.manual` · `levels.three` · `parentPanel.arabic` · `gradebook` · `openStopMarking` · `exams` · `webPlayer` · `teacher.rosterEdit`

## 10. Acceptance walkthrough (QA)

1. Admin creates class 1A and 1B (British, Grade 1) and teacher Sara (Math, British) assigned to both.
2. Sara signs in → changes password → lands on This week with two rows.
3. She taps **+** on 1A · Thursday, uploads a PDF, watches the step strip complete, edits one Level 2 stop, previews as a child, publishes to 1A and 1B.
4. She uploads the same PDF for 2C → **Analyzed before · 0 tokens**.
5. A child of 1A plays it in the web player → Results show attempts within 5 s → Sara marks the retell → releases.
6. The 1A gradebook shows the score; the child page shows the level band.
7. She creates an exam for 1A with a 30-minute window; the child sits it once in the player; a second attempt is refused; results, distribution and the PDF sheet match.
8. Every attempt to reach 2B (not hers) returns 403 and redirects.
