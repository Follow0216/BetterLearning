import Link from "next/link";

import { BlIcon } from "@/components/icons/BlIcon";

interface StatePanelProps {
  title: string;
  message: string;
  traceId?: string;
  retryHref?: string;
  backHref?: string;
  backLabel?: string;
}

export function StatePanel({
  title,
  message,
  traceId,
  retryHref,
  backHref = "/workspace",
  backLabel = "返回题库",
}: StatePanelProps) {
  return (
    <div className="flex flex-col items-center rounded-2xl border border-[var(--auth-border)] bg-white px-6 py-12 text-center">
      <span className="flex h-11 w-11 items-center justify-center rounded-full bg-[var(--auth-bg)] text-[var(--auth-muted)]">
        <BlIcon name="warning" size={22} />
      </span>
      <h2 className="mt-4 text-lg leading-7 font-semibold text-[var(--auth-text)]">{title}</h2>
      <p role="alert" className="mt-2 max-w-[460px] text-sm leading-6 text-[var(--auth-muted)]">
        {message}
      </p>
      {traceId !== undefined && traceId.length > 0 ? (
        <p className="mt-2 font-mono text-[11px] break-all text-[var(--auth-muted)]">
          追踪号 {traceId}
        </p>
      ) : null}
      <div className="mt-6 flex flex-wrap items-center justify-center gap-2">
        {retryHref === undefined ? null : (
          <Link
            href={retryHref}
            className="inline-flex h-10 items-center justify-center rounded-lg bg-[var(--auth-primary)] px-4 text-sm font-medium text-white transition-colors hover:bg-[var(--auth-primary-hover)] focus-visible:ring-2 focus-visible:ring-[var(--auth-primary)] focus-visible:ring-offset-2 focus-visible:outline-none"
          >
            重试
          </Link>
        )}
        <Link
          href={backHref}
          className="inline-flex h-10 items-center justify-center rounded-lg border border-[var(--auth-border)] bg-white px-4 text-sm font-medium text-[var(--auth-primary)] transition-colors hover:bg-[var(--auth-primary-soft)] focus-visible:ring-2 focus-visible:ring-[var(--auth-primary)] focus-visible:outline-none"
        >
          {backLabel}
        </Link>
      </div>
    </div>
  );
}
