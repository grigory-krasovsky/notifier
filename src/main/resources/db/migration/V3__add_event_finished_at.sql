-- When a series moved to FINISHED, so /finished can show the completion date.
-- Nullable: still null for active/paused events (and any pre-existing finished rows).
alter table event
    add column finished_at timestamptz;
