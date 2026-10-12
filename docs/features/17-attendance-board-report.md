# Feature 17 — Live attendance board and monthly report

**Status:** Not started · **Area:** dashboard (Admin, Manager, direct managers), server · **Depends on:** 16 ·
**Feature switch:** `staff_attendance`

## Goal

Leaders see today's staff attendance at a glance and export a monthly report ready for payroll — in one page.

## Where to look first

- Real-time updates used by messaging (milestones 04 and 08).
- Gradebook export to CSV and Excel (milestone 03).
- The Manager's existing "Staff attendance" with daily manual marking and monthly summary (milestone 06).

## Behaviour

### Page — "حضور الموظفين / Staff attendance"
**Who sees what**
- Admin: the whole school. Academy director: her academy. Any direct manager: the people who report to them.

**Today view (default)**
- Count cards: حاضر (present), متأخر (late), لم يصل (not arrived), عن بعد (remote), لم يسجل انصراف (no check-out).
  Tapping a card filters the table.
- Table: name, category, academy, check-in, check-out, status, mode. Search by name; filters by status, academy
  and category.
- Updates live as people check in (real-time if it fits the existing channel; otherwise refresh every 30 s).

**Employee day**
- Clicking a row shows the day: attempts, refusals with reasons, notifications sent, and the month calendar.

**Monthly report**
- Pick a month. One row per employee: working days, days present (onsite and remote), late count, total late
  minutes, absent days, early-leave minutes, missing check-outs, corrections.
- Export CSV and Excel, reusing the gradebook export.

### Existing manual marking (milestone 06)
- When `staff_attendance` is on, hide the daily manual marking. Past manual marks stay visible in history with
  mode `manual`. Manual changes from now on go through corrections (feature 18).

## Out of scope

- Remote-day and correction screens (18). A mobile version of the board.

## Acceptance criteria

- [ ] The Admin sees every employee; a director sees only her academy; a direct manager sees only their people.
- [ ] Counts match the table; tapping a count filters it.
- [ ] A new check-in appears on the board within 30 seconds at most.
- [ ] The monthly report's totals match the daily records for a sample employee.
- [ ] CSV and Excel exports open correctly with Arabic names.
- [ ] Manual daily marking is hidden when the feature is on, and old marks stay in history.

## Tests

- Scope per role (Admin, director, direct manager, teacher with no reports).
- Monthly aggregation against a fixture month with every status.
- Export columns and Arabic encoding.
- School isolation.
