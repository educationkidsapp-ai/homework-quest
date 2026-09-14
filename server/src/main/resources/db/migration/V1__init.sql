-- Homework Quest — dev prompt §6. Slide content is never stored: only analysis JSON, plays and page crops.

CREATE TABLE parents (
    id TEXT PRIMARY KEY,
    firebase_uid TEXT NOT NULL UNIQUE,
    email TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE children (
    id TEXT PRIMARY KEY,
    parent_id TEXT NOT NULL REFERENCES parents(id) ON DELETE CASCADE,
    name TEXT NOT NULL,
    avatar_color TEXT NOT NULL,
    curriculum TEXT NOT NULL,
    grade INTEGER NOT NULL,
    languages TEXT NOT NULL DEFAULT 'en',
    pin_hash TEXT,
    created_at TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP
);

CREATE TABLE courses (
    id TEXT PRIMARY KEY,
    curriculum TEXT NOT NULL,
    grade INTEGER NOT NULL,
    UNIQUE (curriculum, grade)
);
INSERT INTO courses (id, curriculum, grade) VALUES
    ('american/1', 'american', 1), ('american/2', 'american', 2), ('american/3', 'american', 3),
    ('british/1', 'british', 1), ('british/2', 'british', 2), ('british/3', 'british', 3);

CREATE TABLE lessons (
    id TEXT PRIMARY KEY,
    course_id TEXT NOT NULL REFERENCES courses(id),
    subject TEXT NOT NULL,
    date DATE NOT NULL,
    status TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    title TEXT,
    notes TEXT,
    practice_length INTEGER NOT NULL DEFAULT 7,
    source_hash TEXT,
    token_usage BIGINT NOT NULL DEFAULT 0,
    tokens_saved BIGINT NOT NULL DEFAULT 0,
    error_code TEXT,
    error_message TEXT,
    created_by TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    published_at TIMESTAMP
);

CREATE TABLE source_files (
    id TEXT PRIMARY KEY,
    lesson_id TEXT NOT NULL REFERENCES lessons(id) ON DELETE CASCADE,
    file_name TEXT NOT NULL,
    file_hash TEXT NOT NULL,
    kind TEXT NOT NULL,
    mime_type TEXT NOT NULL,
    page_count INTEGER NOT NULL DEFAULT 0,
    storage_path TEXT NOT NULL,
    size_bytes BIGINT NOT NULL,
    cache_hit BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    deleted_at TIMESTAMP
);

-- Permanent: never expired, invalidated only by prompt_version.
CREATE TABLE analysis_cache (
    cache_key TEXT PRIMARY KEY,
    source_hash TEXT NOT NULL,
    curriculum TEXT NOT NULL,
    grade INTEGER NOT NULL,
    subject TEXT NOT NULL,
    prompt_version TEXT NOT NULL,
    analysis_json TEXT NOT NULL,
    token_usage BIGINT NOT NULL DEFAULT 0,
    hits INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL
);

-- Plays, stops and parent panels generated for a (sourceHash, level, variant, seed, promptVersion).
CREATE TABLE generation_cache (
    cache_key TEXT PRIMARY KEY,
    source_hash TEXT NOT NULL,
    kind TEXT NOT NULL,                 -- play | stop | panel
    prompt_version TEXT NOT NULL,
    json TEXT NOT NULL,
    token_usage BIGINT NOT NULL DEFAULT 0,
    hits INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL
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

CREATE TABLE plays (
    id TEXT PRIMARY KEY,
    lesson_id TEXT NOT NULL REFERENCES lessons(id) ON DELETE CASCADE,
    level INTEGER NOT NULL,
    variant INTEGER NOT NULL DEFAULT 0,
    play_json TEXT NOT NULL,
    prompt_version TEXT NOT NULL,
    seed INTEGER NOT NULL DEFAULT 0,
    generated_at TIMESTAMP NOT NULL,
    UNIQUE (lesson_id, level, variant)
);

CREATE TABLE stops (
    id TEXT PRIMARY KEY,
    play_id TEXT NOT NULL REFERENCES plays(id) ON DELETE CASCADE,
    lesson_id TEXT NOT NULL,
    position INTEGER NOT NULL,
    type TEXT NOT NULL,
    category TEXT NOT NULL,
    title TEXT NOT NULL,
    ingredient TEXT NOT NULL,
    content_json TEXT NOT NULL,
    parent_tip_en TEXT NOT NULL,
    parent_tip_ar TEXT NOT NULL
);

CREATE TABLE parent_panels (
    lesson_id TEXT PRIMARY KEY REFERENCES lessons(id) ON DELETE CASCADE,
    panel_json TEXT NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE TABLE page_images (
    id TEXT PRIMARY KEY,
    lesson_id TEXT NOT NULL REFERENCES lessons(id) ON DELETE CASCADE,
    page_number INTEGER NOT NULL,
    storage_path TEXT NOT NULL,
    width INTEGER NOT NULL,
    height INTEGER NOT NULL,
    description TEXT NOT NULL DEFAULT ''
);

CREATE TABLE attempts (
    id TEXT PRIMARY KEY,
    child_id TEXT NOT NULL REFERENCES children(id) ON DELETE CASCADE,
    stop_id TEXT NOT NULL,
    lesson_id TEXT NOT NULL,
    level INTEGER NOT NULL,
    answer_json TEXT NOT NULL,
    correct BOOLEAN NOT NULL,
    attempt_number INTEGER NOT NULL,
    mistakes INTEGER NOT NULL DEFAULT 0,
    stars INTEGER NOT NULL DEFAULT 0,
    answered_at TIMESTAMP NOT NULL
);

CREATE TABLE stop_completions (
    child_id TEXT NOT NULL REFERENCES children(id) ON DELETE CASCADE,
    stop_id TEXT NOT NULL,
    lesson_id TEXT NOT NULL,
    level INTEGER NOT NULL,
    stars INTEGER NOT NULL,
    completed_at TIMESTAMP NOT NULL,
    recording_path TEXT,
    drawing_path TEXT,
    PRIMARY KEY (child_id, stop_id)
);

CREATE TABLE lesson_completions (
    child_id TEXT NOT NULL REFERENCES children(id) ON DELETE CASCADE,
    lesson_id TEXT NOT NULL,
    level INTEGER NOT NULL,
    stars_earned INTEGER NOT NULL,
    stars_total INTEGER NOT NULL,
    most_stops_two_stars BOOLEAN NOT NULL DEFAULT FALSE,
    certificate_issued BOOLEAN NOT NULL DEFAULT TRUE,
    completed_at TIMESTAMP NOT NULL,
    PRIMARY KEY (child_id, lesson_id, level)
);

CREATE TABLE parent_unlocks (
    child_id TEXT NOT NULL REFERENCES children(id) ON DELETE CASCADE,
    lesson_id TEXT NOT NULL,
    level INTEGER NOT NULL,
    PRIMARY KEY (child_id, lesson_id, level)
);

CREATE TABLE stickers (
    id TEXT PRIMARY KEY,
    child_id TEXT NOT NULL REFERENCES children(id) ON DELETE CASCADE,
    sticker_key TEXT NOT NULL,
    earned_at TIMESTAMP NOT NULL
);

CREATE TABLE streaks (
    child_id TEXT PRIMARY KEY REFERENCES children(id) ON DELETE CASCADE,
    current_days INTEGER NOT NULL,
    last_played_date DATE
);

CREATE TABLE child_media (
    id TEXT PRIMARY KEY,
    child_id TEXT NOT NULL REFERENCES children(id) ON DELETE CASCADE,
    stop_id TEXT NOT NULL,
    kind TEXT NOT NULL,
    storage_path TEXT NOT NULL,
    mime_type TEXT NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE admin_users (
    id TEXT PRIMARY KEY,
    email TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_children_parent ON children(parent_id);
CREATE INDEX idx_lessons_course_date ON lessons(course_id, date);
CREATE INDEX idx_lessons_status ON lessons(status);
CREATE INDEX idx_skills_lesson ON skills(lesson_id);
CREATE INDEX idx_plays_lesson ON plays(lesson_id);
CREATE INDEX idx_stops_play ON stops(play_id);
CREATE INDEX idx_attempts_child ON attempts(child_id, answered_at);
CREATE INDEX idx_attempts_lesson ON attempts(lesson_id, stop_id);
CREATE INDEX idx_source_files_hash ON source_files(file_hash);
CREATE INDEX idx_analysis_source ON analysis_cache(source_hash);
CREATE INDEX idx_generation_source ON generation_cache(source_hash);
