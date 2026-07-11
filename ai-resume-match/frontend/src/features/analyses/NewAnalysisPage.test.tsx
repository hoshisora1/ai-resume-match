import type { QueryClient } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { cwd } from 'node:process'
import { HttpResponse, http } from 'msw'
import { StrictMode } from 'react'
import { afterEach, expect, test, vi } from 'vitest'
import { createMemoryRouter } from 'react-router'

import { App } from '../../app/App'
import { createQueryClient } from '../../app/queryClient'
import { appRoutes, type AppRouter } from '../../app/router'
import { ApiError } from '../../shared/api/client'
import { server } from '../../test/server'
import { analysisFormSchema } from './analysisFormSchema'
import { summarizeSubmissionError } from './submissionError'

const MAX_FILE_SIZE = 5 * 1024 * 1024
const VALID_TITLE = '高级后端工程师'
const VALID_JOB_CONTENT = '负责 Java 服务与 Redis 基础设施建设。'

interface RenderedApp {
  queryClient: QueryClient
  router: AppRouter
  dispose: () => void
}

interface RenderNewAnalysisOptions {
  strictMode?: boolean
}

const renderedApps: RenderedApp[] = []

function createSubmissionResponse(
  jobTitle = VALID_TITLE,
  resumeFileName = 'candidate.pdf',
) {
  return {
    taskId: 42,
    resumeId: 10,
    jobDescriptionId: 20,
    jobTitle,
    resumeFileName,
    matchScore: null,
    status: 'PENDING',
    attemptCount: 0,
    maxAttempts: 3,
    failureCode: null,
    failureMessage: null,
    nextRetryAt: null,
    startedAt: null,
    completedAt: null,
    createdAt: '2026-07-11T09:00:00',
    updatedAt: '2026-07-11T09:00:00',
  }
}

function renderNewAnalysis(
  initialEntries = ['/analyses/new'],
  { strictMode = false }: RenderNewAnalysisOptions = {},
) {
  server.use(
    http.get('/backend-health', () => HttpResponse.json({ status: 'UP' })),
  )

  const queryClient = createQueryClient()
  const defaultOptions = queryClient.getDefaultOptions()
  queryClient.setDefaultOptions({
    ...defaultOptions,
    queries: {
      ...defaultOptions.queries,
      gcTime: Infinity,
      retry: false,
    },
  })
  const router: AppRouter = createMemoryRouter(appRoutes, { initialEntries })
  const appElement = <App queryClient={queryClient} router={router} />
  const rendered = render(
    strictMode ? <StrictMode>{appElement}</StrictMode> : appElement,
  )
  let disposed = false

  const app = {
    queryClient,
    router,
    dispose: () => {
      if (disposed) {
        return
      }
      disposed = true

      try {
        rendered.unmount()
      } finally {
        try {
          router.dispose()
        } finally {
          queryClient.clear()
        }
      }
    },
  }
  renderedApps.push(app)
  return app
}

function setTextValues(
  jobTitle = VALID_TITLE,
  jobContent = VALID_JOB_CONTENT,
) {
  fireEvent.change(screen.getByRole('textbox', { name: '岗位名称' }), {
    target: { value: jobTitle },
  })
  fireEvent.change(screen.getByRole('textbox', { name: '岗位 JD' }), {
    target: { value: jobContent },
  })
}

function expectFieldError(control: HTMLElement, message: string) {
  const error = screen.getByText(message)
  const describedBy = control.getAttribute('aria-describedby')?.split(/\s+/)

  expect(control).toHaveAttribute('aria-invalid', 'true')
  expect(error).toHaveAttribute('id')
  expect(describedBy).toContain(error.id)
}

afterEach(() => {
  for (const app of renderedApps.splice(0)) {
    app.dispose()
  }
  vi.restoreAllMocks()
})

