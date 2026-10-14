# Feature 14 — Attendance settings and direct manager

**Status:** Done · **Area:** dashboard (Admin), server · **Depends on:** — · **Feature switch:** `staff_attendance`

**Leftovers**
- Not yet on QA or testers' phones: this needs the PR merged (feature 13 and its V36 are already on develop;
  this feature is V37). Then the Admin turns `staffAttendance` on for the pilot school.
- "Set direct manager" for several at once lives on the staff list of the Attendance page, not on each of the
  four staff lists; the single-employee fields are on every staff edit form.
- The school pin is the lighter option the spec allows: latitude and longitude, "Use my current location" and a
  drawn circle, with a link that opens the pin in OpenStreetMap. No interactive map.
- A teacher's academy is `teachers.curriculum`; a teacher without one falls back to the Admin rather than to a
  director derived from her sections.
- After feature 13 merges: re-check that a worker with app access appears once (as a worker) in the staff list.
- The switch key in code is `staffAttendance` (camelCase, like every other key), not `staff_attendance`.

## Goal

Give the Admin one settings page that defines where and when staff must be present, and give every employee a
direct manager who receives their attendance alerts and approves their requests.

## Where to look first

- The Admin's staff pages: Teachers, Coordinators, Managers and Workers (milestone 07).
- Departments (the British and American academies) and their managers (milestone 06).
- The school settings model and feature switches (milestone 01).

## Behaviour

### Settings page — Admin → "الحضور / Attendance"

**School location**
- A map picker to place the school pin and set the radius. Default 150 m, allowed 50–500 m.
- If adding a map library is heavy, v1 may use latitude and longitude fields, a "Use my current location" button
  and a static preview of the circle. Choose the lighter option that fits the dashboard.

**Staff categories and work hours**
- Three categories: Teacher, Administrative, Worker. Each employee has one; the default comes from the role.
- For each category: working days (default Sunday–Thursday), start time and end time.
- **Ramadan hours:** a date range plus start and end per category. Inside the range they replace the normal hours.

**Holidays**
- A list of date ranges with a name ("إجازة منتصف الفصل"). No attendance is expected on these days.

**Rules**
- Grace period for lateness, in minutes. Set by the Admin; default 10.
- "Not arrived" threshold: minutes after start with no check-in. Default 60.
- Missing check-out check: minutes after end with no check-out. Default 60.

**Notifications**
- The direct manager is always notified immediately (fixed).
- Academy director and Admin: "Immediate", "Morning digest" (default) or "Off", each set separately.
- Digest time. Default 07:30.

**School time zone**
- Shown on the page, default Asia/Riyadh. All attendance dates and times use it.

### Direct manager
- Every staff record gets a **Direct manager** field: any staff member of the same school.
- Set it on the employee's edit form, and in bulk from the staff list (select several, "Set direct manager").
- Fallback when empty: the director of the employee's academy, then the Admin.
- The employee's category is also editable on the same form.

### Permissions
- Only the Admin edits these settings. Managers can view them.

## Data (conceptual — fit to the existing model)

- `AttendanceSettings` — school, latitude, longitude, radius, grace minutes, not-arrived minutes,
  missing-check-out minutes, notification policy for director and Admin, digest time, time zone.
- `WorkSchedule` — school, category, weekdays, start, end, and an optional date range for Ramadan.
- `Holiday` — school, from, to, name.
- Staff gets `directManagerId` and `category`.

## Out of scope

- Check-in itself (15), statuses and notifications (16), per-employee custom hours.

## Acceptance criteria

- [x] The Admin sets the school pin and radius and sees the circle.
- [x] The Admin sets hours per category and a Ramadan range; inside the range the Ramadan hours apply.
- [x] The Admin adds holidays.
- [x] The Admin sets the grace period and both thresholds.
- [x] The Admin sets a direct manager for one employee and for several at once.
- [x] An employee with no direct manager resolves to the academy director, then the Admin.
- [x] A Manager can view but not edit the settings.
- [x] Arabic, English, right to left and dark mode work.

## Tests

- Schedule resolution: normal day, Ramadan day, holiday, weekend.
- Direct manager fallback chain.
- Validation: radius bounds, start before end, Ramadan range valid.
- Only the Admin can write; school isolation on every endpoint.
