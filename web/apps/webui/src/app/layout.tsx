import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  title: "BetterLearning",
  description: "BetterLearning 学习平台",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="zh-CN" className="h-full antialiased">
      <body className="flex min-h-full flex-col">{children}</body>
    </html>
  );
}
