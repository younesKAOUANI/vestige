import type {
  AuditVerifyResult,
  GateConfig,
  Issue,
  IssueFilters,
  IssueHistory,
  PageResponse,
  Run,
  TriageRequest,
} from './types'

const BASE_URL = (import.meta.env.VITE_API_BASE_URL as string | undefined) ?? '/api/v1'
const API_KEY_STORAGE_KEY = 'vestige.apiKey'

/** Thrown for any non-2xx response, carrying the RFC 7807 `detail` the backend always sends. */
export class ApiError extends Error {
  readonly status: number

  constructor(status: number, detail: string) {
    super(detail)
    this.status = status
    this.name = 'ApiError'
  }
}

export function getStoredApiKey(): string {
  return localStorage.getItem(API_KEY_STORAGE_KEY) ?? ''
}

export function setStoredApiKey(key: string): void {
  localStorage.setItem(API_KEY_STORAGE_KEY, key)
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const headers: Record<string, string> = { 'X-API-Key': getStoredApiKey() }
  if (init?.body) {
    headers['Content-Type'] = 'application/json'
  }

  const response = await fetch(`${BASE_URL}${path}`, { ...init, headers: { ...headers, ...init?.headers } })

  if (!response.ok) {
    throw new ApiError(response.status, await extractProblemDetail(response))
  }
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}

async function extractProblemDetail(response: Response): Promise<string> {
  try {
    const problem = (await response.json()) as { detail?: string }
    return problem.detail ?? response.statusText
  } catch {
    return response.statusText
  }
}

export function fetchIssues(
  projectId: string,
  filters: IssueFilters,
  page: number,
): Promise<PageResponse<Issue>> {
  const params = new URLSearchParams({ page: String(page), size: '25' })
  if (filters.status) params.set('status', filters.status)
  if (filters.severity) params.set('severity', filters.severity)
  if (filters.rule) params.set('rule', filters.rule)
  if (filters.sinceRun) params.set('sinceRun', filters.sinceRun)
  return request(`/projects/${encodeURIComponent(projectId)}/issues?${params.toString()}`)
}

export function fetchIssueHistory(issueId: string): Promise<IssueHistory> {
  return request(`/issues/${encodeURIComponent(issueId)}/history`)
}

export function triageIssue(issueId: string, body: TriageRequest): Promise<Issue> {
  return request(`/issues/${encodeURIComponent(issueId)}`, {
    method: 'PATCH',
    body: JSON.stringify(body),
  })
}

export function fetchRun(runId: string): Promise<Run> {
  return request(`/runs/${encodeURIComponent(runId)}`)
}

export function fetchGateConfig(projectId: string): Promise<GateConfig> {
  return request(`/projects/${encodeURIComponent(projectId)}/gate`)
}

export function verifyAuditChain(): Promise<AuditVerifyResult> {
  return request('/audit/verify')
}
