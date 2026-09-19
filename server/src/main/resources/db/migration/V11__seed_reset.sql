-- The ledger of `SEED_RESET`. The wipe is one-shot: it deletes every school-scoped row once, writes the row below,
-- and every later start with the flag still on finds it and does nothing — otherwise a deploy that forgot to switch
-- the variable back off would wipe the owner's acceptance data on every revision.
--
-- To run the wipe a second time, delete the row (`DELETE FROM seed_resets;`) and deploy with SEED_RESET=true again;
-- `docs/runbook.md` says so beside the environment variables.
CREATE TABLE IF NOT EXISTS seed_resets (
    id TEXT PRIMARY KEY,                                        -- always 'once'
    ran_at TIMESTAMP NOT NULL,
    deleted_json TEXT NOT NULL DEFAULT '{}'                     -- the per-table counts the run logged
);
