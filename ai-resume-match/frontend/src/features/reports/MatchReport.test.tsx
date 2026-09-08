import { fireEvent, render, screen } from '@testing-library/react'
import { expect, test, vi } from 'vitest'

import { MatchReport } from './MatchReport'

const report = {
  taskId: 42,
  matchScore: 88,
  reportSchemaVersion: 'markdown-v1',
  structuredReport: null,
  provenance: null,
  reportContent: `# 匹配结论

- Java
- Redis

| 维度 | 评价 |
| --- | --- |
| 技能 | 良好 |

<script>window.__unsafeReportScript = true</script>
<div id="unsafe-report-html" onclick="window.__unsafeReportHtml = true">raw html</div>

[安全外链](https://example.com/report?q=match)
[站内链接](/analyses/42)
[脚本链接](javascript:alert('unsafe'))
[数据链接](data:text/html,unsafe)
`,
  createdAt: '2026-07-10T09:05:00',
} as const

test('offers JSON, Markdown and print actions for validated reports', () => {
  const print = vi.spyOn(window, 'print').mockImplementation(() => undefined)
  render(<MatchReport report={report} />)

  const actions = screen.getByRole('group', { name: '报告操作' })
  expect(actions).toBeVisible()
  expect(
    screen.getByRole('button', { name: '下载 JSON' }),
  ).toBeVisible()
  expect(
    screen.getByRole('button', { name: '下载 Markdown' }),
  ).toBeVisible()

  fireEvent.click(screen.getByRole('button', { name: '打印报告' }))

  expect(print).toHaveBeenCalledOnce()
})

test('renders GFM headings, lists and tables', () => {
  render(<MatchReport report={report} />)

  expect(
    screen.getByRole('heading', { level: 1, name: '匹配结论' }),
  ).toBeVisible()
  expect(screen.getByRole('list')).toBeVisible()
  expect(screen.getByRole('table')).toBeVisible()
  expect(screen.getByRole('cell', { name: '良好' })).toBeVisible()
})

test('omits raw HTML and scripts without creating dangerous DOM', () => {
  render(<MatchReport report={report} />)

  expect(document.querySelector('script')).toBeNull()
  expect(document.querySelector('#unsafe-report-html')).toBeNull()
  expect(document.querySelector('[onclick]')).toBeNull()
  expect(
    (window as Window & { __unsafeReportScript?: boolean })
      .__unsafeReportScript,
  ).toBeUndefined()
  expect(
    (window as Window & { __unsafeReportHtml?: boolean }).__unsafeReportHtml,
  ).toBeUndefined()
})

test('opens safe external links defensively and leaves safe internal links local', () => {
  render(<MatchReport report={report} />)

  expect(screen.getByRole('link', { name: '安全外链' })).toHaveAttribute(
    'href',
    'https://example.com/report?q=match',
  )
  expect(screen.getByRole('link', { name: '安全外链' })).toHaveAttribute(
    'target',
    '_blank',
  )
  expect(screen.getByRole('link', { name: '安全外链' })).toHaveAttribute(
    'rel',
    'noreferrer noopener',
  )

  expect(screen.getByRole('link', { name: '站内链接' })).toHaveAttribute(
    'href',
    '/analyses/42',
  )
  expect(screen.getByRole('link', { name: '站内链接' })).not.toHaveAttribute(
    'target',
  )
})

test('renders dangerous-scheme link labels as non-clickable text', () => {
  render(<MatchReport report={report} />)

  expect(screen.getByText('脚本链接')).toBeVisible()
  expect(screen.getByText('数据链接')).toBeVisible()
  expect(
    screen.queryByRole('link', { name: '脚本链接' }),
  ).not.toBeInTheDocument()
  expect(
    screen.queryByRole('link', { name: '数据链接' }),
  ).not.toBeInTheDocument()
})

test('omits every Markdown image without exposing a source URL', () => {
  const imageReport = {
    ...report,
    reportContent: `![external tracker](https://images.example/private.png)
![embedded bitmap](data:image/png;base64,cHJpdmF0ZQ==)
![external svg](https://images.example/private.svg?candidate=42)
![embedded svg](data:image/svg+xml,%3Csvg%20xmlns='http://www.w3.org/2000/svg'%3E%3C/svg%3E)`,
  }

  const { container } = render(<MatchReport report={imageReport} />)

  expect(container.querySelector('img')).toBeNull()
  expect(container.querySelector('[src]')).toBeNull()
  expect(screen.getAllByText('图片已省略')).toHaveLength(4)
  expect(container).not.toHaveTextContent('https://images.example')
  expect(container).not.toHaveTextContent('data:image')
})

