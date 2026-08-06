import type { GateStatus, IssueStatus, Severity } from '../lib/types'

const SEVERITY_CLASS: Record<Severity, string> = {
  INFO: 'badge badge-severity-info',
  MINOR: 'badge badge-severity-minor',
  MAJOR: 'badge badge-severity-major',
  CRITICAL: 'badge badge-severity-critical',
  BLOCKER: 'badge badge-severity-blocker',
}

export function SeverityBadge({ severity }: { severity: Severity }) {
  return <span className={SEVERITY_CLASS[severity]}>{severity}</span>
}

const STATUS_CLASS: Record<IssueStatus, string> = {
  OPEN: 'badge badge-status-open',
  REOPENED: 'badge badge-status-open',
  RESOLVED_FIXED: 'badge badge-status-resolved',
  RESOLVED_FALSE_POSITIVE: 'badge badge-status-silenced',
  RESOLVED_WONT_FIX: 'badge badge-status-silenced',
}

const STATUS_LABEL: Record<IssueStatus, string> = {
  OPEN: 'Open',
  REOPENED: 'Reopened',
  RESOLVED_FIXED: 'Fixed',
  RESOLVED_FALSE_POSITIVE: 'False positive',
  RESOLVED_WONT_FIX: "Won't fix",
}

export function StatusBadge({ status }: { status: IssueStatus }) {
  return <span className={STATUS_CLASS[status]}>{STATUS_LABEL[status]}</span>
}

export function GateStatusBadge({ status }: { status: GateStatus }) {
  return <span className={status === 'PASS' ? 'badge badge-gate-pass' : 'badge badge-gate-fail'}>{status}</span>
}
