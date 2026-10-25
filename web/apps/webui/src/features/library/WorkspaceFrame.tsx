import { SessionBoundary } from "@/features/learning-notes/SessionBoundary";
import type { ReactNode } from "react";

import type { CollectionSummary } from "@/lib/api/library-contract";
import type { LibraryFailure } from "@/lib/api/library-server";

import type { LibraryView } from "./library-params";
import styles from "./workspace.module.css";
import { WorkspaceHeader } from "./WorkspaceHeader";
import { WorkspaceSidebar } from "./WorkspaceSidebar";

interface WorkspaceFrameProps {
  view: LibraryView;
  collections: CollectionSummary[];
  collectionsFailure?: LibraryFailure;
  inboxCount?: number;
  allCount?: number;
  children: ReactNode;
}

export function WorkspaceFrame({
  view,
  collections,
  collectionsFailure,
  inboxCount,
  allCount,
  children,
}: WorkspaceFrameProps) {
  return (
    <SessionBoundary>
      <div className={styles.root}>
        <WorkspaceHeader />
        <div className={styles.shell}>
          <WorkspaceSidebar
            view={view}
            collections={collections}
            collectionsFailure={collectionsFailure}
            inboxCount={inboxCount}
            allCount={allCount}
          />
          <main tabIndex={-1} id="workspace-content" className={styles.main}>
            {children}
          </main>
        </div>
      </div>
    </SessionBoundary>
  );
}
