import { queryOptions, useQuery } from '@tanstack/react-query'
import { useMemo } from 'react'

import { getAnalysisTask } from '../../shared/api/analyses'
import type { AnalysisStatus } from '../../shared/api/schemas'

const INITIAL_POLL_INTERVAL = 2_000
const MAX_POLL_INTERVAL = 8_000
const ACTIVE_STATUSES = new Set<AnalysisStatus>(['PENDING', 'RUNNING'])

export function analysisTaskQueryKey(taskId: number) {
  return ['analysis-task', taskId] as const
}

function isActiveStatus(status: AnalysisStatus) {
  return ACTIVE_STATUSES.has(status)
}

interface PollingState {
  activeFetchCount: number
  taskId: number | undefined
}

function readPollingState(meta: Record<string, unknown> | undefined) {
  const pollingState = meta?.pollingState
  if (
    typeof pollingState !== 'object' ||
    pollingState === null ||
    !('activeFetchCount' in pollingState)
  ) {
    throw new Error('Analysis polling state is unavailable')
  }

  return pollingState as PollingState
}

function createAnalysisTaskQueryOptions(
  taskId: number | undefined,
  pollingState: PollingState,
) {
  return queryOptions({
    queryKey: ['analysis-task', taskId] as const,
    queryFn: async ({ meta, signal }) => {
      if (taskId === undefined) {
        throw new Error('A valid task id is required')
      }

      const task = await getAnalysisTask(taskId, signal)
      const currentPollingState = readPollingState(meta)
      if (isActiveStatus(task.status)) {
        currentPollingState.activeFetchCount += 1
      } else {
        currentPollingState.activeFetchCount = 0
      }
      return task
    },
    enabled: taskId !== undefined,
    meta: { pollingState },
    refetchInterval: (query) => {
      const task = query.state.data
      if (task === undefined || !isActiveStatus(task.status)) {
        return false
      }

      if (query.state.error !== null) {
        return MAX_POLL_INTERVAL
      }

      const successfulActiveFetches = Math.max(
        1,
        readPollingState(query.meta).activeFetchCount,
      )
      return Math.min(
        INITIAL_POLL_INTERVAL * 2 ** (successfulActiveFetches - 1),
        MAX_POLL_INTERVAL,
      )
    },
    refetchIntervalInBackground: false,
  })
}

export function useAnalysisTask(taskId: number | undefined) {
  const pollingState = useMemo<PollingState>(
    () => ({ activeFetchCount: 0, taskId }),
    [taskId],
  )

  return useQuery(createAnalysisTaskQueryOptions(taskId, pollingState))
}
