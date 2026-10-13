-- Feature 14 `attendance-settings` (docs/features/14-attendance-settings.md) — where and when the school's staff must
-- be present, and who hears about each employee. Nothing here records a check-in: that is feature 15. These are the
-- rules features 15 and 16 read.
--
-- 1. `attendance_settings` — one row per school, keyed by `school_id`: the school pin (latitude, longitude, radius in
--    metres, 50–500), the three rule thresholds in minutes, the notification policy for the academy director and the
--    Admin (`IMMEDIATE`, `DIGEST` or `OFF`) and the digest time. No row means the defaults the service answers with.
--    The time zone is deliberately not here: `schools.timezone` (V7) already decides what "today" is for the school,
--    and two columns that could disagree would be one too many.
--
-- 2. `work_schedules` — one row per (school, category), the category being `TEACHER`, `ADMINISTRATIVE` or `WORKER`:
--    the working days as a comma list of `DayOfWeek` names, start and end, and an optional Ramadan range with its own
--    start and end that replace the normal hours inside the range.
--
-- 3. `attendance_holidays` — date ranges with a name. No attendance is expected on those days.
--
-- 4. `staff_attendance_profiles` — an employee's category and direct manager, one row per (school, kind, id). `kind`
--    is `USER` (a teacher, coordinator or manager, keyed by `users.id`) or `WORKER` (keyed by `workers.id`), because a
--    worker may have no account. It is a table of its own rather than two columns on `users` and two on `workers`, so
--    one endpoint sets one employee or several. A null `category` is "the role's default" and a null
--    `direct_manager_id` is "fall back to the academy director, then the Admin".
--
-- And the `staffAttendance` flag the routes sit behind, off until an Admin turns it on for a school.
--
-- Every table carries `school_id` for the Hibernate `school` filter. Additive and idempotent: `IF NOT EXISTS`
-- throughout, valid on PostgreSQL 16 and on H2 in PostgreSQL mode.

CREATE TABLE IF NOT EXISTS attendance_settings (
    school_id                  TEXT PRIMARY KEY REFERENCES schools(id),
    latitude                   DOUBLE PRECISION,
    longitude                  DOUBLE PRECISION,
    radius_m                   INTEGER NOT NULL DEFAULT 150,
    grace_minutes              INTEGER NOT NULL DEFAULT 10,
    not_arrived_minutes        INTEGER NOT NULL DEFAULT 60,
    missing_check_out_minutes  INTEGER NOT NULL DEFAULT 60,
    director_notify            TEXT NOT NULL DEFAULT 'DIGEST',
    admin_notify               TEXT NOT NULL DEFAULT 'DIGEST',
    digest_time                TIME NOT NULL DEFAULT '07:30:00',
    updated_by                 TEXT,
    updated_at                 TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS work_schedules (
    id             TEXT PRIMARY KEY,
    school_id      TEXT NOT NULL REFERENCES schools(id),
    category       TEXT NOT NULL,
    weekdays       TEXT NOT NULL,
    start_time     TIME NOT NULL,
    end_time       TIME NOT NULL,
    ramadan_from   DATE,
    ramadan_to     DATE,
    ramadan_start  TIME,
    ramadan_end    TIME
);
CREATE UNIQUE INDEX IF NOT EXISTS work_schedules_school_category ON work_schedules(school_id, category);

CREATE TABLE IF NOT EXISTS attendance_holidays (
    id         TEXT PRIMARY KEY,
    school_id  TEXT NOT NULL REFERENCES schools(id),
    name       VARCHAR(80) NOT NULL,
    from_date  DATE NOT NULL,
    to_date    DATE NOT NULL
);
CREATE INDEX IF NOT EXISTS attendance_holidays_school ON attendance_holidays(school_id, from_date);

CREATE TABLE IF NOT EXISTS staff_attendance_profiles (
    id                 TEXT PRIMARY KEY,
    school_id          TEXT NOT NULL REFERENCES schools(id),
    staff_kind         TEXT NOT NULL,
    staff_id           TEXT NOT NULL,
    category           TEXT,
    direct_manager_id  TEXT REFERENCES users(id),
    updated_at         TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS staff_attendance_profiles_staff ON staff_attendance_profiles(school_id, staff_kind, staff_id);

INSERT INTO feature_flags (flag_key, description, default_on, rollout_stage, created_at)
SELECT 'staffAttendance', 'Staff attendance: settings, direct managers, check-in', FALSE, 'internal', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM feature_flags f WHERE f.flag_key = 'staffAttendance');
