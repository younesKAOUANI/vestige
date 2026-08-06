import { useVerifyAuditChain } from '../hooks/queries'

/**
 * §6/§9: every triage decision is appended to a hash chain, and {@code GET /api/v1/audit/verify}
 * walks the whole thing, recomputing each entry's hash from its predecessor. This panel is a thin
 * wrapper around that one endpoint - there is deliberately no way to "fix" a broken chain from the
 * UI, because a broken chain is evidence, not a bug to dismiss.
 */
export function AuditPanel() {
  const mutation = useVerifyAuditChain()

  return (
    <section className="audit-panel">
      <h2>Audit chain integrity</h2>
      <p>
        Every triage decision is appended to a hash-chained log: each entry&apos;s hash covers the
        previous entry&apos;s hash, so altering or deleting a row after the fact breaks the chain
        from that point on. This walks the full chain and recomputes every hash from scratch.
      </p>
      <button type="button" onClick={() => mutation.mutate()} disabled={mutation.isPending}>
        {mutation.isPending ? 'Verifying…' : 'Verify audit chain'}
      </button>

      {mutation.isError ? (
        <p className="empty-state error">
          {mutation.error instanceof Error ? mutation.error.message : 'Verification failed'}
        </p>
      ) : null}

      {mutation.isSuccess ? (
        <div className={mutation.data.intact ? 'audit-result audit-intact' : 'audit-result audit-broken'}>
          {mutation.data.intact ? (
            <p>
              Chain intact — {mutation.data.length ?? 0} {mutation.data.length === 1 ? 'event' : 'events'}{' '}
              verified, no tampering detected.
            </p>
          ) : (
            <p>Chain broken at entry #{mutation.data.brokenAtIndex}. The log has been tampered with.</p>
          )}
        </div>
      ) : null}
    </section>
  )
}
