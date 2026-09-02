-- Backfill finished_at for series that were finished before V3 added the column
-- (their finished_at is NULL, so /finished shows "—").
--
-- We have no recorded completion time for them, so approximate with the latest
-- occurrence activity: max over the event's occurrences of greatest(done_at, fired_at).
-- Postgres GREATEST ignores NULLs and occurrence.fired_at is NOT NULL, so that inner
-- value is always present; if the event has no occurrences at all, fall back to its
-- own created_at.
update event e
set finished_at = coalesce(
        (select max(greatest(o.done_at, o.fired_at))
         from occurrence o
         where o.event_id = e.id),
        e.created_at)
where e.status = 'FINISHED'
  and e.finished_at is null;
