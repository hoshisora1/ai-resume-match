import { useCallback, useEffect } from 'react'
import { Link, useSearchParams } from 'react-router'

import type { AnalysisStatus } from '../../shared/api/schemas'
import { AsyncState } from '../../shared/components/AsyncState'
import { Button } from '../../shared/components/Button'
import {
  Pagination,
  type PaginationChangeOptions,
} from '../../shared/components/Pagination'
import { AnalysisTable, AnalysisTableSkeleton } from './AnalysisTable'
import {
  analysisStatusOptions,
  parseAnalysisStatus,
} from './analysisPresentation'
import { useAnalysesQuery } from './useAnalysesQuery'
import './analyses.css'

const DEFAULT_PAGE = 0
const DEFAULT_SIZE = 20
const JAVA_INTEGER_MAX_VALUE = 2_147_483_647
const PAGE_SIZES = [10, 20, 50, 100] as const
const CANONICAL_DECIMAL_INTEGER = /^(?:0|[1-9]\d*)$/

interface HistoryFilters {
  page: number
  size: number
  status: AnalysisStatus | undefined
}

function parseCanonicalInteger(
  value: string,
  minimum: number,
  maximum: number,
) {
  if (!CANONICAL_DECIMAL_INTEGER.test(value)) {
    return undefined
  }

  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed >= minimum && parsed <= maximum
    ? parsed
    : undefined
}

function readIntegerParameter(
  searchParams: URLSearchParams,
  name: string,
  fallback: number,
  minimum: number,
  maximum: number,
) {
  const values = searchParams.getAll(name)
  if (values.length !== 1) {
    return fallback
  }

  return parseCanonicalInteger(values[0]!, minimum, maximum) ?? fallback
}

function readHistoryFilters(searchParams: URLSearchParams): HistoryFilters {
  const statusValues = searchParams.getAll('status')
  return {
    status:
      statusValues.length === 1
        ? parseAnalysisStatus(statusValues[0]!)
        : undefined,
    page: readIntegerParameter(
      searchParams,
      'page',
      DEFAULT_PAGE,
      0,
      JAVA_INTEGER_MAX_VALUE,
    ),
    size: readIntegerParameter(searchParams, 'size', DEFAULT_SIZE, 1, 100),
  }
}

function buildHistorySearchParams({
  status,
  page,
  size,
}: HistoryFilters) {
  const searchParams = new URLSearchParams()
  if (status !== undefined) {
    searchParams.set('status', status)
  }
  searchParams.set('page', String(page))
  searchParams.set('size', String(size))
  return searchParams
}

export function AnalysesPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const { status, page, size } = readHistoryFilters(searchParams)
  const canonicalSearch = buildHistorySearchParams({ status, page, size }).toString()
  const currentSearch = searchParams.toString()
  const analysesQuery = useAnalysesQuery(
    { status, page, size },
    currentSearch === canonicalSearch,
  )

  useEffect(() => {
    if (currentSearch !== canonicalSearch) {
      setSearchParams(canonicalSearch, { replace: true })
    }
  }, [canonicalSearch, currentSearch, setSearchParams])

  const updateFilters = useCallback(
    (nextFilters: HistoryFilters, replace = false) => {
      setSearchParams(buildHistorySearchParams(nextFilters), { replace })
    },
    [setSearchParams],
  )

  const handlePageChange = useCallback(
    (nextPage: number, { replace }: PaginationChangeOptions) => {
      updateFilters({ status, page: nextPage, size }, replace)
    },
    [size, status, updateFilters],
  )

  return (
    <section
      aria-labelledby="analyses-page-heading"
      className="analyses-page"
    >
      <header className="analyses-page__header">
        <h1 id="analyses-page-heading">分析历史</h1>
      </header>

      <div className="analyses-toolbar">
        <div className="analyses-filter">
          <label htmlFor="analysis-status-filter">状态</label>
          <select
            id="analysis-status-filter"
            onChange={(event) => {
              const nextStatus = parseAnalysisStatus(event.target.value)
              updateFilters({ status: nextStatus, page: 0, size })
            }}
            value={status ?? ''}
          >
            <option value="">全部状态</option>
            {analysisStatusOptions.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </div>

        <div className="analyses-filter analyses-filter--size">
          <label htmlFor="analysis-page-size">每页数量</label>
          <select
            id="analysis-page-size"
            onChange={(event) => {
              const nextSize =
                parseCanonicalInteger(event.target.value, 1, 100) ?? DEFAULT_SIZE
              updateFilters({ status, page: 0, size: nextSize })
            }}
            value={String(size)}
          >
            {!PAGE_SIZES.includes(size as (typeof PAGE_SIZES)[number]) ? (
              <option value={size}>{size}</option>
            ) : null}
            {PAGE_SIZES.map((pageSize) => (
              <option key={pageSize} value={pageSize}>
                {pageSize}
              </option>
            ))}
          </select>
        </div>
      </div>

      <div className="analyses-results">
        {analysesQuery.isPending ? (
          <AnalysisTableSkeleton label="正在加载分析历史" rowCount={6} />
        ) : null}

        {analysesQuery.isError ? (
          <AsyncState
            className="analyses-page-state"
            description="请检查服务连接后重试，当前筛选会保留。"
            onRetry={() => void analysesQuery.refetch()}
            state="error"
            title="分析历史加载失败"
          />
        ) : null}

        {analysesQuery.isSuccess && analysesQuery.data.totalElements === 0 ? (
          <div className="analyses-page-state analyses-empty-action">
            {status === undefined ? (
              <>
                <AsyncState
                  description="创建第一项分析后，记录会显示在这里。"
                  state="empty"
                  title="还没有分析记录"
                />
                <Link
                  className="button button--primary"
                  to="/analyses/new"
                >
                  <span className="button__content">新建分析</span>
                </Link>
              </>
            ) : (
              <>
                <AsyncState
                  description="更换状态或清除筛选后再查看。"
                  state="empty"
                  title="当前条件无结果"
                />
                <Button
                  onClick={() =>
                    updateFilters({ status: undefined, page: 0, size })
                  }
                  variant="secondary"
                >
                  清除筛选
                </Button>
              </>
            )}
          </div>
        ) : null}

        {analysesQuery.isSuccess && analysesQuery.data.totalElements > 0 ? (
          <>
            <div className="analyses-results__summary">
              共 {analysesQuery.data.totalElements} 条
            </div>
            <AnalysisTable
              accessibleName="分析历史"
              items={analysesQuery.data.items}
            />
            <div className="analyses-results__pagination">
              <Pagination
                onPageChange={handlePageChange}
                page={page}
                totalPages={analysesQuery.data.totalPages}
              />
            </div>
          </>
        ) : null}
      </div>
    </section>
  )
}