test('renders structured requirements and opens cited evidence with provenance', () => {
  const structuredReport = {
    ...report,
    matchScore: 100,
    reportSchemaVersion: 'match-report-v2' as const,
    structuredReport: {
      schemaVersion: 'match-report-v2' as const,
      matchScore: 100,
      requirements: [
        {
          requirementId: 'requirement:0',
          text: 'Java 后端开发',
          mustHave: true,
          weight: 2,
          modelStatus: 'supported' as const,
          status: 'supported' as const,
          explanation: '引用片段覆盖 Java 后端经验。',
          evidenceIds: ['resume:0'],
          verification: {
            verifierVersion: 'verifier-v1',
            status: 'supported' as const,
            termCoverage: 1,
            reason: 'required_terms_supported',
            evidenceIds: ['resume:0'],
          },
        },
      ],
      coreClaims: [{ claim: '具备 Java 经验', evidenceIds: ['resume:0'] }],
      matchedSkills: [],
      skillGaps: ['缺少线上容量数据'],
      recommendations: ['补充指标', '补充压测', '补充告警'],
      interviewQuestions: ['如何限流？', '如何重试？', '如何评测？'],
      evidence: [
        {
          evidenceId: 'resume:0',
          excerpt: 'Implemented Java Spring Boot services.',
          score: 0.91,
          sourceStart: 0,
          sourceEnd: 38,
        },
      ],
      scoreBreakdown: {
        rawScore: 100,
        finalScore: 100,
        totalWeight: 2,
        supportedWeight: 2,
        partialWeight: 0,
        missingWeight: 0,
        mustHaveCapApplied: false,
      },
    },
    provenance: {
      schemaVersion: 'analysis-run-v1' as const,
      correlationId: 'correlation-42',
      model: 'test-model',
      promptVersion: 'prompt-v3',
      retrieverVersion: 'hybrid-v1',
      verifierVersion: 'verifier-v1',
      traceId: '0123456789abcdef0123456789abcdef',
      steps: 3,
      runMetadata: {
        schemaVersion: 'agent-run-v1' as const,
        requestSchemaVersion: 'agent-analysis-request-v1' as const,
        agentRuntimeVersion: 'bounded-tool-agent-v1' as const,
        inputFingerprintVersion: 'sha256-task-scoped-length-prefixed-v1' as const,
        inputFingerprint:
          '6d4c96fb440708f7e456e0598d60ea1b940fd2441362c792961f8bd89345b5d1',
        chatProviderCalls: 3,
        chatProviderDurationMs: 80,
        toolDurationMs: 4,
        totalDurationMs: 125,
        contextCharsSent: 2048,
      },
      modelUsage: {
        promptTokens: 100,
        completionTokens: 30,
        totalTokens: 130,
        providerReported: true,
        estimatedCostUsd: '0.00027000',
        pricingVersion: 'test-price-2026-08-01',
      },
      toolTrace: [
        { name: 'search_resume_evidence', outcome: 'success', durationMs: 4 },
      ],
    },
  }

  render(<MatchReport report={structuredReport} />)

  expect(screen.getByRole('heading', { name: 'Java 后端开发' })).toBeVisible()
  expect(screen.getByText('已支持')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '查看证据 resume:0' }))
  expect(screen.getByRole('complementary', { name: '证据详情 resume:0' })).toHaveTextContent(
    'Implemented Java Spring Boot services.',
  )
  fireEvent.click(screen.getByText('查看运行溯源'))
  expect(screen.getByText('test-model')).toBeVisible()
  expect(screen.getByText('bounded-tool-agent-v1')).toBeVisible()
  expect(screen.getByText(/总计 125 ms/)).toBeVisible()
  expect(screen.getByText(/Chat 3 次 \/ 上下文 2048 字符/)).toBeVisible()
  expect(screen.getByText('$0.00027000 USD（test-price-2026-08-01）')).toBeVisible()
  fireEvent.click(screen.getByRole('button', { name: '关闭' }))
  expect(
    screen.queryByRole('complementary', { name: '证据详情 resume:0' }),
  ).not.toBeInTheDocument()
})
