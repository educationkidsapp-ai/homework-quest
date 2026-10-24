# Feature 36 — School structure builder

**Status:** Done · **Area:** dashboard (Admin), server · **Depends on:** 14 (settings, academy directors),
06 (departments), 03 (gradebook and exports), 20 (floor supervisor) · **Feature switch:** `schoolStructure`
(on by default, D30)

**Leftovers:**
- **No third academy, and no new subject key.** `quest.api.dto.Curriculum` (`british`, `american`) and
  `quest.api.dto.Subject` (six values) are closed enums in the shared contract, in about 41 OpenAPI enums and in the
  Android builds already on testers' phones, so a new key would make a released app fail to read its own data. The
  academies are therefore the two existing keys, and an academy's subjects are a choice among the six under its own
  names. Both need a contract change and a coordinated app release.
- **`curriculumType` is descriptive only.** British / American / Ministry / other is a label on the academy; nothing
  in the server branches on it.
- **The academy's grade naming reaches the Admin's screens only.** `hqGradeLabel` and the `gradeLabel` pipe read the
  naming from `GET /admin/structure`, which is `structure.manage` — the Admin's alone — so a teacher's, a
  coordinator's, a manager's and a parent's screens still say "Grade N", and so do the CSV and Excel exports. Giving
  the other roles the labels needs a read route of its own.
- **Two band displays still use the legacy words.** The exam results' distribution chart and the child page's skill
  pills are computed from `Bands` rather than from the academy's scheme; the gradebook, the child page's levels, the
  Results page, both exports and the exam table all follow the scheme.
- **The director cache can go stale.** `academies.director_id` mirrors the MANAGERIAL `staff_scopes` row, and only
  `AcademyService` writes both. Changing a manager's department from Admin → Managers instead
  (`PUT /admin/managers/{id}/scopes`) leaves the cached id behind until the structure screen is read again.
- **The subjects list is enforced on new writes only.** A drag onto a subject the academy does not offer is refused
  and a class move drops those assignments, but V58 does not revisit assignments already in the database.
- **No Playwright screenshots** for the structure tree or its dialogs.

## Goal

Let the Admin model the school as it really is — academies such as British and American, each with its own
curriculum settings — and see one visual tree of who is responsible for what, editing it in place instead of across
a dozen separate pages.

## Where to look first

- **Departments (milestone 06) are the academies.** Today a department is only a curriculum key (`british`,
  `american`) repeated on `courses`, `classes`, `children`, `teachers` and the managers' `staff_scopes` rows. This
  feature gives that key a row of its own — an **academy** — and every existing `curriculum` column keeps pointing at
  it by the same key. There is no second, parallel concept.
- Feature 14: the academy director (the department manager, a `MANAGERIAL` `staff_scopes` row with that curriculum).
- `courses` (curriculum, grade) and `classes` (the sections) — the grades and classes of the tree.
- `teaching_assignments` (class, subject, teacher) — the teachers per subject; coordinators from `staff_scopes`;
  floor supervisors from `floor_classes` / `floor_supervisors` (feature 20).
- Grading (milestone 03): `grading/Bands.java` — today one fixed scheme of four bands over a 0–100 score —
  the gradebook, its CSV and Excel exports, and the child page.

## Behaviour

### Academies — Admin → "هيكل المدرسة / School structure"
One row per academy, created from the existing departments on first run. Per academy:
- **Name** in Arabic and English.
- **Curriculum type:** British, American, Ministry or other.
- **Grade naming:** how grades are called — "Year 7" or "Grade 6" style, with an offset (British Year *n* = grade
  *n − 1*) and the Arabic word; used everywhere a grade is shown.
- **Grading scheme:** letter bands (e.g. A*–U) or numeric, each band with its lower threshold over 0–100 and its
  name in both languages. Parents and children keep seeing words, never numbers, as today.
- **Terms:** a name in both languages and start and end dates, per school year.
- **Subjects:** the shared Ministry subjects (Arabic, Islamic studies, social studies…) available to every academy,
  plus subjects of this academy only.
- **Director:** the academy director (the department manager). Changing her here changes her `staff_scopes` row.

### The tree
One visual tree: **academy → grades → classes → teachers per subject**, with the floor supervisor shown on each class
on her floor and the coordinators shown on the subjects they cover.
- Click any node to edit it in a side panel (an academy's settings, a grade's name, a class, an assignment).
- **Drag a teacher** onto another class (and subject) to move her assignment; dropping on a class where the subject
  is already taught asks whether to replace that teacher.
- Empty spots are visible: a class with a subject nobody teaches, a grade with no class, an academy with no director.

### Grading scheme in use
- The gradebook, its CSV and Excel exports (03) and the child page show the class's academy scheme.
- **Existing data keeps working:** an academy without a scheme uses the **default scheme**, which is exactly today's
  four bands (emerging, developing, secure, exceeding at 40, 60 and 85).
- The score itself is never changed; only the band it is shown in.

### Bulk tools
- **Create next year's classes** from this year's: each class moves up one grade with the same name pattern, the
  children optionally carried over, the teachers not carried over. A preview lists what will be created first.
- **Rename grades** of an academy in one go (the grade naming above).
- **Move a class to another academy:** a confirmation lists everything that changes — the class's curriculum, its
  children's academy, its assignments whose subject does not exist in the target academy, the director and
  coordinators who will see it, and its grading scheme.

### Guardrails
- The Admin only: every route requires the Admin; other roles keep their read-only views.
- A change that affects **released results** (a new or edited grading scheme of an academy with released results, or
  moving a class with released results) shows how many results will show a different band and asks for
  confirmation.
- School isolation for every new table; Arabic and English for every string; right to left; dark mode.

## Out of scope

- A timetable (periods and times).
- Changing scores, or recomputing exam results.
- Promoting individual children between years outside the bulk tool.

## Acceptance criteria

- [ ] The existing British and American departments appear as academies with their directors, and nothing that
      used them changes.
- [ ] The Admin sets an academy's names, curriculum type, grade naming, grading scheme, terms and subjects.
- [ ] The tree shows academy → grades → classes → teachers per subject, with floor supervisors and coordinators;
      clicking a node edits it; dragging a teacher moves her assignment.
- [ ] A British class shows "Year 2" where an American one shows "Grade 1" when the naming says so.
- [ ] With no scheme set, the gradebook and exports show exactly today's bands; with a letter scheme they show
      letters.
- [ ] Next year's classes are created from this year's after a preview.
- [ ] Moving a class lists what changes and asks for confirmation; a scheme change touching released results asks
      too.
- [ ] Only the Admin can change any of it; the switch off answers 404; another school's structure is invisible.

## Tests

- Migration: one academy per existing curriculum key, with its director; every existing row still resolves.
- Default scheme equals `Bands` for every score boundary; a custom letter scheme and a numeric scheme band scores
  at their thresholds; the gradebook, CSV and Excel and the child page use the academy's scheme.
- Grade naming in the dashboard and exports.
- Terms: dates valid, no overlap inside one year.
- Subjects: shared and academy-only; an assignment cannot use a subject the class's academy does not have.
- Tree: nodes per role on the structure; drag moves the assignment; replace asks first.
- Bulk: next year's classes (with and without children); rename grades; move class lists changes and applies them.
- Released results confirmation for scheme changes and class moves.
- Admin only on every route; school isolation; switch off 404.
