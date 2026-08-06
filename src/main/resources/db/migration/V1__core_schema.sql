-- Vestige core schema.
--
-- Every tenant-scoped table carries organization_id explicitly rather than relying on a join to
-- reach the tenant. That is a deliberate denormalisation: the row-level security policies added in
-- V2 have to be able to decide, from the row alone, whether the current tenant may see it. A policy
-- that needed a join would be both slow and easy to get wrong (ADR-002).

create table organization (
    id          uuid primary key,
    slug        text        not null unique,
    name        text        not null,
    created_at  timestamptz not null default now()
);

comment on table organization is 'A tenant. Everything else hangs off this.';

create table api_key (
    id              uuid primary key,
    organization_id uuid        not null references organization (id) on delete cascade,
    name            text        not null,
    key_prefix      text        not null unique,
    key_hash        text        not null,
    created_at      timestamptz not null default now(),
    last_used_at    timestamptz,
    revoked_at      timestamptz
);

comment on column api_key.key_prefix is
    'Non-secret lookup handle. The secret itself is only ever stored as key_hash.';

create index api_key_organization_idx on api_key (organization_id);

create table project (
    id              uuid primary key,
    organization_id uuid        not null references organization (id) on delete cascade,
    provider        text        not null,
    owner           text        not null,
    name            text        not null,
    default_branch  text        not null default 'main',
    created_at      timestamptz not null default now(),
    constraint project_unique_per_organization unique (organization_id, provider, owner, name)
);

comment on column project.provider is 'github|gitlab|bitbucket (§2.2). Only github has a working adapter in v1 - §11.';

-- A first-class row, not a text column on analysis_run: §2.2 lists it as its own entity because
-- feature branches inherit their matching baseline from the reference branch, and that
-- relationship (baseline_branch_id) has to be recorded somewhere that outlives any one run.
create table branch (
    id                 uuid primary key,
    organization_id    uuid        not null references organization (id) on delete cascade,
    project_id         uuid        not null references project (id) on delete cascade,
    name               text        not null,
    is_reference       boolean     not null default false,
    baseline_branch_id uuid references branch (id),
    created_at         timestamptz not null default now(),
    constraint branch_unique_per_project unique (project_id, name)
);

comment on column branch.is_reference is
    'True for the branch new feature branches baseline against (usually the project''s default
     branch, e.g. main).';

create index branch_project_idx on branch (project_id);

create table analysis_run (
    id               uuid primary key,
    organization_id  uuid        not null references organization (id) on delete cascade,
    project_id       uuid        not null references project (id) on delete cascade,
    branch_id        uuid        not null references branch (id) on delete cascade,
    commit_sha       text        not null,
    base_commit_sha  text,
    analyser_name    text        not null,
    analyser_version text        not null,
    report_digest    text        not null,
    idempotency_key  text,
    status           text        not null,
    failure_reason   text,
    attempt_count    integer     not null default 0,
    finding_count    integer     not null default 0,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    completed_at     timestamptz
);

comment on column analysis_run.status is
    'RECEIVED -> PARSING -> MATCHING -> COMPLETE | FAILED | QUARANTINED (§2.2). PARSING/MATCHING
     are only ever visible to a concurrent reader if the process crashes mid-transaction; a run
     that commits goes straight from RECEIVED to COMPLETE (or back to RECEIVED for the outbox
     worker to retry, or to QUARANTINED - see analysis_job below).';

-- sha256(project || commit || analyser || report_digest), the natural key §4.1 describes. Two CI
-- jobs racing on the same commit both try to insert this; exactly one wins.
create unique index analysis_run_natural_key_idx
    on analysis_run (project_id, commit_sha, analyser_name, report_digest);

-- A client-supplied Idempotency-Key is a second, independent way to say "this is the same
-- submission", scoped to the tenant that supplied it.
create unique index analysis_run_idempotency_key_idx
    on analysis_run (organization_id, idempotency_key)
    where idempotency_key is not null;

create index analysis_run_project_recent_idx on analysis_run (project_id, created_at desc);

-- The matcher's baseline for a new run is "the most recently completed run on this branch".
create index analysis_run_branch_recent_idx on analysis_run (branch_id, status, created_at desc);

