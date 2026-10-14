-- Feature 16 `attendance-notifications` (docs/features/16-attendance-notifications.md) — check-ins become statuses,
-- employees are reminded, and leaders are told. `StaffAttendanceJob` writes both tables every five minutes for the
-- schools with `staffAttendance` on.
--
-- 1. `staff_attendance_days` — one row per (school, employee, working day), the day in the school's time zone: the
--    statuses the day has reached so far. A day can carry more than one (late and early leave). `late_minutes` and
--    `early_leave_minutes` are minutes after the start and before the end; the three booleans are not-arrived (no
--    check-in by start + threshold), absent (no check-in by the end of the day) and missing check-out (none by end +
--    threshold). Holidays and days off have no row. Feature 17's board and report read these rows.
--
-- 2. `staff_attendance_alerts` — what has been sent, keyed (school, employee, day, type) and unique, so a rerun of
--    the job, or two instances running it at once, sends each notification once: the job inserts the key with
--    ON CONFLICT DO NOTHING and sends only when its insert wrote the row. The morning digest uses the same key with
--    `staff_kind` `DIGEST` and the recipient's id.
--
-- Employees are named as in `staff_check_ins` (V38): `staff_kind` `USER` keys `users.id`, `WORKER` keys `workers.id`.
-- Every table carries `school_id` for the Hibernate `school` filter. Additive and idempotent.

CREATE TABLE IF NOT EXISTS staff_attendance_days (
    id                    TEXT PRIMARY KEY,
    school_id             TEXT NOT NULL REFERENCES schools(id),
    staff_kind            TEXT NOT NULL,
    staff_id              TEXT NOT NULL,
    work_day              DATE NOT NULL,
    late_minutes          INTEGER,
    not_arrived           BOOLEAN NOT NULL DEFAULT FALSE,
    absent                BOOLEAN NOT NULL DEFAULT FALSE,
    early_leave_minutes   INTEGER,
    missing_checkout      BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at            TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS staff_attendance_days_staff_day ON staff_attendance_days(school_id, staff_kind, staff_id, work_day);
CREATE INDEX IF NOT EXISTS staff_attendance_days_school_day ON staff_attendance_days(school_id, work_day);

CREATE TABLE IF NOT EXISTS staff_attendance_alerts (
    id          TEXT PRIMARY KEY,
    school_id   TEXT NOT NULL REFERENCES schools(id),
    staff_kind  TEXT NOT NULL,
    staff_id    TEXT NOT NULL,
    work_day    DATE NOT NULL,
    type        TEXT NOT NULL,
    sent_at     TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS staff_attendance_alerts_once ON staff_attendance_alerts(school_id, staff_kind, staff_id, work_day, type);
