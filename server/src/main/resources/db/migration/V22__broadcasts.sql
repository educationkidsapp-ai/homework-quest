-- RM2 `backend/broadcasts` (DR6) — one table for the three things a manager or a coordinator sends out: the weekly
-- plan, an announcement and an event. DR6 is "one server feature reused by coordinator and manager", so the audience
-- is a property of the row rather than of the route, and the two composers write the same shape.
--
--   * `kind` — `weekly_plan`, `announcement` or `event`. A weekly plan carries `week_start` (the Sunday of the school
--     week) and there is one per week per department: re-posting replaces it, which `BroadcastService` does by
--     deleting the previous row. A partial unique index would say it in the database, but `CREATE UNIQUE INDEX … WHERE`
--     is not portable to H2 in PostgreSQL mode, so the rule lives in the service and the index below only makes the
--     lookup cheap.
--   * `audience_roles` — the subset of `parents`, `teachers`, `coordinators` this row is for, comma-separated. Three
--     words in one column rather than a child table: nothing joins on it and every read wants all of it.
--   * `curriculum` — the author's department, set on a manager's row and NULL on a coordinator's, whose audience is
--     named section by section instead.
--   * `subject` — the coordinator's own subjects, comma-separated, and NULL on a manager's row: a manager is wide in
--     subject and narrow in track, so the pair of columns is how the app labels the card ("from your maths
--     coordinator", "from the British department") without a second request.
--   * `section_ids` — comma-separated class ids, or NULL meaning "every section of the department". A coordinator's row
--     always names them (they are the classes she coordinates), so the delivery rule is one line: match the sections
--     when they are named, the curriculum when they are not.
--   * `title` is nullable because `POST /coordinator/announcements` has no title field and now writes a broadcast
--     through the same service; `body_en` is required and `body_ar` optional, as `announcements` has it.
--   * `attachment_url` / `attachment_name` — a reference to bytes that already exist (a `/media/**` path or a URL).
--     RM2 adds no upload route: the chat attachment storage DR6 would reuse is not on `develop` yet.
--
-- `broadcast_reads` is the unread rule, one row per (broadcast, reader). `reader_id` is a `users` id for a dashboard
-- recipient and a `parents` id for a parent — the two never collide and neither side reads the other's rows — so one
-- table serves both feeds and `unread` is "mine minus these". No foreign keys, as in `V15__chat.sql` and
-- `V17__notifications.sql`; the service deletes a replaced weekly plan's read rows itself.
--
-- `school_id` carries the Hibernate `school` filter like every other tenant table and is the author's own school,
-- never a request parameter. Additive and idempotent: re-running it on QA data creates nothing twice.

CREATE TABLE IF NOT EXISTS broadcasts (
    id              TEXT PRIMARY KEY,
    school_id       TEXT NOT NULL,
    author_user_id  TEXT NOT NULL,
    author_role     TEXT NOT NULL,
    kind            TEXT NOT NULL,
    week_start      DATE,
    title           TEXT,
    body_en         TEXT NOT NULL,
    body_ar         TEXT,
    attachment_url  TEXT,
    attachment_name TEXT,
    audience_roles  TEXT NOT NULL,
    curriculum      TEXT,
    subject         TEXT,
    section_ids     TEXT,
    expires_at      TIMESTAMP,
    created_at      TIMESTAMP NOT NULL
);

-- Every feed reads the school's rows newest first and then filters in memory over one page of them.
CREATE INDEX IF NOT EXISTS broadcasts_school_created ON broadcasts(school_id, created_at DESC);
-- The weekly plan of one department and week, which a re-post replaces.
CREATE INDEX IF NOT EXISTS broadcasts_weekly_plan ON broadcasts(school_id, kind, curriculum, week_start);

CREATE TABLE IF NOT EXISTS broadcast_reads (
    id           TEXT PRIMARY KEY,
    school_id    TEXT NOT NULL,
    broadcast_id TEXT NOT NULL,
    reader_id    TEXT NOT NULL,
    read_at      TIMESTAMP NOT NULL
);

-- Read twice is read once, and the unread count is one statement per reader.
CREATE UNIQUE INDEX IF NOT EXISTS broadcast_reads_broadcast_reader ON broadcast_reads(broadcast_id, reader_id);
CREATE INDEX IF NOT EXISTS broadcast_reads_reader ON broadcast_reads(reader_id);
