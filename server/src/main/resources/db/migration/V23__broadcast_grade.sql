-- MG1 `backend/manager-plans-chat` (DR6, owner's manager list items 3 and 4) — the weekly plan gains a grade.
--
-- Today a `weekly_plan` is one per (school, week_start, curriculum): the whole department's week. The owner asks for
-- "the manager adds the weekly plan for all grades", which is a plan *per grade* as well as one for all of them, so
-- `grade` joins the row:
--
--   * `grade` NULL — the department's own plan, every grade of it, which is exactly what every row written before
--     this migration is. That is why the column is nullable with no default and no backfill: the old meaning is the
--     new NULL, so QA's rows keep saying what they said.
--   * `grade` set — the sections of that grade in that department, and nobody else. The read-time predicate is one
--     line in `BroadcastService.touches`: a reader sees it when one of her sections is in that grade.
--
-- The replace key of a weekly plan becomes (school_id, week_start, curriculum, grade), so a grade plan and the
-- department's all-grades plan coexist for one week and a re-post replaces only its own. `= NULL` is never true on
-- PostgreSQL, so `BroadcastRepository.weeklyPlans` matches a NULL grade with an explicit `is null` branch, as it
-- already does for `curriculum`.
--
-- Additive and idempotent: `IF NOT EXISTS` throughout, valid on PostgreSQL 16 and on H2 in PostgreSQL mode, and
-- re-running it on QA data changes no row.

ALTER TABLE broadcasts ADD COLUMN IF NOT EXISTS grade INTEGER;

-- The weekly plan of one department, week and grade — the replace lookup and the archive's window in one index.
CREATE INDEX IF NOT EXISTS broadcasts_weekly_plan_grade ON broadcasts(school_id, kind, curriculum, week_start, grade);
-- `GET /management/weekly-plans?from&to` and its two siblings: one school's plans over a window, newest week first.
CREATE INDEX IF NOT EXISTS broadcasts_school_week ON broadcasts(school_id, kind, week_start DESC);
