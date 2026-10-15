-- Feature 22 `voice note to announcement` (docs/features/22-voice-to-announcement.md): a leader speaks a short note
-- in Saudi dialect and gets a formal announcement in Modern Standard Arabic and in English, addressed to real
-- parents, ready to review. Nothing is sent until she presses Send, which is the `broadcasts` route she already uses.
--
-- 1. The `voiceAnnouncement` flag, off until an Admin turns it on for a school (`INSERT … WHERE NOT EXISTS`, as V43).
--
-- 2. `announcement_drafts` — one row per prepared announcement, written by `POST /management/broadcasts/voice-drafts`
--    and *never* delivered to anybody. `transcript` is what her device transcribed; **speech to text runs on the
--    device**, so no audio is ever uploaded, written or deleted here. `curriculum` and `grade` are the suggested
--    audience and are null when nothing could be suggested or when the suggestion was not one of the author's real
--    cells (the server drops it rather than correcting it). `broadcast_id` is null until she sends: it is what makes
--    "a draft posts nothing", "one draft sends once" and the seven-day sweep of unsent drafts all one column.
--
-- `school_id` carries the Hibernate `school` filter, as every tenant table does. Additive and idempotent, so running
-- this over QA data adds a switch that is off and an empty table.

INSERT INTO feature_flags (flag_key, description, default_on, rollout_stage, created_at)
SELECT 'voiceAnnouncement', 'A leader''s voice note becomes a formal bilingual announcement she reviews and sends', FALSE, 'internal', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM feature_flags f WHERE f.flag_key = 'voiceAnnouncement');

CREATE TABLE IF NOT EXISTS announcement_drafts (
    id           TEXT PRIMARY KEY,
    school_id    TEXT NOT NULL REFERENCES schools(id),
    author_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    transcript   TEXT NOT NULL,
    title        TEXT NOT NULL,
    body_ar      TEXT NOT NULL,
    body_en      TEXT NOT NULL,
    curriculum   TEXT,
    grade        INTEGER,
    expires_at   TIMESTAMP,
    broadcast_id TEXT,
    created_at   TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS announcement_drafts_author ON announcement_drafts(school_id, author_id, created_at);
CREATE INDEX IF NOT EXISTS announcement_drafts_unsent ON announcement_drafts(broadcast_id, created_at);
