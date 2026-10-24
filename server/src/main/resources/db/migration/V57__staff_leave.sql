-- Feature 34 `leave and permission requests` (docs/features/34-leave-and-permission-requests.md): a member of staff
-- asks for leave or a short permission (استئذان) from the phone, her direct manager decides from the push, and
-- attendance knows about it — nobody is marked absent or late while excused.
--
-- 1. The `staffLeave` switch, seeded **on** — D30 (V54): a switch is a kill switch now, and
--    `FlagAdminTest.every_flag_except_multiSchool_is_on_by_default` is what fails when a new one is seeded off. It
--    also needs `staffAttendance`, exactly as feature 18's requests do: leave is an answer to a question the
--    attendance board asks.
--
-- 2. `staff_attendance_requests` (V40) gains the columns the two new types need, so **one** table still holds every
--    request and feature 18's inbox, its `mayDecide` and the Android push's Approve/Reject keep working unchanged:
--      * `leave_type` — ANNUAL / SICK / EMERGENCY / UNPAID / OTHER, null for a remote day or a correction;
--      * `to_day` — the last day of a leave (inclusive); null means the range is `work_day` alone;
--      * `from_time` / `to_time` — a permission's window, wall-clock times in the school's zone on `work_day`;
--      * `days` — the **school days of her own schedule** inside the range, which is what a balance is spent in;
--      * `minutes` — a permission's length;
--      * `reject_reason` — mandatory on a rejected leave or permission (and kept optional on the two older types,
--        whose released Android build rejects with no body at all);
--      * `stage` — MANAGER or ADMIN, the step a pending request waits for. A two-step school moves it from MANAGER to
--        ADMIN on the manager's approval, and a request for a past date starts at ADMIN whatever the chain says.
--    Every existing row is backfilled with `stage = 'MANAGER'`, which is what feature 18's single step was.
--
-- 3. `staff_attendance_days` (V39) gains `excused_minutes`: the minutes of lateness and early leaving that fell
--    inside an approved permission window. `NOT NULL DEFAULT 0`, so every row that exists already reads zero.
--
-- 4. `leave_types` — one row per (school, type): whether the school offers it, and the document rule
--    (`document_after_days`: a request longer than that many days needs an attachment; null means never). No row is
--    the default, which is "offered, no document rule", so a school that never opened the page still works.
--
-- 5. `leave_balances` — one row per (school, type, category): the yearly allowance in days. No row means the type has
--    no balance for that category and nothing is ever refused for being over budget, which is what UNPAID and OTHER
--    are until the Admin sets one.
--
-- 6. `attendance_settings` gains `leave_chain` (MANAGER or MANAGER_THEN_ADMIN) and `permission_max_minutes` (the
--    longest permission allowed in one day; the spec's three hours). Both are nullable and null means the default, so
--    a school that saved the page before this migration keeps working and nothing has to be backfilled.
--
-- Employees are named as in V38: `staff_kind` `USER` keys `users.id`, `WORKER` keys `workers.id`. Every new table
-- carries `school_id` for the Hibernate `school` filter and has a case in `tenancy/IsolationTest`. Additive and
-- idempotent: re-running it over QA data adds a switch that is on, two empty tables and no row twice. Valid on
-- PostgreSQL 16 and on H2 in PostgreSQL mode (the test profile): no partial index, no UUID function.

INSERT INTO feature_flags (flag_key, description, default_on, rollout_stage, created_at)
SELECT 'staffLeave', 'Staff ask for leave and short permissions, their manager decides, and attendance excuses them', TRUE, 'internal', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM feature_flags f WHERE f.flag_key = 'staffLeave');

ALTER TABLE staff_attendance_requests ADD COLUMN IF NOT EXISTS leave_type TEXT;
ALTER TABLE staff_attendance_requests ADD COLUMN IF NOT EXISTS to_day DATE;
ALTER TABLE staff_attendance_requests ADD COLUMN IF NOT EXISTS from_time TIME;
ALTER TABLE staff_attendance_requests ADD COLUMN IF NOT EXISTS to_time TIME;
ALTER TABLE staff_attendance_requests ADD COLUMN IF NOT EXISTS days INTEGER;
ALTER TABLE staff_attendance_requests ADD COLUMN IF NOT EXISTS minutes INTEGER;
ALTER TABLE staff_attendance_requests ADD COLUMN IF NOT EXISTS reject_reason VARCHAR(500);
ALTER TABLE staff_attendance_requests ADD COLUMN IF NOT EXISTS stage TEXT;
UPDATE staff_attendance_requests SET stage = 'MANAGER' WHERE stage IS NULL;

ALTER TABLE staff_attendance_days ADD COLUMN IF NOT EXISTS excused_minutes INTEGER NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS leave_types (
    id                   TEXT PRIMARY KEY,
    school_id            TEXT NOT NULL REFERENCES schools(id),
    leave_type           TEXT NOT NULL,
    enabled              BOOLEAN NOT NULL DEFAULT TRUE,
    document_after_days  INTEGER,
    updated_at           TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS leave_types_school_type ON leave_types(school_id, leave_type);

CREATE TABLE IF NOT EXISTS leave_balances (
    id           TEXT PRIMARY KEY,
    school_id    TEXT NOT NULL REFERENCES schools(id),
    leave_type   TEXT NOT NULL,
    category     TEXT NOT NULL,
    yearly_days  INTEGER NOT NULL,
    updated_at   TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS leave_balances_school_type_category ON leave_balances(school_id, leave_type, category);

ALTER TABLE attendance_settings ADD COLUMN IF NOT EXISTS leave_chain TEXT;
ALTER TABLE attendance_settings ADD COLUMN IF NOT EXISTS permission_max_minutes INTEGER;