test('renders a compact accessible workflow with an exact file accept contract', async () => {
  const user = userEvent.setup()
  renderNewAnalysis()

  expect(
    await screen.findByRole('heading', { name: '新建分析' }),
  ).toBeVisible()
  expect(screen.getByRole('form', { name: '新建分析表单' })).toBeVisible()

  const fileInput = screen.getByLabelText('选择简历文件')
  const titleInput = screen.getByRole('textbox', { name: '岗位名称' })
  const jobContentInput = screen.getByRole('textbox', { name: '岗位 JD' })

  expect(fileInput).toHaveAttribute('type', 'file')
  expect(fileInput).toHaveAttribute('accept', '.pdf,.docx')
  expect(screen.getByText('支持 PDF、DOCX，文件不超过 5 MB')).toBeVisible()
  expect(titleInput).toBeVisible()
  expect(jobContentInput).toBeVisible()

  fileInput.focus()
  expect(fileInput).toHaveFocus()
  await user.keyboard('{Tab}')
  expect(titleInput).toHaveFocus()

  const file = new File([new Uint8Array(1536)], 'candidate.resume.PDF', {
    type: 'text/plain',
  })
  await user.upload(fileInput, file)
  await user.type(titleInput, VALID_TITLE)
  await user.type(jobContentInput, VALID_JOB_CONTENT)

  const selectedFile = screen.getByRole('status', { name: '已选择简历' })
  expect(within(selectedFile).getByText(file.name)).toBeVisible()
  expect(within(selectedFile).getByText('1.5 KB')).toBeVisible()

  const confirmation = screen.getByRole('region', { name: '提交确认' })
  expect(within(confirmation).getByText(file.name)).toBeVisible()
  expect(within(confirmation).getByText(VALID_TITLE)).toBeVisible()
  expect(confirmation.querySelector('[class*="card"]')).toBeNull()
})

test('shows Chinese field errors with aria links when required values are missing', async () => {
  const user = userEvent.setup()
  renderNewAnalysis()

  await user.click(
    await screen.findByRole('button', { name: '提交分析' }),
  )

  expectFieldError(screen.getByLabelText('选择简历文件'), '请选择简历文件')
  expectFieldError(
    screen.getByRole('textbox', { name: '岗位名称' }),
    '请输入岗位名称',
  )
  expectFieldError(
    screen.getByRole('textbox', { name: '岗位 JD' }),
    '请输入岗位描述',
  )
})

test('rejects an empty resume file', async () => {
  const user = userEvent.setup()
  renderNewAnalysis()
  setTextValues()

  await user.upload(
    screen.getByLabelText('选择简历文件'),
    new File([], 'empty.pdf', { type: 'application/pdf' }),
  )
  await user.click(screen.getByRole('button', { name: '提交分析' }))

  expectFieldError(
    screen.getByLabelText('选择简历文件'),
    '请选择非空简历文件',
  )
})

test('tracks drag state without replacing the keyboard input and rejects an unsupported extension', async () => {
  const user = userEvent.setup()
  renderNewAnalysis()
  setTextValues()

  const zone = screen.getByTestId('analysis-upload-zone')
  const fileInput = screen.getByLabelText('选择简历文件')
  const unsupportedFile = new File(['resume'], 'candidate.txt', {
    type: 'application/pdf',
  })

  fireEvent.dragEnter(zone, { dataTransfer: { files: [unsupportedFile] } })
  expect(zone).toHaveAttribute('data-dragging', 'true')
  expect(fileInput).toBeEnabled()

  fireEvent.dragLeave(zone, { dataTransfer: { files: [unsupportedFile] } })
  expect(zone).toHaveAttribute('data-dragging', 'false')

  fireEvent.dragEnter(zone, { dataTransfer: { files: [unsupportedFile] } })
  fireEvent.drop(zone, { dataTransfer: { files: [unsupportedFile] } })
  expect(zone).toHaveAttribute('data-dragging', 'false')
  const selection = screen.getByRole('status', { name: '已选择简历' })
  const selectedName = within(selection).getByText(unsupportedFile.name)
  const selectedSize = within(selection).getByText('6 B')
  expect(selectedName).toBeVisible()
  expect(selectedSize).toBeVisible()

  fileInput.focus()
  expect(fileInput).toHaveFocus()
  expect(selectedName).toHaveAttribute('id')
  expect(selectedSize).toHaveAttribute('id')
  const describedBy = fileInput
    .getAttribute('aria-describedby')
    ?.split(/\s+/)
  expect(describedBy).toEqual(
    expect.arrayContaining([selectedName.id, selectedSize.id]),
  )
  expect(fileInput).toHaveAccessibleDescription(/candidate\.txt.*6 B/)

  await user.click(screen.getByRole('button', { name: '提交分析' }))

  expectFieldError(fileInput, '仅支持 PDF 或 DOCX')
})

test('rejects a resume larger than 5 MiB', async () => {
  const user = userEvent.setup()
  renderNewAnalysis()
  setTextValues()

  await user.upload(
    screen.getByLabelText('选择简历文件'),
    new File([new Uint8Array(MAX_FILE_SIZE + 1)], 'oversize.pdf', {
      type: 'application/octet-stream',
    }),
  )
  await user.click(screen.getByRole('button', { name: '提交分析' }))

  expectFieldError(
    screen.getByLabelText('选择简历文件'),
    '简历文件不能超过 5 MB',
  )
})

