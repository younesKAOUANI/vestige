import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import * as api from '../lib/api'
import type { IssueFilters, TriageRequest } from '../lib/types'

export function useIssues(projectId: string, filters: IssueFilters, page: number) {
  return useQuery({
    queryKey: ['issues', projectId, filters, page],
    queryFn: () => api.fetchIssues(projectId, filters, page),
    enabled: projectId.trim().length > 0,
    placeholderData: keepPreviousData,
  })
}

export function useIssueHistory(issueId: string | null) {
  return useQuery({
    queryKey: ['issue-history', issueId],
    queryFn: () => api.fetchIssueHistory(issueId as string),
    enabled: issueId !== null,
  })
}

export function useTriageIssue(issueId: string, projectId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: TriageRequest) => api.triageIssue(issueId, body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['issue-history', issueId] })
      void queryClient.invalidateQueries({ queryKey: ['issues', projectId] })
    },
  })
}

export function useRun(runId: string | null) {
  return useQuery({
    queryKey: ['run', runId],
    queryFn: () => api.fetchRun(runId as string),
    enabled: runId !== null && runId.trim().length > 0,
    retry: false,
  })
}

export function useGateConfig(projectId: string) {
  return useQuery({
    queryKey: ['gate-config', projectId],
    queryFn: () => api.fetchGateConfig(projectId),
    enabled: projectId.trim().length > 0,
  })
}

export function useVerifyAuditChain() {
  return useMutation({ mutationFn: api.verifyAuditChain })
}
