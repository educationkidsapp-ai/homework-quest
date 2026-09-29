# The department manager's dashboard

One person per curriculum (DR5): British or American, **every grade and every subject** of it —
`ManagerScope` is `CoordinatorScope` one axis over. She is read-only on all of it (RM1) with one
exception, the staff register, and what she does about what she finds is a message or a broadcast
— RM3b's two screens.

Signing in lands her on `/management`. `roleGuard` keeps everyone else out, and every row of hers
carries one of RM1/RM5's own keys — `management.read`, `management.lesson.read`,
`management.attendance.read`, `management.results.read`, `management.exams.read`,
`management.staff.attendance`, `management.people`. Not `teacher.read` or `section.read`, which
the stubs used to carry: those are the Admin's tenant-wide keys that a MANAGERIAL account happens
to hold, and gating her area on them would open her screens the day one of them stops being
department-scoped.

## Her screens (RM3a)

| Screen                                                                                             | Reads                                                                                  | What she sees                                                                                                                                                                                                                                                                    |
| -------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Home** `/management`                                                                             | `GET /management/me`, `/stats?from&to`, `/lessons?status=`                             | DR5's statistics: a row per grade and the department's total — children, classes, attendance, lessons published and played, exams with their average and pass rate — over a window she picks (a month ending today by default), then the quiet teachers, then **What needs you** |
| **Coordinators** `/management/coordinators`                                                        | `GET /management/coordinators`                                                         | Name, email, subjects, tracks, how many sections each supervises. Searchable, no row action                                                                                                                                                                                      |
| **Teachers** `/management/teachers`                                                                | `GET /management/teachers`, `/classes`                                                 | The coordinator's own screen: who teaches in the department and whether today has happened in their sections                                                                                                                                                                     |
| **Attendance** `/management/attendance`                                                            | `GET /management/classes/{id}/attendance?from&to`                                      | The teacher's register, read-only, one class at a time — the one record screen MG2a kept                                                                                                                                                                                         |
| **People** `/management/people`                                                                    | `GET /management/people/children\|teachers\|coordinators?q&page&size`                  | Three tabs, searched and paged by the server, with a client-side CSV of the whole tab                                                                                                                                                                                            |
| **Staff attendance** `/management/staff-attendance`                                                | `GET\|PUT /management/staff-attendance?day=`, `…/summary?month=`, `…/{userId}?from&to` | The department's register for a day, each person's month, and one person's marked days in a drawer                                                                                                                                                                               |

## What MG2a took off her rail (the owner's list, 2026-09-30)

