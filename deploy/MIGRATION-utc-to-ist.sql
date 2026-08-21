-- Shifts the five wall-clock timestamp columns from UTC to Asia/Kolkata.
--
-- The stack ran TZ=UTC because the data was migrated from Render, which ran UTC, and
-- created_at is a LocalDateTime — a wall-clock reading with no zone attached. The CRM cannot
-- stay on UTC: opening hours are stored as 10:00-19:00 Parbhani time, and a UTC clock reads
-- 10am IST as 04:30, so every response-time figure and every follow-up date would be wrong.
--
-- Run this ONCE, with the backend stopped, immediately before deploying the CRM. Any rows
-- written between now and then are shifted too, because it moves every row.

BEGIN;

-- Refuses a second run. Shifting twice would move everything another five and a half hours,
-- and the damage would be invisible until somebody noticed dates drifting.
CREATE TABLE IF NOT EXISTS schema_migrations (
    name        text PRIMARY KEY,
    applied_at  timestamptz NOT NULL DEFAULT now()
);
INSERT INTO schema_migrations (name) VALUES ('utc-to-ist-timestamps');
-- If that row exists the insert violates the primary key, the transaction aborts, and nothing
-- below runs. That is the intended behaviour, not an error to work around.

UPDATE hiring           SET created_at = created_at + interval '5 hours 30 minutes';
UPDATE leads            SET created_at = created_at + interval '5 hours 30 minutes';
UPDATE mentors          SET created_at = created_at + interval '5 hours 30 minutes';
UPDATE messages         SET created_at = created_at + interval '5 hours 30 minutes';
UPDATE placed_students  SET created_at = created_at + interval '5 hours 30 minutes';

COMMIT;