test('accepts exactly 5 MiB with a case-insensitive extension regardless of MIME', () => {
  const file = new File(
    [new Uint8Array(MAX_FILE_SIZE)],
    'five-mebibytes.DoCx',
    { type: 'text/plain' },
  )

  expect(
    analysisFormSchema.safeParse({
      file,
      jobTitle: VALID_TITLE,
      jobContent: VALID_JOB_CONTENT,
    }).success,
  ).toBe(true)
})

test('submits the latest same-name file after consecutive reselection', async () => {
  const submission: { file: FormDataEntryValue | null } = { file: null }
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    if (input === '/backend-health') {
      return Response.json({ status: 'UP' })
    }

    if (input === '/api/analysis-submissions') {
      submission.file =
        init?.body instanceof FormData ? init.body.get('file') : null
      return Response.json(createSubmissionResponse())
    }

    throw new Error(`Unexpected fetch input: ${String(input)}`)
  })
  const user = userEvent.setup()
  const { router } = renderNewAnalysis()
  const fileInput = screen.getByLabelText('选择简历文件')
  const firstFile = new File(['old'], 'candidate.pdf', {
    lastModified: 1_000,
    type: 'application/pdf',
  })
  const secondFile = new File(['new-resume'], 'candidate.pdf', {
    lastModified: 2_000,
    type: 'application/pdf',
  })

  await user.upload(fileInput, firstFile)
  expect(fileInput).toHaveValue('')
  expect((fileInput as HTMLInputElement).files).toHaveLength(0)

  await user.upload(fileInput, secondFile)
  expect(fileInput).toHaveValue('')
  expect((fileInput as HTMLInputElement).files).toHaveLength(0)
  expect(
    within(screen.getByRole('status', { name: '已选择简历' })).getByText(
      '10 B',
    ),
  ).toBeVisible()
  setTextValues()

  await user.click(screen.getByRole('button', { name: '提交分析' }))

  await waitFor(() => {
    expect(router.state.location.pathname).toBe('/analyses/42')
  })
  expect(submission.file).toBeInstanceOf(File)
  if (!(submission.file instanceof File)) {
    throw new Error('Expected a submitted resume file')
  }
  expect(submission.file.name).toBe(secondFile.name)
  expect(submission.file.size).toBe(secondFile.size)
  expect(submission.file.lastModified).toBe(secondFile.lastModified)
})

test.each([
  {
    caseName: 'U+3000 and NBSP in the title',
    jobTitle: '\u3000\u00a0',
    jobContent: VALID_JOB_CONTENT,
    fieldName: '岗位名称',
    message: '请输入岗位名称',
  },
  {
    caseName: 'NBSP and U+3000 in the JD',
    jobTitle: VALID_TITLE,
    jobContent: '\u00a0\u3000',
    fieldName: '岗位 JD',
    message: '请输入岗位描述',
  },
])(
  'treats $caseName as Unicode-aware blank input',
  async ({ fieldName, jobContent, jobTitle, message }) => {
    const user = userEvent.setup()
    renderNewAnalysis()
    setTextValues(jobTitle, jobContent)
    await user.upload(
      screen.getByLabelText('选择简历文件'),
      new File(['resume'], 'candidate.pdf'),
    )

    await user.click(screen.getByRole('button', { name: '提交分析' }))

    expectFieldError(
      screen.getByRole('textbox', { name: fieldName }),
      message,
    )
  },
)

