-- R2 (DR1): `users.role` gains COORDINATOR, the fourth dashboard role.
--
-- `V4__schools_roles.sql` wrote the role list as an inline `CHECK (role IN ('ADMIN','TEACHER','MANAGERIAL'))`, which
-- is a single constraint rather than a widenable list, so a fourth role means replacing it. Nothing else changes: no
-- column is added or dropped, no data is rewritten, and rolling the image back keeps working — the old code simply
-- never writes the new value.
--
-- <strong>Why this file is under `db/vendor/{vendor}` rather than `db/migration`.</strong> The constraint V4 created
-- is anonymous, and the two engines name it differently: PostgreSQL derives `users_role_check`, H2 generates
-- `CONSTRAINT_<hash>`. Finding it needs dynamic SQL, and the dialects for that do not overlap — PostgreSQL has
-- `DO $$ … $$` and H2 has `EXECUTE IMMEDIATE`. `spring.flyway.locations` therefore carries Flyway's own `{vendor}`
-- placeholder and this statement has an H2 twin of the same version in `db/vendor/h2/`. Every other migration stays
-- in `db/migration`, which is the only directory the migration-check workflow reads.
--
-- Idempotent: the block drops whichever CHECK still lists MANAGERIAL without COORDINATOR, and adds the new one only
-- when it is not there, so a re-run against a database that already has it does nothing.

DO $$
DECLARE stale text;
BEGIN
    FOR stale IN
        SELECT c.conname FROM pg_constraint c
        WHERE c.conrelid = 'users'::regclass AND c.contype = 'c'
          AND pg_get_constraintdef(c.oid) LIKE '%MANAGERIAL%'
          AND pg_get_constraintdef(c.oid) NOT LIKE '%COORDINATOR%'
    LOOP
        EXECUTE format('ALTER TABLE users DROP CONSTRAINT %I', stale);
    END LOOP;

    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'users'::regclass AND conname = 'users_role_check') THEN
        ALTER TABLE users ADD CONSTRAINT users_role_check
            CHECK (role IN ('ADMIN', 'TEACHER', 'MANAGERIAL', 'COORDINATOR'));
    END IF;
END $$;
