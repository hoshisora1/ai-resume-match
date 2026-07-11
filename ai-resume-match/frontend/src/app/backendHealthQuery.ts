import { queryOptions } from '@tanstack/react-query'

import { getBackendHealth } from '../shared/api/analyses'

export const backendHealthQueryOptions = queryOptions({
  queryKey: ['backend-health'],
  queryFn: getBackendHealth,
  staleTime: 60_000,
  refetchInterval: false,
  refetchOnWindowFocus: true,
  refetchOnReconnect: true,
})
