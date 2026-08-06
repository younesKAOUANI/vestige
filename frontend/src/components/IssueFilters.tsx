import type { IssueFilters as IssueFiltersValue, IssueStatus, Severity } from '../lib/types'

const STATUSES: IssueStatus[] = [
  'OPEN',
  'REOPENED',
  'RESOLVED_FIXED',
  'RESOLVED_FALSE_POSITIVE',
  'RESOLVED_WONT_FIX',
]

const SEVERITIES: Severity[] = ['BLOCKER', 'CRITICAL', 'MAJOR', 'MINOR', 'INFO']

interface IssueFiltersProps {
  value: IssueFiltersValue
  onChange: (next: IssueFiltersValue) => void
}

export function IssueFiltersBar({ value, onChange }: IssueFiltersProps) {
  return (
    <div className="filters-bar">
      <label>
        <span>Status</span>
        <select
          value={value.status ?? ''}
          onChange={(event) =>
            onChange({ ...value, status: (event.target.value || undefined) as IssueStatus | undefined })
          }
        >
          <option value="">Any</option>
          {STATUSES.map((status) => (
            <option key={status} value={status}>
              {status}
            </option>
          ))}
        </select>
      </label>
      <label>
        <span>Severity</span>
        <select
          value={value.severity ?? ''}
          onChange={(event) =>
            onChange({ ...value, severity: (event.target.value || undefined) as Severity | undefined })
          }
        >
          <option value="">Any</option>
          {SEVERITIES.map((severity) => (
            <option key={severity} value={severity}>
              {severity}
            </option>
          ))}
        </select>
      </label>
      <label>
        <span>Rule</span>
        <input
          type="text"
          placeholder="java:S3649"
          value={value.rule ?? ''}
          onChange={(event) => onChange({ ...value, rule: event.target.value || undefined })}
        />
      </label>
      <label>
        <span>Since run</span>
        <input
          type="text"
          placeholder="run id"
          value={value.sinceRun ?? ''}
          onChange={(event) => onChange({ ...value, sinceRun: event.target.value || undefined })}
        />
      </label>
      <button type="button" className="secondary" onClick={() => onChange({})}>
        Clear filters
      </button>
    </div>
  )
}
