# The coordinator's dashboard

One person per subject per track (DR1): Math/British, English/American, and so on. A scope row
with no curriculum means **both** tracks. She supervises every grade and every class that carries
her subject, and she is **read-only** on all of it (DR2) — what she does about what she finds is a
message, which is R7's Messages, Complaints and Announcements screens below.

Signing in lands her on `/coordinator`. `roleGuard` keeps everyone else out of the area, and every
screen of hers carries one of the two keys R2 gates the server routes with — `coordinator.read` or
`coordinator.lesson.read`. She holds no `lesson.write`, `lesson.publish`, `play.write`,
`stop.write`, `calendar.read` or `results.read` at all, which is what makes the shared screens draw
themselves without write controls rather than with disabled ones.

## Her screens (R5)

| Screen                                   | Reads                                                       | What she sees                                                                                                                                                                                                                                                                                                                                |
| ---------------------------------------- | ----------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Home** `/coordinator`                  | `GET /coordinator/me`, `/classes`, `/lessons?status=`       | Her scope ("Math · both tracks"), the three counts (classes, teachers, children), **What needs you**, and a preview of her teachers and her classes                                                                                                                                                                                          |
| **Teachers** `/coordinator/teachers`     | `GET /coordinator/teachers`                                 | Name, email, subjects, the classes of hers each teacher takes, and how many of them have today's lesson. Search by name, email or class                                                                                                                                                                                                      |
| **Classes** `/coordinator/classes`       | `GET /coordinator/classes`, `/coordinator/calendar?from&to` | Every section in scope with grade, track, teacher, roster size and today's status; below it one section's **month**, drawn by the same calendar the teacher's class page uses                                                                                                                                                                |
| **All lessons** `/coordinator/lessons`   | `GET /coordinator/lessons?classId&status&from&to`           | Every lesson of every class in scope, narrowed by class, status and a date range                                                                                                                                                                                                                                                             |
| **A lesson** `/coordinator/lessons/{id}` | `GET /coordinator/lessons/{id}`                             | The teacher's own lesson page in **read-only mode**: the steps, the files, the questions and the phone preview. No control that writes — including the "Lesson day" date input, which is a teacher's. R6 turned the 2.5 s `/status` poll on for her (see below); **Refresh** stays in the header, and a lesson still being generated says so |

A failed read is the house error band with Try again on every one of her screens, never an
empty state: "no classes carry your subject yet" is a statement about her school, and a request
that did not happen has made no such statement.

**What needs you** is the point of the Home: the lessons in her scope that failed, then the ones
waiting for a review, then the classes with nothing on today. Each line is a way _in_ to the lesson
or the class, never an action of her own.

## Her manager (T2)

`/coordinator/manager`, behind the `chat` flag and `coordinator.chat` — the keys
`GET /coordinator/managers` and `POST /coordinator/chat/threads` carry.

One card per department manager whose department meets her scope: name, job ("American department
manager", built in the browser from the contact's `jobParts` so it reads as a sentence in Arabic
too), the phone as a `tel:` and the address as a `mailto:`, and **Message**. Message is a
navigation, not a composer: `POST /coordinator/chat/threads {managerUserId}` answers the thread that
exists or opens one — one row per pair however many times either side asks — so she lands on
`/coordinator/messages?thread=<id>` with the conversation already on screen.

It is **the teacher's screen**, one namespace over (`features/staff/staff-manager.page.ts`,
`docs/teacher-flow.md` step 13): `StaffAreaService` decides which of `GET /teacher/managers` and
`GET /coordinator/managers` to read and which thread route to open, the way Announcements and
Messages are one component for three roles.

## Her records (R6)

