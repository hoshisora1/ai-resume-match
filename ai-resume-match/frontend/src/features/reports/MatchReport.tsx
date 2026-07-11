import type { ReactNode } from 'react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

import type { MatchReport as MatchReportData } from '../../shared/api/schemas'
import './match-report.css'

interface SafeLink {
  external: boolean
  href: string
}

function readSafeLink(href: string | undefined): SafeLink | undefined {
  if (href === undefined || href.trim() !== href || href.length === 0) {
    return undefined
  }

  if (
    href.startsWith('#') ||
    href.startsWith('?') ||
    href.startsWith('./') ||
    href.startsWith('../') ||
    (href.startsWith('/') && !href.startsWith('//'))
  ) {
    return { external: false, href }
  }

  if (!/^https?:\/\//i.test(href)) {
    return undefined
  }

  try {
    const url = new URL(href)
    if (url.protocol !== 'http:' && url.protocol !== 'https:') {
      return undefined
    }
    return { external: true, href: url.href }
  } catch {
    return undefined
  }
}

function SafeMarkdownLink({
  children,
  href,
}: {
  children?: ReactNode
  href?: string | undefined
}) {
  const safeLink = readSafeLink(href)
  if (safeLink === undefined) {
    return <span>{children}</span>
  }

  return (
    <a
      href={safeLink.href}
      {...(safeLink.external
        ? { rel: 'noreferrer noopener', target: '_blank' }
        : {})}
    >
      {children}
    </a>
  )
}

interface MatchReportProps {
  report: MatchReportData
}

export function MatchReport({ report }: MatchReportProps) {
  return (
    <div className="match-report">
      <div className="match-report__score">
        <span>匹配度</span>
        <strong>{report.matchScore.toFixed(1)} / 100</strong>
      </div>
      <div className="match-report__content">
        <ReactMarkdown
          components={{
            a: ({ children, href }) => (
              <SafeMarkdownLink href={href}>{children}</SafeMarkdownLink>
            ),
            img: () => <span className="match-report__image-omitted">图片已省略</span>,
            table: ({ children }) => (
              <div className="match-report__table-scroll">
                <table>{children}</table>
              </div>
            ),
          }}
          remarkPlugins={[remarkGfm]}
        >
          {report.reportContent}
        </ReactMarkdown>
      </div>
    </div>
  )
}
