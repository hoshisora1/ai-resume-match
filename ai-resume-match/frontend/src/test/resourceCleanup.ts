interface CleanupStep {
  label: string
  run: () => Promise<void> | void
}

async function runWithTimeout(step: CleanupStep, timeoutMs: number) {
  let timeoutHandle: ReturnType<typeof setTimeout> | undefined

  try {
    await Promise.race([
      Promise.resolve().then(step.run),
      new Promise<never>((_resolve, reject) => {
        timeoutHandle = setTimeout(() => {
          reject(new Error(`${step.label} cleanup timed out after ${timeoutMs}ms`))
        }, timeoutMs)
      }),
    ])
  } catch (error) {
    throw new Error(`${step.label} cleanup failed`, { cause: error })
  } finally {
    if (timeoutHandle !== undefined) {
      clearTimeout(timeoutHandle)
    }
  }
}

export async function runCleanupSteps(
  steps: readonly CleanupStep[],
  timeoutMs = 5_000,
) {
  const results = await Promise.allSettled(
    steps.map((step) => runWithTimeout(step, timeoutMs)),
  )
  const failures: unknown[] = []

  for (const result of results) {
    if (result.status === 'rejected') {
      failures.push(result.reason)
    }
  }

  if (failures.length > 0) {
    throw new AggregateError(failures, 'Frontend test cleanup failed')
  }
}