-- The uploaded bytes, kept so the worker can parse them asynchronously (§4.2). In production this
-- would be an object-storage key instead of the bytes themselves - see README "Roadmap". Plain
-- bytea rather than jsonb: nothing ever queries into this document's structure through SQL, so
-- jsonb would only add parse/validate cost on a payload that can be 200 MB, for no benefit.
create table analysis_report_payload (
    analysis_run_id uuid primary key references analysis_run (id) on delete cascade,
    organization_id uuid  not null references organization (id) on delete cascade,
    sarif           bytea not null,
    received_at     timestamptz not null default now()
);

-- ---------------------------------------------------------------------------------------------
-- The Finding / Issue split (§2.1, ADR-003). A Finding is immutable: one raw result from one
-- analyser in one run. An Issue is the mutable, cross-run claim a Finding is matched into.
-- ---------------------------------------------------------------------------------------------

create table issue (
    id                    uuid primary key,
    organization_id       uuid        not null references organization (id) on delete cascade,
    project_id            uuid        not null references project (id) on delete cascade,
    branch_id             uuid        not null references branch (id) on delete cascade,
    rule_id               text        not null,
    severity              text        not null,
    message               text        not null,
    file_path             text        not null,
    symbol_path           text,
    start_line            integer     not null,
    status                text        not null,
    first_seen_run_id     uuid        not null references analysis_run (id),
    last_seen_run_id      uuid        not null references analysis_run (id),
    introduced_at_commit  text        not null,
    created_at            timestamptz not null default now(),
    updated_at            timestamptz not null default now()
);

comment on table issue is
    'The tracked claim a Finding is matched into (§2.1). Mutable: status moves as the matcher and
     triage act on it, but rule_id/file_path/symbol_path/start_line always reflect the most recent
     sighting, not the first - matching always compares the head commit against the previous one.';

create index issue_project_branch_status_idx on issue (project_id, branch_id, status);
create index issue_branch_rule_idx on issue (branch_id, rule_id);
create index issue_first_seen_run_idx on issue (first_seen_run_id);
create index issue_last_seen_run_idx on issue (last_seen_run_id);

create table finding (
    id               uuid primary key,
    -- Tie-break ordinal for the matcher (§3.3: "lowest finding id"). A UUID has no meaningful
    -- ordering; this bigint identity column gives the matcher a total, reproducible order without
    -- making the public id sequential (and therefore guessable/enumerable).
    seq              bigint generated always as identity,
    organization_id  uuid             not null references organization (id) on delete cascade,
    analysis_run_id  uuid             not null references analysis_run (id) on delete cascade,
    issue_id         uuid             references issue (id) on delete cascade,
    rule_id          text             not null,
    severity         text             not null,
    message          text             not null,
    file_path        text             not null,
    symbol_path      text,
    start_line       integer          not null,
    end_line         integer          not null,
    start_column     integer          not null default 0,
    end_column       integer          not null default 0,
    line_snippet     text,
    identity_fp      text,
    context_fp       text,
    weak_fp          text             not null,
    match_rung       text,
    created_at       timestamptz      not null default now()
);

comment on column finding.issue_id is
    'Null immediately after the streaming parse (§4.3); set exactly once, by the matcher, before
     the run''s transaction commits - see IssueTrackingService. Never revised after that, which is
     what "immutable" means for a Finding in practice: the fact of which claim it supports is
     decided once and never replayed.';
comment on column finding.match_rung is
    'IDENTITY | CONTEXT | WEAK | NEW - which rung of §3.3''s ladder produced this finding''s issue
     link, surfaced in the UI so a reviewer can see why two runs were considered the same issue.';

create index finding_run_idx on finding (analysis_run_id);
create index finding_issue_idx on finding (issue_id);
create index finding_identity_fp_idx on finding (identity_fp) where identity_fp is not null;
create index finding_context_fp_idx on finding (context_fp) where context_fp is not null;
create index finding_weak_fp_idx on finding (weak_fp);

-- ---------------------------------------------------------------------------------------------
-- Tamper-evident triage log (§6). One hash chain per organisation - not per issue - so
-- audit_chain_head is the row every append serialises on (see TriageEventAppender).
-- ---------------------------------------------------------------------------------------------

create table triage_event (
    id              uuid primary key,
    organization_id uuid        not null references organization (id) on delete cascade,
    issue_id        uuid        not null references issue (id) on delete cascade,
    sequence_number bigint      not null,
    actor           text        not null,
    from_status     text        not null,
    to_status       text        not null,
    justification   text,
    occurred_at     timestamptz not null,
    prev_hash       text        not null,
    entry_hash      text        not null,
    constraint triage_event_sequence_unique unique (organization_id, sequence_number)
);

