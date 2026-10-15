-- Feature 25 `morning-brief` (docs/features/25-morning-brief.md): the three to five lines an Admin or a department
-- manager reads first thing, above feature 21's cards.
--
-- One row per (school, leader, school day). The facts are counted by the server out of what features 16, 17, 18, 12
-- and 04 already store — this table stores the *answer*, so that a brief is written once a morning however many
-- instances are running and however often the Home is reloaded:
--
--   * `facts_json` — the numbers the lines were written from, which is what makes the brief auditable and what the
--     validator rejected an invented number against. Numbers and labels only: no child, parent or employee is named
--     here, and none is named to the model either.
--   * `text_ar` / `text_en` — the lines, one per line (`\n`-separated), the same things in the same order.
--   * `source` — `ai` when the model's wording was accepted, `template` when the deterministic fallback was used.
--
-- `brief_day` rather than `day`, because `day` is a keyword in H2 (`staff_attendance_days.work_day`'s reason).
--
-- **The unique key is the claim.** `UNIQUE (school_id, user_id, brief_day)` is what `MorningBriefRepository.claim`
-- inserts against with ON CONFLICT DO NOTHING, exactly as `staff_attendance_alerts` does for feature 16's
-- notifications: the run whose insert wrote the row is the only one that calls the model, so two Cloud Run instances
-- waking at 07:15 cannot both generate, and a reload cannot either.
--
-- `school_id` carries the Hibernate `school` filter, as every tenant table does. No foreign key on `user_id`: a
-- leader who leaves the school takes her account with her and yesterday's brief is not worth a cascade.
--
-- Additive and idempotent: running it over QA data adds an empty table and seeds one switch, off.

CREATE TABLE IF NOT EXISTS morning_briefs (
    id         TEXT PRIMARY KEY,
    school_id  TEXT NOT NULL,
    user_id    TEXT NOT NULL,
    brief_day  DATE NOT NULL,
    facts_json TEXT NOT NULL,
    text_ar    TEXT NOT NULL,
    text_en    TEXT NOT NULL,
    source     TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    CONSTRAINT morning_briefs_one_per_day UNIQUE (school_id, user_id, brief_day)
);
CREATE INDEX IF NOT EXISTS morning_briefs_day ON morning_briefs(school_id, brief_day);

INSERT INTO feature_flags (flag_key, description, default_on, rollout_stage, created_at)
SELECT 'morningBrief', 'The Admin and manager Home opens with a short morning brief', FALSE, 'internal', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM feature_flags f WHERE f.flag_key = 'morningBrief');
