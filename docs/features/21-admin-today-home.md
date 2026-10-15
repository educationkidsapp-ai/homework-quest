# Feature 21 — "What needs me today?" home (stretch)

**Status:** Done · **Area:** dashboard (Admin, Manager) · **Depends on:** 16, 18 ·
**Feature switch:** `todayHome` (camelCase, like the other switches)

**Leftovers:**
- **Load time on QA is not measured yet.** "Under one second on QA with the pilot school's data" needs someone signed
  in on QA. Locally, a statement-count test bounds `GET /me/today` as children, classes, complaints and threads grow.
  It does not grow employees or pending requests: the feature 17 board and feature 18's `mayDecide` run one query per
  person, by their own design.
- **The counts strip reuses the whole Admin home call.** It keeps only the counts, so the old home's "needs you"
  queries still run. If QA is slow, the first fix is a counts-only method in `HomeService`.
- **"Device resets" are blocked check-ins.** The code has no reset *request*. The card lists staff whose check-in
  was refused today because the phone is not the approved one. The Admin resets the device inline, and only she
  sees this part, because only she may reset a device.
- **Errors show in the red band, not a toast.** That is the dashboard's rule for failures.
- **Not deployed yet.** The QA deploy follows the merge into `develop`, after feature 20 (migration V42 before V43).
  The switch is off until the school turns it on.

## Goal

Replace the Admin's counts-only home with one screen that says what needs a decision today, each item one tap
from being acted on. This is the clearest demo of "simpler than Classera".

## Where to look first

- The Admin home with counts (milestone 07) and the Manager home statistics (milestone 06).
- Attendance statuses (16), remote-day and correction requests (18), complaints (12), messages (04).

## Behaviour

Cards, top to bottom, shown only when they have something in them:

1. **حضور اليوم / Today's attendance** — present, late, not arrived, remote. Tap → the live board (17), filtered.
2. **بانتظار موافقتك / Waiting for you** — remote-day requests, corrections, device resets. Approve or reject
   inline on the card.
3. **شكاوى متأخرة / Overdue complaints** — complaints unresolved for more than 24 hours, in either academy, with
   the age. Tap → the complaint.
4. **رسائل غير مقروءة / Unread messages** — count and the three latest senders. Tap → Messages.

- When nothing needs attention: one calm line, "لا شيء يحتاجك الآن".
- The old counts move to a small strip at the bottom.
- The Manager gets the same screen scoped to her academy.

## Acceptance criteria

- [ ] Each card shows only real, current items, and disappears when empty.
- [ ] A request is approved from the card in one tap and the card updates.
- [ ] The Manager's version is limited to her academy.
- [ ] The page loads in under one second on QA with the pilot school's data.

## Tests

- Card contents per role and scope. Inline approve permissions. School isolation.
