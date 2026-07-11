import { ChevronLeft, ChevronRight } from 'lucide-react'

interface PaginationProps {
  onPageChange: (page: number) => void
  page: number
  totalPages: number
}

export function Pagination({
  onPageChange,
  page,
  totalPages,
}: PaginationProps) {
  const pageCount = Number.isFinite(totalPages)
    ? Math.max(0, Math.trunc(totalPages))
    : 0
  const requestedPage = Number.isFinite(page) ? Math.trunc(page) : 0
  const currentPage =
    pageCount === 0
      ? 0
      : Math.min(Math.max(requestedPage, 0), pageCount - 1)
  const canGoBack = pageCount > 0 && currentPage > 0
  const canGoForward = pageCount > 0 && currentPage < pageCount - 1
  const visiblePage = pageCount > 0 ? currentPage + 1 : 0

  function changePage(nextPage: number) {
    if (
      Number.isInteger(nextPage) &&
      nextPage >= 0 &&
      nextPage < pageCount &&
      nextPage !== currentPage
    ) {
      onPageChange(nextPage)
    }
  }

  return (
    <nav aria-label="分页导航" className="pagination">
      <button
        aria-label="上一页"
        className="icon-button pagination__button"
        disabled={!canGoBack}
        onClick={() => changePage(currentPage - 1)}
        title="上一页"
        type="button"
      >
        <ChevronLeft aria-hidden="true" size={18} />
      </button>
      <span aria-atomic="true" aria-live="polite" className="pagination__status">
        第 {visiblePage} / {pageCount} 页
      </span>
      <button
        aria-label="下一页"
        className="icon-button pagination__button"
        disabled={!canGoForward}
        onClick={() => changePage(currentPage + 1)}
        title="下一页"
        type="button"
      >
        <ChevronRight aria-hidden="true" size={18} />
      </button>
    </nav>
  )
}
