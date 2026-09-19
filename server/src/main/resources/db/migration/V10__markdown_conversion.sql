-- CR4 §4: uploads become Markdown on our own machines, and the model reads only that.
--
-- Every source file carries its own conversion ledger. `convert_status` is pending | converting | ready | error;
-- `convert_error_code` is one of encrypted | unsupported | malformed | needs_ocr | ocr_failed | tool_missing | io
-- and is set only in the error state; `convert_method` is anydoc | ocr | text once a file is ready; `markdown_path`
-- points at the `.md` written next to the original in the bucket and `markdown_chars` says how long it is, so the
-- editor can show "Ready · 4 210 characters" without fetching it. Existing QA rows default to `pending`, which is
-- the truth about them: nothing has been converted yet, and the teacher's Retry conversion turns one into Markdown.
ALTER TABLE source_files ADD COLUMN IF NOT EXISTS markdown_path TEXT;
ALTER TABLE source_files ADD COLUMN IF NOT EXISTS convert_status TEXT NOT NULL DEFAULT 'pending';
ALTER TABLE source_files ADD COLUMN IF NOT EXISTS convert_error_code TEXT;
ALTER TABLE source_files ADD COLUMN IF NOT EXISTS convert_method TEXT;
ALTER TABLE source_files ADD COLUMN IF NOT EXISTS markdown_chars INTEGER;

-- The new `convert` step sits between `upload` and `analyze`. A lesson whose analysis is already done keeps it done:
-- that answer was paid for under the old prompt version and re-converting would not change it. Anything earlier gets
-- a pending row, so "Retry and continue" walks through the conversion before it reaches the model.
INSERT INTO lesson_steps (id, lesson_id, step, position, status, attempt, updated_at)
SELECT s.lesson_id || ':convert', s.lesson_id, 'convert', 1,
       CASE WHEN a.status = 'done' THEN 'done' ELSE 'pending' END, 0, CURRENT_TIMESTAMP
FROM (SELECT DISTINCT lesson_id FROM lesson_steps) s
LEFT JOIN lesson_steps a ON a.lesson_id = s.lesson_id AND a.step = 'analyze'
WHERE NOT EXISTS (SELECT 1 FROM lesson_steps c WHERE c.lesson_id = s.lesson_id AND c.step = 'convert');

-- Positions are rewritten from the step name rather than shifted, so re-running this on a half-migrated database
-- lands on the same numbers (`LessonSteps.ORDER` is the same order).
UPDATE lesson_steps SET position = CASE step
    WHEN 'upload' THEN 0 WHEN 'convert' THEN 1 WHEN 'analyze' THEN 2 WHEN 'skills' THEN 3
    WHEN 'generate_L1' THEN 4 WHEN 'generate_L2' THEN 5 WHEN 'generate_L3' THEN 6
    WHEN 'generate_again' THEN 7 WHEN 'panel' THEN 8 ELSE position END;
