-- Schools Dashboard §5 (Teacher) and §6 screens 14 and 16: questions a teacher sends to her students, the answers
-- children give them, and the announcements she posts to a class.
--
-- Additive only (`.github/workflows/migration-check.yml` rejects DROP) and valid on PostgreSQL 16 and on H2 in
-- PostgreSQL mode (the test profile). It creates three new tables and nothing else, so re-running it over QA data
-- cannot change a row: there is no INSERT, no ALTER and no UPDATE here.
--
-- The teacher's own profile needs no column: `teachers` (V4) already holds subjects / curriculum / grades / bios and
-- `users` holds `display_name` and `photo_url`. `GET|PUT /teacher/profile` writes exactly those.
--
-- Lists are stored as JSON text, as everywhere else in this schema, so the file is valid on both engines.

-- §6 screen 14. A draft until `sent_at` is set; once sent the stops are frozen and the window decides which children
-- see the island. `stops_json` is a JSON array of §5 `Stop` objects, validated with the shared `SchemaValidator` in
-- lenient mode (the same rule manual plays use: 1–9 stops, the exit ticket optional).
CREATE TABLE teacher_questions (
    id TEXT PRIMARY KEY,
    school_id TEXT NOT NULL REFERENCES schools(id),
    teacher_id TEXT NOT NULL REFERENCES users(id),
    title TEXT NOT NULL,
    stops_json TEXT NOT NULL DEFAULT '[]',
    class_ids_json TEXT NOT NULL DEFAULT '[]',                  -- the classes of hers it was sent to
    from_date DATE NOT NULL,                                    -- inclusive; the island appears from this day
    to_date DATE NOT NULL,                                      -- inclusive; the island disappears after it
    created_at TIMESTAMP NOT NULL,
    sent_at TIMESTAMP                                           -- NULL while it is a draft
);

-- One row per (question, child, stop): the batch upload is idempotent on that key, exactly as `attempts` is on its id.
--
-- `school_id` is not in the §5 sketch of this table and is added deliberately: the row is a tenant row (it names a
-- child and a question of one school) and every tenant table in this schema is filtered by `school_id` through the
-- Hibernate filter rather than by a join. Without the column the filter has nothing to bind to and a teacher of
-- another school could read the answers by id.
CREATE TABLE teacher_question_answers (
    id TEXT PRIMARY KEY,
    school_id TEXT NOT NULL REFERENCES schools(id),
    question_id TEXT NOT NULL REFERENCES teacher_questions(id),
    child_id TEXT NOT NULL REFERENCES children(id),
    stop_id TEXT NOT NULL,
    answer_json TEXT NOT NULL DEFAULT '{}',
    correct BOOLEAN NOT NULL DEFAULT FALSE,
    stars INTEGER NOT NULL DEFAULT 0,
    answered_at TIMESTAMP NOT NULL,
    UNIQUE (question_id, child_id, stop_id)
);

-- §6 screen 16: a short note to all parents of one class, shown in the app's parent mode while it is live.
CREATE TABLE announcements (
    id TEXT PRIMARY KEY,
    school_id TEXT NOT NULL REFERENCES schools(id),
    teacher_id TEXT NOT NULL REFERENCES users(id),
    class_id TEXT NOT NULL REFERENCES classes(id),
    body_en TEXT NOT NULL,
    body_ar TEXT,
    published_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP,                                       -- NULL = no end date
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_teacher_questions_school ON teacher_questions(school_id);
CREATE INDEX idx_teacher_questions_teacher ON teacher_questions(teacher_id, created_at);
CREATE INDEX idx_teacher_questions_window ON teacher_questions(school_id, from_date, to_date);
CREATE INDEX idx_teacher_question_answers_school ON teacher_question_answers(school_id);
CREATE INDEX idx_teacher_question_answers_question ON teacher_question_answers(question_id);
CREATE INDEX idx_teacher_question_answers_child ON teacher_question_answers(child_id, answered_at);
CREATE INDEX idx_announcements_school ON announcements(school_id);
CREATE INDEX idx_announcements_class ON announcements(class_id, published_at);
CREATE INDEX idx_announcements_teacher ON announcements(teacher_id, created_at);
