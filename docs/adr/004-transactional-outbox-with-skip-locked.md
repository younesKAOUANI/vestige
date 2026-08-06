# ADR-004: Transactional outbox + `SKIP LOCKED` polling

**Status:** Accepted (v1) · **Date:** August 2026 · **Related:** ARCHITECTURE.md §4.2, ADR-005

## Context

`POST /api/v1/runs` accepts a report and must answer quickly — a CI job is blocked on
the response — but parsing, matching, and gate evaluation for a large report can take
seconds. That work has to happen somewhere other than the request thread, which means
the handoff between "accepted" and "processed" has to survive the one failure mode
that matters most: the process crashing, or the database call failing, in the gap
between the two. If acknowledging a submission and actually queuing it for processing
are two separate operations, there is a window in which a client is told `202` for
work that was never queued at all.

## Decision

An `analysis_job` row is written in the **same transaction** as the `analysis_run`
row it queues (`RunIngestionService.submit`) — the transactional outbox pattern.
Acknowledging a submission and enqueuing it either both commit or neither does; there
is no separate "publish to the queue" step that can fail independently of the write
that promised it would happen.

A scheduled worker (`OutboxWorker.pollOnce`, no external scheduler) claims the next
runnable row with:

```sql
select id from analysis_job
where next_attempt_at <= :now
  and (status = 'PENDING' or (status = 'RUNNING' and locked_until < :now))
order by next_attempt_at
limit 1
for update skip locked
```

`FOR UPDATE SKIP LOCKED` is what makes several worker instances safe with no
coordinator at all: a row another worker already holds is stepped over rather than
waited on, so adding workers increases throughput instead of serialising everyone on
the head of the queue. A row whose lease (`locked_until`) has expired becomes
claimable again automatically — how a worker that was killed mid-job releases its
work without anyone intervening.

Retries use exponential backoff with full jitter — `delay = min(2^n · 250ms, 5min) ·
U(0,1)` (`RetryPolicy`), the AWS Architecture Blog's own recommended shape for this
exact problem: multiplying the whole capped delay by a fresh draw spreads retries far
wider than adding a small random offset would, which matters when a single database
blip fails many jobs in the same instant and they must not all retry in the same
instant too. After 5 failed attempts (§4.2's number) a job is marked `DEAD`, a
`PoisonReport` row is written alongside it (kept, not dropped — a report that cannot
be processed is usually a broken analyser integration, and the failure mode to avoid
is CI reporting green while nothing is actually being tracked), and the run moves to
`QUARANTINED`. Nothing retries a dead job automatically; requeuing one is a manual,
auditable action, which is the deliberate design: an automatic infinite-retry poison
job is indistinguishable from a queue that is silently stuck.

The worker's three responsibilities — claim, process, record the outcome — are three
separate `@Transactional` methods (`JobLeaseService`, `RunProcessingService`,
`JobOutcomeService`), not one, orchestrated by a plain, non-transactional
`OutboxWorker.pollOnce()`. If processing and outcome-recording shared one
transaction, a processing failure's rollback would also roll back the very row
that needs to durably record that failure — which would make the retry/quarantine
bookkeeping itself unreliable exactly when it matters most.

## Rejected alternatives

**Kafka / RabbitMQ.** The default answer for "we need a queue," and rejected
specifically *because* it is the default answer reached for without asking whether
this problem needs it. A broker buys ordering and fan-out guarantees Vestige does not
need (one worker pool consuming one queue of independent jobs, no pub/sub, no
multiple-consumer-group topology) at the cost of a second stateful system to run,
monitor, and keep consistent with the database — including solving this exact
transactional-outbox problem a second time, since a broker send and a database commit
are still two separate operations that can fail independently of each other unless a
change-data-capture pipeline is added on top. For a single-writer job queue backed by
a database Vestige already has open, that is a lot of infrastructure to buy back
guarantees Postgres already gives for free via `SKIP LOCKED`.

**Spring `@Async`.** The simplest possible thing — fire a method call onto a thread
pool from the request handler — and the fastest way to lose work outright: an
in-memory task queue does not survive the process restarting, has no visibility for
"how many jobs are pending," and turns a poison message into a silent, repeating log
line instead of a queryable, quarantinable row. It fails the "an outbox row is written
in the same transaction as the run" guarantee at the most basic level, since there is
no row at all.

**`pg_cron`.** Would move the polling loop into the database itself rather than an
application thread, and was considered for exactly that reason — one less moving
part. Rejected because it is a Postgres extension that has to be provisioned and
enabled per environment (not guaranteed available on every managed Postgres offering
this might eventually deploy to), and because the actual processing logic — parsing,
matching, gate evaluation, calling `IssueTrackingService` — is regular Java that
belongs in the application, not in a SQL function; `pg_cron` would only ever be
useful for the polling trigger itself, and `@Scheduled` already does that with zero
extra infrastructure.

## Consequences

- The queue is exactly as durable as Postgres is, which is the same durability
  guarantee every other piece of tenant data already has — no separate backup/restore
  story for "the queue" as distinct from "the database."
- Throughput is bounded by how many worker instances poll the same table; that is a
  fine trade for v1's expected volume and is the honest limit stated in the README
  rather than hidden behind a broker's throughput numbers this project does not need.
- The three-transaction split (claim / process / record-outcome) means a crash between
  "processing finished" and "outcome recorded" leaves a job `RUNNING` past its lease,
  which is exactly the case the expired-lease reclaim clause in the claim query exists
  to handle — the job is retried, and because `RunProcessingService` is itself
  transactional and idempotent-on-replay (the matcher is deterministic, §3.3), a
  reclaimed job re-running from the top is safe, not just tolerated.

## What would change our mind

A real need for fan-out to multiple independent consumer types (not just "more worker
threads doing the same job") — for example, a future feature that needs every
completed run published to an external analytics pipeline *in addition to* Vestige's
own processing — would be a genuine argument for a broker, because that is the
problem brokers are actually built for. Nothing in v1's scope needs that.
