-- Feature 29 `parent language bridge` (docs/features/29-parent-language-bridge.md): a parent reads the school's
-- messages in her own language, and staff read what she writes. The owner's rule decides the whole shape:
-- **the original text is always stored and shown as the record**, and a translation is only ever an addition.
--
-- 1. The `languageBridge` flag, off until a school turns it on (`INSERT … WHERE NOT EXISTS`, as V44 and V46).
--
-- 2. `parents.reading_language` — her own choice out of ar, en, ur, hi, bn, fil, fr and id (`PUT
--    /parent/me/reading-language`). Additive and NULL until she picks one, which means "the app's own language",
--    so every family that already exists keeps reading exactly what it read yesterday.
--
-- 3. `translations` — the cache, and nothing else: no source row is ever written to. One row per
--    (`source_kind`, `source_id`, `lang`, `source_hash`), which is the unique index, and `source_hash` is the
--    SHA-256 of the exact text that was sent to the model. That is what makes an **edited** source translate again
--    — a new hash is a new row, and the old one stays harmlessly beside it — and what makes a repeat request cost
--    nothing. `source_kind` is `chat`, `broadcast`, `announcement` or `complaint`, and `source_id` is that row's own
--    id in its own table: there is deliberately no foreign key, because four tables cannot be one reference and a
--    deleted source should take its translations with it only when a sweep says so, never through a cascade nobody
--    asked for.
--
-- `school_id` carries the Hibernate `school` filter, as every tenant table does — what one family reads is that
-- school's business. Additive and idempotent: re-running it over QA data adds a switch that is off, a column that
-- is NULL, and no row twice.

INSERT INTO feature_flags (flag_key, description, default_on, rollout_stage, created_at)
SELECT 'languageBridge', 'Parents read the school''s messages in their own language, and staff read what they write', FALSE, 'internal', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM feature_flags f WHERE f.flag_key = 'languageBridge');

ALTER TABLE parents ADD COLUMN IF NOT EXISTS reading_language VARCHAR(8);

CREATE TABLE IF NOT EXISTS translations (
    id          TEXT PRIMARY KEY,
    school_id   TEXT NOT NULL REFERENCES schools(id),
    source_kind VARCHAR(16) NOT NULL,
    source_id   TEXT NOT NULL,
    source_hash VARCHAR(64) NOT NULL,
    lang        VARCHAR(8) NOT NULL,
    text        TEXT NOT NULL,
    created_at  TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS translations_one_per_language
    ON translations(source_kind, source_id, lang, source_hash);
CREATE INDEX IF NOT EXISTS translations_school ON translations(school_id);
