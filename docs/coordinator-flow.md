# The coordinator's dashboard

One person per subject per track (DR1): Math/British, English/American, and so on. A scope row
with no curriculum means **both** tracks. She supervises every grade and every class that carries
her subject, and she is **read-only** on all of it (DR2) — what she does about what she finds is a
message, which is R7's Messages and Complaints screens.

Signing in lands her on `/coordinator`. `roleGuard` keeps everyone else out of the area, and every
screen of hers carries one of the two keys R2 gates the server routes with — `coordinator.read` or
`coordinator.lesson.read`. She holds no `lesson.write`, `lesson.publish`, `play.write`,
`stop.write`, `calendar.read` or `results.read` at all, which is what makes the shared screens draw
themselves without write controls rather than with disabled ones.

## Her screens (R5)

| Screen | Reads | What she sees |
| --- | --- | --- |
| **Home** `/coordinator` | `GET /coordinator/me`, `/classes`, `/lessons?status=` | Her scope ("Math · both tracks"), the three counts (classes, teachers, children), **What needs you**, and a preview of her teachers and her classes |
| **Teachers** `/coordinator/teachers` | `GET /coordinator/teachers` | Name, email, subjects, the classes of hers each teacher takes, and how many of them have today's lesson. Search by name, email or class |
| **Classes** `/coordinator/classes` | `GET /coordinator/classes`, `/coordinator/calendar?from&to` | Every section in scope with grade, track, teacher, roster size and today's status; below it one section's **month**, drawn by the same calendar the teacher's class page uses |
| **All lessons** `/coordinator/lessons` | `GET /coordinator/lessons?classId&status&from&to` | Every lesson of every class in scope, narrowed by class, status and a date range |
| **A lesson** `/coordinator/lessons/{id}` | `GET /coordinator/lessons/{id}` | The teacher's own lesson page in **read-only mode**: the steps, the files, the questions and the phone preview, with nothing that writes |

**What needs you** is the point of the Home: the lessons in her scope that failed, then the ones
waiting for a review, then the classes with nothing on today. Each line is a way *in* to the lesson
or the class, never an action of her own.

## What she cannot do

- Create, edit, publish, unpublish, move or delete a lesson; add, reorder or remove a question;
  confirm skills; upload or re-convert a file; edit the parent panel or an exam's settings. None of
  those controls is rendered on her copy of the lesson page — hidden rather than disabled.
- Plan anything on the calendar: her month has no `+` and no past-day marker, only the gaps.
- Change a teacher, a class or a roster. Her tables have no row menus.
- "Message to coordinator" is a teacher's action on the Profile screen, so it is not shown to her.

## Deliberate limits

- **One class at a time on the calendar.** A calendar cell holds at most one lesson per day, which
  is exactly right for a section and wrong for six grades at once; the class filter is how she
  reaches the rest, rather than a square that silently shows one of four lessons.
- **Attendance, gradebook, exam results and the child page are not here yet** — they are R6, and
  the `/coordinator/**` routes behind them land with R3. Rows for them are deliberately absent from
  the rail rather than stubbed.
- **Messages, the complaints inbox and announcements are R7.** The notification bell and the chat
  socket already work for her (she holds `notifications.read` and `chat.socket`).

## Trying it

Full local seed (`SEED_SCHOOL=true`): sign in as `coordinator.math@school.test` with the seeded
staff password. Rasha Kamal supervises Math on both tracks; Sara Al Harbi's 1A/1B British carry the
seeded published lesson "Counting to ten". `dashboard/e2e/local/coordinator-area.spec.ts` walks the
four screens; `E2E_STAFF_PASSWORD` must be the seeded staff password.
