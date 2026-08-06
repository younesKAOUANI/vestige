// Mirrors the backend's response DTOs field-for-field (see
// dev.youneskaouani.vestige.issues.api.IssueResponse and its neighbours). Kept as one file,
// hand-written rather than generated from the OpenAPI spec at /v3/api-docs - a reasonable
// follow-up (see README "Roadmap"), not done here to keep the build free of a codegen step.

export type IssueStatus =
  | 'OPEN'
  | 'RESOLVED_FIXED'
  | 'RESOLVED_FALSE_POSITIVE'
  | 'RESOLVED_WONT_FIX'
  | 'REOPENED'

export type Severity = 'INFO' | 'MINOR' | 'MAJOR' | 'CRITICAL' | 'BLOCKER'

export type MatchRung = 'IDENTITY' | 'CONTEXT' | 'WEAK' | 'NEW'

export type RunStatus =
  | 'RECEIVED'
  | 'PARSING'
  | 'MATCHING'
  | 'COMPLETED'
  | 'FAILED'
  | 'QUARANTINED'
  | 'DUPLICATE'

export type GateStatus = 'PASS' | 'FAIL'

export type ConditionType =
  | 'NEW_CRITICAL_ISSUES'
  | 'NEW_ISSUES_TOTAL'
  | 'REOPENED_ISSUES'
  | 'TOTAL_BLOCKER_ISSUES'

export interface Issue {
  id: string
  projectId: string
  branchId: string
  ruleId: string
  severity: Severity
  message: string
  filePath: string
  symbolPath: string | null
  startLine: number
  status: IssueStatus
  firstSeenRunId: string
  lastSeenRunId: string
  introducedAtCommit: string
  createdAt: string
  updatedAt: string
}

export interface PageResponse<T> {
  items: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface FindingSighting {
  id: string
  analysisRunId: string
  ruleId: string
  severity: Severity
  message: string
  filePath: string
  startLine: number
  matchRung: MatchRung | null
  createdAt: string
}

export interface TriageEntry {
  sequenceNumber: number
  actor: string
  fromStatus: IssueStatus
  toStatus: IssueStatus
  justification: string | null
  occurredAt: string
  entryHash: string
}

export interface IssueHistory {
  issue: Issue
  findings: FindingSighting[]
  triageEvents: TriageEntry[]
}

export interface GateConditionOutcome {
  condition: {
    type: ConditionType
    threshold: number
  }
  status: GateStatus
  actualValue: number
  threshold: number
  offendingIssueIds: string[]
}

export interface GateOutcome {
  gateName: string
  status: GateStatus
  conditions: GateConditionOutcome[]
}

export interface Run {
  id: string
  projectId: string
  branchId: string
  commitSha: string
  baseCommitSha: string | null
  analyserName: string
  analyserVersion: string
  status: RunStatus
  failureReason: string | null
  findingCount: number
  createdAt: string
  updatedAt: string
  completedAt: string | null
  // @JsonRawValue on the backend: null until the run has actually been gated.
  gateResult: GateOutcome | null
}

export interface GateConditionView {
  type: ConditionType
  threshold: number
  description: string
}

export interface GateConfig {
  name: string
  conditions: GateConditionView[]
}

export interface AuditVerifyResult {
  intact: boolean
  length: number | null
  brokenAtIndex: number | null
}

export interface IssueFilters {
  status?: IssueStatus
  severity?: Severity
  rule?: string
  sinceRun?: string
}

export interface TriageRequest {
  status: IssueStatus
  actor: string
  justification?: string
}
