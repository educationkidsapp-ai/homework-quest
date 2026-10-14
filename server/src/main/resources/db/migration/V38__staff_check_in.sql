-- Feature 15 `geofence-check-in` (docs/features/15-geofence-check-in.md) — an employee checks in and out from the phone,
-- inside the school's geofence (feature 14's pin and radius), confirmed by the phone's biometric. The server decides
-- the time and whether the attempt counts.
--
-- 1. `staff_check_ins` — the attendance record: one row per (school, employee, school day). `work_day` is the date in the
--    school's time zone; the times are server instants. The distance of each accepted fix is kept, the coordinates
--    never are. `mode` is `onsite` in v1 (`remote`, `device`, `correction` arrive with features 18 and later).
--    Not `staff_attendance`: that name is the department manager's manual register (V21).
--
-- 2. `staff_check_in_attempts` — every call, accepted or refused: the server instant, the phone's claimed instant
--    (audit only), the action, the distance and accuracy when they were reached, the mock flag, the install id and the
--    result with its reason. A mocked fix lands here as `MOCK_LOCATION`, which is how suspicious attempts are found.
--
-- 3. `staff_attendance_devices` — the phone bound to an employee: the first successful check-in writes the row, the
--    Admin's reset stamps `reset_by`/`reset_at`, and the next successful check-in writes a new row. The live binding
--    is the row with `reset_at` null. Not `staff_devices`: that name is the push-token table (V36).
--
-- An employee is (`staff_kind`, `staff_id`) as in V37: `USER` keyed by `users.id`, `WORKER` keyed by `workers.id`.
--
-- **No latitude or longitude column anywhere**, on purpose. Every table carries `school_id` for the Hibernate
-- `school` filter. Additive and idempotent, valid on PostgreSQL 16 and on H2 in PostgreSQL mode.

CREATE TABLE IF NOT EXISTS staff_check_ins (
    id                    TEXT PRIMARY KEY,
    school_id             TEXT NOT NULL REFERENCES schools(id),
    staff_kind            TEXT NOT NULL,
    staff_id              TEXT NOT NULL,
    work_day              DATE NOT NULL,
    check_in_at           TIMESTAMP NOT NULL,
    check_in_distance_m   INTEGER NOT NULL,
    check_out_at          TIMESTAMP,
    check_out_distance_m  INTEGER,
    mode                  TEXT NOT NULL DEFAULT 'onsite'
);
CREATE UNIQUE INDEX IF NOT EXISTS staff_check_ins_staff_day ON staff_check_ins(school_id, staff_kind, staff_id, work_day);
CREATE INDEX IF NOT EXISTS staff_check_ins_school_day ON staff_check_ins(school_id, work_day);

CREATE TABLE IF NOT EXISTS staff_check_in_attempts (
    id           TEXT PRIMARY KEY,
    school_id    TEXT NOT NULL REFERENCES schools(id),
    staff_kind   TEXT NOT NULL,
    staff_id     TEXT NOT NULL,
    server_at    TIMESTAMP NOT NULL,
    phone_at     TEXT,
    action       TEXT NOT NULL,
    distance_m   INTEGER,
    accuracy_m   DOUBLE PRECISION,
    mocked       BOOLEAN NOT NULL DEFAULT FALSE,
    install_id   TEXT,
    result       TEXT NOT NULL,
    reason       TEXT
);
CREATE INDEX IF NOT EXISTS staff_check_in_attempts_staff ON staff_check_in_attempts(school_id, staff_kind, staff_id, server_at);

CREATE TABLE IF NOT EXISTS staff_attendance_devices (
    id             TEXT PRIMARY KEY,
    school_id      TEXT NOT NULL REFERENCES schools(id),
    staff_kind     TEXT NOT NULL,
    staff_id       TEXT NOT NULL,
    install_id     TEXT NOT NULL,
    first_seen_at  TIMESTAMP NOT NULL,
    reset_by       TEXT,
    reset_at       TIMESTAMP
);
CREATE INDEX IF NOT EXISTS staff_attendance_devices_staff ON staff_attendance_devices(school_id, staff_kind, staff_id);
