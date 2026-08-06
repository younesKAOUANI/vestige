import { type FormEvent, useState } from 'react'
import { useIssueHistory, useTriageIssue } from '../hooks/queries'
import type { FindingSighting, IssueHistory, IssueStatus, TriageEntry } from '../lib/types'
import { SeverityBadge, StatusBadge } from './Badges'

interface IssueDetailProps {
  projectId: string
  issueId: string
}

type TimelineEntry =
  | { kind: 'finding'; at: string; finding: FindingSighting }
  | { kind: 'triage'; at: string; event: TriageEntry }

function buildTimeline(history: IssueHistory): TimelineEntry[] {
  const entries: TimelineEntry[] = [
    ...history.findings.map((finding): TimelineEntry => ({ kind: 'finding', at: finding.createdAt, finding })),
    ...history.triageEvents.map((event): TimelineEntry => ({ kind: 'triage', at: event.occurredAt, event })),
  ]
  return entries.sort((a, b) => a.at.localeCompare(b.at))
}

export function IssueDetail({ projectId, issueId }: IssueDetailProps) {
  const { data: history, isLoading, isError, error } = useIssueHistory(issueId)

  if (isLoading) {
    return <p className="empty-state">Loading issue…</p>
  }
  if (isError || !history) {
    return (
      <p className="empty-state error">
        {error instanceof Error ? error.message : 'Failed to load issue history'}
      </p>
    )
  }

  const timeline = buildTimeline(history)

  return (
    <div className="issue-detail">
      <div className="issue-detail-header">
        <SeverityBadge severity={history.issue.severity} />
        <StatusBadge status={history.issue.status} />
        <h2>{history.issue.ruleId}</h2>
      </div>
      <p className="issue-message">{history.issue.message}</p>
      <p className="issue-location">
        {history.issue.filePath}:{history.issue.startLine}
        {history.issue.symbolPath ? ` — ${history.issue.symbolPath}` : ''}
      </p>
      <p className="issue-meta">
        Introduced at <code>{history.issue.introducedAtCommit}</code>, first seen run{' '}
        <code>{history.issue.firstSeenRunId}</code>
      </p>

      <TriageForm projectId={projectId} issueId={issueId} currentStatus={history.issue.status} />

      <h3>Timeline</h3>
      <ol className="timeline">
        {timeline.map((entry) => (
          <li key={`${entry.kind}-${entry.at}-${entry.kind === 'finding' ? entry.finding.id : entry.event.sequenceNumber}`}>
            {entry.kind === 'finding' ? (
              <FindingEntry finding={entry.finding} />
            ) : (
              <TriageEventEntry event={entry.event} />
            )}
          </li>
        ))}
      </ol>
    </div>
  )
}

function FindingEntry({ finding }: { finding: FindingSighting }) {
  return (
    <div className="timeline-entry timeline-finding">
      <span className="timeline-when">{new Date(finding.createdAt).toLocaleString()}</span>
      <span className="timeline-what">
        Sighted by analysis run <code>{finding.analysisRunId}</code>
        {finding.matchRung ? (
          <>
            {' '}
            — matched on the <strong>{finding.matchRung.toLowerCase()}</strong> rung
          </>
        ) : null}
      </span>
    </div>
  )
}

function TriageEventEntry({ event }: { event: TriageEntry }) {
  return (
    <div className="timeline-entry timeline-triage">
      <span className="timeline-when">{new Date(event.occurredAt).toLocaleString()}</span>
      <span className="timeline-what">
        <strong>{event.actor}</strong> moved this issue from <em>{event.fromStatus}</em> to{' '}
        <em>{event.toStatus}</em>
        {event.justification ? <> — “{event.justification}”</> : null}
      </span>
      <span className="timeline-hash" title="entry_hash, this event's link in the audit chain">
        #{event.sequenceNumber} · {event.entryHash.slice(0, 12)}…
      </span>
    </div>
  )
}

const TRIAGE_OPTIONS: IssueStatus[] = ['OPEN', 'RESOLVED_WONT_FIX', 'RESOLVED_FALSE_POSITIVE']

function TriageForm({
  projectId,
  issueId,
  currentStatus,
}: {
  projectId: string
  issueId: string
  currentStatus: IssueStatus
}) {
  const mutation = useTriageIssue(issueId, projectId)
  const [status, setStatus] = useState<IssueStatus>('RESOLVED_WONT_FIX')
  const [actor, setActor] = useState('')
  const [justification, setJustification] = useState('')

  const requiresJustification = status === 'RESOLVED_FALSE_POSITIVE' || status === 'RESOLVED_WONT_FIX'

  function handleSubmit(event: FormEvent) {
    event.preventDefault()
    mutation.mutate({ status, actor, justification: justification.trim() || undefined })
  }

  return (
    <form className="triage-form" onSubmit={handleSubmit}>
      <h3>Triage — currently {currentStatus}</h3>
      <div className="triage-form-fields">
        <label>
          <span>New status</span>
          <select value={status} onChange={(event) => setStatus(event.target.value as IssueStatus)}>
            {TRIAGE_OPTIONS.map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
        </label>
        <label>
          <span>Actor</span>
          <input
            type="text"
            required
            placeholder="your name"
            value={actor}
            onChange={(event) => setActor(event.target.value)}
          />
        </label>
        <label className="triage-justification">
          <span>Justification {requiresJustification ? '(required)' : '(optional)'}</span>
          <textarea
            required={requiresJustification}
            value={justification}
            onChange={(event) => setJustification(event.target.value)}
          />
        </label>
      </div>
      <button type="submit" disabled={mutation.isPending}>
        {mutation.isPending ? 'Saving…' : 'Apply triage decision'}
      </button>
      {mutation.isError && (
        <p className="error">{mutation.error instanceof Error ? mutation.error.message : 'Triage failed'}</p>
      )}
      {mutation.isSuccess && <p className="success">Recorded, and appended to the audit chain.</p>}
    </form>
  )
}
