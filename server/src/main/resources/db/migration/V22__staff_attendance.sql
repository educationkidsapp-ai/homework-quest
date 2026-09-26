-- V22 (RM5, DR7): staff attendance — a department manager's daily register for the teachers and coordinators of her
-- department. The sibling of V16's `attendance`, one row per (person, day), with the same column vocabulary: `date`
-- rather than `day` because DAY is a reserved word in H2 2.x and the student register next door already says `date`.
--
-- Additive and idempotent: nothing here touches an existing row, so QA data survives the migration untouched.

CREATE TABLE IF NOT EXISTS staff_attendance (
    id              TEXT PRIMARY KEY,
    school_id       TEXT NOT NULL REFERENCES schools(id),
    user_id         TEXT NOT NULL REFERENCES users(id),
    date            DATE NOT NULL,
    status          TEXT NOT NULL CHECK (status IN ('present', 'absent', 'late', 'leave')),
    note            TEXT,
    marked_by       TEXT REFERENCES users(id),
    marked_at       TIMESTAMP NOT NULL,
    CONSTRAINT uq_staff_attendance_user_date UNIQUE (user_id, date)
);

-- The roster read is (school, day) and the history and summary reads are (person, window).
CREATE INDEX IF NOT EXISTS idx_staff_attendance_school_date ON staff_attendance(school_id, date);
CREATE INDEX IF NOT EXISTS idx_staff_attendance_user_date ON staff_attendance(user_id, date);
