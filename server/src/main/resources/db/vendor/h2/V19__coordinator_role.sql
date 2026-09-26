-- R2 (DR1): `users.role` gains COORDINATOR — the H2 twin of `db/vendor/postgresql/V19__coordinator_role.sql`, which
-- carries the reasoning and is the file QA and production run. The two differ only in how the anonymous CHECK that
-- `V4__schools_roles.sql` created is found: H2 names it `CONSTRAINT_<hash>`, so it is read out of
-- `information_schema` and dropped through `EXECUTE IMMEDIATE`, which is H2's answer to a `DO $$ … $$` block.
--
-- The `status` CHECK on the same table is deliberately left alone: the `MANAGERIAL` in the clause is what tells the
-- two apart.

EXECUTE IMMEDIATE (
    SELECT 'ALTER TABLE users DROP CONSTRAINT "' || tc.constraint_name || '"'
    FROM information_schema.table_constraints tc
    JOIN information_schema.check_constraints cc
      ON cc.constraint_name = tc.constraint_name AND cc.constraint_schema = tc.constraint_schema
    WHERE tc.table_name = 'users' AND tc.constraint_type = 'CHECK'
      AND cc.check_clause LIKE '%MANAGERIAL%' AND cc.check_clause NOT LIKE '%COORDINATOR%'
);

ALTER TABLE users ADD CONSTRAINT users_role_check
    CHECK (role IN ('ADMIN', 'TEACHER', 'MANAGERIAL', 'COORDINATOR'));