test('counts the title and JD limits by Unicode code point and submits multipart fields', async () => {
  let submittedInput: RequestInfo | URL | undefined
  let submittedInit: RequestInit | undefined
  let requestCount = 0
  const exactTitle = '😀'.repeat(120)
  const exactJobContent = '🚀'.repeat(20_000)
  const submittedResume = new File(['resume'], 'candidate.DoCx', {
    type: 'text/plain',
  })

  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    if (input === '/backend-health') {
      return Response.json({ status: 'UP' })
    }

    if (input === '/api/analysis-submissions') {
      requestCount += 1
      submittedInput = input
      submittedInit = init
      return Response.json(
        createSubmissionResponse(exactTitle, submittedResume.name),
      )
    }

    throw new Error(`Unexpected fetch input: ${String(input)}`)
  })

  const user = userEvent.setup()
  const { queryClient, router } = renderNewAnalysis([
    '/analyses/999',
    '/analyses/new',
  ])
  const mutationBaseline = queryClient.getMutationCache().getAll()
  expect(mutationBaseline).toHaveLength(0)
  const firstHistoryKey = [
    'analyses',
    { status: undefined, page: 0, size: 5 },
  ] as const
  const secondHistoryKey = [
    'analyses',
    { status: 'SUCCESS', page: 2, size: 20 },
  ] as const
  const unrelatedKey = ['analysis', 42] as const
  queryClient.setQueryData(['analysis-summary'], { cached: true })
  queryClient.setQueryData(firstHistoryKey, { cached: true })
  queryClient.setQueryData(secondHistoryKey, { cached: true })
  queryClient.setQueryData(unrelatedKey, { cached: true })
  let releaseInvalidations: () => void = () => undefined
  const invalidationGate = new Promise<void>((resolve) => {
    releaseInvalidations = resolve
  })
  const originalInvalidateQueries = queryClient.invalidateQueries.bind(
    queryClient,
  )
  const invalidateSpy = vi
    .spyOn(queryClient, 'invalidateQueries')
    .mockImplementation(async (...args) => {
      await invalidationGate
      return originalInvalidateQueries(...args)
    })

  const fileInput = screen.getByLabelText('选择简历文件')
  const uploadZone = screen.getByTestId('analysis-upload-zone')
  await user.upload(fileInput, submittedResume)
  setTextValues(exactTitle, exactJobContent)
  fireEvent.dragEnter(uploadZone, {
    dataTransfer: { files: [submittedResume] },
  })
  expect(uploadZone).toHaveAttribute('data-dragging', 'true')
  await user.click(screen.getByRole('button', { name: '提交分析' }))

  await waitFor(() => {
    expect(requestCount).toBe(1)
  })
  await waitFor(() => {
    expect(invalidateSpy).toHaveBeenCalledTimes(2)
  })
  try {
    expect(queryClient.getMutationCache().getAll()).toEqual(mutationBaseline)
    expect(router.state.location.pathname).toBe('/analyses/new')
    expect(fileInput).toHaveValue('')
    expect((fileInput as HTMLInputElement).files).toHaveLength(0)
    expect(screen.getByRole('textbox', { name: '岗位名称' })).toHaveValue('')
    expect(screen.getByRole('textbox', { name: '岗位 JD' })).toHaveValue('')
    expect(uploadZone).toHaveAttribute('data-dragging', 'false')
  } finally {
    releaseInvalidations()
  }
  await waitFor(() => {
    expect(router.state.location.pathname).toBe('/analyses/42')
  })
  expect(router.state.historyAction).toBe('REPLACE')
  expect(queryClient.getMutationCache().getAll()).toEqual(mutationBaseline)
  expect(submittedInput).toBe('/api/analysis-submissions')
  expect(submittedInit?.method).toBe('POST')
  expect(new Headers(submittedInit?.headers).has('Content-Type')).toBe(false)
  expect(submittedInit?.body).toBeInstanceOf(FormData)
  const submittedFormData = submittedInit?.body as FormData
  expect(submittedFormData.get('jobTitle')).toBe(exactTitle)
  expect(submittedFormData.get('jobContent')).toBe(exactJobContent)
  const submittedFile = submittedFormData.get('file')
  expect(submittedFile).toBeInstanceOf(File)
  expect((submittedFile as File).name).toBe(submittedResume.name)
  expect((submittedFile as File).size).toBe(submittedResume.size)

  expect(queryClient.getQueryState(['analysis-summary'])?.isInvalidated).toBe(
    true,
  )
  expect(queryClient.getQueryState(firstHistoryKey)?.isInvalidated).toBe(true)
  expect(queryClient.getQueryState(secondHistoryKey)?.isInvalidated).toBe(true)
  expect(queryClient.getQueryState(unrelatedKey)?.isInvalidated).toBe(false)

  await router.navigate(-1)
  await waitFor(() => {
    expect(router.state.location.pathname).toBe('/analyses/999')
  })

  await router.navigate('/analyses/new')
  const resetFileInput = await screen.findByLabelText('选择简历文件')
  expect(resetFileInput).toHaveValue('')
  expect((resetFileInput as HTMLInputElement).files).toHaveLength(0)
  expect(screen.getByRole('textbox', { name: '岗位名称' })).toHaveValue('')
  expect(screen.getByRole('textbox', { name: '岗位 JD' })).toHaveValue('')
  expect(screen.queryByText(submittedResume.name)).not.toBeInTheDocument()
  expect(screen.queryByText(exactJobContent)).not.toBeInTheDocument()
})

