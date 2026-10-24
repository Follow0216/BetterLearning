"use client";

import Link from "next/link";

export default function WorkspaceError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return (
    <div className="flex min-h-dvh items-center justify-center bg-[var(--auth-bg)] px-4 py-10 text-[var(--auth-text)]">
      <div className="w-full max-w-[520px] rounded-2xl border border-[var(--auth-border)] bg-white p-7 text-center">
        <h1 className="text-lg leading-7 font-semibold">题库页面加载失败</h1>
        <p role="alert" className="mt-2 text-sm leading-6 text-[var(--auth-muted)]">
          页面遇到未预期的错误，当前不会显示空题库。
        </p>
        {error.digest === undefined ? null : (
          <p className="mt-2 font-mono text-[11px] break-all text-[var(--auth-muted)]">
            追踪号 {error.digest}
          </p>
        )}
        <div className="mt-6 flex flex-wrap items-center justify-center gap-2">
          <button
            type="button"
            onClick={() => {
              reset();
            }}
            className="inline-flex h-10 items-center justify-center rounded-lg bg-[var(--auth-primary)] px-4 text-sm font-medium text-white transition-colors hover:bg-[var(--auth-primary-hover)] focus-visible:ring-2 focus-visible:ring-[var(--auth-primary)] focus-visible:ring-offset-2 focus-visible:outline-none"
          >
            重试
          </button>
          <Link
            href="/"
            className="inline-flex h-10 items-center justify-center rounded-lg border border-[var(--auth-border)] bg-white px-4 text-sm font-medium text-[var(--auth-primary)] transition-colors hover:bg-[var(--auth-primary-soft)] focus-visible:ring-2 focus-visible:ring-[var(--auth-primary)] focus-visible:outline-none"
          >
            返回首页
          </Link>
        </div>
      </div>
    </div>
  );
}
