-- Feature 23 `remote study days` (docs/features/23-remote-study-days.md): the school, one academy or chosen grades
-- study from home. It **replaces** feature 18's school-wide remote day — the per-employee REMOTE_DAY request in
-- `staff_attendance_requests` is untouched and goes on working exactly as it did.
--
-- 1. The `remoteStudyDays` flag, off until an Admin turns it on for a school (`INSERT … WHERE NOT EXISTS`, as V44).
--
-- 2. `remote_study_days` — one declared day. `academy` NULL is every academy and `grades_json` NULL is every grade,
--    so a row with both NULL is the whole school, which is all feature 18 could say. `academy` is a curriculum such
--    as `british`, lower-cased, the same value feature 16's academy director is found by; `grades_json` is a JSON
--    array of the school's real grades (`schools.grade_options_json`). The two narrow different people: **staff** are
--    in scope by academy only (a grade does not say who teaches it), while **children and their parents** are in
--    scope by academy *and* grade. `broadcast_id` is the parents' announcement, posted when the day is created and
--    expired when it is deleted — null when no class of the school was in scope.
--
-- 3. Every `attendance_remote_days` row is **copied** here, keeping its id, with no grades and no announcement: a
--    school that already declared a remote day keeps it the moment the flag goes on. The old table stays, because a
--    migration is additive, but nothing reads or writes it any more (`RemoteDayService` reads this one).
--
-- `study_day` rather than `day`, which is a datetime field keyword — the reason V40 called its own column `work_day`.
-- `school_id` carries the Hibernate `school` filter, as every tenant table does. Additive and idempotent: re-running
-- it over QA data adds a switch that is off and copies no row twice.

INSERT INTO feature_flags (flag_key, description, default_on, rollout_stage, created_at)
SELECT 'remoteStudyDays', 'The school, one academy or chosen grades study from home for a day', FALSE, 'internal', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM feature_flags f WHERE f.flag_key = 'remoteStudyDays');

CREATE TABLE IF NOT EXISTS remote_study_days (
    id           TEXT PRIMARY KEY,
    school_id    TEXT NOT NULL REFERENCES schools(id),
    study_day    DATE NOT NULL,
    academy      TEXT,
    grades_json  TEXT,
    reason       VARCHAR(200) NOT NULL,
    broadcast_id TEXT,
    created_by   TEXT NOT NULL,
    created_at   TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS remote_study_days_school_day ON remote_study_days(school_id, study_day);

INSERT INTO remote_study_days (id, school_id, study_day, academy, grades_json, reason, broadcast_id, created_by, created_at)
SELECT src.id, src.school_id, src.work_day, src.academy, NULL, src.reason, NULL, src.created_by, src.created_at
FROM attendance_remote_days src
WHERE NOT EXISTS (SELECT 1 FROM remote_study_days copied WHERE copied.id = src.id);
