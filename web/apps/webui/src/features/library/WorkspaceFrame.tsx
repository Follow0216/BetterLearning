import type { ReactNode } from "react";

import type { CollectionSummary } from "@/lib/api/library-contract";
import type { LibraryFailure } from "@/lib/api/library-server";

import type { LibraryView } from "./library-params";
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
    <div className="min-h-dvh bg-[var(--auth-bg)] text-[var(--auth-text)]">
      <WorkspaceHeader />
      <div className="mx-auto grid w-full max-w-[1200px] items-start gap-5 px-4 py-6 md:px-6 lg:grid-cols-[220px_minmax(0,1fr)]">
        <WorkspaceSidebar
          view={view}
          collections={collections}
          collectionsFailure={collectionsFailure}
          inboxCount={inboxCount}
          allCount={allCount}
        />
        <main className="min-w-0">{children}</main>
      </div>
    </div>
  );
}