| Screen                                                     | Reads                                              | What she sees                                                                                                                                                                                                                                                                       |
| ---------------------------------------------------------- | -------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Attendance** `/coordinator/attendance`                   | `GET /coordinator/classes/{id}/attendance?from&to` | One of her sections over a range of days, defaulting to **this week** (Monday to today): children down the side, days across the top, a letter and a colour per mark, and present/late/absent/excused plus a rate per child. **Export CSV** is built in the browser from those rows |
| **Gradebook** `/coordinator/gradebook`                     | `GET /coordinator/classes/{id}/results?from&to`    | The teacher's grid — children × lessons, one coloured square each, the class average line, each child's average, band and trend. The range is the grid's own eight weeks                                                                                                            |
| **A lesson's results** `/coordinator/lessons/{id}/results` | `GET /coordinator/lessons/{id}/results`            | The teacher's results page: the five numbers, the per-child table, the stops each child got wrong                                                                                                                                                                                   |
| **Exams** `/coordinator/exams`                             | `GET /coordinator/classes/{id}/exams`              | The teacher's exams table: title, window, state, how many sat, how much marking is left                                                                                                                                                                                             |
| **An exam's results** `/coordinator/exams/{id}/results`    | `GET /coordinator/exams/{id}/results`              | The distribution, the per-child rows and the question breakdown                                                                                                                                                                                                                     |
| **A child** `/coordinator/children/{id}`                   | `GET /coordinator/children/{id}`                   | Her report: level per subject, the trend chart, released scores, exam results and the comments a parent has read                                                                                                                                                                    |

Every one of the six is the **teacher's own component**, in read-only mode. Two mechanisms do it,
and they are different on purpose:

- **Which namespace is read** — `ResultsApiService` (`features/results/results-api.service.ts`)
  picks `/coordinator/**` or `/teacher/**` off the role, the way `LessonApiService` already did
  for lessons. It also owns `base()`, so a link out of a shared screen lands in the reader's own
  area — including the mark panel's "Open her page", which the review found was the one link still
  hard-coded to `/teacher/**` — and `supportsExport()`, because every `.csv`, `.xlsx` and
  per-child `.pdf` is a
  teacher-namespace route and a button that answers 403 is worse than no button. Its `ready()`
  makes each read wait for `/me`: fired a tick early, a coordinator's request goes to `/teacher/**`
  and comes back 403.
- **Which controls are drawn** — the permissions she does not hold. `*hqCan="'results.write'"`
  and `*hqCan="'lesson.write'"` already wrapped the marking, the release toggle, the reopen, New
  exam and the settings card, so her copies lose them without an `if`. The `readOnly` input on
  `hq-gradebook`, `hq-exams-tab` and `hq-class-attendance` states the same thing from the caller's
  side — it is what the tests assert against, rather than the absence of a permission they had to
  stub away — and on `hq-class-attendance` it is what replaces the four status pills with the
  mark as a word. `data.readOnly` reaches the shared **pages** too, and today only `lesson.page.ts`
  reads it: on the results, exam-results and child pages the read-only state rests entirely on the
  permissions she does not hold, which is the same thing by a different route.

`Screen.readOnly` on every R6 row (`core/nav/screens.ts`) becomes `data.readOnly` on the route,
which is how the shared **pages** know. Gradebook, the lesson results and the child page carry the
`gradebook` flag; Exams and the exam results carry `exams` — the same flags their controllers
carry, so a school without one meets `/not-found` from `featureGuard` rather than a screen of
404s. Attendance carries none: a school with classes has registers, and the teacher's own marking
tab has no flag either.

**The read-only lesson now polls.** R3 shipped `GET /coordinator/lessons/{id}/status`, so
`LessonApiService.status` has a coordinator branch and `lesson.page.ts` runs its 2.5 s poll under
`readOnly` **for a coordinator only** — a lesson still being written moves under her without a
click. **Refresh** stays in the header: the poll runs only while something is active. Any other
read-only reader a later phase adds keeps the old behaviour, because a role with no `/status`
alias would poll the teacher's and be answered 404 every 2.5 s, silently.

### Deliberate limits in R6

- **One class at a time**, on all three screens: every one of R3's routes is keyed by a class id,
  and a table of six grades at once would be six requests saying less than any of them. The picker
  (`coordinator-class-picker.ts`) defaults to her first section, so none of the three ever draws an
  empty state that only means "choose something".
- **Attendance defaults to this week, not a term** — Monday to today, with "today" read in the
  school's timezone (`PlatformService`), not the reader's. Sixty columns is a sideways scroll
  nobody reads, and the question she opens the screen with is whether her sections have been marked
  at all this week. The server refuses more than 62 days; the screen names that number and says so
  rather than sending the request. A child with **nothing** marked has no rate — a dash, not a
  flattering 100 %.
