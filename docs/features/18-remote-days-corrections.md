# Feature 18 — Remote days and corrections

**Status:** Done · **Area:** mobile app (staff), dashboard, server · **Depends on:** 15 (and 16 for statuses) ·
**Feature switch:** `staff_attendance`

**Leftovers:**
- **The board and the monthly report are feature 17, which is not merged yet.**
  - A remote check-in is stored with mode `remote`, and "My record" shows "عن بعد". The live board's "عن بعد" label
    lands with feature 17.
  - The per-employee correction count is ready for the report (`StaffAttendanceRequestRepository.countAppliedCorrections`).
    The report column itself lands with feature 17.
- **The Admin's direct correction lives on the dashboard's "Attendance requests" screen.** She picks the employee and
  the day there. Feature 17's employee-day view can call the same route.
- **Android Approve and Reject buttons are verified by build and unit test only.** They have not been tried on a phone
  with a real push yet.
- **iPhone has no action buttons.** Tapping the push opens the app. The buttons need the Apple Developer account.
- **No in-app list of requests to decide.** Managers decide from the push buttons or from the dashboard.
- **Dates in the app are typed.** The request and correction forms take the date as `yyyy-MM-dd` (validated, with a
  sensible default). There is no date picker yet.
- **Not deployed yet.** The QA deploy follows the merge into `develop`. Sending the Android build to testers needs the
  owner's go-ahead.

## Goal

Let staff check in from anywhere on approved remote days, clearly labelled as remote, and fix a missed check-in or
check-out through a request their manager approves — without anyone editing records by hand.

## Where to look first

- Check-in validation and `mode` from feature 15. Statuses from feature 16.
- Attachments (milestone 12). Push deep links (milestone 11).

## Behaviour

### Remote days
**School-wide (Admin)**
- Admin → Attendance → "أيام عن بعد / Remote days": date, optional scope (whole school or one academy), reason.
  Example: a rain day announced as remote study.
- On that day, check-in skips the geofence. The record's mode is `remote` and the board shows "عن بعد".
- Everyone in scope gets a push the evening before or as soon as it is created: "غدًا يوم عمل عن بعد".

**Per employee (request)**
- Staff app → Attendance → "طلب يوم عن بعد": date and reason.
- The direct manager approves or rejects. On Android the push carries **Approve** and **Reject** buttons so the
  manager decides without opening the app; the dashboard shows the same request.
- Approved: check-in skips the geofence for that employee and day, mode `remote`.

### Corrections
- Staff app → My record → a day → "طلب تصحيح": type (forgot check-in, forgot check-out, phone problem), the claimed
  time, a reason, and an optional photo or PDF.
- The direct manager approves or rejects, from the push or the dashboard.
- Approved: the record gets the claimed time with mode `correction`, the approver and the time of approval.
  Statuses are recomputed. The original attempts stay in the log.
- The Admin can add a correction directly from the employee's day, with a mandatory reason.
- The monthly report counts corrections per employee.

## Out of scope

- Leave requests, the fingerprint device, QR at the entrance.

## Acceptance criteria

- [ ] On a school-wide remote day, a teacher checks in from home and the board shows "عن بعد".
- [ ] A remote day for one academy does not affect the other.
- [ ] An employee requests a remote day; the manager approves it from the push; that day's check-in skips the geofence.
- [ ] An employee requests a correction with a photo; once approved, the record and status update and the log keeps the originals.
- [ ] A rejected request changes nothing and the employee is told.
- [ ] Every correction shows who approved it and when.

## Tests

- Geofence skipped only on approved remote days and only for people in scope.
- Status recomputation after an approved correction.
- Approve and reject permissions (only the direct manager or the Admin).
- Audit fields set; originals kept.
- School isolation.
