-- Feature 27 `complaint triage` (docs/features/27-complaint-triage.md): a new complaint reaches the member of staff
-- who answers it already sorted — what it is about, how urgent it is, a short bilingual summary, and a reply she
-- edits and sends herself. The AI prepares; she decides, and nothing is ever sent automatically.
--
-- 1. The `complaintTriage` flag, off until an Admin turns it on for a school (`INSERT … WHERE NOT EXISTS`, as V44).
--    Complaints themselves stay behind `chat`, the flag they have always had.
--
-- 2. `complaint_triage` — **one row per complaint**, which is what `complaint_id` being the primary key says: the
--    triage runs once, after the complaint has committed and on a task thread, and is never rewritten. A complaint
--    with no row is the ordinary case for a school with the flag off, for the seconds before the answer comes back,
--    and for a model that failed — so every read treats "no row" as "no triage" rather than as an error.
--
--    `category` and `urgency` are checked here as well as in the server's reader, because this table is what the
--    dashboard's chips are drawn from and a value nobody can draw is worse than no row. The summaries and the
--    suggested replies are held in both languages, as every string the school reads is.
--
--    `ON DELETE CASCADE` on the complaint: a child's hard delete (`RosterService.delete`) takes her threads with her,
--    and a triage of a complaint that no longer exists is nothing. It is why `RosterService` needs no new statement.
--
-- `school_id` carries the Hibernate `school` filter, as every tenant table does. **No part of a complaint's own text
-- reaches the AI ledger** (`ai_calls`, V45, holds numbers only); the words live here, beside the complaint they are
-- about, and are never shown to the parent.
--
-- Additive and idempotent, so running this over QA data adds a switch that is off and an empty table.

INSERT INTO feature_flags (flag_key, description, default_on, rollout_stage, created_at)
SELECT 'complaintTriage', 'A new complaint is sorted by the AI for the staff who answer it: category, urgency, a bilingual summary and a suggested reply', FALSE, 'internal', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM feature_flags f WHERE f.flag_key = 'complaintTriage');

CREATE TABLE IF NOT EXISTS complaint_triage (
    complaint_id TEXT PRIMARY KEY REFERENCES chat_threads(id) ON DELETE CASCADE,
    school_id    TEXT NOT NULL,
    category     TEXT NOT NULL,
    urgency      TEXT NOT NULL,
    summary_ar   TEXT NOT NULL,
    summary_en   TEXT NOT NULL,
    reply_ar     TEXT NOT NULL,
    reply_en     TEXT NOT NULL,
    created_at   TIMESTAMP NOT NULL,
    CONSTRAINT complaint_triage_category CHECK (category IN ('academic', 'behaviour', 'transport', 'fees', 'facilities', 'staff', 'other')),
    CONSTRAINT complaint_triage_urgency  CHECK (urgency  IN ('low', 'normal', 'high'))
);
CREATE INDEX IF NOT EXISTS complaint_triage_school ON complaint_triage(school_id, urgency, created_at);
