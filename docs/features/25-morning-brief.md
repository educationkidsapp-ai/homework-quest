# Feature 25 — Morning brief

*Spec drafted by the planner from the owner's brief (the owner gave no spec file); review it in the PR.*

**Status:** Done · **Area:** server, dashboard and mobile app (Admin, Manager) · **Depends on:** 16 (staff
attendance statuses), 24 (AI service layer); sits above 21's cards where 21 is on ·
**Feature switch:** `morningBrief` (camelCase, like the other switches).

**Decision:** the **server counts and the AI only phrases**. Every number in a brief is computed from what the
platform already stores, through the service that already owns that feature and already narrows it to the caller.
The model is handed those numbers and asked for three to five short lines in Arabic and English. A line that carries
a number the facts do not hold is rejected, and the brief falls back to a plain bilingual template built from the
very same facts. There is no path on which an invented number reaches a leader.

## Goal

An Admin or a department manager opens her Home in the morning and reads, in four or five seconds, what yesterday
was and what today needs — in her own language, for her own academy. Above feature 21's cards, so the brief tells her
where to look and the cards let her act.

## Where to look first

- Feature 21's "what needs me today?" Home (`GET /me/today`, `dashboard/TodayService`): the brief is the sentence
  above those cards and must never disagree with them.
- Feature 16's statuses in `staff_attendance_days`, and feature 17's `BoardScope` — who a leader's people are.
- Feature 24's `quest.server.ai` (`AiService`, `AiTask`, `AiInputGuard`): the one loop every AI feature runs.
- Feature 16's `StaffAttendanceJob` and its `staff_attendance_alerts` claim — how a job sends a thing exactly once.

## Behaviour

### The facts, counted by the server

Narrowed to the caller exactly as feature 21's Home is: the Admin reads the school, a manager reads her academy
(`scope` is `SCHOOL` or `ACADEMY`).

1. **Yesterday's staff day** — how many arrived late, how many were absent, how many did not check out. Read from
   feature 16's own `staff_attendance_days` rows, so the brief agrees with the alerts that were sent and with the
   report she can open. "Yesterday" is the previous **teaching** day, never a Friday's empty board.
2. **The requests waiting for her** — `AttendanceRequestService.toDecide(caller, PENDING)`, already only the ones she
   decides.
3. **Overdue complaints** — feature 21's own number (`TodayService.overdueComplaints`), so the brief and the card
   below it cannot drift.
4. **Today's events** — the events live on her own Announcements feed (`BroadcastService.liveEventsFor`).
5. **Yesterday's children's attendance** — `(present + late) / marked` over the registers of the sections she
   reaches, as a whole percentage, and **absent from the brief when no register was taken** (never "0 %").
6. **Today's remote study day** — feature 23's `remote_study_days`, with the reason the school typed. A leader is
   staff, so the academy alone decides whether a day is hers (the grades on it narrow children and parents); a day
   declared for the whole school is everybody's. `RemoteStudyDayService.forLeader` owns that scope, as it owns
   `covers` for the children's screens.

A feature that is switched off contributes a zero rather than a guess: with `staffAttendance` off there is no staff
day and nothing waiting, with `chat` off no complaints, with `announcements` off no events, with `remoteStudyDays`
off no remote study day. Each switch is read by the service that owns the fact.

### The AI, which only phrases

- `AiService` with feature `morningBrief` (Prompt F), two attempts, `AiInputGuard.numbersOnly()`.
- **No child, parent or employee is named to the provider.** The facts are counts and labels, which is what makes
  that true by construction rather than by a filter.
- The answer is `{ "linesAr": [...], "linesEn": [...] }`: three to five lines in each list, the same things in the
  same order, at most 110 characters a line. With more than five facts the model is told to drop the ones carrying a
  zero; the template cuts at five in the order a leader wants them — the school being closed first, because it
  changes what every other line means.
- **The validator rejects a number that is not a fact** — Arabic-Indic digits folded to ASCII, because the Arabic
  lines are written in them — and a line too long for a glance. The allowed numbers are read off the prompt itself.
- On two rejected answers, or a provider that failed, the stored brief is the deterministic bilingual template and
  `source` says `template` instead of `ai`. **A brief never fails a request and never answers 502**: she did not ask
  for it, and the template says the same facts.

### Storage and timing

