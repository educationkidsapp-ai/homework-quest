# The department manager's dashboard

One person per curriculum (DR5): British or American, **every grade and every subject** of it —
`ManagerScope` is `CoordinatorScope` one axis over. She is read-only on all of it (RM1) with one
exception, the staff register, and what she does about what she finds is a message, which is
RM3b's Messages and Complaints.

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

## What RM3a could not do

- **Complaints and broadcasts are RM3b.** RM2 shipped the server half — `GET
  /management/chat/threads` and `/management/broadcasts` — while this package was being written,
  and nothing here reads either: the manager's Complaints row is still the stub, her Home's "What
  needs you" has no complaint line, and `/management/messages` remains R7's socket-only live
  view. `core/chat/*` and `features/chat/*` are untouched on purpose.
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
Classes, the staff register and People.
