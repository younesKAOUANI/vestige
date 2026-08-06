-- Enforces §6's append-only guarantee at the one layer an application bug (or a compromised
-- application credential) cannot bypass: the database itself.
--
-- This is deliberately a hard failure rather than something the application layer merely avoids
-- doing. Application code changes; a trigger that raises on every UPDATE and DELETE does not need
-- to be remembered by whoever writes the next service class that touches triage_event.
--
-- vestige_app needs no special exemption here - unlike RLS, there is no legitimate reason for
-- *any* role to modify a row after it is written, including a future admin tool. The one
-- correction available if a triage decision was wrong is a new TriageEvent that supersedes it,
-- which preserves the record rather than erasing it. That is a real limitation, not swept under
-- the rug: see README "Threat model" and ADR-006 "What would change our mind".

create or replace function vestige_forbid_triage_event_mutation()
    returns trigger
    language plpgsql
as
$$
begin
    raise exception
        'triage_event is append-only: % of row % is not permitted (issue_id=%, sequence_number=%)',
        tg_op, old.id, old.issue_id, old.sequence_number
        using errcode = '23001'; -- restrict_violation
end;
$$;

create trigger triage_event_append_only
    before update or delete
    on triage_event
    for each row
execute function vestige_forbid_triage_event_mutation();
