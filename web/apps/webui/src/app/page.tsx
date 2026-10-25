import type { Metadata } from "next";
import { Suspense } from "react";

import { HomeSkeleton } from "@/features/home/HomeSkeleton";
import { LearningHomeView } from "@/features/home/LearningHomeView";

export const metadata: Metadata = {
  title: "学习首页 · BetterLearning",
  description: "阅读最近加入的题目，查看已保存作答与辅导，整理自己的题集。",
};

export default function Home() {
  return (
    <Suspense fallback={<HomeSkeleton />}>
      <LearningHomeView />
    </Suspense>
  );
}
