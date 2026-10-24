import type { Metadata } from "next";
import { Suspense } from "react";

import type { SearchParamsInput } from "@/features/library/library-params";
import { WorkspaceSkeleton } from "@/features/library/WorkspaceSkeleton";
import { WorkspaceView } from "@/features/library/WorkspaceView";

export const metadata: Metadata = {
  title: "题库 · BetterLearning",
  description: "当前账号的真实题库：待整理、全部题目与分页浏览。",
};

interface WorkspacePageProps {
  searchParams: Promise<SearchParamsInput>;
}

export default function WorkspacePage({ searchParams }: WorkspacePageProps) {
  return (
    <Suspense fallback={<WorkspaceSkeleton />}>
      <WorkspaceView searchParams={searchParams} />
    </Suspense>
  );
}
