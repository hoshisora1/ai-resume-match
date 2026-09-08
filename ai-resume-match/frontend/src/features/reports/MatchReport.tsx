import { useState, type ReactNode } from 'react'
import { Download, FileJson, Printer } from 'lucide-react'
import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

import type { MatchReport as MatchReportData } from '../../shared/api/schemas'
import { Button } from '../../shared/components/Button'
import {
  createReportExport,
  saveReportExport,
  type ReportExportFormat,
} from './reportExport'
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

const statusLabels = {
  supported: '已支持',
  partial: '部分支持',
  not_found: '未找到证据',
} as const

function evidenceTarget(evidenceId: string) {
  return `evidence-${evidenceId.replace(':', '-')}`
}

export function MatchReport({ report }: MatchReportProps) {
  const [selectedEvidenceId, setSelectedEvidenceId] = useState<string | null>(null)
  const structured = report.structuredReport
  const selectedEvidence = structured?.evidence.find(
    (evidence) => evidence.evidenceId === selectedEvidenceId,
  )
  const downloadReport = (format: ReportExportFormat) => {
    saveReportExport(createReportExport(report, format))
  }

  return (
    <div className="match-report">
      <div aria-label="报告操作" className="match-report__actions" role="group">
        <Button onClick={() => downloadReport('json')} variant="secondary">
          <FileJson aria-hidden="true" size={16} />
          <span>下载 JSON</span>
        </Button>
        <Button onClick={() => downloadReport('markdown')} variant="secondary">
          <Download aria-hidden="true" size={16} />
          <span>下载 Markdown</span>
        </Button>
        <Button onClick={() => window.print()} variant="secondary">
          <Printer aria-hidden="true" size={16} />
          <span>打印报告</span>
        </Button>
      </div>
      <div className="match-report__score">
        <span>匹配度</span>
        <strong>{report.matchScore.toFixed(1)} / 100</strong>
      </div>
      {structured !== null ? (
        <div className="match-report__structured">
          <section aria-labelledby="requirement-breakdown-title">
            <div className="match-report__section-heading">
              <div>
                <span className="match-report__eyebrow">可解释评分</span>
                <h2 id="requirement-breakdown-title">岗位要求逐项判定</h2>
              </div>
              <span className="match-report__weight-summary">
                支持权重 {structured.scoreBreakdown.supportedWeight} /{' '}
                {structured.scoreBreakdown.totalWeight}
              </span>
            </div>
            <div className="match-report__requirements">
              {structured.requirements.map((requirement) => (
                <article
                  className="match-report__requirement"
                  key={requirement.requirementId}
                >
                  <div className="match-report__requirement-heading">
                    <h3>{requirement.text}</h3>
                    <span
                      className={`match-report__status match-report__status--${requirement.status}`}
                    >
                      {statusLabels[requirement.status]}
                    </span>
                  </div>
                  <p>{requirement.explanation}</p>
                  <div className="match-report__requirement-meta">
                    <span>{requirement.mustHave ? '必须项' : '可选项'}</span>
                    <span>权重 {requirement.weight}</span>
                    <span>
                      术语覆盖 {Math.round(requirement.verification.termCoverage * 100)}%
                    </span>
                  </div>
                  {requirement.evidenceIds.length > 0 ? (
                    <div className="match-report__evidence-actions">
                      {requirement.evidenceIds.map((evidenceId) => (
                        <button
                          aria-controls={evidenceTarget(evidenceId)}
                          key={evidenceId}
                          onClick={() => setSelectedEvidenceId(evidenceId)}
                          type="button"
                        >
                          查看证据 {evidenceId}
                        </button>
                      ))}
                    </div>
                  ) : null}
                </article>
              ))}
            </div>
          </section>

          {selectedEvidence !== undefined ? (
            <aside
              aria-label={`证据详情 ${selectedEvidence.evidenceId}`}
              className="match-report__evidence-drawer"
              id={evidenceTarget(selectedEvidence.evidenceId)}
            >
              <div className="match-report__evidence-drawer-heading">
                <div>
                  <span className="match-report__eyebrow">简历原文证据</span>
                  <h2>{selectedEvidence.evidenceId}</h2>
                </div>
                <button onClick={() => setSelectedEvidenceId(null)} type="button">
                  关闭
                </button>
              </div>
              <blockquote>{selectedEvidence.excerpt}</blockquote>
              <p className="match-report__evidence-meta">
                检索分数 {selectedEvidence.score.toFixed(3)}
                {selectedEvidence.sourceStart !== null &&
                selectedEvidence.sourceEnd !== null
                  ? ` · 字符 ${selectedEvidence.sourceStart}–${selectedEvidence.sourceEnd}`
                  : ''}
              </p>
            </aside>
          ) : null}

          <div className="match-report__structured-lists match-report__structured-lists--claims">
            <section>
              <h2>核心结论</h2>
              <ul>
                {structured.coreClaims.map((item) => (
                  <li key={item.claim}>
                    <span>{item.claim}</span>
                    <div className="match-report__evidence-actions">
                      {item.evidenceIds.map((evidenceId) => (
                        <button
                          aria-label={`查看核心结论证据 ${evidenceId}`}
                          key={evidenceId}
                          onClick={() => setSelectedEvidenceId(evidenceId)}
                          type="button"
                        >
                          证据 {evidenceId}
                        </button>
                      ))}
                    </div>
                  </li>
                ))}
              </ul>
            </section>
            <section>
              <h2>部分匹配</h2>
              <ul>
                {structured.matchedSkills.map((item) => (
                  <li key={item.claim}>
                    <span>{item.claim}</span>
                    <div className="match-report__evidence-actions">
                      {item.evidenceIds.map((evidenceId) => (
                        <button
                          aria-label={`查看部分匹配证据 ${evidenceId}`}
                          key={evidenceId}
                          onClick={() => setSelectedEvidenceId(evidenceId)}
                          type="button"
                        >
                          证据 {evidenceId}
                        </button>
                      ))}
                    </div>
                  </li>
                ))}
              </ul>
            </section>
          </div>

          <div className="match-report__structured-lists">
            <section>
              <h2>能力差距</h2>
              <ul>
                {structured.skillGaps.map((item) => <li key={item}>{item}</li>)}
              </ul>
            </section>
            <section>
              <h2>改进建议</h2>
              <ul>
                {structured.recommendations.map((item) => <li key={item}>{item}</li>)}
              </ul>
            </section>
            <section>
              <h2>面试问题</h2>
              <ul>
                {structured.interviewQuestions.map((item) => <li key={item}>{item}</li>)}
              </ul>
            </section>
          </div>

          {report.provenance !== null ? (
            <details className="match-report__provenance">
              <summary>查看运行溯源</summary>
              <dl>
                <div>
                  <dt>模型</dt>
                  <dd>{report.provenance.model}</dd>
                </div>
                <div>
                  <dt>Prompt</dt>
                  <dd>{report.provenance.promptVersion}</dd>
                </div>
                <div>
                  <dt>Retriever</dt>
                  <dd>{report.provenance.retrieverVersion}</dd>
                </div>
                <div>
                  <dt>Verifier</dt>
                  <dd>{report.provenance.verifierVersion}</dd>
                </div>
                <div>
                  <dt>步骤</dt>
                  <dd>{report.provenance.steps}</dd>
                </div>
                <div>
                  <dt>Trace ID</dt>
                  <dd>{report.provenance.traceId ?? '未启用 tracing'}</dd>
                </div>
                {report.provenance.runMetadata !== null ? (
                  <>
                    <div>
                      <dt>Agent 运行时</dt>
                      <dd>{report.provenance.runMetadata.agentRuntimeVersion}</dd>
                    </div>
                    <div>
                      <dt>输入指纹</dt>
                      <dd>{report.provenance.runMetadata.inputFingerprint}</dd>
                    </div>
                    <div>
                      <dt>阶段耗时</dt>
                      <dd>
                        总计 {report.provenance.runMetadata.totalDurationMs} ms（Chat Provider{' '}
                        {report.provenance.runMetadata.chatProviderDurationMs} ms / Tool{' '}
                        {report.provenance.runMetadata.toolDurationMs} ms）
                      </dd>
                    </div>
                    <div>
                      <dt>调用预算</dt>
                      <dd>
                        Chat {report.provenance.runMetadata.chatProviderCalls} 次 / 上下文{' '}
                        {report.provenance.runMetadata.contextCharsSent} 字符
                      </dd>
                    </div>
                  </>
                ) : null}
                <div>
                  <dt>Token</dt>
                  <dd>
                    {report.provenance.modelUsage.totalTokens}
                    {report.provenance.modelUsage.providerReported ? '' : '（仅已报告部分）'}
                  </dd>
                </div>
                <div>
                  <dt>估算成本</dt>
                  <dd>
                    {report.provenance.modelUsage.estimatedCostUsd !== null &&
                    report.provenance.modelUsage.pricingVersion !== null
                      ? `$${report.provenance.modelUsage.estimatedCostUsd} USD（${report.provenance.modelUsage.pricingVersion}）`
                      : '未估算（未配置版本化价格或 usage 不完整）'}
                  </dd>
                </div>
              </dl>
            </details>
          ) : null}
        </div>
      ) : null}
      <details className="match-report__markdown" open={structured === null}>
        <summary>{structured === null ? '匹配报告' : '查看兼容 Markdown 报告'}</summary>
        <div className="match-report__content">
          <ReactMarkdown
            components={{
              a: ({ children, href }) => (
                <SafeMarkdownLink href={href}>{children}</SafeMarkdownLink>
              ),
              img: () => (
                <span className="match-report__image-omitted">图片已省略</span>
              ),
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
      </details>
    </div>
  )
}
