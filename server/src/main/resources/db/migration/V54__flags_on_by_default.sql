-- D30 (owner, 2026-10-17): every feature switch is ON by default, for the schools that exist today and for every
-- school created from now on. The school buys the whole platform and the Admin turns off what she does not want on
-- Admin → Feature switches; a switch is a kill switch now, not a sales gate.
--
-- `multiSchool` is the one exception and stays off. It is not a feature but a tenancy mode: with it on, ADMIN stops
-- meaning "this school" (D13), the school switcher and the Admin school screens appear, and `MySchool` is a
-- one-school platform. Turning it on is a deliberate decision, not a default.
--
-- Three statements, in the order the three sources of truth are read:
--   1. `feature_flags.default_on` — what a school with no row of its own sees, and therefore what a NEW school and
--      a NEW flag key see. `FeatureFlags.load` reads exactly this, so nothing in Java needed a change.
--   2. `school_feature_flags.enabled` — the overrides the schools that exist today already carry. They were only
--      ever written off (V16's `chat` row is the one exception and is already TRUE), so flipping them on does not
--      overrule a decision anyone made; leaving them alone would have left today's school with every switch still
--      off while new schools got them on.
--   3. `flag_audit` — one row per key with `school_id` NULL, which is what that null means (§4: the "for all
--      schools" column action). The trail has to say why every switch changed on this date.
--
-- Additive and idempotent: each statement is a no-op on a second run, and every one is valid on PostgreSQL 16 and on
-- H2 in PostgreSQL mode (`||`, `CURRENT_TIMESTAMP`, no UUID function).
--
-- A migration that adds a new flag from here on seeds it TRUE, not FALSE.
-- `FlagAdminTest.every_flag_except_multiSchool_is_on_by_default` is what fails when one does not.

UPDATE feature_flags SET default_on = TRUE
WHERE flag_key <> 'multiSchool' AND default_on = FALSE;

UPDATE school_feature_flags SET enabled = TRUE, updated_by = 'migration:V54', updated_at = CURRENT_TIMESTAMP
WHERE flag_key <> 'multiSchool' AND enabled = FALSE;

INSERT INTO flag_audit (id, flag_key, school_id, enabled, actor_user_id, created_at)
SELECT 'v54-' || f.flag_key, f.flag_key, NULL, TRUE, NULL, CURRENT_TIMESTAMP
FROM feature_flags f
WHERE f.flag_key <> 'multiSchool'
  AND NOT EXISTS (SELECT 1 FROM flag_audit a WHERE a.id = 'v54-' || f.flag_key);
