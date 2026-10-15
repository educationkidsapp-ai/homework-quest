-- Feature 26 `document reader` (docs/features/26-document-reader.md): the Admin or a department manager uploads a
-- circular — a PDF, a Word file or a photograph of a paper letter — and gets back what it says, in Arabic and in
-- English, with the dates it names and what the school has to do. "Draft announcement" pre-fills the compose sheet
-- she already uses; Send is still `POST /management/broadcasts`.
--
-- 1. The `documentReader` flag, off until an Admin turns it on for a school (`INSERT … WHERE NOT EXISTS`, as V44).
--
-- 2. `document_summaries` — one row per circular read. **The uploaded file itself is nowhere here and nowhere in the
--    bucket**: it is read from a temporary file that `DocumentReaderService` deletes in a `finally`, so there is no
--    storage path, no original to download and nothing to sweep but the summary. `file_name` and `pages` are what was
--    uploaded, kept so her list reads like her own filing cabinet, and `summary_json` is the validated answer —
--    title, both summaries, the key dates, the action items and the suggested announcement — stored whole rather than
--    spread over ten columns, because nothing queries inside it and the shape is the contract's
--    (`quest.api.dto.DocumentSummary`).
--
-- `school_id` carries the Hibernate `school` filter, as every tenant table does, and `author_id` narrows inside it:
-- a summary is the reader's own, so another manager of the same school gets a 404 and another school's is the same
-- answer. A row is deleted after thirty days (`DocumentRetention`).
--
-- Additive and idempotent, so running this over QA data adds a switch that is off and an empty table.

INSERT INTO feature_flags (flag_key, description, default_on, rollout_stage, created_at)
SELECT 'documentReader', 'A leader uploads a circular and reads what it says, in Arabic and English, with its dates and actions', FALSE, 'internal', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM feature_flags f WHERE f.flag_key = 'documentReader');

CREATE TABLE IF NOT EXISTS document_summaries (
    id           TEXT PRIMARY KEY,
    school_id    TEXT NOT NULL REFERENCES schools(id),
    author_id    TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    file_name    TEXT NOT NULL,
    pages        INTEGER NOT NULL DEFAULT 1,
    summary_json TEXT NOT NULL,
    created_at   TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS document_summaries_author ON document_summaries(school_id, author_id, created_at);
CREATE INDEX IF NOT EXISTS document_summaries_sweep ON document_summaries(created_at);
