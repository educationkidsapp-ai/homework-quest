# Feature 34 — Leave and permission requests

**Status:** Done · **Area:** mobile app (staff), dashboard, server · **Depends on:** 14 (settings, categories,
direct manager), 16 (statuses), 17 (monthly report), 18 (requests and push actions) ·
**Feature switch:** `staffLeave` (on by default, D30; needs `staffAttendance`)


**Leftovers:**
- **No in-app list of requests to decide.** Managers decide from the push or the dashboard. Rejecting a leave or a
  permission needs a reason, so the push's bare **Reject** cannot do it: the notification turns into "Open to add a
  reason" and the manager rejects from the dashboard.
- **Dates and times are typed** in the app forms, as in feature 18; there is no date or time picker yet.
- **A leave that spans New Year counts against the year it starts in.** The longest leave is 180 days.
- **The monthly report counts days up to today**, like every other column, so approved leave later in the current
  month appears as those days pass.
- **No dashboard screenshots or end-to-end tests** for the Leave card and the Requests page (their specs cover them).
- **The Leave card saves with the Attendance settings page**, because all its fields belong to the same settings
  record.

## Goal

A member of staff asks for leave or a short permission (استئذان) from the phone, her direct manager decides from the
push, and attendance knows about it: nobody is marked absent or late while excused. Balances are kept per person and
per year, and the monthly report shows them — no paper forms, no hand-kept spreadsheets.

## Where to look first

- Feature 14: attendance settings, the staff categories (`TEACHER`, `ADMINISTRATIVE`, `WORKER`),
  `DirectManagerResolver`, the Admin's Attendance settings page.
- Feature 16: how a day's status is computed (`AttendanceDays`, `AttendanceRules`: late, early leave, not arrived,
  absent) and the alerts `StaffAttendanceJob` sends.
- Feature 17: the attendance board and the monthly report (`AttendanceBoardService`, `AttendanceReportExport`, CSV
  and Excel).
- Feature 18: `AttendanceRequestService` — requests of type `REMOTE_DAY` and `CORRECTION`, who may decide
  (`mayDecide`: the direct manager or the Admin, never yourself), the push with **Approve** and **Reject** actions,
  the dashboard's "Attendance requests" screen, attachments on a correction.
- Attachments (milestone 12): `AttachmentService`, image metadata stripped.

## Behaviour

### Admin settings
Admin → Attendance settings → "الإجازات / Leave":
- **Leave types:** annual, sick, emergency, unpaid, other. Each type can be turned off for the school.
- **Yearly balance per type and per category** (teacher, administrative, worker), in days. Unpaid and other have no
  balance unless the Admin sets one. The year is the calendar year in the school's time zone.
- **Document rule:** a type can require a document — for sick leave, "required when longer than N days".
- **Approval chain:** either *direct manager only*, or *direct manager, then Admin*. With the second, a request the
  manager approves goes to the Admin and counts only once she approves too.
- **Permission limit:** the longest permission allowed in one day (default 3 hours).

### Staff app
Attendance tab → two buttons next to the existing ones, so each is two taps from the staff home:
- **"طلب إجازة / Request leave":** from–to dates, type, reason, optional attachment (photo or PDF; required when the
  type's document rule says so). Shows the days it will use and the balance left before she sends it.
- **"طلب استئذان / Request permission":** date, from–to time, reason.
- **"رصيدي / My balance":** per type, used and remaining this year.
- **"طلباتي / My requests":** every request with its status — pending, approved, rejected (with the reason) or
  cancelled. A pending request can be cancelled by its author.

Leave days count only school days of her schedule — weekends and holidays inside the range are not deducted.

### Approval
- The request goes to the direct manager (feature 14). On Android the push has **Approve** and **Reject** actions, as
  in feature 18; tapping it opens the request.
- Dashboard: "الطلبات / Requests" — leave, permission, and feature 18's remote-day and correction requests in one list,
  with filters for type, status, employee and month. The manager sees her direct reports, the Admin the whole school.
- A rejection needs a reason. The staff member is notified either way.
- With the two-step chain, the Admin is notified after the manager approves, and decides the same way.
- **A request for a past date** (any day before today in the school's zone) always needs the Admin's approval, even
  when the chain is "direct manager only".

### Attendance effect
- **Approved leave:** each day in the range expects no check-in. The day's status is `on_leave`, never absent, late
  or not arrived, and no attendance alert is sent for it. The board and the report show "إجازة / On leave".
- **Approved permission:** inside its time window, lateness and early leaving do not count: a late arrival or an early
  departure that falls inside the window is not `late` or `early_leave`, and those minutes count as **excused minutes**
  on the day.
- Approving a request for a day that is already recorded recomputes that day's status, as a correction does.
- Cancelling or rejecting changes nothing on the record.

### Balances
- The balance is the yearly allowance for her category and the type, minus the days of approved leave that year.
- A leave request that would go below zero is refused, except for a type with no balance (unpaid, other).

### Reports
- **Balance per employee:** allowance, used and remaining per type, for the year.
- **Requests per month:** every request with its employee, type, dates or times, days or minutes, status and decider.
- Both in CSV and Excel, from the dashboard. The monthly report (feature 17) gains the columns leave days, permission
  count and excused minutes.

### Guardrails
- No two approved requests of the same person may overlap (leave with leave, permission with permission, or a
  permission inside a leave day); the second approval is refused.
- Only the direct manager or the Admin decides, never the requester herself (feature 18's rule).
- School isolation for every new table; role checks on every route; Arabic and English, right to left and dark mode.

## Out of scope

- Payroll or salary deductions.
- Half-day leave (a permission covers part of a day).
- Leave for children.

## Acceptance criteria

- [ ] The Admin sets leave types, yearly balances per category, the document rule, the approval chain and the
      permission limit.
- [ ] A teacher requests three days of annual leave from the app; her manager approves from the push; those days show
      `on_leave` on the board, she gets no absence alert, and her balance drops by the school days in the range.
- [ ] A sick leave longer than N days cannot be sent without a document.
- [ ] A permission from 07:00 to 09:00 that is approved turns a 08:30 check-in from late into on time, with 90
      excused minutes (or the minutes that fell inside the window).
- [ ] A rejection without a reason is refused; with one, the employee is told the reason.
- [ ] With the two-step chain, the request counts only after the Admin approves.
- [ ] A request for a past date waits for the Admin even with "direct manager only".
- [ ] An overlapping approval is refused; a request beyond the balance is refused.
- [ ] Balance and monthly request reports export to CSV and Excel; the monthly report shows leave days and excused
      minutes.
- [ ] The switch off answers 404; another school's requests are invisible.

## Tests

- Settings: types, balances per category, document rule, chain, permission limit; only the Admin may change them.
- Leave days count school days only; balance computed per year in the school's zone; over-balance refused.
- Document rule enforced.
- Approve and reject permissions: direct manager or Admin, never the requester; rejection needs a reason; both
  notify the requester; push carries Approve and Reject actions.
- Two-step chain; past date needs the Admin.
- `on_leave` status for every day of approved leave; no alert sent; board and report show it.
- Permission window removes `late` and `early_leave` inside it and counts excused minutes; outside it nothing changes.
- Approving a request for an already recorded day recomputes it.
- Overlap refused; cancel by the author only while pending.
- Reports: balance and monthly requests, CSV and Excel; the monthly report's new columns.
- School isolation for the new tables; switch off 404.
- App: request forms, my balance, my requests; dashboard: the Requests page and the Leave settings.
