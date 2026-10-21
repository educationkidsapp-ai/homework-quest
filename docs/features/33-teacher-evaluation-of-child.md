# Feature 33 — Teacher evaluation of a child

**Status:** Done · **Area:** server, dashboard (Teacher, leaders, Admin), mobile app (Parent) ·
**Depends on:** 31 (the compliance pattern), 32 (a child's team), 17/20 (board scope) ·
**Feature switch:** `childEvaluations` (on by default, D30)

**Leftovers:**
- **One deadline per round** for the whole school; no per-class or per-subject deadline. The reminder defaults to 24
  hours before it, and a draft counts as missing.
- **No staff screen in the app.** Teachers write and leaders review on the dashboard; the reminder and the
  "returned" notice reach the staff app's notifications only.
- **No Playwright e2e or screenshots** for the three dashboard pages (their specs cover them).
- **The app screen was tested against demo data only**, not a live server; the server test covers the real answer.
- **`withBom` (CSV byte-order mark) is now defined in three dashboard features**; worth moving into `core/download`.
- **Return requires a comment on the dashboard**, though the server accepts an empty one.

*Spec drafted by the planner from the owner's brief (the owner gave no spec file); review it in the PR.*

## Goal

A few times a year the school asks every teacher to evaluate each child she teaches. The teacher writes it on the
dashboard, leaders make sure everyone has done it on time and check what was written, and the parent reads it in the
app in plain words. Parents never see a draft or a number; children never see an evaluation at all.

## Where to look first

- Feature 31 (`quest.server.materials`): expected rows from `teaching_assignments`, a computed status
  (`ON_TIME` / `LATE` / `MISSING` / reviewed), `StaffBoardScope` for who a leader sees, review once (409), the
  reminder job with its claim table, the CSV export, the teacher and leaders' dashboard pages.
- Feature 32: `ChildTeamService` (the team's teacher rows) and the app's `ChildTeamScreen` / `asConversation`.
- `NotificationService.parentsOfChannelPost` / `parentsOfAnnouncement` (one row per parent, after commit, once).
- `platform/SchoolCalendar` for the school's zone. There are no terms in the code — rounds are defined here.

## Behaviour

### Rounds (Admin)

The Admin creates an evaluation round: a name in English and Arabic (e.g. "Term 1 — mid-term"), the date it opens,
a deadline (in the school's zone) and how many hours before it to remind. She can edit it until it is released, and
she **releases it to parents** once, with a confirmation.

### The teacher: "Evaluations / تقييم الطلاب"

For an open round, one evaluation is expected per child, per class and subject she teaches (her assignments decide,
never a parameter). Each evaluation has:

- four criteria — participation, effort, behaviour, understanding — each rated on a four-point scale whose points
  are named **Excellent, Good, Developing, Needs support**;
- a comment, plus "going well" and "next step".

She saves a **draft** as often as she likes and **submits** when it is complete (all four criteria and a comment).
A submitted or approved evaluation cannot be edited (409); a returned one can, and is submitted again.

### The leaders: "Evaluation tracking / متابعة التقييم"

The same scope as feature 31's board (Admin: the school; department manager: her academy and direct reports; floor
supervisor: her floors' teachers; coordinator or teacher: direct reports). Per round, one row per teacher with her
counts — on time, late, missing, returned, approved — expandable to her evaluations. A leader **approves** a
submitted evaluation or **returns** it with a comment, once (a second decision is 409). The teacher is notified of a
return. A CSV of the counts (no ratings, no comments).

Status is computed, never stored: missing (nothing submitted — a draft counts as missing), on time or late (submitted
before or after the deadline), returned, approved.

### Reminder

Within the round's reminder window before the deadline, each teacher who still has missing evaluations gets one
`evaluation.reminder`, claimed once per round.

### The parent

`GET /children/{id}/evaluations` returns only **approved** evaluations of **released** rounds: the round, the
subject, the teacher, each criterion **as a word**, the comment, going well and next step. **No number, no draft, no
state, no reviewer is ever in the response.** On release each parent gets one `evaluation.released` per child; an
evaluation approved after the release appears at once and is notified too.

In the app: a "Teacher evaluations" screen, reached from the Progress screen, from the teacher's row on "My child's
team" (feature 32, "Latest evaluation"), and from the push. **Reply privately** opens the parent's chat with that
teacher.

### The child

Never sees an evaluation. The screen exists only in the parent area behind the parent gate, and an architecture test
keeps it out of child mode.

## Out of scope

- AI writing or summarising evaluations.
- Printing a report card.
- Showing evaluations to the child.

## Acceptance criteria

- [ ] Expected evaluations follow the teacher's assignments and the children in each class.
- [ ] Draft → submit → approve or return; on time and late in the school's zone.
- [ ] Leaders see exactly the attendance board's teachers and 404 outside it; review once.
- [ ] One reminder per teacher per round.
- [ ] Parents see only approved evaluations of released rounds, in words, never a number or a draft.
- [ ] Children never see evaluations.
- [ ] The switch off answers 404; school isolation for every new table.
- [ ] Arabic and English for every string, right to left and dark mode.

## Tests

- `ChildEvaluationsApiTest`: expected rows; draft counts as missing; on time and late around the deadline with the
  clock pinned in the school's zone; submit needs all four criteria and a comment; edit after submit 409; a class,
  subject or child that is not hers refused; board scope equals the attendance board's and 404 outside; approve or
  return once, 409 the second time; returned → edited → resubmitted; one reminder per round; release notifies each
  parent once per child; parent sees nothing before release, only approved after, never draft/submitted/returned;
  the parent JSON has no numeric value anywhere; another family's child 404; worker and parent tokens refused on staff
  routes; switch off 404.
- `IsolationTest` cases; `ChildTeamApiTest` for the "Latest evaluation" hook.
- App: view-model test, the child-mode architecture test, push routing, screenshots. Dashboard: the three pages.
