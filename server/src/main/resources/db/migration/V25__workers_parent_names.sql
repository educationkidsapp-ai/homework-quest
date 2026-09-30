-- MA1 `backend/admin-people` (the owner's admin-role list, 2026-09-30, items 1, 4 and 5) — two things the schema has
-- never held: a member of staff who does not teach, and a parent's name.
--
-- 1. `workers` — the owner's item 4, "a workers page: create with full name, job, mobile". The school's caretaker,
--    driver, nurse and secretary are on the payroll and on the Admin's Home count, and none of them signs in: there is
--    deliberately no `users` row, no password and no role, because an account nobody uses is an account nobody
--    rotates. `job` is free text (the schools do not share a job list and one that refused "bus supervisor" would be
--    wrong in a week), `phone` is the normalised E.164 form `quest.server.platform.Phones` writes, and `active`
--    retires a row the way `children.active` does — a person who has left is part of the school's record, so
--    `DELETE /admin/workers/{id}` clears the flag and deletes nothing.
--    `school_id` carries the Hibernate `school` filter like every other tenant table and is always the caller's own
--    scope, never a request parameter, and it `REFERENCES schools(id)` as `classes.school_id` and `children.school_id`
--    do (`V4__schools_roles.sql`): a worker of a school that does not exist is not a row worth keeping, and the
--    constraint is free here because the table is new and empty on every database that runs this file.
--
-- 2. `parents.display_name` — the owner's item 5 asks the Children & parents page for a parent *name*, and until now
--    the only name a parent had was the one Firebase holds: `parents` carried a uid, an address and (MH1) a telephone
--    number. Nullable with no backfill — every row that exists was created by `FirebaseTokenFilter` on first sight of
--    a token and has no name to give — so the directory prints the address for those and the name for the ones the
--    Admin creates from here on. Capped at 80 characters, the length every other `fullName` on the dashboard has.
--
-- Additive and idempotent: `IF NOT EXISTS` throughout, valid on PostgreSQL 16 and on H2 in PostgreSQL mode (the test
-- profile), and re-running it on QA data changes no row.

CREATE TABLE IF NOT EXISTS workers (
    id         TEXT PRIMARY KEY,
    school_id  TEXT NOT NULL REFERENCES schools(id),
    full_name  TEXT NOT NULL,
    job        TEXT NOT NULL,
    phone      VARCHAR(20),
    active     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL
);

-- The one read there is: the school's workers in name order, the Admin's page and the Home's count.
CREATE INDEX IF NOT EXISTS workers_school_name ON workers(school_id, full_name);

ALTER TABLE parents ADD COLUMN IF NOT EXISTS display_name VARCHAR(80);
