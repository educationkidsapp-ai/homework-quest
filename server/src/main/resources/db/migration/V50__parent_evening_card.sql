-- Feature 28 `parent evening card` (docs/features/28-parent-evening-card.md): from 16:00 school time, one card per
-- child about today — what she learned and how it went, in two or three sentences in Arabic and in English.
--
-- 1. The `parentEveningCard` flag, off until an Admin turns it on for a school (`INSERT … WHERE NOT EXISTS`, as V46).
--
-- 2. `evening_cards` — one card per child and day, which is what `evening_cards_once` enforces: the endpoint
--    generates the card on the first request after 16:00 and the 18:00 job generates whatever is still missing, so
--    two instances racing on one child must not be able to write two cards or send two pushes. `card_day` rather
--    than `day`, which is a datetime field keyword (the reason V40 called its own column `work_day`), and it is the
--    school's own date: the whole feature runs on `schools.timezone`.
--
--    `facts_json` is what the card was written from — the day's homework titles, the parent panel's objectives, the
--    `parentTip` of the stop to practise, and the child's own result as a word. **No score and no percentage**, which
--    is the owner's rule for this feature, and nothing about the child beyond her own results. It is kept so that a
--    card the owner questions can be read back against the facts it came from.
--
--    `text_ar` / `text_en` hold the placeholder `{child}` rather than the child's name — the model is never told it —
--    and `source` is `model` or `template`, the fixed bilingual fallback used when an answer broke a rule.
--
-- `school_id` carries the Hibernate `school` filter, as every tenant table does. Additive and idempotent: re-running
-- it over QA data adds a switch that is off and an empty table.

INSERT INTO feature_flags (flag_key, description, default_on, rollout_stage, created_at)
SELECT 'parentEveningCard', 'The parent''s evening card about one child''s day', FALSE, 'internal', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM feature_flags f WHERE f.flag_key = 'parentEveningCard');

CREATE TABLE IF NOT EXISTS evening_cards (
    id         TEXT PRIMARY KEY,
    school_id  TEXT NOT NULL REFERENCES schools(id),
    child_id   TEXT NOT NULL REFERENCES children(id),
    card_day   DATE NOT NULL,
    facts_json TEXT NOT NULL,
    text_ar    TEXT NOT NULL,
    text_en    TEXT NOT NULL,
    source     VARCHAR(16) NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS evening_cards_once ON evening_cards(school_id, child_id, card_day);
