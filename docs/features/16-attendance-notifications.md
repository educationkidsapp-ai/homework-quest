# Feature 16 — Attendance statuses, reminders and notifications

**Status:** Done · **Area:** server, mobile app (staff), dashboard · **Depends on:** 15 ·
**Feature switch:** `staff_attendance`

**Leftovers:**
- Tapping an alert or the digest opens the notifications list, not the employee's day or the live board: both screens
  are feature 17. Every link already names the type, the employee and the date for it.
- "عن بعد" (remote) in the digest is always 0 until feature 18's remote days.
- The job runs on an in-process timer. On Cloud Run (`cpu_idle = true`, no minimum instance on QA) it only ticks while
  an instance is up; a Cloud Scheduler call every five minutes would make reminders punctual. That is infra and IAM
  work for the owner.
- Deploying to QA and the Android build for testers happen after the merge. Arabic and English, right to left and dark
  mode were checked in unit tests of the wording only; a check on a real phone is still to do.

## Goal

Turn check-ins into statuses automatically, remind employees before they miss something, and tell the right
leaders at the right moment — the direct manager immediately, the rest in one morning summary.

## Where to look first

- Background jobs and their timeouts (milestone 05).
- Push notifications, the dashboard bell and deep links (milestones 04, 08 and 11).
- Settings, schedules, holidays and the direct-manager fallback from feature 14.

## Behaviour

### Statuses (per employee, per working day)
A working day is one where the employee's category has hours, the date is not a holiday, and the day is not
a remote day handled by feature 18.

| Status | Rule |
|---|---|
| `on_time` | Check-in at or before start + grace |
| `late` | Check-in after start + grace; store the minutes after start |
| `not_arrived` | No check-in by start + the not-arrived threshold (during the day) |
| `absent` | No check-in by the end of the working day |
| `early_leave` | Check-out before end; store the minutes |
| `missing_checkout` | No check-out by end + the missing-check-out threshold |

A day can carry more than one flag (late and early leave).

### Job
- A scheduled job runs every 5 minutes for schools with the feature on and evaluates thresholds.
- Every notification has a dedupe key (staff, date, type) so it is sent once, even if the job reruns.

### Reminders to the employee
- At start time with no check-in: "لم تسجل حضورك بعد".
- At end time with no check-out: "لا تنس تسجيل الانصراف".
- At end + threshold: "لم يُسجَّل انصرافك اليوم" — then the direct manager is told too.
- Tapping a reminder opens the Attendance tab.

### Leaders
- **Direct manager — immediate**, push and bell, for `late`, `not_arrived`, `early_leave`, `missing_checkout`.
  Example: "أ. خالد تأخر 18 دقيقة". Tapping opens the employee's day.
- **Academy director and Admin** — according to the settings in feature 14:
  - Morning digest (default) at the digest time: "اليوم: 3 متأخرون، 1 لم يصل، 2 عن بعد" with names; the
    director gets her academy, the Admin gets the whole school. Tapping opens the live board (feature 17).
  - Immediate: the same alerts as the direct manager.
  - Off.
- The fallback chain from feature 14 applies when there is no direct manager.

## Out of scope

- The live board screen (17), remote-day logic (18), leave requests, substitution.

## Acceptance criteria

- [ ] A check-in inside the grace period is `on_time`; one minute after is `late` with the right minutes.
- [ ] With no check-in, the employee is reminded at start, the manager is told at the threshold, and the day ends `absent`.
- [ ] An early check-out is `early_leave` and the manager is told.
- [ ] A missing check-out reminds the employee, then tells the manager.
- [ ] Holidays and weekends produce no statuses and no notifications.
- [ ] Ramadan hours are used inside the Ramadan range.
- [ ] Each notification arrives once even if the job runs twice.
- [ ] The digest arrives at the set time with the right scope for the director and the Admin.
- [ ] Switching the director's policy to "Immediate" or "Off" takes effect the next day at the latest.

## Tests

- Status computation table for every rule, including the grace boundary.
- Holiday, weekend, Ramadan and time-zone boundaries.
- Dedupe across reruns.
- Fallback chain for recipients.
- Digest scope per academy and school; school isolation.
