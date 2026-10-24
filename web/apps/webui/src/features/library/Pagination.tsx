import Link from "next/link";

import { buildWorkspaceHref, totalPages, type LibraryView } from "./library-params";

interface PaginationProps {
  view: LibraryView;
  page: number;
  pageSize: number;
  total: number;
}

const PAGE_LINK =
  "inline-flex h-9 items-center justify-center rounded-lg border border-[var(--auth-border)] bg-white px-3 text-sm font-medium text-[var(--auth-primary)] transition-colors hover:bg-[var(--auth-primary-soft)] focus-visible:ring-2 focus-visible:ring-[var(--auth-primary)] focus-visible:outline-none";
const PAGE_DISABLED =
  "inline-flex h-9 items-center justify-center rounded-lg border border-[var(--auth-divider)] bg-[var(--auth-bg)] px-3 text-sm font-medium text-[var(--auth-muted)]";

export function Pagination({ view, page, pageSize, total }: PaginationProps) {
  const pages = totalPages(total, pageSize);
  const hasPrevious = page > 1;
  const hasNext = page < pages;

  return (
    <nav
      aria-label="题库分页"
      className="flex flex-wrap items-center justify-between gap-3 border-t border-[var(--auth-divider)] pt-4"
    >
      <p className="text-xs text-[var(--auth-muted)] tabular-nums">
        共 {total} 题 · 第 {page} / {pages} 页
      </p>
      <div className="flex items-center gap-2">
        {hasPrevious ? (
          <Link href={buildWorkspaceHref(view, page - 1)} className={PAGE_LINK} rel="prev">
            上一页
          </Link>
        ) : (
          <span aria-disabled="true" className={PAGE_DISABLED}>
            上一页
          </span>
        )}
        {hasNext ? (
          <Link href={buildWorkspaceHref(view, page + 1)} className={PAGE_LINK} rel="next">
            下一页
          </Link>
        ) : (
          <span aria-disabled="true" className={PAGE_DISABLED}>
            下一页
          </span>
        )}
      </div>
    </nav>
  );
}