- `V47` adds `morning_briefs(id, school_id, user_id, brief_day, facts_json, text_ar, text_en, source, created_at)`
  with `UNIQUE (school_id, user_id, brief_day)`, and seeds the switch off.
- `MorningBriefJob` runs on the five-minute delay every other job runs on, from **07:15 in the school's own zone** on
  school days, for every active Admin and every active department manager of a school with the switch on.
- **The unique key is the claim.** The template row is inserted with ON CONFLICT DO NOTHING *before* the model is
  called, so the run that wrote the row is the only one that calls the model: two Cloud Run instances waking at the
  same minute do not both generate, a rerun writes nothing, and the bell rings once. The model's wording replaces the
  template in place when it is accepted — which is also why a leader who looks in between reads the template rather
  than an empty Home.
- `GET /me/morning-brief` (permission `brief.read`, ADMIN and MANAGERIAL, behind `morningBrief`) answers today's
  brief and builds it on the spot through the very same claim when the job has not reached her yet. On a day the
  school does not teach it answers **204 No Content**.
- The bell row is `NotificationKind.MORNING_BRIEF` (`morning.brief`) through `NotificationService.staffNotice`, which
  pushes to the staff app only when `staffMobileApp` is on. The row carries no number of its own: the lines are read
  from the route, so a notification written before the model answered cannot contradict the brief.

### The screens (other workers' packages)

- **Dashboard:** `<hq-morning-brief>` above `<hq-today>` on the Admin's and the manager's Home, collapsible.
- **App:** the same lines on the staff Home, from the same route.

## Out of scope

- A brief for a teacher, a coordinator or a floor supervisor; a brief by e-mail; a weekly or monthly version.
- Anything the leader can *do* from the brief — the actions are feature 21's cards underneath it.
- Changing what any of the five facts means. The brief reports them; it does not define them.

## Acceptance criteria

- [ ] An Admin's brief counts the whole school and a manager's counts only her academy, on every one of the six
      facts.
- [ ] Every number in the lines is a number the server counted; an invented one falls the brief back to the template.
- [ ] A provider that is down still produces a brief, and never an error.
- [ ] One brief per leader per school day, however many runs, instances and reloads.
- [ ] A day the school does not teach has no brief.
- [ ] Arabic and English lines, right to left and dark mode.
- [ ] No child, parent or employee name reaches the AI provider.

## Tests

- The facts are correct and scoped (the manager sees only her academy; the Admin the whole school).
- A remote study day declared for one academy is in that academy's manager's brief and in the Admin's, and not in the
  other academy's manager's; with `remoteStudyDays` off it is in nobody's.
- An answer with an invented number falls back to the template; an `AiFailure` does too.
- Running the job twice gives one brief per leader and day, and the endpoint then generates nothing.
- The job does nothing before 07:15 in the school's zone, and nothing on a non-teaching day (204).
- Arabic-Indic digits of a real fact are accepted.
- TEACHER, COORDINATOR, WORKER and a parent are refused (403); the switch off is 404.
- School isolation, with a case in `tenancy/IsolationTest` for `morning_briefs`.
- The model's input contains no child, parent or employee name (`LlmClient` captured).

## Leftovers

- **Wording quality needs the real provider and real data.** The local run and the tests use the sample client, which
  copies the server's fact lines back — enough to prove the validator accepts a true answer and rejects an invented
  number, not enough to judge whether the Arabic reads well. That is a QA judgement on the pilot school's own
  mornings.
- **An event has no date of its own.** `broadcasts` (V22) gives `week_start` to the weekly plan and nothing to the
  event, so "today's events" means the events live on her feed today. A real event date would narrow it and is its own
  migration.
- **07:15 is a constant, not a setting.** Feature 14's settings page has a digest time; the brief does not reuse it,
  because a brief is not a digest. A school that wants another hour needs a setting.
- **The brief is not re-written when the day changes under it.** The row is claimed once a morning; a request decided
  at 10:00 is not reflected until tomorrow. The cards under the brief are live, which is the division of labour.
- **The staff app shows no brief screen.** On the phone, the brief arrives as a push (when `staffMobileApp` is on)
  and the bell row opens the dashboard Home. The card itself is on the dashboard only.
- **The 204 answer is not in `openapi.json`.** The dashboard handles the empty body, but the generated client type
  says 200 only.
- **Deployed to QA** with the merge of #230. The switch is off until the school turns it on.
