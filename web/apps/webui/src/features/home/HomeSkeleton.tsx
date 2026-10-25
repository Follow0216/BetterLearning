import workspace from "@/features/library/workspace.module.css";
import { WorkspaceHeader } from "@/features/library/WorkspaceHeader";

import styles from "./learning-home.module.css";

export function HomeSkeleton() {
  return (
    <div className={workspace.root}>
      <WorkspaceHeader active="home" showAccount={false} />
      <main
        tabIndex={-1}
        id="workspace-content"
        className={styles.main}
        role="status"
        aria-live="polite"
        aria-busy="true"
      >
        <span className="sr-only">正在打开学习空间…</span>
        <div className="motion-safe:animate-pulse" aria-hidden="true">
          <div className={`${styles.skeleton} mb-4 h-3 w-36`} />
          <div className={`${styles.skeleton} mb-4 h-9 w-64 max-w-full`} />
          <div className={`${styles.skeleton} mb-12 h-3 w-56 max-w-full`} />
          <div className={styles.grid}>
            <div className={styles.paper}>
              <div className={`${styles.skeleton} mb-9 h-3 w-24`} />
              <div className="space-y-5 pb-10">
                {["w-full", "w-5/6", "w-full", "w-3/4"].map((width, index) => (
                  <div key={index} className={`${styles.skeleton} h-3 ${width}`} />
                ))}
              </div>
            </div>
            <div className="hidden space-y-6 min-[861px]:block">
              <div className={`${styles.skeleton} h-3 w-24`} />
              <div className={`${styles.skeleton} h-3 w-full`} />
              <div className={`${styles.skeleton} h-3 w-3/4`} />
            </div>
          </div>
        </div>
      </main>
    </div>
  );
}
