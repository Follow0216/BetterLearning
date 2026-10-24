import type { Metadata } from "next";
import Link from "next/link";

import { AdminLoginForm } from "@/features/admin-auth/AdminLoginForm";

export const metadata: Metadata = {
  title: "管理员登录 · BetterLearning",
  description: "BetterLearning 管理后台登录",
  robots: { index: false, follow: false },
};

export default function AdminLoginPage() {
  return (
    <div className="flex min-h-dvh flex-col bg-[var(--admin-bg)] text-[var(--admin-text)]">
      <header className="border-b border-[var(--admin-border)] bg-white">
        <div className="mx-auto flex h-14 w-full max-w-[1100px] items-center gap-3 px-4 md:px-6">
          <Link
            href="/login"
            aria-label="BetterLearning 管理后台"
            className="flex items-baseline text-[18px] leading-none font-bold tracking-tight"
          >
            <span className="text-[var(--admin-text)]">Better</span>
            <span className="text-[var(--admin-primary)]">Learning</span>
          </Link>
          <span className="inline-flex shrink-0 items-center rounded-full border border-[#cfe0ff] bg-[var(--admin-primary-soft)] px-2 py-0.5 text-[11px] leading-4 font-medium text-[var(--admin-primary)]">
            管理后台
          </span>
        </div>
      </header>

      <main className="flex flex-1 items-center justify-center px-4 py-10">
        <div className="w-full max-w-[420px] rounded-3xl border border-[var(--admin-border)] bg-white p-7 shadow-[0_18px_50px_-28px_rgba(21,39,68,0.28)]">
          <h1 className="text-[26px] leading-8 font-semibold text-[var(--admin-text)]">
            管理员登录
          </h1>
          <p className="mt-2 text-sm leading-6 text-[var(--admin-muted)]">
            仅服务端初始化的管理员账号可以进入后台，普通用户账号会被拒绝。
          </p>
          <AdminLoginForm />
        </div>
      </main>
    </div>
  );
}