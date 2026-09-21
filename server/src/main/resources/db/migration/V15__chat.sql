-- C1 `backend/chat-websocket` — real-time chat between a child's parent (app) and a teacher of the child's section
-- (dashboard). Two additive tables, both idempotent for the QA data already in place, and one flag.
--
-- 1. `chat_threads` — one conversation per (child, teacher); the unique index is what makes two first messages sent
--    at once one thread rather than two. `parent_unread` / `teacher_unread` are the badge counts, kept here rather
--    than counted per request because the thread list is the screen both clients open first; they are bumped and
--    cleared with single-row updates so two senders never lose each other's increment. There is deliberately no
--    `parent_id`: the parent of a thread is the child's parent at delivery time, so a roster child claimed later
--    picks up the conversation her teacher already started.
--
-- 2. `chat_messages` — the messages, ≤ 2000 characters of plain text each, never interpreted as HTML by anyone.
--    `read_at` is set when the other party marks the thread read. Paged by (created_at, id) so a burst that lands in
--    one microsecond still pages without a gap.
--
-- `school_id` is on both rows so the Hibernate `school` filter scopes them like every other tenant table; it is the
-- child's school, written by the service, and never taken from a request. Delivery across Cloud Run instances rides
-- on PostgreSQL `LISTEN/NOTIFY` (channel `chat_events`), which needs no table.

CREATE TABLE IF NOT EXISTS chat_threads (
    id              TEXT PRIMARY KEY,
    school_id       TEXT NOT NULL,
    child_id        TEXT NOT NULL,
    teacher_id      TEXT NOT NULL,
    created_at      TIMESTAMP NOT NULL,
    last_message_at TIMESTAMP,
    parent_unread   INTEGER NOT NULL DEFAULT 0,
    teacher_unread  INTEGER NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS chat_threads_child_teacher ON chat_threads(child_id, teacher_id);
-- The teacher's list: her threads, unread first, newest first.
CREATE INDEX IF NOT EXISTS chat_threads_teacher ON chat_threads(teacher_id, last_message_at);
CREATE INDEX IF NOT EXISTS chat_threads_school ON chat_threads(school_id);

CREATE TABLE IF NOT EXISTS chat_messages (
    id          TEXT PRIMARY KEY,
    school_id   TEXT NOT NULL,
    thread_id   TEXT NOT NULL,
    sender_role TEXT NOT NULL,
    sender_id   TEXT NOT NULL,
    body        TEXT NOT NULL,
    created_at  TIMESTAMP NOT NULL,
    read_at     TIMESTAMP
);

-- Paging (`?before=`, `?since=`) and the last message of a thread all walk this one.
CREATE INDEX IF NOT EXISTS chat_messages_thread_created ON chat_messages(thread_id, created_at, id);
CREATE INDEX IF NOT EXISTS chat_messages_school ON chat_messages(school_id);

-- The `chat` flag the routes and the socket sit behind, off until an Admin turns it on for a school. Same shape as
-- `V5__flags_themes.sql`, so re-running seeds nothing twice.
INSERT INTO feature_flags (flag_key, description, default_on, rollout_stage, created_at)
SELECT 'chat', 'Parent and teacher chat in the app and the dashboard', FALSE, 'internal', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM feature_flags f WHERE f.flag_key = 'chat');
