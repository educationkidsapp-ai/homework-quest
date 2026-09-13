-- Mirrors the app's SQLDelight schema (dev prompt §5). Slide content is never stored: only skills and questions.

CREATE TABLE lessons (
    id TEXT PRIMARY KEY,
    child_id TEXT,
    date DATE NOT NULL,
    subject TEXT NOT NULL,
    grade INTEGER NOT NULL,
    curriculum TEXT NOT NULL,
    practice_length INTEGER NOT NULL DEFAULT 7,
    status TEXT NOT NULL,
    error_code TEXT,
    error_message TEXT,
    source_file_names TEXT NOT NULL DEFAULT '[]',
    typed_task TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE uploads (
    id TEXT PRIMARY KEY,
    lesson_id TEXT NOT NULL REFERENCES lessons(id) ON DELETE CASCADE,
    file_name TEXT NOT NULL,
    mime_type TEXT NOT NULL,
    storage_key TEXT NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP
);

CREATE TABLE skills (
    id TEXT PRIMARY KEY,
    lesson_id TEXT NOT NULL REFERENCES lessons(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    subject TEXT NOT NULL,
    method TEXT NOT NULL,
    examples_json TEXT NOT NULL DEFAULT '[]',
    slide_numbers_json TEXT NOT NULL DEFAULT '[]',
    confidence DOUBLE PRECISION NOT NULL,
    unsure_json TEXT,
    confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    position INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE question_sets (
    id TEXT PRIMARY KEY,
    skill_id TEXT NOT NULL REFERENCES skills(id) ON DELETE CASCADE,
    mode TEXT NOT NULL,
    seed TEXT NOT NULL,
    explanation TEXT NOT NULL,
    worked_examples_json TEXT NOT NULL,
    generated_at TIMESTAMP NOT NULL,
    UNIQUE (skill_id, mode, seed)
);

CREATE TABLE questions (
    id TEXT PRIMARY KEY,
    question_set_id TEXT NOT NULL REFERENCES question_sets(id) ON DELETE CASCADE,
    position INTEGER NOT NULL,
    type TEXT NOT NULL,
    prompt_json TEXT NOT NULL,
    options_json TEXT NOT NULL,
    correct_option_id TEXT,
    hint TEXT NOT NULL,
    number_line_json TEXT,
    illustration_key TEXT
);

-- Reserved for the future sync layer (dev prompt §8): same shape as the device tables, unused by v1 endpoints.
CREATE TABLE children (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    avatar_color TEXT NOT NULL,
    grade INTEGER NOT NULL,
    curriculum TEXT NOT NULL,
    languages TEXT NOT NULL,
    pin_hash TEXT
);

CREATE TABLE attempts (
    id TEXT PRIMARY KEY,
    question_id TEXT NOT NULL,
    child_id TEXT NOT NULL,
    chosen_option_id TEXT,
    correct BOOLEAN NOT NULL,
    attempt_number INTEGER NOT NULL,
    answered_at TIMESTAMP NOT NULL
);

CREATE TABLE stickers (
    id TEXT PRIMARY KEY,
    child_id TEXT NOT NULL,
    sticker_key TEXT NOT NULL,
    earned_at TIMESTAMP NOT NULL
);

CREATE TABLE streaks (
    child_id TEXT PRIMARY KEY,
    current_days INTEGER NOT NULL,
    last_played_date DATE
);

CREATE INDEX idx_skills_lesson ON skills(lesson_id);
CREATE INDEX idx_sets_skill ON question_sets(skill_id);
CREATE INDEX idx_questions_set ON questions(question_set_id);
CREATE INDEX idx_uploads_lesson ON uploads(lesson_id);
