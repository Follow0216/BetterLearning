import Link from "next/link";

import { BlIcon } from "@/components/icons/BlIcon";

import { LogoutButton } from "./LogoutButton";

export function WorkspaceHeader() {
  return (
    <header className="sticky top-0 z-20 border-b border-[var(--auth-border)] bg-white">
      <div className="mx-auto flex min-h-14 w-full max-w-[1200px] flex-wrap items-center gap-x-3 gap-y-1 px-4 py-2 md:px-6">
        <Link
          href="/workspace"
          aria-label="BetterLearning 题库"
          className="flex items-baseline text-[18px] leading-none font-bold tracking-tight"
        >
          <span className="text-[var(--auth-text)]">Better</span>
          <span className="text-[var(--auth-primary)]">Learning</span>
        </Link>
        <span className="inline-flex shrink-0 items-center rounded-full border border-[#cfe0ff] bg-[var(--auth-primary-soft)] px-2 py-0.5 text-[11px] leading-4 font-medium text-[var(--auth-primary)]">
          题库
        </span>
        <nav
          aria-label="账号操作"
          className="ml-auto flex items-center gap-2 text-sm text-[var(--auth-muted)]"
        >
          <Link
            href="/"
            className="inline-flex h-9 items-center gap-1.5 rounded-lg px-2 font-medium transition-colors hover:text-[var(--auth-text)] focus-visible:ring-2 focus-visible:ring-[var(--auth-primary)] focus-visible:outline-none"
          >
            <BlIcon name="user" size={16} />
            账号资料
          </Link>
          <LogoutButton />
        </nav>
      </div>
    </header>
  );
}
