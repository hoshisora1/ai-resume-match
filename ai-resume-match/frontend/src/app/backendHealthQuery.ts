import { queryOptions } from '@tanstack/react-query'

import { getBackendHealth } from '../shared/api/analyses'

export const backendHealthQueryOptions = queryOptions({
  queryKey: ['backend-health'],
  queryFn: ({ signal }) => getBackendHealth(signal),
  staleTime: 60_000,
  refetchInterval: 60_000,
  refetchIntervalInBackground: false,
  refetchOnWindowFocus: true,
  refetchOnReconnect: true,
})