test('rejects a title above 120 Unicode code points without submitting', async () => {
  let requestCount = 0
  server.use(
    http.post('/api/analysis-submissions', () => {
      requestCount += 1
      return HttpResponse.json(createSubmissionResponse())
    }),
  )
  const user = userEvent.setup()
  renderNewAnalysis()
  await user.upload(
    screen.getByLabelText('选择简历文件'),
    new File(['resume'], 'candidate.pdf'),
  )
  setTextValues('😀'.repeat(121), VALID_JOB_CONTENT)

  await user.click(screen.getByRole('button', { name: '提交分析' }))

  expectFieldError(
    screen.getByRole('textbox', { name: '岗位名称' }),
    '岗位名称不能超过 120 个字符',
  )
  expect(requestCount).toBe(0)
})

test('rejects a JD above 20,000 Unicode code points without submitting', async () => {
  let requestCount = 0
  server.use(
    http.post('/api/analysis-submissions', () => {
      requestCount += 1
      return HttpResponse.json(createSubmissionResponse())
    }),
  )
  const user = userEvent.setup()
  renderNewAnalysis()
  await user.upload(
    screen.getByLabelText('选择简历文件'),
    new File(['resume'], 'candidate.pdf'),
  )
  setTextValues(VALID_TITLE, '🚀'.repeat(20_001))

  await user.click(screen.getByRole('button', { name: '提交分析' }))

  expectFieldError(
    screen.getByRole('textbox', { name: '岗位 JD' }),
    '岗位描述不能超过 20,000 个字符',
  )
  expect(requestCount).toBe(0)
})

test('preserves all values after a structured server error, shows only a safe message and retries once requested', async () => {
  const rawServerMessage = 'SECRET_RAW_PAYLOAD: rejected resume body'
  let requestCount = 0
  server.use(
    http.post('/api/analysis-submissions', () => {
      requestCount += 1
      return requestCount === 1
        ? HttpResponse.json(
            {
              code: 'RAW_SERVER_CODE',
              message: rawServerMessage,
              requestId: 'req-safe-123',
            },
            { status: 422 },
          )
        : HttpResponse.json(createSubmissionResponse())
    }),
  )
  const user = userEvent.setup()
  const { queryClient, router } = renderNewAnalysis()
  const mutationBaseline = queryClient.getMutationCache().getAll()
  expect(mutationBaseline).toHaveLength(0)
  const file = new File(['resume'], 'retry-me.pdf')
  const fileInput = screen.getByLabelText('选择简历文件')
  await user.upload(fileInput, file)
  setTextValues()

  await user.click(screen.getByRole('button', { name: '提交分析' }))

  await waitFor(() => {
    expect(requestCount).toBe(1)
  })
  const alert = await screen.findByRole('alert')
  expect(alert).toHaveTextContent('提交失败，请稍后重试。')
  expect(alert).toHaveTextContent('关联 ID：req-safe-123')
  expect(document.body).not.toHaveTextContent(rawServerMessage)
  expect(document.body).not.toHaveTextContent('RAW_SERVER_CODE')
  expect(fileInput).toHaveValue('')
  expect((fileInput as HTMLInputElement).files).toHaveLength(0)
  expect(screen.getByRole('textbox', { name: '岗位名称' })).toHaveValue(
    VALID_TITLE,
  )
  expect(screen.getByRole('textbox', { name: '岗位 JD' })).toHaveValue(
    VALID_JOB_CONTENT,
  )
  expect(
    within(screen.getByRole('status', { name: '已选择简历' })).getByText(
      file.name,
    ),
  ).toBeVisible()
  expect(queryClient.getMutationCache().getAll()).toEqual(mutationBaseline)

  await user.click(screen.getByRole('button', { name: '提交分析' }))

  await waitFor(() => {
    expect(router.state.location.pathname).toBe('/analyses/42')
  })
  expect(requestCount).toBe(2)
  expect(queryClient.getMutationCache().getAll()).toEqual(mutationBaseline)
})

