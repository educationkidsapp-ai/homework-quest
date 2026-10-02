# T3 — parent flows end to end (notification, weekly plan, event, exam round trip, complaints)

**When** 2026-10-03, 01:04–01:45 (Asia/Riyadh), a Saturday.
**Against** a LOCAL server only — `server/target/server.jar` from this branch's base (`develop` `bc8998c`),
`SPRING_PROFILES_ACTIVE=h2 SEED_SCHOOL=true SEED_PROFILE=acceptance LLM_PROVIDER=fake PUBLIC_URL=http://10.0.2.2:8080`,
FAKE_AUTH active. QA was not touched (it holds the owner's school).
**App** `app.homeworkquest` 0.1.0 (debug, built from `bc8998c`) on emulator `Pixel_8a` (1080×2400, Android 17).
**Staff side** `e2e/local/parent-flows.sh` (source it; passwords only from `ADMIN_PASSWORD`, `SEED_STAFF_PASSWORD`,
`PARENT_PASSWORD`). Teacher Maya (1A British, math), coordinator Lina (math), manager Nour (British department).
**Child** Hala Ahmed, 1A British, Grade 1. The parent signed in through the app with a FakeAuth address; the server
created the parent row `fake-<uid>@fake.local` and the Admin admitted the child under it (`t3_parent_child`).
**Scope** verification only — no app or server code was changed. Screenshots are in
`docs/screenshots/app-parent-flows/` (540 px wide).

## Results

| # | Item | Result | One line |
|---|------|--------|----------|
| 1 | Notification arrives to the parent | **FAIL** | Messages are live (≤ 1 s) but the Notifications tab only lists announcements/events, is not live, and no tab carries a badge; nothing at all while the app is in the background or closed |
| 2 | Weekly plan (image + PDF, archive, this-week pin, Arabic) | PASS | Image inline, PDF opens in the system viewer, last week archived, home badge, RTL fine (D13 cosmetic) |
| 3 | Event broadcast | PASS | Listed under *Events* on the Notifications tab, counted in the home *Announcements · n* badge, gone after `expiresAt` |
| 4 | Exam round trip | **FAIL** | The child flow is right (identical ack, resume, single sitting, expiry, re-open), but the score leaks before release (D1), the score is client-asserted (D2), offline answers wait for a tap (D3), the child never sees the released result (D4) |
| 5 | Complaints to teacher / coordinator / manager | PASS | Chips in the app, `topic=complaint` in all three inboxes, resolve reaches the app live; wrong banner wording on the manager thread (D7) |
| 6 | Anything else | partial | No crash; dark mode and Arabic render; "Online" shown for offline staff (D6) and five low items |

## 1 — Notification arrives to the parent

| Step | Observed | Screenshot |
|------|----------|------------|
| Sign-in, child linked by the Admin, *Check again* | child appears at once | `00`–`03` |
| Parent area (PIN), bottom navigation Home / Notifications / Messages / Settings | — | `04`, `05` |
| Notifications tab before anything is sent | "Nothing from the school yet." (the tab is titled *Announcements*) | `10` |
| Manager posts an announcement while the tab is open | **not shown after 20 s** (`11`) or 30 s (`81`); shown after pull (`12`) or after Home → Notifications (`83`, ~2 s) | `11`, `12`, `81`–`83` |
| Home after the announcement | *Announcements · 1*; bottom-nav Notifications has **no badge** | `13` |
| Teacher messages the parent | Home shows **no** Messages count, bottom-nav Messages has no badge; the thread row in Messages shows `1` | `14`, `15` |
| Second teacher message while Messages is open | row updates live, **≤ 1 s** (`2`) | `16` |
| Third message with the app in the background | **no system notification** (shade shows only Android's own) | `17` |
| Back to the foreground | row shows the third message and `3` at once | `18` |
| Teacher publishes a lesson | parent home *Today's lessons* gains *Math · Not started*; nothing on the Notifications tab | `19` |
| Exam published / result released / complaint resolved | none of them appears on the Notifications tab | `80` |

**What the parent gets, and when.** There is no push. While the app is open: chat frames are live (≤ 1 s); every
other change (announcement, event, weekly plan, lesson, exam, result) appears on the next screen load — navigating to
the page, pulling, or a relaunch. In the background or with the app closed: nothing, until she opens the app; then the
home counts (*Weekly plan · n*, *Announcements · n*) and the Messages row counts are right on the first load.

## 2 — Weekly plan

Manager posts an image plan for this week and a PDF plan for last week, grade 1 (`t3_plan_image`, `t3_plan_pdf`).
Home shows *Weekly plan · 2* (`20`); *This week's plan* pins the week of 27 September with the image inline (`21`);
*Earlier weeks* holds week of 20 September with a PDF row and *Open* (`22`); *Open* hands the file to the system PDF
viewer (`23`); Arabic mirrors the page, headings and dates translated (`24`). Only defect: D13.

## 3 — Event

`t3_event "Parents evening" …` (no expiry) and `t3_event "Sports day" … <now + 2 min>`. Both appear under **Events** on
the Notifications tab with a *New* chip (`30`); tapping one clears its chip (`31`); after `expiresAt` *Sports day* is
gone and *Parents evening* stays (`32`). The event counts in the home *Announcements · n* badge (`62`, `82`). No push
(item 1). Calendar was not checked.

## 4 — Exam round trip

Exam *Autumn maths test* (5 single-choice questions, window 01:17–01:42) and *Short quiz* (3 questions, window
01:29:43–01:33:43), both on 1A British, created with `t3_exam_create` / `t3_exam_fill` / `t3_exam_publish`.

| Step | Expected | Observed | Result | Screenshot |
|------|----------|----------|--------|------------|
| Exam card on the child home | card with window | *Exam · Math · 17 minutes left · Start exam* | pass | `40` |
| Right answer (Q1, Q3) | neutral ack | "Answer saved", check icon | pass | `43`, `45` |
| Wrong answer (Q2, Q4) | identical ack | "Answer saved" — **pixel-identical** to the right one: frames captured 0.3 s after the tap on Q3 (right) and Q4 (wrong), the ack card region (912×325 px) differs in **0 pixels**; the whole frame differs only in the question text, progress bar and which tile is selected; the selected tile is the same colour (160,173,184) for both | pass | `45`, `46` |
| Hints, score | none | none on any screen | pass | `41`–`46` |
| Leave mid-way (after Q4) | resume at first unanswered | overview *4 of 5 answered*, Q5 *Next*, *Continue exam*; after a **force-stop and relaunch** the same, and *Continue* opens Q5 | pass | `47`, `49` |
| Card after leaving | "Continue" | still says **Start exam** | D9 | `48` |
| Q5 in airplane mode | ack, answers kept | same "Answer saved" (`50`), then *Sending your answers…* with *Try again* / *Back to home* (`51`); server: 4 of 5 answers | pass | `50`, `51` |
| Back online | delivered on reconnect | **nothing sent for 30 s** (no request in the server log; screen unchanged except the signal icon); delivered only on *Try again* | **D3** | `52` |
| Submitted only after delivery | yes | *Exam submitted*, no score, appears after the 200 | pass | `53` |
| Second attempt | refused, clear | app: card *Submitted. Your teacher will share the result after marking.*, tapping does nothing; API: `409 exam_already_taken` "\"Autumn maths test\" has already been handed in." | pass | `54` |
| Teacher results | sitting visible | `GET /teacher/exams/{id}/results` → `children[0]`: submitted, 5/5 answered, 9/15, 60 %, *secure*, 455 s | pass | — |
| Before release | no score anywhere | the app shows none, but **`GET /children/{id}/map` carries `starsEarned: 9, starsTotal: 15`** for the unreleased exam | **D1** | — |
| Expiry mid-sitting (*Short quiz*, on Q2 at closesAt) | Submitted screen | **question stays on screen 30 s after closesAt**; answering then → `409` → *Exam closed — the answers that reached your teacher were handed in; one answer … kept on this device* | pass, D8 | `56`, `57` |
| Teacher re-opens (`POST …/reopen/{child}`) | child can continue | card *Re-opened* (`58`); the kept answer is uploaded on the next home load (200), overview *2 of 3*, Q3 *Next* (`59`); Q3 → *Exam submitted* (`60`); results: 3/3, `reopened: true`, `secondsTaken` 23 (counts only the re-opened sitting) | pass | `58`–`60` |
| Release + comment | parent and child see score/band/comment | parent *Progress* → *Marked by the teacher*: **60**, *Secure*, the comment, missed Q2/Q4 (`63`); the **child home is unchanged** — still "Your teacher will share the result after marking." (`61`) | **D4** | `61`–`63` |

The comment needs the `openStopMarking` flag: `PUT /teacher/marks` answers `404 No such endpoint.` while it is off
(the script now switches it on in `t3_flags`).

## 5 — Complaints

Parent → Messages → *New message* → *Hala Ahmed* → *Complaint* → Maya / Lina / Nour (`70`, `71`). The thread opens
with *This is a complaint* on and a role-specific hint (`72`); after sending, the header carries *Complaint* and
*Open* (`73`–`75`). API right after each send:

- `GET /teacher/chat/threads` (Maya) → `topic: complaint, status: open, unread: 1`
- `GET /coordinator/complaints?status=open` (Lina) → the thread, `topic: complaint`
- `GET /management/complaints?status=open` (Nour) → the thread, `topic: complaint`

`PATCH /management/chat/threads/{id}/status resolved` while the parent is in Nour's thread: header turns *Resolved*
within 2 s, banner "**Resolved — the coordinator answered this.** You can still write here." (`76`, D7). Coordinator
resolve likewise; the list shows *Complaint · Resolved* for Lina and Nour, *Complaint · Open* for Maya (`77`).

## 6 — Dark mode, Arabic, server log

Dark mode (`adb shell cmd uimode night yes`): Messages and Progress render with correct contrast (`90`, `91`).
Arabic: Progress (dark), parent home and Messages mirror correctly (`92`–`94`, `24`). No crash, no ANR during the pass.
Server log 4xx/5xx caused by the app: two `POST /children/{id}/chat/threads/{staffId}/read → 404` (D11). Two
`GET /children/{id}/map → 400` at 01:06:47/51 could not be attributed or reproduced. Everything else 4xx in the log
came from the staff scripts.

## Defects

| # | Sev | Owner | Defect | Reproduction |
|---|-----|-------|--------|--------------|
| D1 | High | backend | Unreleased exam score reaches the parent: `/children/{id}/map` islands of an exam carry `starsEarned`, `starsTotal` and `completedLevels` before `release` | Sit an exam, do not release; `t3_parent_token_for_child; t3_parent_map` → `"Autumn maths test" … "starsEarned":9,"starsTotal":15` |
| D2 | High | backend | Exam score is whatever the client says: `AttemptUpload.correct` / `stars` are stored and scored without checking `answerJson` against the stop | `t3_exam_create "Integrity probe" 10; t3_exam_fill $EXAM 1; t3_exam_publish $EXAM`; `POST /children/$CHILD/attempts` with the stop's id, `answerJson:{"optionId":"c"}` (wrong), `correct:true, stars:3` → `t3_exam_children $EXAM` → 3/3, 100 % |
| D3 | Medium | mobile | Answers kept offline are not sent when the network returns; *Sending your answers…* waits for *Try again* | Exam open, airplane mode on, answer the last question, airplane mode off, wait 30 s → no `POST /attempts` in the log, screen unchanged |
| D4 | Medium | mobile | The child never sees a released result (score/band/comment); the card keeps "Your teacher will share the result after marking." | Release with `t3_exam_release`, add `t3_exam_comment`, go back to the child home |
| D5 | Medium | mobile | Notifications tab = broadcasts only, not live, no badge on Notifications or Messages tabs; teacher messages, new lessons/exams, results, plans, complaint resolutions never appear there | Item 1 table |
| D6 | Medium | mobile | Conversation header shows staff "Online" while `peerOnline` is `false` and no staff socket exists | Open any thread with no dashboard signed in; compare `GET /children/{id}/chat/threads` |
| D7 | Low | mobile | A complaint resolved by the **manager** shows "Resolved — the coordinator answered this." | `t3_manager_resolve <thread>` with the parent in that thread |
| D8 | Low | mobile | No client-side exam clock: the question stays answerable on screen past `closesAt`; the late answer is kept and counted if the teacher re-opens | Short window, stay on a question past `closesAt` |
| D9 | Low | mobile | Card says *Start exam* after a partial sitting; child home has no refresh — a newly published exam appears only after leaving and re-entering (pull issued no `/map` request) | Item 4 steps; publish an exam while the child home is open, pull down |
| D10 | Low | mobile | Parent Progress shows the date as "3/10" under the exam title beside the score 60 — reads as a mark of 3/10; parent home lists exams as bare "Math · Completed" | `63`, `62` |
| D11 | Low | mobile | Opening a not-yet-created coordinator/manager thread posts `…/read` → 404 | Open Lina's thread before writing to her |
| D12 | Low | mobile | Arabic: the English teacher comment's final full stop lands at the start of the last line (".4") | `92` |
| D13 | Low | mobile | PDF opened from the weekly plan is titled `attachment-<uuid>…` instead of `plan-last-week.pdf` | `23` |
