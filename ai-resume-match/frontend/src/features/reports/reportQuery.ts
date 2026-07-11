export function analysisReportQueryKey(taskId: number) {
  return ['analysis-report', taskId] as const
}