test('clears a stale request error when an edited title makes the retry invalid', async () => {
  let requestCount = 0
  server.use(
    http.post('/api/analysis-submissions', () => {
      requestCount += 1
      return HttpResponse.json(
        {
          code: 'SUBMISSION_REJECTED',
          message: 'unsafe server detail',
          requestId: 'req-stale-title',
        },
        { status: 422 },
      )
    }),
  )
  const user = userEvent.setup()
  renderNewAnalysis()
  const file = new File(['resume'], 'keep-me.pdf')
  await user.upload(screen.getByLabelText('选择简历文件'), file)
  setTextValues()

  await user.click(screen.getByRole('button', { name: '提交分析' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '关联 ID：req-stale-title',
  )

  const titleInput = screen.getByRole('textbox', { name: '岗位名称' })
  await user.clear(titleInput)
  await user.click(screen.getByRole('button', { name: '提交分析' }))

  expect(screen.queryByText('关联 ID：req-stale-title')).not.toBeInTheDocument()
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expectFieldError(titleInput, '请输入岗位名称')
  expect(requestCount).toBe(1)
  expect(screen.getByRole('textbox', { name: '岗位 JD' })).toHaveValue(
    VALID_JOB_CONTENT,
  )
  expect(
    within(screen.getByRole('status', { name: '已选择简历' })).getByText(
      file.name,
    ),
  ).toBeVisible()
})

test('clears a stale request error when the RHF file selection changes', async () => {
  server.use(
    http.post('/api/analysis-submissions', () =>
      HttpResponse.json(
        {
          code: 'SUBMISSION_REJECTED',
          message: 'unsafe server detail',
          requestId: 'req-stale-file',
        },
        { status: 422 },
      ),
    ),
  )
  const user = userEvent.setup()
  renderNewAnalysis()
  const fileInput = screen.getByLabelText('选择简历文件')
  const originalFile = new File(['resume'], 'original.pdf')
  const replacementFile = new File(['replacement'], 'replacement.pdf')
  await user.upload(fileInput, originalFile)
  setTextValues()

  await user.click(screen.getByRole('button', { name: '提交分析' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '关联 ID：req-stale-file',
  )
  expect(
    within(screen.getByRole('status', { name: '已选择简历' })).getByText(
      originalFile.name,
    ),
  ).toBeVisible()

  await user.upload(fileInput, replacementFile)

  expect(screen.queryByText('关联 ID：req-stale-file')).not.toBeInTheDocument()
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  expect(fileInput).toHaveValue('')
  expect((fileInput as HTMLInputElement).files).toHaveLength(0)
  expect(screen.getByRole('textbox', { name: '岗位名称' })).toHaveValue(
    VALID_TITLE,
  )
  expect(screen.getByRole('textbox', { name: '岗位 JD' })).toHaveValue(
    VALID_JOB_CONTENT,
  )
  expect(
    within(screen.getByRole('status', { name: '已选择简历' })).getByText(
      replacementFile.name,
    ),
  ).toBeVisible()
})

test('reduces an error and its sensitive cause chain to a plain allowlisted summary', () => {
  const submittedValues = {
    file: new File(['private resume'], 'private-resume.pdf'),
    jobTitle: 'private job title',
    jobContent: 'private job description',
  }
  const rootCause = Object.assign(new Error('private network failure'), {
    cause: submittedValues,
  })
  const apiError = Object.assign(
    new ApiError(
      422,
      'RAW_SERVER_CODE',
      'private server payload',
      'req-safe-123',
      { cause: rootCause },
    ),
    { payload: submittedValues },
  )

  const summary: unknown = summarizeSubmissionError(apiError)

  expect(summary).toEqual({
    code: 'RAW_SERVER_CODE',
    message: '提交失败，请稍后重试。',
    requestId: 'req-safe-123',
  })
  expect(summary).not.toBeInstanceOf(Error)
  expect(Object.getPrototypeOf(summary)).toBe(Object.prototype)
  expect(Object.keys(summary as object).sort()).toEqual([
    'code',
    'message',
    'requestId',
  ])
  expect(Object.values(summary as object)).toSatisfy((values: unknown[]) =>
    values.every((value) => typeof value === 'string'),
  )
  expect(summary).not.toHaveProperty('cause')
  expect(summary).not.toHaveProperty('payload')
  expect(summary).not.toHaveProperty('file')
  expect(summary).not.toHaveProperty('jobTitle')
  expect(summary).not.toHaveProperty('jobContent')

  expect(summarizeSubmissionError(rootCause)).toEqual({
    code: 'SUBMISSION_FAILED',
    message: '提交失败，请稍后重试。',
  })
})

test('preserves the form after a network error without exposing the raw failure', async () => {
  let requestCount = 0
  server.use(
    http.post('/api/analysis-submissions', () => {
      requestCount += 1
      return HttpResponse.error()
    }),
  )
  const user = userEvent.setup()
  renderNewAnalysis()
  const file = new File(['resume'], 'network-retry.docx')
  await user.upload(screen.getByLabelText('选择简历文件'), file)
  setTextValues()

  await user.click(screen.getByRole('button', { name: '提交分析' }))

  await waitFor(() => {
    expect(requestCount).toBe(1)
  })
  const alert = await screen.findByRole('alert')
  expect(alert).toHaveTextContent('提交失败，请稍后重试。')
  expect(alert).not.toHaveTextContent('Failed to fetch')
  expect(screen.getByRole('textbox', { name: '岗位名称' })).toHaveValue(
    VALID_TITLE,
  )
  expect(screen.getByRole('textbox', { name: '岗位 JD' })).toHaveValue(
    VALID_JOB_CONTENT,
  )
  expect(
    within(screen.getByRole('status', { name: '已选择简历' })).getByText(
      file.name,
    ),
  ).toBeVisible()
})

test('disables the stable submit button while one request is pending and blocks a double submit', async () => {
  let requestCount = 0
  let markRequestStarted: () => void = () => undefined
  let releaseResponse: () => void = () => undefined
  const requestStarted = new Promise<void>((resolve) => {
    markRequestStarted = resolve
  })
  const responseGate = new Promise<void>((resolve) => {
    releaseResponse = resolve
  })
  server.use(
    http.post('/api/analysis-submissions', async () => {
      requestCount += 1
      markRequestStarted()
      await responseGate
      return HttpResponse.json(createSubmissionResponse())
    }),
  )
  const user = userEvent.setup()
  const { router } = renderNewAnalysis()
  const fileInput = screen.getByLabelText('选择简历文件')
  const titleInput = screen.getByRole('textbox', { name: '岗位名称' })
  const jobContentInput = screen.getByRole('textbox', { name: '岗位 JD' })
  const uploadZone = screen.getByTestId('analysis-upload-zone')
  const originalFile = new File(['resume'], 'single-submit.pdf')
  await user.upload(fileInput, originalFile)
  setTextValues()
  const submitButton = screen.getByRole('button', { name: '提交分析' })

  await user.click(submitButton)
  await requestStarted

  try {
    expect(submitButton).toBeDisabled()
    expect(submitButton).toHaveAttribute('aria-busy', 'true')
    expect(fileInput).toBeDisabled()
    expect(titleInput).toBeDisabled()
    expect(jobContentInput).toBeDisabled()
    expect(uploadZone).toHaveAttribute('aria-disabled', 'true')
    fireEvent.drop(uploadZone, {
      dataTransfer: { files: [new File(['changed'], 'changed.pdf')] },
    })
    expect(
      within(screen.getByRole('status', { name: '已选择简历' })).getByText(
        originalFile.name,
      ),
    ).toBeVisible()
    await user.click(submitButton)
    expect(requestCount).toBe(1)
  } finally {
    releaseResponse()
  }

  await waitFor(() => {
    expect(router.state.location.pathname).toBe('/analyses/42')
  })
  expect(requestCount).toBe(1)
})

test('does not start a POST when an async resolver completes after StrictMode unmount', async () => {
  let requestCount = 0
  let markResolverStarted: () => void = () => undefined
  let markResolverFinished: () => void = () => undefined
  let releaseResolver: () => void = () => undefined
  const resolverStarted = new Promise<void>((resolve) => {
    markResolverStarted = resolve
  })
  const resolverFinished = new Promise<void>((resolve) => {
    markResolverFinished = resolve
  })
  const resolverGate = new Promise<void>((resolve) => {
    releaseResolver = resolve
  })
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input) => {
    if (input === '/backend-health') {
      return Response.json({ status: 'UP' })
    }

    if (input === '/api/analysis-submissions') {
      requestCount += 1
      return Response.json(createSubmissionResponse())
    }

    throw new Error(`Unexpected fetch input: ${String(input)}`)
  })
  const user = userEvent.setup()
  const app = renderNewAnalysis(['/analyses/new'], { strictMode: true })
  const invalidateSpy = vi.spyOn(app.queryClient, 'invalidateQueries')
  const navigateSpy = vi.spyOn(app.router, 'navigate')
  await user.upload(
    screen.getByLabelText('选择简历文件'),
    new File(['resume'], 'resolver-race.pdf'),
  )
  setTextValues()
  const originalResolverRun = analysisFormSchema._zod.run
  vi.spyOn(analysisFormSchema._zod, 'run').mockImplementation(
    async (payload, context) => {
      markResolverStarted()
      await resolverGate
      try {
        const result = await originalResolverRun(payload, context)
        return result
      } finally {
        markResolverFinished()
      }
    },
  )

  fireEvent.submit(screen.getByRole('form', { name: '新建分析表单' }))
  await resolverStarted
  app.dispose()
  releaseResolver()
  await resolverFinished
  await new Promise((resolve) => setTimeout(resolve, 0))

  expect(requestCount).toBe(0)
  expect(invalidateSpy).not.toHaveBeenCalled()
  expect(navigateSpy).not.toHaveBeenCalled()
  expect(app.router.state.location.pathname).toBe('/analyses/new')
})

test('aborts a slow submission on unmount without cache, invalidation or navigation side effects', async () => {
  let observedSignal: AbortSignal | null | undefined
  let markRequestStarted: () => void = () => undefined
  let rejectSubmission: (reason?: unknown) => void = () => undefined
  let abortObserved = false
  const requestStarted = new Promise<void>((resolve) => {
    markRequestStarted = resolve
  })
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, init) => {
    if (input === '/backend-health') {
      return Response.json({ status: 'UP' })
    }

    if (input !== '/api/analysis-submissions') {
      throw new Error(`Unexpected fetch input: ${String(input)}`)
    }

    observedSignal = init?.signal
    markRequestStarted()
    return new Promise<Response>((_resolve, reject) => {
      rejectSubmission = reject
      observedSignal?.addEventListener(
        'abort',
        () => {
          abortObserved = true
          reject(new DOMException('Aborted', 'AbortError'))
        },
        { once: true },
      )
    })
  })
  const user = userEvent.setup()
  const app = renderNewAnalysis()
  const mutationBaseline = app.queryClient.getMutationCache().getAll()
  const invalidateSpy = vi.spyOn(app.queryClient, 'invalidateQueries')
  const navigateSpy = vi.spyOn(app.router, 'navigate')
  await user.upload(
    screen.getByLabelText('选择简历文件'),
    new File(['resume'], 'abort-me.pdf'),
  )
  setTextValues()

  try {
    await user.click(screen.getByRole('button', { name: '提交分析' }))
    await requestStarted

    expect(mutationBaseline).toHaveLength(0)
    expect(app.queryClient.getMutationCache().getAll()).toEqual(
      mutationBaseline,
    )
    expect(observedSignal).toBeInstanceOf(AbortSignal)
    expect(observedSignal?.aborted).toBe(false)

    app.dispose()

    expect(observedSignal?.aborted).toBe(true)
    await waitFor(() => {
      expect(abortObserved).toBe(true)
    })
    expect(invalidateSpy).not.toHaveBeenCalled()
    expect(navigateSpy).not.toHaveBeenCalled()
    expect(app.router.state.location.pathname).toBe('/analyses/new')
  } finally {
    rejectSubmission(new DOMException('Test cleanup', 'AbortError'))
    app.dispose()
  }
})

