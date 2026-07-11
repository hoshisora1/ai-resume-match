import { useQuery } from '@tanstack/react-query'

import { getAnalysisSummary } from '../../shared/api/analyses'

export const analysisSummaryQueryKey = ['analysis-summary'] as const

export function useAnalysisSummaryQuery() {
  return useQuery({
    queryKey: analysisSummaryQueryKey,
    queryFn: getAnalysisSummary,
  })
}
