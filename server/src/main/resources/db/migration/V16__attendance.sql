-- V16: Attendance tracking for teacher sections and parent reflection
-- Table `attendance` records daily attendance per student with status (PRESENT, ABSENT, LATE, EXCUSED)
-- and optional teacher notes. Unique on (child_id, date).

CREATE TABLE IF NOT EXISTS attendance (
    id              TEXT PRIMARY KEY,
    school_id       TEXT NOT NULL REFERENCES schools(id),
    section_id      TEXT NOT NULL REFERENCES classes(id),
    child_id        TEXT NOT NULL REFERENCES children(id),
    date            DATE NOT NULL,
    status          TEXT NOT NULL CHECK (status IN ('PRESENT', 'ABSENT', 'LATE', 'EXCUSED')),
    notes           TEXT,
    marked_by       TEXT REFERENCES users(id),
    created_at      TIMESTAMP NOT NULL,
    updated_at      TIMESTAMP NOT NULL,
    CONSTRAINT uq_attendance_child_date UNIQUE (child_id, date)
);

CREATE INDEX IF NOT EXISTS idx_attendance_section_date ON attendance(section_id, date);
CREATE INDEX IF NOT EXISTS idx_attendance_school_date ON attendance(school_id, date);
CREATE INDEX IF NOT EXISTS idx_attendance_child_date ON attendance(child_id, date);

-- Expand default grade options for existing schools from [1,2,3] to [1,2,3,4,5,6]
UPDATE schools SET grade_options_json = '[1,2,3,4,5,6]' WHERE grade_options_json = '[1,2,3]';

-- Enable chat for all existing schools so parents and teachers see chat immediately
INSERT INTO school_feature_flags (school_id, flag_key, enabled, updated_at)
SELECT s.id, 'chat', TRUE, CURRENT_TIMESTAMP FROM schools s
WHERE NOT EXISTS (
    SELECT 1 FROM school_feature_flags f WHERE f.school_id = s.id AND f.flag_key = 'chat'
);
