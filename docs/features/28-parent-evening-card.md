# Feature 28 — Parent evening card

*Spec drafted by the planner from the owner's brief (the owner gave no spec file); review it in the PR.*

**Status:** Done · **Area:** mobile app (parent), server · **Depends on:** 24 ·
**Feature switch:** `parentEveningCard`

**Leftovers:**
- **The wording quality of the Arabic and English needs the real model on QA.** Tests use the fake model.
- **The card is written on first read after 16:00.** The first parent to open the app pays one model wait per child
  per day.
- **No band without a confirmed skill.** A lesson with no confirmed skill gives no band, and the card then says only
  whether the child finished.
- **One push per child.** A parent of two children gets two evening pushes.
- **The card is kept with its facts for audit.** The facts are the teacher's lesson text and the result words,
  never scores.
- **Numbers in a card** may only be quoted from the teacher's own lesson text (a title like "Counting by 2s"). Any
  other number, `%` or score word sends the card to the template.

## Goal

From 16:00 school time, the parent app shows **one card per child about today**: what the child learned, and how it
went — two or three short sentences in Arabic and in English, written so a parent can read them in the school pick-up
queue.

The owner's rules are the whole design:

- The card uses **only the teacher's own lesson text and the child's own results**. No new fact about the child is
  invented, and nothing about another child is ever in it.
- **No scores and no percentages.** How it went is said in words.

## Where to look first

- The child's results: `children/ProgressService` (bands from first tries), `quest.api.progress.ProgressBands`
  (`GOING_WELL`, `GETTING_THERE`, `NEEDS_ANOTHER_LOOK`, and `accuracyWords`, which is already "words, never a
  percentage").
- The teacher's lesson text: `ParentPanel.objectives`, `ParentPanel.stopTips` and each stop's `parentTip`.
- Exams: `exams/ExamPlays.isExam` and `ExamPlays.sealed` — the rule that nothing derived from an exam's answers
  reaches a parent before the teacher releases it.
- The AI loop and its ledger: `ai/AiService`, `ai/AiTask`, `analysis/Prompts`, feature 22's `VoiceDraftService` as
  the shape to copy.
- The job and the push: `staffattendance/StaffAttendanceJob` (the `@Scheduled` pass per school, with its claim) and
  `notifications/NotificationService`'s parent rows.
- The school's day and zone: `platform/SchoolCalendar`.

## Behaviour

### 1. The facts the server computes (today, in the school's own zone)

- The **homework** lessons the child played today or that were due today. A sealed or unreleased exam is never one of
  them (`ExamPlays`): the card is homework only.
- From the **teacher's lesson text**: the lesson title, the parent panel's objectives, and the `parentTip` of the one
  stop worth practising.
- From the **child's own results**, in words: whether she finished the lesson, the band
  (`went well` / `getting there` / `worth another look`), and which stop to practise — the one with the most mistakes,
  and the fewest stars where mistakes tie.

Stars, scores and counts are never carried as numbers.

### 2. The AI writes the sentences

- Feature `parentEveningCard`, prompt `Prompts.EVENING_CARD`, through `AiService`. The answer is
  `{ "textAr": …, "textEn": … }`, two or three sentences each.
- The child is the placeholder **`{child}`**, which the app fills in. **No child name, age, class or any other datum
  about her goes to the model** — the facts above and nothing else.
- The validator rejects an answer that holds a digit (Western `0-9` or Arabic-Indic `٠-٩`) the **teacher's own
  lesson text does not hold word for word** — a lesson named "Counting by 2s" may be quoted, a made-up "3 stars" may
  not — and any answer with a `%`, with the words score/scores/percent/درجة/نسبة, missing `{child}`, or too long.
  After two attempts a fixed bilingual **template** built from the same facts is used, and the card says so
  (`source: "template"`). The template itself never quotes a title, so it is digit-free by construction.

### 3. The endpoint

`GET /children/{id}/evening-card` (parent, her own child; another parent's child is a 404) answers
`{ day, textAr, textEn, lessonIds, source }`, or **204** when there is nothing to show:

- before 16:00 school time,
- on a day the school does not teach,
- or when nothing happened today.

The card is generated on the first request after 16:00 and stored, one row per child and day, so every later request
answers the same words and asks the model nothing.

### 4. The evening push

A job from 18:00 school time, for each school with the switch on: every child who has a card today (generating the
missing ones) gets **one push to her parent, once per child per day** — notification kind `evening.card`, the title
"Today with &lt;her name&gt;" (the name is the parent's own child's, on the parent's own phone; it never went to the
model), linking to `/children/{id}/evening-card`.

## Out of scope

- A weekly or monthly card, a card for staff, and anything the parent writes back.
- Changing what `GET /children/{id}/progress` says. The released score stays there, as it is.

## Acceptance criteria

- [ ] After 16:00 school time a parent sees one card per child, in Arabic and English, with her child's name in it.
- [ ] No digit, no `%` and no score word is ever in the card.
- [ ] A sealed or unreleased exam never contributes to it.
- [ ] Nothing about the child but her own results reaches the model, and never her name.
- [ ] One card and one push per child per day.

## Tests

- The validator rejects digits, Arabic-Indic digits, `%` and score words, and the template is used instead.
- A number the teacher herself wrote (a title such as "Counting by 2s") is accepted, quoted exactly; one that is not
  in the lesson text is still rejected, and a `%` is rejected even beside a quoted number.
- A sealed exam never reaches the facts.
- The captured model input holds no child name.
- 204 before 16:00, 204 on a non-school day, 204 when nothing happened today.
- The second request answers the same row and makes no second model call.
- The job sends one push per child per day.
- Another parent's child is a 404; school isolation; the switch off is a 404.
