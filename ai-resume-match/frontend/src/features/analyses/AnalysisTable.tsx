import { Link } from 'react-router'

import type { AnalysisListItem } from '../../shared/api/schemas'
import { StatusBadge } from '../../shared/components/StatusBadge'
import { formatAnalysisDate, formatAnalysisScore } from './analysisPresentation'
import './analysis-table.css'

interface AnalysisTableProps {
  accessibleName: string
  items: AnalysisListItem[]
  recent?: boolean
}

interface AnalysisTableSkeletonProps {
  label: string
  recent?: boolean
  rowCount: number
}

function AnalysisTableColumns() {
  return (
    <colgroup>
      <col className="analysis-table__col--title" />
      <col className="analysis-table__col--resume" />
      <col className="analysis-table__col--score" />
      <col className="analysis-table__col--status" />
      <col className="analysis-table__col--completed" />
    </colgroup>
  )
}

function AnalysisTableHeader() {
  return (
    <thead>
      <tr>
        <th scope="col">岗位</th>
        <th className="analysis-table__cell--resume" scope="col">
          简历
        </th>
        <th className="analysis-table__cell--score" scope="col">
          匹配度
        </th>
        <th className="analysis-table__cell--status" scope="col">
          状态
        </th>
        <th className="analysis-table__cell--completed" scope="col">
          完成时间
        </th>
      </tr>
    </thead>
  )
}

export function AnalysisTable({
  accessibleName,
  items,
  recent = false,
}: AnalysisTableProps) {
  return (
    <div
      className={`analysis-table-wrap${recent ? ' analysis-table-wrap--recent' : ''}`}
    >
      <table aria-label={accessibleName} className="analysis-table">
        <AnalysisTableColumns />
        <AnalysisTableHeader />
        <tbody>
          {items.map((item) => {
            const jobTitle = item.jobTitle?.trim() || '未命名岗位'
            const resumeFileName = item.resumeFileName?.trim() || '—'
            const completedAt = formatAnalysisDate(item.completedAt)

            return (
              <tr key={item.taskId}>
                <th scope="row">
                  <Link
                    className="analysis-table__title-link"
                    title={jobTitle}
                    to={`/analyses/${item.taskId}`}
                  >
                    {jobTitle}
                  </Link>
                </th>
                <td className="analysis-table__cell--resume">
                  <span
                    className="analysis-table__filename"
                    title={resumeFileName === '—' ? undefined : resumeFileName}
                  >
                    {resumeFileName}
                  </span>
                </td>
                <td className="analysis-table__cell--score">
                  {formatAnalysisScore(item.matchScore)}
                </td>
                <td className="analysis-table__cell--status">
                  <StatusBadge status={item.status} />
                </td>
                <td className="analysis-table__cell--completed">
                  {item.completedAt === null ? (
                    '—'
                  ) : (
                    <time dateTime={item.completedAt}>{completedAt}</time>
                  )}
                </td>
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}

export function AnalysisTableSkeleton({
  label,
  recent = false,
  rowCount,
}: AnalysisTableSkeletonProps) {
  return (
    <div
      aria-busy="true"
      aria-label={label}
      className={`analysis-table-loading${recent ? ' analysis-table-loading--recent' : ''}`}
      role="status"
    >
      <div
        aria-hidden="true"
        className={`analysis-table-wrap${recent ? ' analysis-table-wrap--recent' : ''}`}
      >
        <table className="analysis-table">
          <AnalysisTableColumns />
          <AnalysisTableHeader />
          <tbody>
            {Array.from({ length: rowCount }, (_, index) => (
              <tr data-testid="analysis-row-skeleton" key={index}>
                <th scope="row">
                  <span className="skeleton-line skeleton-line--title" />
                </th>
                <td className="analysis-table__cell--resume">
                  <span className="skeleton-line" />
                </td>
                <td className="analysis-table__cell--score">
                  <span className="skeleton-line skeleton-line--short" />
                </td>
                <td className="analysis-table__cell--status">
                  <span className="skeleton-line skeleton-line--badge" />
                </td>
                <td className="analysis-table__cell--completed">
                  <span className="skeleton-line" />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  )
}
