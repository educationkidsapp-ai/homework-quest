# Feature 21 — "What needs me today?" home (stretch)

**Status:** Not started · **Area:** dashboard (Admin, Manager) · **Depends on:** 16, 18 ·
**Feature switch:** `today_home`

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
