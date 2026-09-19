-- N4.1 `backend/scoring-marks-levels` — `docs/teacher-flow.md` step 9 and the teacher prompt §7.
--
-- Two additive things, both idempotent for the QA data that is already there.
--
-- 1. `lessons.released_at` — §7's release toggle. Null is "not released"; the parent's progress report carries the
--    score and the teacher's comment only for a lesson that has a timestamp here. Existing rows stay null, which is
--    the safe direction: nothing a parent could not already see becomes visible because a migration ran.
--
-- 2. `teacher_marks` — §7's `TeacherMark(childId, lessonId, stopId?, stars?, score?, comment, markedAt)`: the stars
--    and one-line comment a teacher gives an open stop (retell, open answer, free writing) and the lesson-level
--    score override and comment to the parent.
--
--    `stop_id` is NOT NULL with `''` for "this is about the whole lesson" rather than a nullable column, because the
--    unique index is what makes a mark idempotent per (child, lesson, stop) and a NULL never equals a NULL in
--    PostgreSQL — a nullable column would let the same child collect a new lesson-level row on every save.
--
--    `school_id` is carried on the row so the Hibernate `school` filter scopes it like every other tenant table; it
--    is the lesson's school, written by the service, never a parameter.

ALTER TABLE lessons ADD COLUMN IF NOT EXISTS released_at TIMESTAMP;

CREATE TABLE IF NOT EXISTS teacher_marks (
    id          TEXT PRIMARY KEY,
    school_id   TEXT NOT NULL,
    child_id    TEXT NOT NULL,
    lesson_id   TEXT NOT NULL,
    stop_id     TEXT NOT NULL DEFAULT '',
    stars       INTEGER,
    score       INTEGER,
    comment     TEXT,
    marked_by   TEXT,
    marked_at   TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS teacher_marks_child_lesson_stop ON teacher_marks(child_id, lesson_id, stop_id);
CREATE INDEX IF NOT EXISTS teacher_marks_lesson ON teacher_marks(lesson_id);
CREATE INDEX IF NOT EXISTS teacher_marks_school ON teacher_marks(school_id);

-- The gradebook grid reads every attempt of a class's children over a month; `attempts` was indexed by nothing but
-- its primary key, so that read was a full scan of the largest table in the database.
CREATE INDEX IF NOT EXISTS attempts_child_lesson ON attempts(child_id, lesson_id);

-- The `gradebook` and `openStopMarking` flags this package's routes sit behind were seeded off by V7 and are left
-- exactly as they are: a flag default is an Admin decision, not a migration's, and `PUT /admin/schools/{id}/flags`
-- is how a school switches them on.
