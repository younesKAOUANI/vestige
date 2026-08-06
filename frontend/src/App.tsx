import { useState } from 'react'
import './App.css'
import { AuditPanel } from './components/AuditPanel'
import { IssueDetail } from './components/IssueDetail'
import { IssueFiltersBar } from './components/IssueFilters'
import { IssueList } from './components/IssueList'
import { RunPanel } from './components/RunPanel'
import { TopBar } from './components/TopBar'
import type { IssueFilters } from './lib/types'

type Tab = 'issues' | 'runs'

function App() {
  const [projectId, setProjectId] = useState('')
  const [filters, setFilters] = useState<IssueFilters>({})
  const [selectedIssueId, setSelectedIssueId] = useState<string | null>(null)
  const [tab, setTab] = useState<Tab>('issues')

  function selectProject(next: string) {
    setProjectId(next)
    setSelectedIssueId(null)
  }

  return (
    <div className="app">
      <TopBar projectId={projectId} onProjectIdChange={selectProject} />

      <nav className="tab-bar">
        <button
          type="button"
          className={tab === 'issues' ? 'active' : ''}
          onClick={() => setTab('issues')}
        >
          Issues
        </button>
        <button type="button" className={tab === 'runs' ? 'active' : ''} onClick={() => setTab('runs')}>
          Runs &amp; audit
        </button>
      </nav>

      <main className="app-main">
        {tab === 'issues' ? (
          <div className="issues-tab">
            <div className="issues-tab-list">
              <IssueFiltersBar value={filters} onChange={setFilters} />
              <IssueList
                projectId={projectId}
                filters={filters}
                selectedIssueId={selectedIssueId}
                onSelectIssue={setSelectedIssueId}
              />
            </div>
            <div className="issues-tab-detail">
              {selectedIssueId ? (
                <IssueDetail projectId={projectId} issueId={selectedIssueId} />
              ) : (
                <p className="empty-state">Select an issue on the left to see its history and triage it.</p>
              )}
            </div>
          </div>
        ) : (
          <div className="runs-tab">
            <RunPanel />
            <AuditPanel />
          </div>
        )}
      </main>
    </div>
  )
}

export default App