test('keeps upload, long filename, textarea and submit dimensions stable across responsive layouts', () => {
  const cssPath = resolve(
    cwd(),
    'src/features/analyses/analysis-form.css',
  )
  const sourcePath = resolve(
    cwd(),
    'src/features/analyses/NewAnalysisPage.tsx',
  )

  expect(existsSync(cssPath)).toBe(true)
  expect(existsSync(sourcePath)).toBe(true)
  if (!existsSync(cssPath) || !existsSync(sourcePath)) {
    return
  }

  const css = readFileSync(cssPath, 'utf8')
  const source = readFileSync(sourcePath, 'utf8')
  const draggingRule = css.match(
    /\.analysis-upload-zone\[data-dragging="true"\]\s*\{[^}]*\}/s,
  )?.[0]

  expect(css).toMatch(
    /\.analysis-upload-zone\s*\{[^}]*min-height:\s*[^;]+;/s,
  )
  expect(css).toMatch(
    /\.analysis-upload-selection\s*\{[^}]*min-height:\s*[^;]+;/s,
  )
  expect(css).toMatch(
    /\.analysis-upload-selection__empty\s*\{[^}]*grid-column:\s*1\s*\/\s*-1;/s,
  )
  expect(draggingRule).toBeDefined()
  expect(draggingRule).not.toMatch(
    /(?:border-width|height|min-height|margin|padding)\s*:/,
  )
  expect(css).toMatch(
    /\.analysis-file-name\s*\{[^}]*overflow-wrap:\s*anywhere;/s,
  )
  expect(css).toMatch(
    /\.analysis-form textarea\s*\{[^}]*width:\s*100%;/s,
  )
  expect(css).toMatch(
    /\.analysis-form__submit\s*\{[^}]*min-width:\s*[^;]+;/s,
  )
  expect(css).toMatch(/@media\s*\(max-width:\s*48rem\)/)

  expect(source).toContain('useForm')
  expect(source).toContain('zodResolver(analysisFormSchema)')
  expect(source).toContain('createAnalysisSubmission')
  expect(source).not.toMatch(/\b(?:localStorage|sessionStorage)\b/)
  expect(source).not.toMatch(/\bconsole\./)
  expect(source).not.toContain('Content-Type')
})