Four rail items and the six routes behind them: **Classes** (and its calendar), **All lessons**
(and the read-only lesson page), **Gradebook** and **Exams** (and a lesson's results, an exam's
results and a child's report). Her question is not a coordinator's — she runs a track and asks
how it is *doing*, which is her Home — and a rail of ten items that mostly opened somebody
else's screen read-only was ten places to look for the two she uses.

**UI-only.** Every `/management/**` route on the server stays: the coordinator reads the same
shapes through her own namespace, `LessonApiService`/`ResultsApiService` still branch on the
role, and `ManagementApiTest` is untouched. The paths are **redirect rows** in
`core/nav/screens.ts` (`MANAGER_RETIRED`) rather than deletions, so a bookmark, the runbook's
own URL and a `link` an old notification carries all land on her Home instead of `/not-found`.

**The service branches stay.** `LessonApiService` and `ResultsApiService` still answer
`/management/**` for six reads no screen of hers makes any more (a lesson, a lesson's results, a
child, an exam's results, and the gradebook and exam lists). They are one `if` each in services
the coordinator shares, a department-scoped restore is on the server roadmap, and the redirect
rows above still need the *live* two — Home's attention list (`lesson-api.service.ts`) and
Attendance (`results-api.service.ts`). Kept deliberately, not overlooked.

**A child's name on Attendance is a name, not a link.** `children/:childId` is one of the
retired rows, so `childBase` is nullable and the register draws a `<span>` for her; the
coordinator's child report never left her area, so hers is still a link.

Her Home's **What needs you** followed: a failed or unreviewed lesson is now a line rather than
a link (there is no lesson page of hers to open, and what she does about it was always a message),
and "nothing on today" points at **Teachers**, the screen that says whether today has happened in
a teacher's sections.

**"Show me around" went with them.** `MANAGERIAL` has no row in `TOURS`, so there is no entry in
her account menu and nothing is offered on first sign-in; the other three roles are unchanged.
The other two "developer" surfaces were never hers: **Show the raw JSON** is `ViewModeService`,
which is `role === 'ADMIN'` and nothing else, and the **Design** styleguide (`/styleguide`) is
replaced with an empty route array in the `qa` *and* `production` build configurations, so it is
not in the bundle either environment serves.

## School usage (MG2a) — `/management/usage`

`GET /school/usage` over a window she picks (the month ending today, in the school's timezone, by
default — asked for once she has **stopped typing or left the box**, because a `type="date"`
input emits `0002-09-05` on the way to `2026-09-05` and each of those was a request the server
answered 400; a window longer than `Reports.MAX_DAYS` (400 days) and one that ends before it
starts are both said on screen instead of sent): four tiles — children, active families, lessons published and lessons played, both
summed across the window rather than read off its last week — and a row per teacher from
`teacherConsistency`: lessons published, **weeks with a lesson out of the window's weeks**, that
ratio as a percentage, and when she last published. A CSV of the rows on screen, built in the
browser by `core/download/csv.ts`, because the endpoint publishes none.

**Hers is the department's, since MG2b.** MG1 added `GET /management/usage`, the same `SchoolUsage`
shape scoped to the department(s) her `staff_scopes` name, and the screen reads that one for a
MANAGERIAL account and keeps `GET /school/usage` for every other role that holds `usage.school` (the
Admin's). The subtitle is the only thing that has to stay true to which — "Your department" or "The
whole school" — and the request waits for `/me` to answer, because asking before the role is known
would send her to the school-wide endpoint and correct itself a moment later. **No AI tokens or
cost**, either — those are on `PlatformUsage` behind `usage.platform`, which only an Admin holds.

## Where a notification goes (MG2a item 7)

Clicking a row — in the bell, or on `/notifications` — marks it read **and navigates**, which
until MG2a it only did when the server had written a `link`. `core/notifications/
notification-target.ts` is the single place that turns a row into a route for the *viewer's*
role, because E2 writes `link` from the recipient's role as it was at the moment the row was
created and so had three ways to be wrong: a kind with no link at all (`teacher.message` is
written with `null`), a link into an area the viewer does not have, and a link to a screen a role
has since lost.

| Kind                                             | Teacher (and Admin)                        | Coordinator                    | Manager                            |
| ------------------------------------------------ | ------------------------------------------ | ------------------------------ | ---------------------------------- |
| `lesson.needs_skills` · `lesson.ready` · `lesson.failed` | `/teacher/lessons/{id}` · `/admin/lessons/{id}` | `/coordinator/lessons/{id}`    | `/notifications` (no lesson page)  |
| `broadcast.posted`                               | `/teacher/broadcasts?open={id}` (Admin: `/notifications`) | `/coordinator/broadcasts?open={id}` | `/management/broadcasts?open={id}` |
| `teacher.message`                                | `/notifications` (she never gets one)      | `/coordinator/messages`        | `/management/messages`             |
| anything else                                    | `/notifications`                           | `/notifications`               | `/notifications`                   |

The **server's own `link` wins** when it is a path inside the viewer's area and is not one of the
manager's retired screens — so MG1's role-correct fan-out takes effect without a second dashboard
deploy. `?open=` and `?thread=` come from `lessonId`, which is E2's *entity* id rather than a
lesson's alone (`BroadcastService` puts the broadcast's id there, which is how a superseded weekly
plan's bell rows are found and forgotten). Broadcasts honours `?open=` by drawing that row open,
marking it read and scrolling to it — off `toSignal(route.queryParamMap)` rather than the
snapshot, because the bell is on every screen and clicking a row *while already on Broadcasts*
is a query-param-only navigation that reuses the component. `features/chat/chat.page.ts` still
reads `?thread=` off the snapshot and has the same gap; it is a follow-up.

## Broadcasts and Messages (RM3b, DR5, DR6)

| Screen                                  | Reads                                                                                             | What she sees                                                                                                                                                |
| --------------------------------------- | ------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **Broadcasts** `/management/broadcasts` | `GET /me/broadcasts`, `POST /me/broadcasts/{id}/read`, `GET\|POST /management/broadcasts`         | Two tabs. **For you** is the feed every staff role reads, with the week's plan pinned, drawn open and marked read on arrival (opening the screen *is* opening it) and still collapsible; every other row is read when it is opened and not before. **You posted** is her own list, expired rows included |
| **Messages** `/management/messages`     | `GET /management/chat/threads`, `…/{id}/messages`, `…/{id}/read`, `POST /management/chat/threads`, `GET /management/teachers` | Her real inbox: the parents of her department, her **teachers** (MG2b), her coordinators and the admin, filtered by a chip strip, with **New message** to open a staff thread from her side |

**Teachers, since MG2b** (owner's item 6). `POST /management/chat/threads {teacherUserId}` opens a
thread with a teacher of her department — the chooser is `GET /management/teachers`, and a Teachers
chip counts them in the strip. The teacher's end of it is her own `/teacher/chat/staff-threads`
(`docs/teacher-flow.md`), and a "Message to coordinator" she sends from her profile lands in that
same thread, so an answer has somewhere to go.

Both rows carry the flag the server carries — `announcements` and `chat` — plus a key of her own
(`broadcast.read`, `management.chat`). A school without the flag meets `/not-found` in the router
and a "not enabled yet" state at the URL, which is the same answer the API gives.

**Her composer's three kinds.** `weekly_plan` is hers alone (DR6: the plan is the department's,
and `POST /coordinator/broadcasts` answers 400 for it); `announcement` and `event` she shares with
her coordinators. The week picker offers **Sundays only** — the server snaps `weekStart` back to
the Sunday of whatever day it is given, and a date input would have let her pick a Wednesday and
read a different week back — and says in words that posting replaces the current plan for that
week, because it does, read marks and all.

## Weekly plans (MG2b) — `/management/weekly-plans`

Owner's items 3 and 4: "the manager is who adds the weekly plan for all grades", and "a feature to
see all weekly plans". Her own rail row, behind `announcements` and `management.broadcast`, with
three things on it:

1. **This week at a glance** — one card per grade of her department plus the department's own
   all-grades plan, each either the plan that is posted (with **Replace plan**) or **Add plan**.
   This is the Sunday-morning question, and a list of twelve weeks does not answer it.
2. **The composer** — the Broadcasts screen's own sheet
   (`dashboard/src/app/features/broadcasts/compose-sheet.component.ts`, shared by both screens) with
   the kind fixed to `weekly_plan` and the week and grade prefilled from the card she pressed.
3. **The archive** — `GET /management/weekly-plans?from=&to=&grade=`, weeks newest first, **past
   weeks and expired plans included** (the feeds drop an expired row; the archive is the screen that
   must not), filtered by grade and by date range, each plan expandable, `readBy` on every row, and
   a CSV of the list (title, week, grade, readBy) built in the browser.

**A grade plan needs a single-department account.** `POST /management/broadcasts` takes `grade`
only on a row that names **no** `sectionIds` ("name the sections or the grade, not both"), and a row
with no `sectionIds` is refused for a manager who holds two departments, because nothing else on the
request says which department it is for (`BroadcastService.one`). Together those two rules mean a
**two-department manager cannot send `grade` at all**: she is offered one all-grades card per
department, the sheet says why in words, and her grade plans wait for the server to accept a
`grade` + department pair. A manager of one department gets All grades plus a card per grade she
actually manages — a grade she manages no class in is a 400, so the list is built from her own
classes and never from 1…12.

A grade's plan and the department's all-grades plan for the same week are **two rows that coexist**:
replacement is keyed on `(weekStart, department, grade)`, so posting grade 3's week again replaces
grade 3's plan and leaves the department's alone.

**Teachers and coordinators read the same archive** on a **Weekly plans tab** on their own
Broadcasts screen (`GET /me/weekly-plans`) — a tab rather than a rail row, because they already come
to Broadcasts for this week's plan and "the weeks before it" is the same screen one tab over. It is
read when the tab is opened, not on arrival, and `readBy` is absent from it: an audience is resolved
per reader, so only the author's archive counts openings.

**"Choose a department" is "name that department's sections".** `CreateBroadcastRequest` has no
`curriculum` field: the server reads the track off the sections the row names, or off her one
department when it names none. A manager of two departments who names neither is `400 You manage
more than one department`, so the sheet shows her a Department select and sends every section of
the track she picks. `core/broadcasts/broadcast.rules.ts` is the one place that turns a draft into
a body, and the one place the Post button asks whether it may be enabled.

One consequence worth knowing: a row that names sections reaches *those* sections, so a section
created after the post does not get it, where a curriculum-only row would (`BroadcastService`
resolves the audience at read time from the track). It still replaces the right plan — the server
reads the track back off the sections (`oneTrack`) and `replacePlan` keys on (school, week, track) —
so the only cost is a class opened mid-week. A manager of one department is unaffected: her rows
name no section at all.

An expiry is stored as the last second of that day **in the school's timezone**, not in Greenwich:
`today` on the sheet is read in that zone too, and one date that meant somewhere else's day would be
the sheet's only lie.

**Her inbox has three kinds of correspondent and the row cannot say which.** `staff_role` is the
*staff side's* role, so a parent writing to her about a child, a coordinator's staff thread and the
admin's all carry `MANAGERIAL`. An empty `childId` separates the parent threads from the staff
ones, and the peer's id is matched against `GET /management/coordinators` and `GET
/management/admins` — the two lists **New message** already needs — to tell the other two apart.

**A complaint from a parent is labelled and not resolvable.** `PATCH …/status` exists only on
`/coordinator/chat/threads/{id}/status`, so the badge is read-only for her: the coordinator closes
her own complaints, and a manager who could close one would be closing it over her head.

## Why almost none of this is new code

Her screens **are** the coordinator's. Two seams do it, and both already existed:

- **Which namespace is read.** `core/auth/staff-area.ts` turns the role into one of `teacher`,
  `coordinator` or `management`, and `StaffScopeService` (`features/coordinator/`),
  `ResultsApiService` and `LessonApiService` each branch there once. `ManagementApiTest` asserts
  the bodies are the same JSON as the teacher's, so there was nothing else to change: a screen
  asks for `classes()` and gets the ones it may see.
- **Which controls are drawn.** The permissions she does not hold. She has no `results.write`,
  `lesson.write` or `attendance` write of her own, so the marking, the release toggle, New exam
  and every lesson control are simply not rendered — hidden, not disabled — and `readOnly` on her
  rows in `core/nav/screens.ts` says the same thing from the caller's side.

`GET /management/classes` answers a `GradeGroup` per grade; the service flattens it and groups it
again for both areas, which is why a coordinator's Classes screen now has grade headings too.

## The one thing she writes

`PUT /management/staff-attendance?day=` — the only write in the whole namespace, and named in
`ManagerScopeArchitectureTest.ALLOWED_WRITES`. The screen sends **only the people she changed**
and holds whatever the server answers back, so a refusal (a person of the other department, a day
that closed while the screen was open) leaves the register exactly as it was under the red band
rather than a row that quietly disagrees with the database. **Nobody is present by default**: a
person nobody has marked has no status at all. And the day rule is the server's — a teaching day
of this school, not after today in the school's zone — read off `editable` and said in words, not
re-derived: "that day has not happened yet" and "the school does not teach on that day" are
different facts, and a Save that is merely grey is a control nobody can act on.

## What RM3a and RM3b could not do

- **Complaints is still the stub**, and since MG2a it is not in her rail either — the route
  resolves, the label comes back with phase 5. RM3b took the broadcast and the message halves of row RM3;
  `/management/complaints` and her Home's "What needs you" complaint line are the rest of it. The
  Complaint badge is already on the row in Messages, which is where a complaint of hers lives.
- **No attachment on a broadcast.** `{"attachment":{"url","name"}}` is a *reference* to bytes that
  already exist and RM2 added no upload route; R7's chat composer holds its attachment in the
  message body as a `[attachment:…]` tag rather than uploading anything, so there is nothing to
  reuse. The feed renders an attachment a server-side author put there, and the sheet does not
  offer one.
- **No `/management/**` export exists**, so the People CSV is built in the browser from the rows,
  through `core/download/csv.ts` (byte-order mark, `\r\n`, a tab before a leading `=`, `+`, `-`
  or `@`) — the same writer the register's CSV uses.
- **Her middle breadcrumb does not link**, for the coordinator's reason: her Classes screen lists
  sections and opens none of them.
- **`GET /me` carries `departments`** for her, but nothing reads it yet: her scope and her five
  counts arrive together on `/management/me`, and a header that labelled itself from one and
  counted from the other would have two sources for one sentence.

## What was taken out of the shared Home

The comp's sample data, for every role: a weekly attendance chart, a staff directory with
telephone numbers no table in this system holds, three named "schedule gaps", two exam counters
and a "+12.5 % vs last month" on every stat card. None of it was read from anywhere. What is left
on `features/home/home.page.ts` is `GET /me/home` — the counts, the teacher's classes, her
weakest skills and what needs somebody.

## Trying it

Full local seed (`SEED_SCHOOL=true`): sign in as `manager.a@school.test` (Huda Salem, the British
department; `seed/managers.csv`) with `E2E_STAFF_PASSWORD`.
`dashboard/e2e/local/management-area.spec.ts` walks the Home's statistics, the Coordinators list,
the staff register, People and School usage, and checks that a bookmark of a removed screen lands
on her Home. `dashboard/e2e/local/broadcasts.spec.ts` turns
`announcements` on, posts this week's plan as her, and reads it back as Sara Al Harbi — the
British Math teacher — pinned at the top of `/teacher/broadcasts` with the bell ringing. It is
re-runnable: a weekly plan replaces the one before it for the same week.
