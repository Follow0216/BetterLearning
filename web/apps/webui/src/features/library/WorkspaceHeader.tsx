import Link from "next/link";

import { BlIcon } from "@/components/icons/BlIcon";

import { LogoutButton } from "./LogoutButton";
import styles from "./workspace.module.css";

export function WorkspaceHeader() {
  return (
    <header className={styles.header}>
      <div className={styles.headerInner}>
        <Link href="/workspace" aria-label="BetterLearning 题库" className={styles.brand}>
          <span>Better</span>
          <span className="text-[var(--auth-primary)]">Learning</span>
        </Link>
        <Link href="/workspace" className={styles.headerLink}>
          题库
        </Link>
        <nav
          aria-label="账号操作"
          className="ml-auto flex min-w-0 items-center gap-1 text-sm text-[var(--auth-muted)] sm:gap-2"
        >
          <Link
            href="/"
            title="账号资料"
            className="inline-flex h-9 items-center gap-1.5 rounded-lg px-2 font-medium transition-colors hover:bg-[var(--auth-hover)] hover:text-[var(--auth-text)]"
          >
            <BlIcon name="user" size={17} />
            <span className="sr-only sm:not-sr-only">账号资料</span>
          </Link>
          <LogoutButton />
        </nav>
      </div>
    </header>
  );
}
