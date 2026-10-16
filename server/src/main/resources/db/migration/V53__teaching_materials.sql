-- Feature 31 `teaching material tracking` (docs/features/31-teaching-material-tracking.md): every week each teacher
-- hands in her teaching material — دفتر التحضير — for each class and subject she teaches, and the leaders of
-- feature 17's board see who handed in on time, late or not at all, and mark it reviewed.
--
-- 1. The `teachingMaterials` flag, off until a school turns it on (`INSERT … WHERE NOT EXISTS`, as V44, V46 and V51).
--
-- 2. `material_settings` — one row per school: the deadline's weekday and time, read in the school's own zone, and
--    how many hours before it the reminder goes out. No row is the defaults (Thursday 12:00, four hours), so a
--    school that never opens the page still has a deadline. The material for a teaching week is due at that weekday
--    and time **in the week before it**, which is what makes the reminder about next week's preparation.
--
-- 3. `material_submissions` — what was handed in: one row per (teacher, class, subject, week), which is the unique
--    index, so "replace what I handed in" is an update of that row rather than a second one. `week_start` is the
--    first teaching day of the week the material is for (`SchoolCalendar.Week.startOf`). The **status is not a
--    column**: it is computed from `submitted_at` against that week's deadline and from `reviewed_at`, so a changed
--    deadline re-reads every row rather than leaving old ones asserting a rule nobody holds any more. `attachment_id`
--    names an `attachments` row of purpose `material` (V24/V33's table, feature 18's pattern); a row may instead
--    carry only a note.
--
-- 4. `material_reminders` — what has been sent, keyed (school, teacher, week) and unique, exactly as V39's
--    `staff_attendance_alerts`: the job inserts the key with ON CONFLICT DO NOTHING and sends only when its insert
--    wrote the row, so a rerun and a second Cloud Run instance send one reminder between them.
--
-- Every table carries `school_id` for the Hibernate `school` filter. Additive and idempotent: re-running it over QA
-- data adds a switch that is off, three empty tables, and no row twice.

INSERT INTO feature_flags (flag_key, description, default_on, rollout_stage, created_at)
SELECT 'teachingMaterials', 'Teachers hand in their weekly teaching material and leaders track and review it', FALSE, 'internal', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM feature_flags f WHERE f.flag_key = 'teachingMaterials');

CREATE TABLE IF NOT EXISTS material_settings (
    school_id            TEXT PRIMARY KEY REFERENCES schools(id),
    due_weekday          TEXT NOT NULL,
    due_time             TIME NOT NULL,
    remind_hours_before  INTEGER NOT NULL,
    updated_by           TEXT,
    updated_at           TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS material_submissions (
    id              TEXT PRIMARY KEY,
    school_id       TEXT NOT NULL REFERENCES schools(id),
    teacher_id      TEXT NOT NULL,
    class_id        TEXT NOT NULL,
    subject         TEXT NOT NULL,
    week_start      DATE NOT NULL,
    attachment_id   TEXT,
    note            TEXT,
    submitted_at    TIMESTAMP NOT NULL,
    reviewed_by     TEXT,
    reviewed_at     TIMESTAMP,
    review_comment  TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS material_submissions_once
    ON material_submissions(school_id, teacher_id, class_id, subject, week_start);
CREATE INDEX IF NOT EXISTS material_submissions_school_week ON material_submissions(school_id, week_start);

CREATE TABLE IF NOT EXISTS material_reminders (
    id          TEXT PRIMARY KEY,
    school_id   TEXT NOT NULL REFERENCES schools(id),
    teacher_id  TEXT NOT NULL,
    week_start  DATE NOT NULL,
    sent_at     TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS material_reminders_once ON material_reminders(school_id, teacher_id, week_start);
