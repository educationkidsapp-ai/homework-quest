-- Feature 24 `AI service layer` (docs/features/24-ai-service-layer.md): `quest.server.ai.AiService` becomes the one
-- loop every AI feature runs, and this is the one thing the four hand-copied loops never had — a ledger.
--
-- One row per *model attempt*, written by `AiCallLedger` in its own REQUIRES_NEW transaction, so a teacher's save that
-- rolls back and a pipeline step abandoned at its deadline both still leave behind what the provider will invoice for.
-- A rejected answer and a busy provider are attempts too: `outcome` is one of `ok`, `rejected`, `transient`,
-- `provider`, `aborted`.
--
-- `school_id` carries the Hibernate `school` filter, as every tenant table does. It is nullable because the platform
-- ADMIN's cross-school call and a lesson with no school yet really have no school, and there is deliberately **no
-- foreign key**: a write that must never fail may not depend on another table's row, and a billing trail must outlive
-- whatever is deleted around it.
--
-- **Numbers only.** No prompt, no answer, no name. The index is the one read the owner's cost view will want.
--
-- Additive and idempotent, so running it over QA data adds an empty table and nothing else. There is no feature flag:
-- the service is internal and has no route of its own.

CREATE TABLE IF NOT EXISTS ai_calls (
    id            TEXT PRIMARY KEY,
    school_id     TEXT,
    feature       TEXT   NOT NULL,
    tag           TEXT,
    provider      TEXT   NOT NULL,
    input_tokens  BIGINT NOT NULL DEFAULT 0,
    output_tokens BIGINT NOT NULL DEFAULT 0,
    outcome       TEXT   NOT NULL,
    duration_ms   BIGINT NOT NULL DEFAULT 0,
    created_at    TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS ai_calls_school ON ai_calls(school_id, created_at);
