-- A lesson is a pipeline of steps, each with its own status, so a failure is retried at the step that failed.
CREATE TABLE lesson_steps (
    id TEXT PRIMARY KEY,                       -- <lesson_id>:<step>
    lesson_id TEXT NOT NULL REFERENCES lessons(id) ON DELETE CASCADE,
    step TEXT NOT NULL,                        -- upload | analyze | skills | generate_L1 | generate_L2 | generate_L3 | generate_again | panel
    position INTEGER NOT NULL,
    status TEXT NOT NULL,                      -- pending | running | done | error
    attempt INTEGER NOT NULL DEFAULT 0,
    error_code TEXT,
    error_message TEXT,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX lesson_steps_lesson ON lesson_steps(lesson_id, position);
ALTER TABLE lessons ADD COLUMN current_step TEXT;
