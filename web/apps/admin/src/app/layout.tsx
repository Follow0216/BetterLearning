import type { Metadata } from "next";
import "@betterlearning/ui-feedback/styles.css";

import { AdminFeedbackProvider } from "@/features/feedback/AdminFeedbackProvider";
import "./globals.css";

export const metadata: Metadata = {
  title: "BetterLearning Admin",
  description: "BetterLearning 管理后台",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="zh-CN" className="h-full antialiased">
      <body className="flex min-h-full flex-col">
        <AdminFeedbackProvider>{children}</AdminFeedbackProvider>
      </body>
    </html>
  );
}
