-- Feature 18 `remote-days-corrections` (docs/features/18-remote-days-corrections.md) — staff check in from anywhere on an
-- approved remote day, and a missed check-in or check-out is fixed by a request the direct manager approves.
--
-- 1. `attendance_remote_days` — a day the Admin declared remote: the whole school (`academy` NULL) or one academy (a
--    curriculum such as `british`, the same lower-cased value feature 16's academy director is found by). On that day
--    check-in skips the geofence for everyone in scope and the record's mode is `remote`.
--
-- 2. `staff_attendance_requests` — an employee's request for a remote day of her own, or for a correction of a day
--    (forgot check-in, forgot check-out, phone problem) with the claimed `HH:mm` times in the school's zone, a reason
--    and an optional photo or PDF (`attachments.id`, purpose `attendance`). `status` is PENDING until the direct manager
--    or an Admin decides; `decided_by`/`decided_at` say who and when. An Admin's direct correction is written here too,
--    already APPROVED, so every correction has one row that names its approver.
--
-- 3. `staff_check_ins` gains the audit of a correction: who approved it, when, which request, and the times it
--    replaced (`original_check_in_at`/`original_check_out_at`; null when there was nothing to replace). The attempts
--    log (`staff_check_in_attempts`) is never touched, so the original taps stay where they were.
--
-- Employees are named as in V38: `staff_kind` `USER` keys `users.id`, `WORKER` keys `workers.id`. Every table carries
-- `school_id` for the Hibernate `school` filter. Additive and idempotent, valid on PostgreSQL 16 and on H2.

CREATE TABLE IF NOT EXISTS attendance_remote_days (
    id          TEXT PRIMARY KEY,
    school_id   TEXT NOT NULL REFERENCES schools(id),
    work_day    DATE NOT NULL,
    academy     TEXT,
    reason      VARCHAR(200) NOT NULL,
    created_by  TEXT NOT NULL,
    created_at  TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS attendance_remote_days_school_day ON attendance_remote_days(school_id, work_day);

CREATE TABLE IF NOT EXISTS staff_attendance_requests (
    id                 TEXT PRIMARY KEY,
    school_id          TEXT NOT NULL REFERENCES schools(id),
    staff_kind         TEXT NOT NULL,
    staff_id           TEXT NOT NULL,
    type               TEXT NOT NULL,
    work_day           DATE NOT NULL,
    correction_kind    TEXT,
    claimed_check_in   TIME,
    claimed_check_out  TIME,
    reason             VARCHAR(500) NOT NULL,
    attachment_id      TEXT,
    status             TEXT NOT NULL,
    created_by         TEXT NOT NULL,
    created_at         TIMESTAMP NOT NULL,
    decided_by         TEXT,
    decided_at         TIMESTAMP
);
CREATE INDEX IF NOT EXISTS staff_attendance_requests_staff ON staff_attendance_requests(school_id, staff_kind, staff_id, work_day);
CREATE INDEX IF NOT EXISTS staff_attendance_requests_status ON staff_attendance_requests(school_id, status, created_at);

ALTER TABLE staff_check_ins ADD COLUMN IF NOT EXISTS corrected_by TEXT;
ALTER TABLE staff_check_ins ADD COLUMN IF NOT EXISTS corrected_at TIMESTAMP;
ALTER TABLE staff_check_ins ADD COLUMN IF NOT EXISTS correction_request_id TEXT;
ALTER TABLE staff_check_ins ADD COLUMN IF NOT EXISTS original_check_in_at TIMESTAMP;
ALTER TABLE staff_check_ins ADD COLUMN IF NOT EXISTS original_check_out_at TIMESTAMP;
