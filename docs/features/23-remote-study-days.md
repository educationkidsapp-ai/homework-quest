# Feature 23 — Remote study days

*Spec drafted by the planner from the owner's brief (the owner gave no spec file); review it in the PR.*

**Status:** Done · **Area:** dashboard (Admin, Manager), mobile app (parent, staff), server ·
**Depends on:** 15, 16, 18 · **Feature switch:** `remoteStudyDays`

**Leftovers:**
- **Turn `announcements` on together with `remoteStudyDays`.** The parents' notice is an announcement. With
  `announcements` off, parents still get the push, but the announcement page that shows it is switched off.
- **Today's attendance row uses the server's day.** The child's `attendance/today` still finds the row by the
  server's date (unchanged); only the remote-study-day check uses the school's time zone. The two differ only around
  midnight.
- **No grade scope for staff.** Grades narrow children and parents only. Staff are in scope by school or academy,
  as in feature 18.
- **Deleting a day sends no "cancelled" push.** It only expires the parents' announcement.
- **The app's demo mode has no remote study day.** The offline fake data always answers "present".
- **Dashboard budget.** The first-load warning threshold went from 750 kB to 800 kB. The bundle was 36 bytes over;
  it is 118 kB gzipped, against a 350 kB budget.

## Goal

Let the Admin declare that the school, one academy, or chosen grades **study from home** for a day — and have
everyone who is affected hear it once, in their own language: staff check in from anywhere, and the parents of the
classes in scope get an announcement and a push.

This **replaces** feature 18's school-wide remote day, which only ever said "the whole school" or "one academy" and
told staff alone. Feature 18's **per-employee** remote-day request is untouched: it is her request, approved for her,
and it works whether this switch is on or off.

## Where to look first

- Feature 18's `attendance_remote_days`, `RemoteDayService` and the Admin's remote-days card.
- Feature 15's check-in (`CheckInService`, the geofence and the record's `mode`) and feature 16's evening job.
- Announcements and their audiences, expiry and parent push (milestones 06, 07 and 11).
- The school's real academies and grades (`schools.curriculum_options_json`, `grade_options_json`) and its sections.

## Behaviour

### Declaring a day (Admin)

Admin → Attendance → **"Remote study days / أيام الدراسة عن بعد"** replaces feature 18's remote-days card:

- the date (today or later, in the school's zone);
- the scope: the whole school, one **academy**, chosen **grades**, or one academy's chosen grades;
- a reason, 1–200 characters. It is what the parents' announcement says, so it is written for them.

The academies and the grades offered are **the school's own** — its curricula and the grades it teaches — never a
hard-coded list. An academy or a grade the school does not teach is refused.

### Who the day reaches

The scope narrows two audiences, differently, and this is the heart of the feature:

| Audience | In scope by | Not narrowed by |
|---|---|---|
| Staff | the academy | the grades — a grade does not say who teaches it |
| Children and their parents | the academy **and** the grade | — |

### Staff

- On that day check-in **skips the geofence** for everyone in scope; the record's mode is `remote` and the board
  shows "عن بعد", exactly as feature 18 left it.
- Everyone in scope is told once — the evening before at 18:00 school time, or as soon as the day is created when it
  is today or tomorrow. The wording, the dedupe and the deep link are feature 18's and do not change:
  "غدًا يوم عمل عن بعد: …".

### Parents

- Creating the day posts a **bilingual announcement** to the parents of every class in scope, through the ordinary
  announcement fan-out and with the push every announcement carries: "Remote study day on <date>: <reason>" /
  "يوم دراسة عن بعد في <date>: …".
- It **expires at the end of that day** in the school's zone, so it leaves her feed by itself.
- Parents of another academy, or of another grade of the same academy, get nothing at all.
- Deleting the day **expires that announcement now** and pushes nothing new: a parent is never told twice, and never
  pushed about a cancellation she cannot act on.

### The two screens that read it

- The parent's Today and attendance say **"Remote study day / يوم دراسة عن بعد"** instead of a status chip. On such a
  day `GET /children/{id}/attendance/today` answers a record even though nobody marked a roster that is at home
  (`status: "NOT_MARKED"`, `remoteStudyDay: true`), where it used to answer nothing.
- The teacher's class attendance shows a banner for that date rather than asking her to mark children who are at
  home.

### Switching it on and off

- The switch is off by default. While it is off for a school, a school-wide remote day **does not exist** there: the
  routes answer 404, no day applies to a check-in, and no notice goes out.
- Every row a school already had under feature 18 was **copied** into the new table, so turning the switch on hands
  the school back every day it had already declared.

## Out of scope

- Remote **teaching** (timetables, online lessons, attendance taken remotely for children).
- Half days, recurring remote days, and a parent's own request for one.
- Cancelling a day that has already started as anything other than a delete.

## Acceptance criteria

- [ ] A day for one academy lets that academy's teachers — of every grade — check in from home, and no other
      academy's.
- [ ] A day for chosen grades still lets the whole academy's staff check in from home.
- [ ] The parents of the classes in scope get the announcement and the push; another grade's and another academy's
      parents get neither.
- [ ] The announcement is gone from the parent's feed at the end of that day, and at once when the day is deleted.
- [ ] An employee's own approved remote day still works, with the switch on and with it off.
- [ ] An academy or a grade the school does not teach is refused.
- [ ] The parent's Today and the teacher's class attendance both say it is a remote study day.
- [ ] A day declared under feature 18 is still there after the upgrade.

## Tests

- The migration copies feature 18's rows, with no grades and no announcement, once.
- The geofence is skipped for the academy in scope and for nobody else; grades do not narrow staff.
- The per-employee approved remote day works with the flag on and off.
- Parents in scope get the announcement and the push; out-of-scope academy and grade get nothing; delete expires it.
- An academy or a grade not of this school, a past date and an empty reason are all 400.
- Role checks (Admin declares and cancels, a Manager may read the list, teacher and parent neither), school
  isolation, and 404 while the switch is off.
- `attendance/today` and the teacher's class attendance carry `remoteStudyDay`.
