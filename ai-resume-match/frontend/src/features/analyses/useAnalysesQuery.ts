import { useQuery } from '@tanstack/react-query'

import {
  listAnalyses,
  type ListAnalysesParams,
} from '../../shared/api/analyses'

export function analysesQueryKey({
  status,
  page,
  size,
}: ListAnalysesParams) {
  return ['analyses', { status, page, size }] as const
}

export function useAnalysesQuery(params: ListAnalysesParams, enabled = true) {
  return useQuery({
    queryKey: analysesQueryKey(params),
    queryFn: ({ signal }) => listAnalyses(params, signal),
    enabled,
  })
}
