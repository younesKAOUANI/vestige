import { useState } from 'react'
import { useIssues } from '../hooks/queries'
import type { IssueFilters } from '../lib/types'
import { SeverityBadge, StatusBadge } from './Badges'

interface IssueListProps {
  projectId: string
  filters: IssueFilters
  selectedIssueId: string | null
  onSelectIssue: (issueId: string) => void
}

export function IssueList({ projectId, filters, selectedIssueId, onSelectIssue }: IssueListProps) {
  const [page, setPage] = useState(0)
  const { data, isLoading, isError, error } = useIssues(projectId, filters, page)

  if (projectId.trim().length === 0) {
    return <p className="empty-state">Enter a project id above to load its issues.</p>
  }
  if (isLoading) {
    return <p className="empty-state">Loading issues…</p>
  }
  if (isError) {
    return <p className="empty-state error">{error instanceof Error ? error.message : 'Failed to load issues'}</p>
  }
  if (!data || data.items.length === 0) {
    return <p className="empty-state">No issues match these filters.</p>
  }

  return (
    <div className="issue-list">
      <table>
        <thead>
          <tr>
            <th>Severity</th>
            <th>Rule</th>
            <th>Location</th>
            <th>Status</th>
            <th>Updated</th>
          </tr>
        </thead>
        <tbody>
          {data.items.map((issue) => (
            <tr
              key={issue.id}
              className={issue.id === selectedIssueId ? 'selected' : ''}
              onClick={() => onSelectIssue(issue.id)}
            >
              <td>
                <SeverityBadge severity={issue.severity} />
              </td>
              <td>{issue.ruleId}</td>
              <td className="location">
                {issue.filePath}:{issue.startLine}
              </td>
              <td>
                <StatusBadge status={issue.status} />
              </td>
              <td>{new Date(issue.updatedAt).toLocaleString()}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="pagination">
        <button type="button" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
          Previous
        </button>
        <span>
          Page {data.page + 1} of {Math.max(data.totalPages, 1)} ({data.totalElements} issues)
        </span>
        <button type="button" disabled={page + 1 >= data.totalPages} onClick={() => setPage((p) => p + 1)}>
          Next
        </button>
      </div>
    </div>
  )
}
