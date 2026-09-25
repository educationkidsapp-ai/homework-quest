-- E2 `backend/notifications` (D26) — what the dashboard bell shows, persisted server-side so a teacher who closed
-- the tab while a lesson was generating still finds out that it finished.
--
-- One row per (dashboard user, event). `user_id` is the recipient — the lesson's creator, resolved from
-- `lessons.created_by` to a `users` row — and never a parent: parents have the app, not the bell. `kind` is the
-- machine-readable reason (`lesson.needs_skills`, `lesson.ready`, `lesson.failed`) so the dashboard can localise
-- its own copy; `title` and `body` are the English server strings to fall back on. `link` is a dashboard path
-- (`/teacher/lessons/{id}`), not a URL, so one row reads the same on QA and in production.
--
-- `school_id` carries the Hibernate `school` filter like every other tenant table; it is the lesson's school,
-- written by the service and never taken from a request. No foreign keys, as in `V15__chat.sql`: a notification is
-- a record of something that happened and must survive the lesson being deleted.
--
-- Additive and idempotent: re-running it on QA data creates nothing twice.

CREATE TABLE IF NOT EXISTS notifications (
    id         TEXT PRIMARY KEY,
    school_id  TEXT NOT NULL,
    user_id    TEXT NOT NULL,
    kind       TEXT NOT NULL,
    title      TEXT NOT NULL,
    body       TEXT,
    link       TEXT,
    lesson_id  TEXT,
    read_at    TIMESTAMP,
    created_at TIMESTAMP NOT NULL
);

-- The bell's two queries: the newest page, and the unread count. Both start from the recipient.
CREATE INDEX IF NOT EXISTS notifications_user_read_created ON notifications(user_id, read_at, created_at DESC);
CREATE INDEX IF NOT EXISTS notifications_school ON notifications(school_id);
