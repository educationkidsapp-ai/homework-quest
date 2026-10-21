-- Feature 32 `a child's team and the class channel` (docs/features/32-child-team-class-channel.md): a parent sees
-- everyone who looks after her child on one page and writes to any of them in two taps, and each class has one
-- channel where its teachers post what the whole class needs to know.
--
-- 1. The two switches, `childTeam` and `classChannel`, seeded **on** — D30 (V54): a switch is a kill switch now, and
--    `FlagAdminTest.every_flag_except_multiSchool_is_on_by_default` is what fails when a new one is seeded off. Both
--    also need `chat`, which the services check beside the flag, so a school without chat has neither.
--
-- 2. `chat_threads.kind` and `chat_threads.class_id` — **there is no second chat system.** A channel is a thread of
--    this table with `kind = 'class_channel'` and the section on `class_id`; its posts are ordinary `chat_messages`
--    and its files ordinary chat attachments. Every row written before today is `direct`, which is what the default
--    gives them, and every existing query of a *conversation* now names `kind = 'direct'` so a channel never appears
--    in a thread list, an inbox or the Admin's support view.
--
--    A channel has no single staff member on the other end, so `teacher_id` — NOT NULL since V15 — carries the
--    **section's own id** on these rows. Nothing is made nullable and no existing comparison changes meaning: a
--    class id is never a user id, so `teacher_id = :someUserId` simply never matches a channel, and `class_id` is
--    the column the new code reads.
--
-- 3. `chat_messages.deleted_at` / `deleted_by` — a post the author or the Admin takes down. The row stays, so paging
--    cursors and unread counts hold, and the clients draw a tombstone; nothing else in the chat ever deletes a
--    message, so every row written before today keeps `deleted_at` NULL.
--
-- 4. `class_channels` — the map from a section to its one thread, created the first time the channel is used. A
--    table rather than a partial unique index on `chat_threads`, because H2 (the test profile) has no partial
--    indexes; the unique index on (school_id, class_id) is what makes two first posts land in one channel.
--
-- 5. `channel_reads` — each parent's read mark, one row per (thread, parent), exactly as V22's `broadcast_reads`
--    keeps a reader's. `last_read_message_id` is the post she had read, and the unread count is the posts after it.
--
-- Both new tables carry `school_id` for the Hibernate `school` filter, and `IsolationTest` has a case for each.
-- Additive and idempotent: re-running it over QA data adds two switches that are on, two columns that default,
-- two empty tables, and no row twice.

INSERT INTO feature_flags (flag_key, description, default_on, rollout_stage, created_at)
SELECT 'childTeam', 'A parent sees everyone who looks after her child on one page, and messages any of them', TRUE, 'internal', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM feature_flags f WHERE f.flag_key = 'childTeam');

INSERT INTO feature_flags (flag_key, description, default_on, rollout_stage, created_at)
SELECT 'classChannel', 'One channel per class: its teachers post, every parent of the class reads', TRUE, 'internal', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM feature_flags f WHERE f.flag_key = 'classChannel');

ALTER TABLE chat_threads  ADD COLUMN IF NOT EXISTS kind       TEXT DEFAULT 'direct' NOT NULL;
ALTER TABLE chat_threads  ADD COLUMN IF NOT EXISTS class_id   TEXT;
ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP;
ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS deleted_by TEXT;

-- The channel of one section, and the staff half of the feature, are both read by `class_id`.
CREATE INDEX IF NOT EXISTS chat_threads_kind_class ON chat_threads(school_id, kind, class_id);

CREATE TABLE IF NOT EXISTS class_channels (
    id         TEXT PRIMARY KEY,
    school_id  TEXT NOT NULL REFERENCES schools(id),
    class_id   TEXT NOT NULL,
    thread_id  TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);

-- One channel per section, whoever posts first, and one section per thread.
CREATE UNIQUE INDEX IF NOT EXISTS class_channels_class  ON class_channels(school_id, class_id);
CREATE UNIQUE INDEX IF NOT EXISTS class_channels_thread ON class_channels(thread_id);

CREATE TABLE IF NOT EXISTS channel_reads (
    id                   TEXT PRIMARY KEY,
    school_id            TEXT NOT NULL REFERENCES schools(id),
    thread_id            TEXT NOT NULL,
    parent_id            TEXT NOT NULL,
    last_read_message_id TEXT,
    read_at              TIMESTAMP NOT NULL
);

-- Read twice is read once, and one statement answers one parent's mark.
CREATE UNIQUE INDEX IF NOT EXISTS channel_reads_thread_parent ON channel_reads(thread_id, parent_id);
CREATE INDEX IF NOT EXISTS channel_reads_parent ON channel_reads(parent_id);
