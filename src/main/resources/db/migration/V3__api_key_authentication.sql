-- Authentication has a chicken-and-egg problem with row-level security: to know which tenant the
-- request belongs to, Vestige must read a row from api_key, but api_key is itself protected by a
-- policy keyed on the tenant it has not established yet.
--
-- The usual answers are both bad. Leaving api_key unprotected means a compromised tenant session
-- can enumerate every key in the installation. Connecting as a privileged role for the lookup means
-- the application holds credentials that bypass RLS entirely, which defeats the point of having it.
--
-- Instead, the lookup is a SECURITY DEFINER function owned by the migration role. vestige_app may
-- execute it but cannot read the table it reads, and the function only accepts a key prefix and
-- only returns the row for that prefix - so it cannot be used to enumerate anything. The secret
-- comparison stays in the application, where it is done in constant time; the function deliberately
-- does not take the hash as an argument, because comparing it in SQL would give that up.

create or replace function vestige_lookup_api_key(p_key_prefix text)
    returns table
            (
                api_key_id      uuid,
                organization_id uuid,
                key_hash        text
            )
    language sql
    stable
    security definer
    set search_path = public
as
$$
select k.id, k.organization_id, k.key_hash
from api_key k
where k.key_prefix = p_key_prefix
  and k.revoked_at is null
$$;

create or replace function vestige_touch_api_key(p_api_key_id uuid)
    returns void
    language sql
    security definer
    set search_path = public
as
$$
update api_key set last_used_at = now() where id = p_api_key_id
$$;

revoke all on function vestige_lookup_api_key(text) from public;
revoke all on function vestige_touch_api_key(uuid) from public;
grant execute on function vestige_lookup_api_key(text) to vestige_app;
grant execute on function vestige_touch_api_key(uuid) to vestige_app;
