-- CR5: teachers read and write a stop as readable English, never as JSON.
--
-- `stops.content_json` stays the single source of truth for the app; `stops.text` is the prose the teacher last
-- saved and `stops.text_updated_at` says when. NULL means "nobody has edited the prose yet" — the read side then
-- renders it deterministically from the JSON (`StopText.describe`) and returns that without storing it, so a stop
-- the pipeline or the raw-JSON admin panel rewrote always describes what is actually there. Additive and
-- idempotent: existing QA rows keep their JSON and start with a NULL text.
ALTER TABLE stops ADD COLUMN IF NOT EXISTS text TEXT;
ALTER TABLE stops ADD COLUMN IF NOT EXISTS text_updated_at TIMESTAMP;
