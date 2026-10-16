# Feature 31 — Teaching material tracking

**Status:** Done · **Area:** server, dashboard (Teacher, Admin, Manager, Coordinator, Floor supervisor) ·
**Depends on:** 14 (attendance settings and direct manager), 17 (attendance board), 20 (floor supervisor) ·
**Feature switch:** `teachingMaterials`

**Leftovers:**
- **No screen in the staff app.** The reminder reaches the phone through the bell and push, but material is handed
  in on the dashboard. The app shows the reminder with the generic "update" label.
- **The board leaves out leaders' staff with no teaching assignment.** Only people who teach have material to hand
  in.
- **Only teachers hand in material.** An Admin cannot use the teacher page; she follows material on the board.
- **A parent or child token on these routes gets 403, not 404.** The `/materials/**` routes sit behind the same
  security rule as `/staff-attendance/**`.
- **The file picker reuses the chat's wording,** so a refused file reads "attached".
- **The leaders' week picker** is a date plus previous and next week. The teacher page has the This week and Next
  week tabs.

*Spec drafted by the planner from the owner's brief (the owner gave no spec file); review it in the PR.*

## Goal

Every week each teacher hands in her teaching material — the weekly lesson preparation, دفتر التحضير — for each
class and subject she teaches, by a deadline the Admin sets. Leaders see at a glance who handed in on time, who was
late and who has not handed in at all, open the file, and mark it reviewed with a comment.

## Where to look first

- Feature 14: `attendance_settings`, the direct manager, and the Admin's Attendance settings page.
- Feature 17: `staffattendance/BoardScope` — who a leader sees — and the board's scope strings
  (`SCHOOL`, `ACADEMY`, `REPORTS`). Feature 20 already put the floor supervisor's teachers inside it.
- `tenancy/TeachingAssignmentRepository`: who teaches what. That table, not a hand-kept list, says what is expected.
- `platform/SchoolCalendar`: the school week and its zone (`Week.startOf`, `today`).
- Attachments: `files/AttachmentService.store(…, purpose)`, `files/MediaAccess`, and feature 18's `ATTENDANCE`
  purpose as the model for a new one.
- Background jobs: `staffattendance/StaffAttendanceJob` and its claim table.

## Behaviour

### The deadline

One row per school (`material_settings`): a weekday, a time and a number of hours before it to remind. The default
is **Thursday 12:00**, reminding **4 hours** before, read in the school's own time zone. Material for a teaching
week is due at that weekday and time **in the week before it**, so a teacher prepares the coming week while the
current one runs.

### The teacher: "My teaching material / دفتر التحضير"

1. `GET /teacher/materials?week=` lists one row per (class, subject) she is assigned to for that teaching week —
   this week's and next week's are the two the page offers — each with its status, the file, her note, and the
   review if a leader has made one. An absent `week` is the current teaching week.
2. `POST /teacher/materials/attachments` stores a PDF (up to 10 MB) or an image (up to 5 MB). Image metadata is
   stripped, as for a correction's photo.
3. `PUT /teacher/materials/{classId}/{subject}?week=` with `{attachmentId?, note?}` hands it in. At least one of
   the two is required. She may replace it until a leader has reviewed it; after that the row answers 409.
4. A class or subject that is not hers is refused — the assignment, never a parameter, decides.

### The leaders: "Teaching material / متابعة التحضير"

1. `GET /materials/board?week=` returns one row per teacher, with her counts and her (class, subject) rows, plus
   the totals of the page.
2. **The rows are the attendance board's own scope**, and nothing else: the Admin sees the school, a department
   manager her academy and her direct reports, a floor supervisor the teachers of her floors' classes and her
   direct reports, a coordinator or a teacher her direct reports only. A teacher outside that view is 404. Only
   employees who have teaching assignments appear — a worker has no material to hand in.
3. `POST /materials/{id}/review` with `{comment}` marks one row reviewed, naming the leader and the time. A second
   review of the same row answers 409: a review is a decision, not a field.
4. `GET /materials/board.csv?week=` is the same rows as a file.

### The Admin

`GET` and `PUT /admin/material-settings`, on the Attendance settings page, under the same two permissions as the
attendance settings themselves (`attendance.settings.read` / `.write`).

### The reminder

A job runs every five minutes for the schools with the switch on. From `remind_hours_before` before the deadline
until the deadline, each teacher who still has a row missing for the coming week is sent one reminder —
`NotificationKind.MATERIAL_REMINDER` (`material.reminder`), linking to her materials page. Claimed in
`material_reminders` under (school, teacher, week), so a rerun and a second instance send nothing twice.

### Status

Computed, never stored: `REVIEWED` once a leader has reviewed it, else `MISSING` with nothing handed in,
else `ON_TIME` when it arrived at or before the deadline and `LATE` after it.

## Out of scope

- Handing material in from the mobile app: the reminder reaches the staff app's bell and push, and the handing in
  stays on the dashboard.
- Any judgement of the material's content. The server records who handed in what and when, and nothing more.

## Acceptance criteria

- [ ] A teacher sees exactly the (class, subject) rows her assignments give her, for this week and the next.
- [ ] A row handed in before the deadline is on time, after it late, and never handed in missing — all in the
      school's own time zone.
- [ ] A teacher cannot hand in for a class or a subject she does not teach.
- [ ] The leaders' page shows exactly the teachers the attendance board shows that leader, and 404s outside it.
- [ ] A row is reviewed once; the second review is refused.
- [ ] One reminder per teacher per week, whatever the number of runs.
- [ ] A parent's or a child's token is refused, and the whole feature answers 404 with the switch off.
- [ ] Arabic and English for every string, right to left and dark mode.

## Tests

- Expected rows follow the teaching assignments; on time, late and missing around the deadline with the clock
  pinned in the school's zone; a class that is not hers refused.
- The board's scope matches the attendance board's for the Admin, a department manager, a floor supervisor and a
  coordinator or teacher with direct reports, and a row outside the scope is 404.
- Review once, 409 on the second; the reminder sent once per week.
- Parent and child tokens refused; the switch off gives 404; school isolation per new table.
- A material file is readable by its uploader and by a leader whose board scope covers her, and refused to anyone
  else.
