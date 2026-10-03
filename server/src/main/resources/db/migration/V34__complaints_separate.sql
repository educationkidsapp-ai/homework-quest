-- B6 `backend/complaints-separate` (owner, 2026-10-03): a complaint is its own conversation, apart from Messages.
--
-- Until now a complaint was a `chat_threads` row with `topic = 'complaint'`, and since one row per (child, staff) was
-- the rule, it was usually a Messages conversation the parent had relabelled. From here on a parent opens a complaint
-- as a new row of its own, beside any Messages thread with the same person, and may open several over time.
--
-- 1. `thread_key` replaces the (child, staff) uniqueness with (child, staff, key): `''` on every Messages thread — so
--    two first messages sent at once still resolve into one row, exactly as `chat_threads_child_teacher` made them —
--    and the row's own id on a complaint, so each complaint is distinct. Staff-to-staff threads have no child and are
--    untouched (`chat_threads_staff_pair` still de-duplicates them).
-- 2. `title` — the complaint's short subject line. Rows written before B6 have none; theirs is the first 120 characters of
--    their first message, or empty for a complaint that has no message at all. `subject` is the recipient's subjects on
--    the child's section as the parent chose them (a teacher's or a coordinator's; NULL for a manager); a row written
--    before B6 has none, and the server derives it when it reads one.
-- 3. `complaint_events` — every status change with who and when (`resolved` / `open`, by the parent or a staff
--    member), what a client draws as "Resolved by Nour · 3 Oct". `resolved_at` / `resolved_by` on the thread keep the
--    latest resolution; the events keep all of them. A complaint already resolved before B6 gets its one event back.
--
-- Existing complaint-topic threads become complaints as they are, messages and all, whether or not they began life as
-- a question: they leave every Messages list, and the next message the parent sends that staff member opens a fresh
-- Messages thread. Idempotent for the QA data in place: every DDL is `IF NOT EXISTS`, and every DML only fills what is
-- still empty.

DROP INDEX IF EXISTS chat_threads_child_teacher;

ALTER TABLE chat_threads ADD COLUMN IF NOT EXISTS thread_key TEXT DEFAULT '' NOT NULL;
ALTER TABLE chat_threads ADD COLUMN IF NOT EXISTS title      TEXT;
ALTER TABLE chat_threads ADD COLUMN IF NOT EXISTS subject    TEXT;

UPDATE chat_threads SET thread_key = id WHERE topic = 'complaint' AND thread_key = '';

CREATE UNIQUE INDEX IF NOT EXISTS chat_threads_child_staff_key ON chat_threads(child_id, teacher_id, thread_key);

UPDATE chat_threads SET title = COALESCE((
    SELECT SUBSTRING(m.body, 1, 120) FROM chat_messages m WHERE m.thread_id = chat_threads.id
    ORDER BY m.created_at, m.id FETCH FIRST 1 ROWS ONLY), '')
WHERE topic = 'complaint' AND title IS NULL;

CREATE TABLE IF NOT EXISTS complaint_events (
    id         TEXT PRIMARY KEY,
    school_id  TEXT NOT NULL,
    thread_id  TEXT NOT NULL,
    status     TEXT NOT NULL,
    actor_role TEXT NOT NULL,
    actor_id   TEXT NOT NULL,
    changed_at TIMESTAMP NOT NULL,
    CONSTRAINT complaint_events_status CHECK (status IN ('open', 'resolved')),
    CONSTRAINT complaint_events_actor CHECK (actor_role IN ('parent', 'staff'))
);

CREATE INDEX IF NOT EXISTS complaint_events_thread ON complaint_events(thread_id, changed_at);
CREATE INDEX IF NOT EXISTS complaint_events_school ON complaint_events(school_id);

INSERT INTO complaint_events (id, school_id, thread_id, status, actor_role, actor_id, changed_at)
SELECT 'v34-' || t.id, t.school_id, t.id, 'resolved', 'staff', t.resolved_by, t.resolved_at
FROM chat_threads t
WHERE t.topic = 'complaint' AND t.status = 'resolved' AND t.resolved_at IS NOT NULL AND t.resolved_by IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM complaint_events e WHERE e.id = 'v34-' || t.id);

-- The Complaints lists read by topic and status within a school; `chat_threads_topic` (V20) already serves them.
