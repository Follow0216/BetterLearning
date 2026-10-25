import styles from "./workspace.module.css";
import { WorkspaceHeader } from "./WorkspaceHeader";

function SkeletonLines() {
  return (
    <div className="space-y-4" aria-hidden="true">
      <div className={`${styles.skeletonLine} w-3/4`} />
      <div className={styles.skeletonLine} />
      <div className={`${styles.skeletonLine} w-5/6`} />
    </div>
  );
}

export function WorkspaceSkeleton({ variant = "library" }: { variant?: "library" | "detail" }) {
  return (
    <div className={styles.root}>
      <WorkspaceHeader />
      {variant === "detail" ? (
        <div className={styles.detailShell} role="status" aria-live="polite">
          <span className="sr-only">正在加载题目详情…</span>
          <div
            className={`${styles.skeletonLine} w-24 motion-safe:animate-pulse`}
            aria-hidden="true"
          />
          <div className={`${styles.detailArticle} motion-safe:animate-pulse`}>
            <div className={`${styles.skeletonLine} mb-6 w-40`} aria-hidden="true" />
            <div className={styles.detailGrid}>
              <div className={styles.questionPane}>
                <SkeletonLines />
              </div>
              <div className={styles.answerPane}>
                <SkeletonLines />
              </div>
            </div>
          </div>
        </div>
      ) : (
        <div className={styles.shell}>
          <div className={`${styles.sidebar} motion-safe:animate-pulse`} aria-hidden="true">
            <div className={`${styles.skeletonLine} w-28`} />
            <div className="hidden space-y-5 pt-7 min-[900px]:block">
              <SkeletonLines />
            </div>
          </div>
          <div className={styles.main} role="status" aria-live="polite">
            <span className="sr-only">正在加载题库…</span>
            <div className="motion-safe:animate-pulse" aria-hidden="true">
              <div className={`${styles.skeletonLine} mb-3 w-28`} />
              <div className="h-8 w-40 rounded bg-[var(--auth-border)]" />
              <div className="mt-6 border-t border-[var(--auth-border)]">
                {[0, 1, 2, 3, 4].map((item) => (
                  <div key={item} className="space-y-4 border-b border-[var(--auth-border)] py-6">
                    <div className={`${styles.skeletonLine} w-5/6`} />
                    <div className={`${styles.skeletonLine} w-2/5`} />
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
