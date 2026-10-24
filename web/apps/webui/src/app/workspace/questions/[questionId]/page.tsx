import type { Metadata } from "next";
import { Suspense } from "react";

import { QuestionDetailView } from "@/features/library/QuestionDetailView";
import { WorkspaceSkeleton } from "@/features/library/WorkspaceSkeleton";

export const metadata: Metadata = {
  title: "题目详情 · BetterLearning",
  description: "查看当前账号下题目的原始 Markdown 内容。",
};

interface QuestionDetailPageProps {
  params: Promise<{ questionId: string }>;
}

export default function QuestionDetailPage({ params }: QuestionDetailPageProps) {
  return (
    <Suspense fallback={<WorkspaceSkeleton />}>
      <QuestionDetailView params={params} />
    </Suspense>
  );
}
