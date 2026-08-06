-- Tenant isolation, enforced by PostgreSQL rather than by remembering to write a WHERE clause
-- (§5.2, ADR-002).
--
-- Two roles are involved. Migrations run as the database owner, which is a superuser and therefore
-- exempt from row-level security - that is fine, it only ever runs DDL. The application connects as
-- vestige_app, which is NOSUPERUSER and NOBYPASSRLS, so it is subject to every policy below and
-- cannot opt out of them. FORCE ROW LEVEL SECURITY is set as well, so even owning the table would
-- not help: a bug in a future migration that made vestige_app a table owner still would not open a
-- hole.
--
-- The current tenant travels in a transaction-local GUC, vestige.current_org, set by
-- TenantRoutingDataSource (an AbstractRoutingDataSource) the moment a connection is handed to a
-- transaction. When it is unset, NULLIF turns the empty string into NULL and every comparison is
-- NULL, so the policies deny everything: forgetting to set the tenant fails closed, returning zero
-- rows rather than all of them. See VestigeAdversarialTenancyIT for the test that exercises this
-- with the policy itself removed from the query, not just from the session.

do $$
begin
    if not exists (select 1 from pg_roles where rolname = 'vestige_app') then
        create role vestige_app login password '${appPassword}' nosuperuser nobypassrls nocreatedb nocreaterole;
    else
        alter role vestige_app with login password '${appPassword}' nosuperuser nobypassrls;
    end if;
end
$$;

grant usage on schema public to vestige_app;
grant select, insert, update, delete on all tables in schema public to vestige_app;
alter default privileges in schema public
    grant select, insert, update, delete on tables to vestige_app;

-- ---------------------------------------------------------------------------------------------
-- Tenant-scoped tables
-- ---------------------------------------------------------------------------------------------

-- organization is keyed on id rather than organization_id, so it gets its own policy.
alter table organization enable row level security;
alter table organization force row level security;
create policy organization_tenant_isolation on organization
    using (id = nullif(current_setting('vestige.current_org', true), '')::uuid)
    with check (id = nullif(current_setting('vestige.current_org', true), '')::uuid);

do $$
declare
    tenant_table text;
begin
    foreach tenant_table in array array[
        'api_key',
        'project',
        'branch',
        'analysis_run',
        'analysis_report_payload',
        'issue',
        'finding',
        'triage_event',
        'audit_chain_head',
        'quality_gate',
        'quality_gate_condition',
        'quality_gate_evaluation'
        ]
        loop
            execute format('alter table %I enable row level security', tenant_table);
            execute format('alter table %I force row level security', tenant_table);
            execute format(
                    'create policy %I on %I using (organization_id = nullif(current_setting(''vestige.current_org'', true), '''')::uuid) '
                        || 'with check (organization_id = nullif(current_setting(''vestige.current_org'', true), '''')::uuid)',
                    tenant_table || '_tenant_isolation', tenant_table);
        end loop;
end
$$;

-- ---------------------------------------------------------------------------------------------
-- Control plane: the outbox
-- ---------------------------------------------------------------------------------------------
--
-- The queue is platform infrastructure, not tenant data: the worker has to find the next job
-- across all tenants before it can know which tenant to become. Rather than leave these tables
-- unprotected, the policy admits a second, explicit escalation - vestige.worker = 'on' - which the
-- worker sets with set_config(..., true) so that it lasts exactly one transaction and appears in
-- the code as a deliberate act (OutboxWorker#claimNext). A request served on behalf of a tenant
-- never sets it and therefore sees only its own rows.

do $$
declare
    queue_table text;
begin
    foreach queue_table in array array['analysis_job', 'poison_report']
        loop
            execute format('alter table %I enable row level security', queue_table);
            execute format('alter table %I force row level security', queue_table);
            execute format(
                    'create policy %I on %I using ('
                        || 'coalesce(current_setting(''vestige.worker'', true), ''off'') = ''on'' '
                        || 'or organization_id = nullif(current_setting(''vestige.current_org'', true), '''')::uuid) '
                        || 'with check ('
                        || 'coalesce(current_setting(''vestige.worker'', true), ''off'') = ''on'' '
                        || 'or organization_id = nullif(current_setting(''vestige.current_org'', true), '''')::uuid)',
                    queue_table || '_worker_or_tenant', queue_table);
        end loop;
end
$$;