- **No exports but the attendance CSV.** The gradebook's two, the lesson results' CSV and the
  per-child exam PDF are all `/teacher/**`. Her register is the one document she has a reason to
  carry to a teacher, so that one is built client-side from the rows already on screen — with a
  byte-order mark, so Excel reads an Arabic roster, and a tab before a leading `-` so a name is
  not run as a formula.
- **Her breadcrumb's middle crumb does not link.** Her area lists sections and opens none of
  them (R5's limit), so the class's name is text; a crumb that navigated to `/not-found` would be
  worse.
- **A coordinator's exam list costs one request.** R3's `ExamRow` carries `sat`, `roster` and
  `needsMarking`; the teacher's `ExamSettings` does not, so her tab still fans out one results
  request per open exam. A `sat` column on `GET /teacher/classes/{classId}/exams` would remove
  that — reported, not hidden.

## What she cannot do

- Mark a register, write a note on one, or press Save on the attendance screen; override a score,
  release results to parents, edit a parent comment, reopen an exam for a child, or create or
  change an exam. Every one of those _actions_ is absent from her copies — hidden, not disabled.
  The **one exception is the mark panel** a child's name opens on the results and exam-results
  tables: its stars, score and parent-comment fields are drawn _disabled_ to any reader without
  `results.write`, which is how a `MANAGERIAL` account has always seen them, so she sees the marks
  as fields she cannot type in rather than as text. Apply is still absent.
- Create, edit, publish, unpublish, **move** or delete a lesson; add, reorder or remove a question;
  confirm skills; upload or re-convert a file; edit the parent panel or an exam's settings. None of
  those controls is rendered on her copy of the lesson page — hidden rather than disabled.
- Plan anything on the calendar: her month has no `+` and no past-day marker, only the gaps.
- Change a teacher, a class or a roster. Her tables have no row menus.
- "Message to coordinator" is a teacher's action on the Profile screen, so it is not shown to her.

## Deliberate limits

- **One class at a time on the calendar.** A calendar cell holds at most one lesson per day, which
  is exactly right for a section and wrong for six grades at once; the class filter is how she
  reaches the rest, rather than a square that silently shows one of four lessons.

## Trying it

Full local seed (`SEED_SCHOOL=true`): sign in as `coordinator.math@school.test` with the seeded
staff password. Rasha Kamal supervises Math on both tracks; Sara Al Harbi's 1A/1B British carry the
seeded published lesson "Counting to ten". `dashboard/e2e/local/coordinator-area.spec.ts` walks the
four R5 screens and `coordinator-area-2.spec.ts` the three R6 ones plus the child page;
`E2E_STAFF_PASSWORD` must be the seeded staff password.

## Her messages (R7, DR3)

| Screen                                         | Reads / writes                                                                                                        | What she sees                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| ---------------------------------------------- | --------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Messages** `/coordinator/messages`           | `GET` + `POST /coordinator/chat/threads/{id}/messages`, `POST …/read`                                                 | The teacher's chat screen — the same threads list, conversation, composer, attachments and emoji — over her own routes. A parent thread carries the child and the class; a **manager** thread carries a "Management" badge, the manager's name and no child at all                                                                                                                                                                                                                                                   |
| **Complaints** `/coordinator/complaints`       | `GET /coordinator/complaints?status=`, `PATCH …/threads/{id}/status`                                                  | The `complaint` threads she is the staff peer of, filtered open/resolved: child, whose parent, class, last message. A row opens the conversation; **Mark resolved** / **Reopen** ask first, in a red band                                                                                                                                                                                                                                                                                                            |
| **Announcements** `/coordinator/announcements` | `GET /me/broadcasts`, `POST /me/broadcasts/{id}/read`, `GET` + `POST /coordinator/broadcasts`, `GET /me/weekly-plans` | Three tabs: **For you** is the feed — announcements and events only since MH2 item 5, read when a row is opened and not before; **Weekly plans** is the manager's plans as **pictures** (`GET /me/weekly-plans`, read when the tab is opened), each a thumbnail that opens full size; **You posted** is her own list. One sheet writes another: kind (announcement or event), title, body EN (required), AR (optional), the classes of hers it goes to, an optional expiry. `/coordinator/broadcasts` redirects here |

**One screen, two sets of routes.** A teacher's chat is keyed by **child**
(`/teacher/chat/threads/{childId}/…`); a coordinator's by **thread**, because
`POST /coordinator/chat/threads {managerUserId}` opens a conversation with no child on it at all.
`core/chat/chat-routes.ts` is that seam and the only place that knows it: it answers _which_ four
routes this role's chat runs on and _what a key is_ (`childId` for a teacher, `threadId` for her,
also as the socket command's field), so `ChatService` and the screen speak in keys and one chat
screen serves both roles rather than two that drift. RM3b filled in the last two roles — a manager
and an Admin, both keyed by thread as well — so all four now have one, and `null` means only that
nobody is signed in.

Her two chat rows carry the **`chat`** flag and `coordinator.chat` / `coordinator.complaints`;
Announcements carries `announcements` and `broadcast.read`, the key every dashboard role reads
`GET /me/broadcasts` with, while her composer inside it is gated on `coordinator.broadcast`.

Complaints is `chat` rather than N5.2's `complaints`: DR3 keeps a complaint **in the conversation it arrived in**, so
there is no complaint record here to gate. A complaint exists only because a parent named it
(`topic: "complaint"` on the message that opens the thread); nothing on this side can label one.

The **`status` frame** (R4) reaches both parties and lands in `ChatService.threads`. Both screens
that care read that signal — the thread header, and the Complaints rows, which take a thread's
status from there over their own `GET /coordinator/complaints` — so a complaint resolved on one
tab, or in the conversation itself, leaves her open list on the other with no refetch. Her header
badge is the teacher's, pointed at `/coordinator/messages`.

**Her announcements became broadcasts (RM3b), and the screen is called Announcements again (MH2
item 5).** `POST /coordinator/broadcasts` is the route, and it writes the `announcements` rows the
app's shipped screen reads as a side effect — so nothing changed for a parent, and what she gains is
a **kind**, a **title** and the department's weekly plans one tab over. `weekly_plan` is 400 on her
route (DR6: the plan is the department's) and is no longer a kind either sheet offers, since a plan is
a grade, a week and an image now; her audience is always the parents of her classes and the server
ignores the field, so the sheet says that in a line instead of offering boxes she cannot change.
The rail row is `announcements` and `/coordinator/broadcasts` is the redirect — the arrow RM3b drew
now points the other way, and both URLs still resolve.

**What a manager does here now.** `/management/messages` is a real inbox on RM2's
`GET /management/chat/threads`, and `Peer.chat` reaches MANAGERIAL and ADMIN, so she replies on the
same composer everyone else uses. See `docs/management-flow.md`.

## What R7 and RM3b could not do

- ~~**"New message to a manager" is still not on her screen.**~~ **Fixed in T2 (item b).** The
  route RM3b was waiting for — `GET /coordinator/managers` — exists, and her **Manager** screen is
  it: see below.
- **No parent's name anywhere.** `ChatThread` carries the child, the class and the _staff_ peer;
  the parent has no name on the contract, so the Complaints table says "Parent of <child>".
- **`GET /coordinator/announcements` is no longer read by the dashboard.** It still exists, and
  RM2 made it a second door onto the same service, so the mis-exported
  `CreateAnnouncementRequest` (`classIds` plural, sharing a schema _name_ with the teacher's
  `classId`) no longer needs a hand-asserted body anywhere in `dashboard/`: `CreateBroadcastRequest`
  is exported correctly and the screen posts that.
- **No attachment on a broadcast.** RM2's `attachment` is a reference to bytes that already exist
  and added no upload route; R7's chat composer never uploaded anything either (it carries the file
  inside the message body as an `[attachment:…]` tag), so there is nothing to reuse. The feed shows
  an attachment when a row has one; the sheet does not offer one.

## Trying R7 and RM3b

Turn `chat` (and `announcements`) on for the school first — both are off in the seed:
`PUT /admin/schools/{id}/flags/chat {"enabled":true}`. A complaint needs a parent, so
`dashboard/e2e/local/coordinator-comms.spec.ts` makes one: a fake-auth parent creates a child with
1A British's join code, asks `GET /children/{id}/coordinators`, and posts her first message with
`{"topic":"complaint"}`. That spec walks Messages and Complaints;
`dashboard/e2e/local/announcements.spec.ts` walks Announcements and the weekly-plan upload for all
three staff roles.
