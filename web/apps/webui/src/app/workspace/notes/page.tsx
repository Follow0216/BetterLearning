import type { Metadata } from "next";
import Link from "next/link";

import { WorkspaceHeader } from "@/features/library/WorkspaceHeader";
import workspace from "@/features/library/workspace.module.css";
import { NotesWorkspace } from "@/features/learning-notes/NotesWorkspace";
import { SessionBoundary } from "@/features/learning-notes/SessionBoundary";

export const metadata: Metadata = { title: "我的笔记 · BetterLearning" };

export default function NotesPage() {
  return (
    <div className={workspace.root}>
      <WorkspaceHeader active="notes" />
      <main id="workspace-content" className="mx-auto w-full max-w-[1200px] px-4 py-6 md:px-6">
        <Link
          href="/workspace"
          className="mb-4 inline-flex text-sm text-[var(--auth-primary)] hover:underline"
        >
          返回题库
        </Link>
        <SessionBoundary>
          <NotesWorkspace />
        </SessionBoundary>
      </main>
    </div>
  );
}
