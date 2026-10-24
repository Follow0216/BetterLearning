import Link from "next/link";

import { BlIcon, type BlIconName } from "@/components/icons/BlIcon";

import type { LibraryView } from "./library-params";

interface WorkspaceSidebarProps {
  view: LibraryView;
  inboxCount?: number;
  allCount?: number;
}

function NavItem({
  href,
  icon,
  label,
  active,
  count,
}: {
  href: string;
  icon: BlIconName;
  label: string;
  active: boolean;
  count?: number;
}) {
  return (
    <Link
      href={href}
      aria-current={active ? "page" : undefined}
      className={`flex h-10 items-center gap-2 rounded-lg px-3 text-sm font-medium transition-colors focus-visible:ring-2 focus-visible:ring-[var(--auth-primary)] focus-visible:outline-none ${
        active
          ? "bg-[var(--auth-primary-soft)] text-[var(--auth-primary)]"
          : "text-[var(--auth-text)] hover:bg-[var(--auth-hover)]"
      }`}
    >
      <BlIcon name={icon} size={17} />
      <span className="min-w-0 flex-1">{label}</span>
      {count === undefined ? null : (
        <span className="text-xs text-[var(--auth-muted)] tabular-nums">{count}</span>
      )}
    </Link>
  );
}

export function WorkspaceSidebar({ view, inboxCount, allCount }: WorkspaceSidebarProps) {
  return (
    <aside className="min-w-0">
      <div className="rounded-2xl border border-[var(--auth-border)] bg-white p-2">
        <nav aria-label="题库视图" className="flex flex-col gap-1">
          <NavItem
            href="/workspace"
            icon="list"
            label="全部题目"
            active={view === "all"}
            count={allCount}
          />
          <NavItem
            href="/workspace?view=inbox"
            icon="inbox"
            label="待整理"
            active={view === "inbox"}
            count={inboxCount}
          />
        </nav>
      </div>
      <p className="mt-3 px-2 text-[11px] leading-5 text-[var(--auth-muted)]">
        待整理固定承接手机端新录入的题目。Web 端只读浏览，不提供编辑、移动或删除。
      </p>
    </aside>
  );
}