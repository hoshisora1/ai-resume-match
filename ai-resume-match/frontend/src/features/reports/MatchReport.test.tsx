import { render, screen } from '@testing-library/react'
import { expect, test } from 'vitest'

import { MatchReport } from './MatchReport'

const report = {
  taskId: 42,
  matchScore: 88,
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
