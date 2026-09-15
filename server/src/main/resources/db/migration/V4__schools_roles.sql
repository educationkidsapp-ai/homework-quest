-- Schools Dashboard §2 (tenancy) and §5 (roles). Every tenant table gains `school_id`; existing rows move into the
-- default school. Lists are stored as JSON text so the file is valid on PostgreSQL 16 and on H2 in PostgreSQL mode.

CREATE TABLE schools (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    code TEXT NOT NULL UNIQUE,                                  -- 6 characters, shown to parents when they join
    curriculum_options_json TEXT NOT NULL DEFAULT '[]',         -- ["american","british"]
    grade_options_json TEXT NOT NULL DEFAULT '[]',              -- [1,2,3]
    theme_json TEXT,                                            -- filled by the theme package (phase 2)
    feature_flags_json TEXT NOT NULL DEFAULT '{}',
    status TEXT NOT NULL DEFAULT 'active',                      -- active | suspended
    created_at TIMESTAMP NOT NULL
);

INSERT INTO schools (id, name, code, curriculum_options_json, grade_options_json, theme_json, feature_flags_json, status, created_at)
VALUES ('default', 'Default school', 'HQ0001', '["american","british"]', '[1,2,3]', NULL, '{}', 'active', CURRENT_TIMESTAMP);

-- One table for every dashboard user; parents stay in Firebase Auth. ADMIN is the platform owner (school_id NULL).
CREATE TABLE users (
    id TEXT PRIMARY KEY,
    school_id TEXT REFERENCES schools(id),
    email TEXT NOT NULL UNIQUE,                                 -- always stored lowercase
    password_hash TEXT NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('ADMIN', 'TEACHER', 'MANAGERIAL')),
    status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'disabled', 'invited')),
    must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
    display_name TEXT,
    photo_url TEXT,
    language TEXT NOT NULL DEFAULT 'en',
    last_login_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

-- The seeded admin(s) become platform ADMINs and keep their id, so existing JWTs still name a real row.
-- `admin_users` itself is left behind on purpose: migrations in this repo must be additive (`.github/workflows/
-- migration-check.yml`) so rolling the image back keeps working. Nothing reads or writes it from here on; the
-- table is dropped in the release that removes `webAdmin/`.
INSERT INTO users (id, school_id, email, password_hash, role, status, must_change_password, language, created_at, updated_at)
SELECT id, NULL, LOWER(email), password_hash, 'ADMIN', 'active', FALSE, 'en', created_at, created_at FROM admin_users;

CREATE TABLE teachers (
    user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    subjects_json TEXT NOT NULL DEFAULT '[]',
    curriculum TEXT,
    grades_json TEXT NOT NULL DEFAULT '[]',
    bio_en TEXT,
    bio_ar TEXT,
    updated_at TIMESTAMP NOT NULL
);

-- A Class replaces the global Course as the unit a lesson is published into: (school, curriculum, grade, subject, teacher).
CREATE TABLE classes (
    id TEXT PRIMARY KEY,
    school_id TEXT NOT NULL REFERENCES schools(id),
    curriculum TEXT NOT NULL,
    grade INTEGER NOT NULL,
    subject TEXT NOT NULL,
    teacher_id TEXT REFERENCES users(id),
    created_at TIMESTAMP NOT NULL,
    UNIQUE (school_id, curriculum, grade, subject, teacher_id)
);

ALTER TABLE children ADD COLUMN school_id TEXT NOT NULL DEFAULT 'default';
ALTER TABLE children ADD CONSTRAINT fk_children_school FOREIGN KEY (school_id) REFERENCES schools(id);
ALTER TABLE lessons ADD COLUMN school_id TEXT NOT NULL DEFAULT 'default';
ALTER TABLE lessons ADD CONSTRAINT fk_lessons_school FOREIGN KEY (school_id) REFERENCES schools(id);
ALTER TABLE lessons ADD COLUMN class_id TEXT;
ALTER TABLE lessons ADD CONSTRAINT fk_lessons_class FOREIGN KEY (class_id) REFERENCES classes(id);

-- Backfill: one class in the default school per (curriculum, grade, subject) that existing lessons use, teacher unassigned.
INSERT INTO classes (id, school_id, curriculum, grade, subject, created_at)
SELECT DISTINCT 'default:' || c.curriculum || ':' || CAST(c.grade AS VARCHAR) || ':' || l.subject,
       'default', c.curriculum, c.grade, l.subject, CURRENT_TIMESTAMP
FROM lessons l JOIN courses c ON c.id = l.course_id;

UPDATE lessons SET class_id = (
    SELECT k.id FROM classes k JOIN courses c ON c.curriculum = k.curriculum AND c.grade = k.grade
    WHERE c.id = lessons.course_id AND k.subject = lessons.subject AND k.school_id = 'default');

-- Dashboard sessions and onboarding (used by the auth package; created here so phase 1 needs one migration only).
CREATE TABLE refresh_tokens (
    id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL UNIQUE,
    expires_at TIMESTAMP NOT NULL,
    revoked_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE invites (
    id TEXT PRIMARY KEY,
    school_id TEXT REFERENCES schools(id),
    email TEXT NOT NULL,
    role TEXT NOT NULL CHECK (role IN ('ADMIN', 'TEACHER', 'MANAGERIAL')),
    token_hash TEXT NOT NULL UNIQUE,
    invited_by TEXT NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    accepted_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL
);

CREATE TABLE audit_log (
    id TEXT PRIMARY KEY,
    actor_user_id TEXT,
    action TEXT NOT NULL,
    target_type TEXT,
    target_id TEXT,
    school_id TEXT REFERENCES schools(id),
    details_json TEXT NOT NULL DEFAULT '{}',
    created_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_users_school ON users(school_id);
CREATE INDEX idx_users_role ON users(role);
CREATE INDEX idx_classes_school ON classes(school_id);
CREATE INDEX idx_classes_teacher ON classes(teacher_id);
CREATE INDEX idx_classes_lookup ON classes(school_id, curriculum, grade, subject);
CREATE INDEX idx_children_school ON children(school_id);
CREATE INDEX idx_lessons_school ON lessons(school_id);
CREATE INDEX idx_lessons_class ON lessons(class_id);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);
CREATE INDEX idx_invites_school ON invites(school_id);
CREATE INDEX idx_invites_email ON invites(email);
CREATE INDEX idx_audit_log_school ON audit_log(school_id);
CREATE INDEX idx_audit_log_actor ON audit_log(actor_user_id, created_at);
