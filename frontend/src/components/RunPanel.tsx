import { type FormEvent, useState } from 'react'
import { useGateConfig, useRun } from '../hooks/queries'
import type { GateConditionOutcome, GateOutcome } from '../lib/types'
import { GateStatusBadge } from './Badges'

/**
 * Vestige v1 has no "list runs for a project" endpoint (§8) - a run is looked up by the id the
 * ingestion response handed back. This panel is the honest reflection of that: paste a run id,
 * see its status and (once the worker has reached COMPLETED) the quality gate outcome embedded
 * in it.
 */
export function RunPanel() {
  const [runIdInput, setRunIdInput] = useState('')
  const [submittedRunId, setSubmittedRunId] = useState<string | null>(null)
  const { data: run, isLoading, isError, error } = useRun(submittedRunId)

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    setSubmittedRunId(runIdInput.trim() || null)
  }

  return (
    <section className="run-panel">
      <h2>Look up a run</h2>
      <form className="inline-form" onSubmit={handleSubmit}>
        <input
          type="text"
          placeholder="run id"
          value={runIdInput}
          onChange={(event) => setRunIdInput(event.target.value)}
        />
        <button type="submit">Load run</button>
      </form>

      {isLoading && <p className="empty-state">Loading run…</p>}
      {isError && (
        <p className="empty-state error">{error instanceof Error ? error.message : 'Run not found'}</p>
      )}

      {run && (
        <div className="run-details">
          <dl className="run-summary">
            <div>
              <dt>Status</dt>
              <dd>{run.status}</dd>
            </div>
            <div>
              <dt>Analyser</dt>
              <dd>
                {run.analyserName} {run.analyserVersion}
              </dd>
            </div>
            <div>
              <dt>Commit</dt>
              <dd>
                <code>{run.commitSha}</code>
              </dd>
            </div>
            <div>
              <dt>Findings parsed</dt>
              <dd>{run.findingCount}</dd>
            </div>
            {run.failureReason ? (
              <div>
                <dt>Failure reason</dt>
                <dd>{run.failureReason}</dd>
              </div>
            ) : null}
          </dl>

          {run.gateResult ? (
            <GateResultView outcome={run.gateResult} />
          ) : (
            <p className="empty-state">
              This run has not reached the quality gate yet (current status: {run.status}).
            </p>
          )}

          <GateConfigPreview projectId={run.projectId} />
        </div>
      )}
    </section>
  )
}

function GateResultView({ outcome }: { outcome: GateOutcome }) {
  return (
    <div className="gate-result">
      <div className="gate-result-header">
        <h3>{outcome.gateName}</h3>
        <GateStatusBadge status={outcome.status} />
      </div>
      <ul className="gate-conditions">
        {outcome.conditions.map((condition) => (
          <GateConditionRow key={condition.condition.type} condition={condition} />
        ))}
      </ul>
    </div>
  )
}

function GateConditionRow({ condition }: { condition: GateConditionOutcome }) {
  return (
    <li className={`gate-condition gate-condition-${condition.status.toLowerCase()}`}>
      <div className="gate-condition-summary">
        <GateStatusBadge status={condition.status} />
        <span className="gate-condition-type">{condition.condition.type}</span>
        <span className="gate-condition-value">
          {condition.actualValue} / {condition.threshold}
        </span>
      </div>
      {condition.offendingIssueIds.length > 0 ? (
        <ul className="gate-offenders">
          {condition.offendingIssueIds.map((issueId) => (
            <li key={issueId}>
              <code>{issueId}</code>
            </li>
          ))}
        </ul>
      ) : null}
    </li>
  )
}

/** Shows the gate as configured *now*, which may differ from the thresholds baked into an older run's result. */
function GateConfigPreview({ projectId }: { projectId: string }) {
  const { data, isLoading, isError } = useGateConfig(projectId)

  if (isLoading) {
    return <p className="empty-state">Loading gate configuration…</p>
  }
  if (isError || !data) {
    return null
  }

  return (
    <div className="gate-config-preview">
      <h4>Current gate configuration — {data.name}</h4>
      <ul>
        {data.conditions.map((condition) => (
          <li key={condition.type}>{condition.description}</li>
        ))}
      </ul>
    </div>
  )
}