comment on table triage_event is
    'Append-only, hash-chained audit log (§6). entry_hash = SHA-256(prev_hash || canonical_json
     {issue_id, actor, from_status, to_status, justification, occurred_at}), chained per
     organisation. Editing or deleting any row invalidates it and every row after it; V4 adds the
     trigger that makes UPDATE/DELETE raise outright.';

create index triage_event_issue_idx on triage_event (issue_id, sequence_number);

-- The current tail of each organisation's chain. Appending an event means SELECT ... FOR UPDATE on
-- this row first (TriageEventAppender), which serialises concurrent triage within one organisation
-- without blocking any other organisation.
create table audit_chain_head (
    organization_id uuid primary key references organization (id) on delete cascade,
    length          bigint      not null default 0,
    last_hash       text        not null,
    updated_at      timestamptz not null default now()
);

-- ---------------------------------------------------------------------------------------------
-- Quality gates (§7)
-- ---------------------------------------------------------------------------------------------

create table quality_gate (
    id              uuid primary key,
    organization_id uuid        not null references organization (id) on delete cascade,
    project_id      uuid        not null references project (id) on delete cascade,
    name            text        not null,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    constraint quality_gate_project_unique unique (project_id)
);

create table quality_gate_condition (
    id              uuid    primary key,
    organization_id uuid    not null references organization (id) on delete cascade,
    quality_gate_id uuid    not null references quality_gate (id) on delete cascade,
    condition_type  text    not null,
    threshold       bigint  not null default 0,
    position        integer not null default 0
);

create index quality_gate_condition_gate_idx on quality_gate_condition (quality_gate_id);

-- One evaluation per run, stored as the full computed document (result_json) plus the flat columns
-- that are actually queried on their own. §9 calls for JSONB for structured payloads Vestige does
-- not need to filter inside SQL; a computed, once-written gate verdict is exactly that.
create table quality_gate_evaluation (
    id              uuid        primary key,
    organization_id uuid        not null references organization (id) on delete cascade,
    project_id      uuid        not null references project (id) on delete cascade,
    analysis_run_id uuid        not null references analysis_run (id) on delete cascade,
    gate_name       text        not null,
    status          text        not null,
    result_json     jsonb       not null,
    evaluated_at    timestamptz not null default now(),
    constraint quality_gate_evaluation_once_per_run unique (analysis_run_id)
);

create index quality_gate_evaluation_project_idx on quality_gate_evaluation (project_id, evaluated_at desc);

-- ---------------------------------------------------------------------------------------------
-- Control plane: the transactional outbox (§4.2, ADR-004). Not tenant data - see V2 for how it
-- is guarded.
-- ---------------------------------------------------------------------------------------------

create table analysis_job (
    id              uuid primary key,
    organization_id uuid        not null references organization (id) on delete cascade,
    analysis_run_id uuid        not null references analysis_run (id) on delete cascade,
    status          text        not null,
    attempt_count   integer     not null default 0,
    next_attempt_at timestamptz not null default now(),
    locked_until    timestamptz,
    last_error      text,
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    constraint analysis_job_once_per_run unique (analysis_run_id)
);

comment on table analysis_job is
    'The outbox row (§4.2, ADR-004): written in the same transaction as the analysis_run it queues,
     so acknowledging a submission and enqueuing it either both happen or neither does. The worker
     claims rows with FOR UPDATE SKIP LOCKED (AnalysisJobRepository.claimNextRunnable).';

-- The polling query orders by next_attempt_at among runnable rows; this index is what keeps it
-- from degenerating into a sequential scan once the queue has any history in it.
create index analysis_job_runnable_idx on analysis_job (status, next_attempt_at);

create table poison_report (
    id              uuid primary key,
    organization_id uuid        not null references organization (id) on delete cascade,
    analysis_run_id uuid        not null references analysis_run (id) on delete cascade,
    attempt_count   integer     not null,
    last_error      text        not null,
    created_at      timestamptz not null default now()
);

comment on table poison_report is
    'Reports that exhausted their attempts (§4.2). Kept rather than dropped so that a bad analyser
     integration is visible instead of silently losing data. Requeue is a manual, audited action -
     see AnalysisJobRepository / RunAdminService.';

create index poison_report_organization_idx on poison_report (organization_id);
