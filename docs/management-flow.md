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
| **Classes** `/management/classes`                                                                  | `GET /management/classes`, `/calendar?from&to`                                         | Every section, under a heading per grade, and one section's month below it                                                                                                                                                                                                       |
| **All lessons** `/management/lessons`                                                              | `GET /management/lessons?classId&status&from&to`                                       | Every lesson of the department, narrowed by class, status and a date range                                                                                                                                                                                                       |
| **A lesson** `/management/lessons/{id}`                                                            | `GET /management/lessons/{id}` (+ `/status`)                                           | The teacher's lesson page, read-only, polling her own `/status` alias                                                                                                                                                                                                            |
| **Records** attendance · gradebook · exams, plus a lesson's results, an exam's results and a child | the six `/management/**` reads                                                         | The teacher's own screens, read-only, one class at a time                                                                                                                                                                                                                        |
| **People** `/management/people`                                                                    | `GET /management/people/children\|teachers\|coordinators?q&page&size`                  | Three tabs, searched and paged by the server, with a client-side CSV of the whole tab                                                                                                                                                                                            |
| **Staff attendance** `/management/staff-attendance`                                                | `GET\|PUT /management/staff-attendance?day=`, `…/summary?month=`, `…/{userId}?from&to` | The department's register for a day, each person's month, and one person's marked days in a drawer                                                                                                                                                                               |

## Broadcasts and Messages (RM3b, DR5, DR6)

| Screen                                  | Reads                                                                                             | What she sees                                                                                                                                                |
| --------------------------------------- | ------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **Broadcasts** `/management/broadcasts` | `GET /me/broadcasts`, `POST /me/broadcasts/{id}/read`, `GET\|POST /management/broadcasts`         | Two tabs. **For you** is the feed every staff role reads, with the week's plan pinned and opened; **You posted** is her own list, expired rows included       |
| **Messages** `/management/messages`     | `GET /management/chat/threads`, `…/{id}/messages`, `…/{id}/read`, `POST /management/chat/threads` | Her real inbox: the parents of her department, her coordinators and the admin, filtered by a chip strip, with **New message** to open a staff thread from her side |

Both rows carry the flag the server carries — `announcements` and `chat` — plus a key of her own
(`broadcast.read`, `management.chat`). A school without the flag meets `/not-found` in the router
and a "not enabled yet" state at the URL, which is the same answer the API gives.

**Her composer's three kinds.** `weekly_plan` is hers alone (DR6: the plan is the department's,
and `POST /coordinator/broadcasts` answers 400 for it); `announcement` and `event` she shares with
her coordinators. The week picker offers **Sundays only** — the server snaps `weekStart` back to
the Sunday of whatever day it is given, and a date input would have let her pick a Wednesday and
read a different week back — and says in words that posting replaces the current plan for that
week, because it does, read marks and all.

**"Choose a department" is "name that department's sections".** `CreateBroadcastRequest` has no
`curriculum` field: the server reads the track off the sections the row names, or off her one
department when it names none. A manager of two departments who names neither is `400 You manage
more than one department`, so the sheet shows her a Department select and sends every section of
the track she picks. `core/broadcasts/broadcast.rules.ts` is the one place that turns a draft into
a body, and the one place the Post button asks whether it may be enabled.

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

- **Complaints is still the stub.** RM3b took the broadcast and the message halves of row RM3;
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
Classes, the staff register and People. `dashboard/e2e/local/broadcasts.spec.ts` turns
`announcements` on, posts this week's plan as her, and reads it back as Sara Al Harbi — the
British Math teacher — pinned at the top of `/teacher/broadcasts` with the bell ringing. It is
re-runnable: a weekly plan replaces the one before it for the same week.
