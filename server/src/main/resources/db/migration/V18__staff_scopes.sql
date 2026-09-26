-- R2 `backend/coordinator-role` (DR1, DR2) — what a supervising staff account is allowed to see.
--
-- One table for both shapes the owner's spec has, so the department managers (RM1) need no second migration:
--
--   * a COORDINATOR row has `subject` set and `curriculum` nullable — one subject across a track, or across both
--     tracks when `curriculum` is NULL. A user may hold several rows.
--   * a MANAGERIAL row has `subject` NULL and `curriculum` set — a department (British / American), every grade and
--     every coordinator of it. The seed writes these; the reads that use them are RM1.
--
-- `school_id` carries the Hibernate `school` filter like every other tenant table, and is the account's own school,
-- written by the service or the seed and never taken from a request (`CoordinatorScope` never reads a UI parameter).
-- No foreign keys, as in `V15__chat.sql` and `V17__notifications.sql`: a scope row is a statement about a person and
-- must not stop a `users` row being deleted by `SeedReset`, which empties this table itself.
--
-- The unique index is `(user_id, subject, curriculum)`. Both engines treat NULLs as distinct in a unique index, so it
-- stops an exact duplicate pair and not a second "both tracks" row of the same subject; the service refuses those
-- itself (`CoordinatorAdminService.rows`) before anything is written, and the index is what the database enforces.
--
-- Additive and idempotent: re-running it on existing QA data creates nothing twice and changes no other table.

CREATE TABLE IF NOT EXISTS staff_scopes (
    id         TEXT PRIMARY KEY,
    school_id  TEXT NOT NULL,
    user_id    TEXT NOT NULL,
    subject    TEXT,
    curriculum TEXT,
    created_at TIMESTAMP NOT NULL
);

-- One row per (person, subject, track): a scope asked for twice is the same scope.
CREATE UNIQUE INDEX IF NOT EXISTS staff_scopes_user_subject_curriculum ON staff_scopes(user_id, subject, curriculum);
-- The two reads: every scope of one account (sign-in, `/coordinator/me`), and every scope of a school (Admin list).
CREATE INDEX IF NOT EXISTS staff_scopes_school ON staff_scopes(school_id);
