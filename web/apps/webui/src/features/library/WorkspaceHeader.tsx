import Link from "next/link";

import { BlIcon } from "@/components/icons/BlIcon";

import styles from "./workspace.module.css";

export function WorkspaceHeader({
  active = "library",
  username,
  showAccount = true,
}: {
  active?: "home" | "library" | "notes" | "account";
  username?: string;
  showAccount?: boolean;
}) {
  return (
    <header className={styles.header}>
      <a href="#workspace-content" className={styles.skipLink}>
        跳到主要内容
      </a>
      <div className={styles.headerInner}>
        <Link href="/" aria-label="BetterLearning 学习首页" className={styles.brand} translate="no">
          <span>Better</span>
          <span className="text-[var(--auth-primary)]">Learning</span>
        </Link>
        <nav aria-label="主要导航" className={styles.primaryNav}>
          <Link
            href="/"
            aria-label="学习首页"
            aria-current={active === "home" ? "page" : undefined}
            className={styles.headerLink}
          >
            <span className="hidden sm:inline">学习</span>首页
          </Link>
          <Link
            href="/workspace"
            aria-current={active === "library" ? "page" : undefined}
            className={styles.headerLink}
          >
            题库
          </Link>
          <Link
            href="/workspace/notes"
            aria-label="我的笔记"
            prefetch={false}
            aria-current={active === "notes" ? "page" : undefined}
            className={styles.headerLink}
          >
            <span className="hidden sm:inline">我的</span>笔记
          </Link>
        </nav>
        {showAccount ? (
          <Link
            href="/account"
            title="账号资料"
            className={styles.accountLink}
            aria-current={active === "account" ? "page" : undefined}
          >
            <BlIcon name="user" size={17} />
            <span className="sr-only max-w-32 truncate sm:not-sr-only">
              {username ?? "账号资料"}
            </span>
          </Link>
        ) : null}
      </div>
    </header>
  );
}
