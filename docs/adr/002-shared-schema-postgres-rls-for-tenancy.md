# ADR-002: Shared schema + Postgres RLS for tenancy

**Status:** Accepted (v1) · **Date:** August 2026 · **Related:** ARCHITECTURE.md §5, `V2__row_level_security.sql`

## Context

Vestige is multi-tenant from day one: every organisation's issues, findings, and
audit log must be invisible to every other organisation, with no exceptions. This is
not a performance requirement or a UX nicety — it is a security requirement, and
`ARCHITECTURE.md` §5.2 states the stakes plainly: "application-layer tenancy filters
are one forgotten `WHERE` clause away from a cross-tenant data leak." A repository
method, a hand-written report query, a new endpoint added in a hurry six months from
now — any one of them can omit the tenant predicate, compile cleanly, pass code
review, and leak data in production. The question this ADR answers is not "how do we
filter by tenant" but "how do we make omitting the filter *impossible*, not just
unlikely."

## Decision

One shared schema, one set of tables, every tenant-scoped table carrying
`organization_id` directly (not reached through a join — V1's schema comment is
explicit that this is a deliberate denormalisation, because a row-level security
policy has to be able to decide whether the current tenant may see a row *from the
row alone*; a policy that needed a join to decide would be both slower and easier to
get subtly wrong). Every tenant-scoped table is created with:

```sql
alter table issue enable row level security;
alter table issue force row level security;

create policy tenant_isolation on issue
  using (organization_id = current_setting('vestige.current_org')::uuid);
```

`FORCE ROW LEVEL SECURITY` is the detail that makes this structural rather than
conventional: without it, Postgres exempts the table's *owner* from its own RLS
policies by default, which would mean the exact role the application connects as is
the one role RLS does not protect against. A Spring `AbstractRoutingDataSource`
wrapper (`TenantRoutingDataSource`) issues `SET LOCAL vestige.current_org = ?` from
the authenticated principal at the start of every transaction, and `TenantContext`
fails closed: no context set means the setting resolves to nothing a valid UUID cast
can match, so a query that forgets to scope by tenant does not see *everyone's* rows —
it sees *no* rows.

The database connects as `vestige_app`, an ordinary (non-superuser) role — necessary,
because Postgres never applies row-level security to a superuser regardless of
`FORCE ROW LEVEL SECURITY`; that pitfall is exactly why the test suite (below)
does not use Spring Boot's `@ServiceConnection` for its Testcontainers wiring, which
would default to the container's own superuser and defeat the entire point of this ADR
without anyone noticing.

## Rejected alternatives

**Schema-per-tenant** (one Postgres schema per organisation, same table shapes,
`search_path` switched per request). Gives strong isolation without relying on every
query author remembering a predicate, which is a real point in its favour. Rejected
for two reasons: migrations become an N-schema fan-out — Flyway would need to run
every migration once per tenant schema, turning a single `flyway migrate` into an
operation whose cost scales with tenant count — and cross-tenant operational queries
(a support engineer answering "which organisations are on analyser version X")
become a `UNION ALL` across every schema instead of one `WHERE`. At the tenant counts
a tool like this realistically has (tens to low thousands of organisations, not
millions of end-users), RLS gives the same isolation guarantee without either cost.

**Database-per-tenant.** The strongest possible isolation — a compromised connection
string for one tenant cannot even see that other tenants' schemas exist — but the
operational cost is the worst of the three by a wide margin: one connection pool, one
migration run, and one backup/restore job per tenant, which does not scale
operationally for a product whose entire pitch is "point your CI at us in five
minutes." This is the right answer for a small number of very large, contractually
isolated customers; it is the wrong answer for Vestige's expected shape.

**Application-layer filtering** (every repository method takes an `organizationId`
parameter and appends `WHERE organization_id = ?`, enforced by convention and code
review). The simplest to build and the one most tools ship with. Rejected because it
is exactly the risk this ADR exists to close: it is one missed parameter, one new
query written under deadline pressure, one refactor that drops a clause, away from a
leak — a *conventional* guarantee, not a structural one, and §5.2 is explicit that
conventional is not good enough for this property specifically.

## Consequences

- Every tenant-scoped table needs the RLS boilerplate (`ENABLE`, `FORCE`,
  `CREATE POLICY`) added explicitly in its migration — there is no ambient default
  that applies it automatically to a table someone forgets to think about. `V2` adds
  it table-by-table for exactly this reason: it forces the decision to be visible in
  the diff.
- `TenantRoutingDataSource` and `TenantContext` become load-bearing infrastructure
  that every request path depends on; getting the context set/cleared correctly
  across the ingestion request thread *and* the outbox worker thread (which is not a
  request at all) is real engineering weight, documented on `WorkerContext`.
- The test suite includes a deliberate adversarial case (`VestigeAdversarialTenancyIT`):
  a query with **no** tenant predicate in it at all — `IssueRepository.findAll()` — is
  asserted to still return only the calling tenant's rows when switching
  `TenantContext` between two seeded organisations, and to return *nothing* when no
  context is set. That test is the actual proof this ADR's central claim holds; without
  it, this document would just be an assertion.

## What would change our mind

A single tenant whose contract requires physical data isolation (their own database,
their own backup schedule, provable to their auditors) would need database-per-tenant
for that one tenant specifically — that is an additive deployment concern, not a
reason to abandon RLS for everyone else. Nothing observed in v1 argues for
schema-per-tenant at any point in between; it inherits RLS's migration discipline
requirements without any of its structural guarantee over application-layer filtering.
