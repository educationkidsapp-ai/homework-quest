-- Feature 21 `admin-today-home` (docs/features/21-admin-today-home.md): the Admin's and the department manager's Home
-- becomes one screen of what needs a decision today — today's staff attendance, the requests waiting for her, the
-- complaints open for more than a day, her unread messages, and the old counts in a strip at the bottom.
--
-- It adds no table and no column: every card is read from what features 16, 17, 18, 12 and 04 already store, through
-- the services that already narrow those rows to the caller. The one thing to seed is the switch, off until an Admin
-- turns it on for a school (`INSERT … WHERE NOT EXISTS`, as V41), so running this on QA data changes nothing else.

INSERT INTO feature_flags (flag_key, description, default_on, rollout_stage, created_at)
SELECT 'todayHome', 'The Admin and manager Home leads with what needs a decision today', FALSE, 'internal', CURRENT_TIMESTAMP
WHERE NOT EXISTS (SELECT 1 FROM feature_flags f WHERE f.flag_key = 'todayHome');
