-- Schools Dashboard §3 (themes) and §4 (feature flags), plus §A's PlatformSettings row.
--
-- Additive only (`.github/workflows/migration-check.yml` rejects DROP) and valid on PostgreSQL 16 and on H2 in
-- PostgreSQL mode. Every INSERT is guarded by NOT EXISTS so re-running the file over QA data changes nothing.
--
-- `schools.feature_flags_json` (added in V4) stays UNUSED: `school_feature_flags` is the only source of truth for a
-- school's overrides, so there is no denormalised copy to keep in step. `schools.theme_json` is used — it is where a
-- school's theme lives, and `SchoolService` already reads `logoUrl` out of it.
--
-- The flag's key column is `flag_key`, not `key`: `KEY` is a reserved word in H2 2.x, so `key TEXT PRIMARY KEY` is a
-- syntax error on the test profile. The same name in all three tables also keeps the joins readable.

CREATE TABLE feature_flags (
    flag_key TEXT PRIMARY KEY,                                  -- "lessons.pdf", "complaints", … (§4)
    description TEXT NOT NULL DEFAULT '',
    default_on BOOLEAN NOT NULL DEFAULT FALSE,                  -- the value for a school with no row of its own
    rollout_stage TEXT NOT NULL DEFAULT 'internal' CHECK (rollout_stage IN ('internal', 'beta', 'ga')),
    created_at TIMESTAMP NOT NULL
);

-- One row per (school, flag) the Admin has actually set; absence means "the flag's default_on".
CREATE TABLE school_feature_flags (
    school_id TEXT NOT NULL REFERENCES schools(id),
    flag_key TEXT NOT NULL REFERENCES feature_flags(flag_key),
    enabled BOOLEAN NOT NULL,
    updated_by TEXT,                                            -- users.id; no FK, the trail outlives the account
    updated_at TIMESTAMP NOT NULL,
    PRIMARY KEY (school_id, flag_key)
);

-- Who flipped what and when (§4 "an audit log"). school_id NULL = the "enable/disable for all" column action.
CREATE TABLE flag_audit (
    id TEXT PRIMARY KEY,
    flag_key TEXT NOT NULL,
    school_id TEXT REFERENCES schools(id),
    enabled BOOLEAN NOT NULL,
    actor_user_id TEXT,
    created_at TIMESTAMP NOT NULL
);

-- §A: the product name, logo and support address live in the database, not in the code. One row, id 'default'.
CREATE TABLE platform_settings (
    id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    short_name TEXT NOT NULL,
    logo_url TEXT,
    support_email TEXT,
    default_theme_json TEXT,                                    -- NULL = the theme built from design/tokens.json
    updated_at TIMESTAMP NOT NULL
);

INSERT INTO platform_settings (id, name, short_name, logo_url, support_email, default_theme_json, updated_at)
SELECT 'default', 'Schools Dashboard', 'Schools', NULL, NULL, NULL, CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM platform_settings WHERE id = 'default');

-- The 14 flags of §4. `default_on` is true for the features that exist today, so nothing a school already uses is
-- switched off by this migration; the four that are not built yet start off and internal.
INSERT INTO feature_flags (flag_key, description, default_on, rollout_stage, created_at)
SELECT v.flag_key, v.description, v.default_on, v.rollout_stage, CURRENT_TIMESTAMP FROM (
    SELECT 'lessons.pdf'            AS flag_key, 'New lesson from a PDF'                                  AS description, TRUE  AS default_on, 'ga'       AS rollout_stage UNION ALL
    SELECT 'lessons.slides',             'New lesson from PowerPoint slides',                        TRUE,  'ga'       UNION ALL
    SELECT 'lessons.images',             'New lesson from photos of the workbook',                   TRUE,  'ga'       UNION ALL
    SELECT 'lessons.manual',             'New lesson from typed questions',                          TRUE,  'ga'       UNION ALL
    SELECT 'levels.three',               'Three difficulty levels per lesson',                       TRUE,  'ga'       UNION ALL
    SELECT 'retell.recording',           'Children record themselves retelling the story',           TRUE,  'ga'       UNION ALL
    SELECT 'openAnswer.drawing',         'Children answer by drawing',                               TRUE,  'ga'       UNION ALL
    SELECT 'parentPanel.arabic',         'Arabic parent panel alongside the English one',            TRUE,  'ga'       UNION ALL
    SELECT 'complaints',                 'Parents send complaints and messages from the app',        FALSE, 'internal' UNION ALL
    SELECT 'announcements',              'Teachers post announcements to a class',                   FALSE, 'internal' UNION ALL
    SELECT 'teacherQuestions',           'Teachers send questions to their students',                FALSE, 'internal' UNION ALL
    SELECT 'stickers.treasureChest',     'Streak treasure chest in the sticker book',                TRUE,  'ga'       UNION ALL
    SELECT 'progress.weeklyEmail',       'Weekly progress email to parents',                         FALSE, 'internal' UNION ALL
    SELECT 'certificates',               'Certificates when a child finishes a skill',               TRUE,  'ga'
) v
WHERE NOT EXISTS (SELECT 1 FROM feature_flags f WHERE f.flag_key = v.flag_key);

CREATE INDEX idx_school_feature_flags_school ON school_feature_flags(school_id);
CREATE INDEX idx_school_feature_flags_flag ON school_feature_flags(flag_key);
CREATE INDEX idx_flag_audit_flag ON flag_audit(flag_key, created_at);
CREATE INDEX idx_flag_audit_school ON flag_audit(school_id, created_at);
