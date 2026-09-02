-- Which event the user is currently editing via /manage, so text/calendar edit
-- steps (name, interval, time) know their target. Cleared when the edit ends.
-- Loose bigint (no FK): a stale id just fails to resolve and the edit no-ops,
-- and it never blocks event deletion.
alter table app_user
    add column editing_event_id bigint;
