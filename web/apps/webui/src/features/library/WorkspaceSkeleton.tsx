import { WorkspaceHeader } from "./WorkspaceHeader";

export function WorkspaceSkeleton() {
  return (
    <div className="min-h-dvh bg-[var(--auth-bg)] text-[var(--auth-text)]">
      <WorkspaceHeader />
      <div className="mx-auto grid w-full max-w-[1200px] items-start gap-5 px-4 py-6 md:px-6 lg:grid-cols-[220px_minmax(0,1fr)]">
        <div className="h-[132px] animate-pulse rounded-2xl border border-[var(--auth-border)] bg-white" />
        <div role="status" aria-live="polite">
          <span className="sr-only">正在加载题库…</span>
          <div className="h-8 w-36 animate-pulse rounded-lg bg-white" />
          <div className="mt-4 flex flex-col gap-2">
            {[0, 1, 2, 3, 4].map((item) => (
              <div
                key={item}
                className="h-[58px] animate-pulse rounded-xl border border-[var(--auth-border)] bg-white"
              />
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}