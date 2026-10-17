# Feature 27 — Complaint triage

*Spec drafted by the planner from the owner's brief (the owner gave no spec file); review it in the PR.*

**Status:** Done · **Area:** server, dashboard (Teacher, Coordinator, Manager), mobile app (parent) ·
**Depends on:** feature 24 (the AI service layer) and, for the expected reply time only, feature 19 (office hours) ·
**Feature switch:** `complaintTriage` (camelCase, like the other switches). Complaints themselves stay behind `chat`,
the switch they have always had.

**Leftovers:**
- **A rejected AI answer is no longer saved to disk.** Since the review fixes, a rejected answer about a complaint is
  not written to `data/llm-failures`. Only the error list is logged, and the `ai_calls` ledger keeps numbers only.
- **Triage accuracy and the suggested replies' wording need real complaints and the real model on QA.** Tests use
  the fake model.
- **No "Urgent first" sort yet.** Lists show the category and urgency chips only.
- **The expected reply time is shown only while a complaint is open,** and only when the school uses office hours
  (`chatOfficeHours`). A time already past is hidden.

The triage prompt is `Prompts.COMPLAINT_TRIAGE`, renamed from `SYSTEM_F` at merge: features 25, 26 and 27 were each
written against a prompt of that name, and a prompt is now named after its feature rather than lettered.

**Decision:** the triage runs **after the complaint is committed, on a task thread**. The parent's complaint is
created, answered and pushed exactly as it is today whether the AI succeeds, fails, is slow or is switched off. The
triage is an extra row beside the complaint, never a step on the way to one.

**Decision:** the **expected reply time is not part of the triage** and does not follow its switch. It is arithmetic
on feature 19's office hours, so it is on whenever `chatOfficeHours` is on and absent otherwise.

## Goal

Every new complaint arrives at the right person already sorted: what it is about, how urgent it is, two or three
lines of summary in Arabic and English, and a suggested reply the member of staff edits and sends herself. The parent,
for her part, is told when she can expect an answer instead of waiting in silence.

The AI prepares; the human always decides. **Nothing is ever sent automatically.**

## Where to look first

- Complaints as their own conversations (milestone 12 / B6): `chat/ComplaintService`, `chat/ComplaintController`,
  and `quest.api.dto.Complaints.kt`.
- The AI service layer (feature 24): `ai/AiService`, `ai/AiTask`, `ai/AiInputGuard`, `analysis/Prompts`.
- Office hours (feature 19): `chat/OfficeHours`, and feature 14's work hours underneath it
  (`staffattendance/AttendanceSettingsService`, `ScheduleResolver`).
- The complaint bells: `notifications/NotificationService` (`complaint.new`, `complaint.status`).
- The after-commit, off-the-request-thread pattern: `mail/OutgoingMail` and `push/ParentPush`.

## Behaviour

### The triage

1. A parent opens a complaint (`POST /children/{id}/complaints`). It is written, its first message is sent, the
   recipient's `complaint.new` bell rings — all unchanged — and **then** the triage is asked for, after the
   transaction has committed and on a task thread.
2. The AI is given the **complaint's title and its first message, and nothing else**. The child's name, the parent's
   name, the recipient's name, e-mail addresses and telephone numbers are masked by `AiInputGuard` first.
3. It answers one JSON object:
   - `category`: one of `academic`, `behaviour`, `transport`, `fees`, `facilities`, `staff`, `other`;
   - `urgency`: one of `low`, `normal`, `high`;
   - `summaryAr` / `summaryEn`: two or three lines, at most 400 characters each;
   - `replyAr` / `replyEn`: a suggested reply in the school's voice, at most 600 characters each.
4. An answer that is not that shape is asked for again once. If the second is wrong too, or the provider fails,
   **there is no triage row**: the failure is logged and the complaint is untouched.
5. A complaint is triaged **once**. There is no re-triage button in this feature.

### What the staff see

- The Complaints lists (teacher, coordinator, management and support) carry the category and the urgency, so the
  dashboard can draw its chips and sort by urgency.
- The detail carries the summary in both languages and the suggested reply in both languages.
- **"Use suggested reply" fills the reply box and nothing else.** Sending is the existing staff send endpoint and the
  member of staff pressing Send. No message is ever written by the triage.
- A **high** urgency also rings the manager(s) of the child's department once, on the existing complaint bell, linked
  to her own Complaints page. It is the one escalation this feature has.

### What the parent sees

- **Nothing of the triage.** The parent's own endpoints never carry the category, the urgency, the summary or the
  suggested reply: a guess about her complaint is the school's working note, not an answer to her.
- **When to expect an answer** (`expectedReplyBy`, on the complaint she has just opened and on her complaint detail),
  computed from the recipient's own office hours in the school's time zone:
  - she is inside her office hours now → four hours from now, and never later than today's closing time;
  - she is outside them → two hours after her next working period starts;
  - `chatOfficeHours` off → nothing is promised, and the field is absent.

### Privacy

- The provider is told the complaint's words and nothing the school holds about the family: no name, no address, no
  number. What the parent herself wrote is still her own words, which is the same limit feature 22 has.
- The AI ledger (feature 24) records numbers only, so no part of a complaint reaches `ai_calls`.

## Out of scope

- Re-triaging a complaint, or triaging the complaints that already exist.
- Auto-replying, auto-assigning or auto-escalating beyond the one high-urgency bell.
- Routing a complaint to a different recipient, and SLA reporting on reply times.
- Triaging ordinary messages.

## Acceptance criteria

- [ ] A new complaint gets a category, an urgency, a bilingual summary and a bilingual suggested reply.
- [ ] A failing, invalid or switched-off AI never delays, blocks or changes the complaint the parent created.
- [ ] No message is ever sent by the triage; "Use suggested reply" only fills the box.
- [ ] A high-urgency complaint rings the department manager once.
- [ ] The parent never sees any part of the triage.
- [ ] The parent is told an expected reply time that follows the recipient's office hours, and nothing when
      `chatOfficeHours` is off.
- [ ] Names, e-mail addresses and telephone numbers are masked in what the provider is sent.

## Tests

- The triage is stored and returned on the staff list and detail in all three staff areas, and on neither parent
  endpoint.
- A provider failure and a twice-invalid answer each leave the complaint created and no triage row.
- The triage writes no chat message.
- High urgency rings the department manager once; normal urgency rings nobody extra.
- The captured model input holds no child, parent or recipient name, no e-mail address and no telephone number.
- The switch off: no AI call and no triage row.
- `expectedReplyBy` for open hours, closed hours and `chatOfficeHours` off, on a pinned clock.
- School isolation (an `IsolationTest` case for `complaint_triage`) and the role checks complaints already have.
