-- The watchdog's sweep (PR #96) reads `SELECT … FROM lesson_steps WHERE status = 'running'` at startup and every
-- 60 s, and `lesson_steps` only had an index on (lesson_id, position) — so every sweep was a full scan of a table
-- that grows with nine rows per lesson forever. `running` is a handful of rows out of all of them, which is exactly
-- the shape an index answers well.
CREATE INDEX IF NOT EXISTS lesson_steps_status ON lesson_steps(status);
