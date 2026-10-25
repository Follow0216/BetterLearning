import type { Metadata } from "next";

import { AuthForm } from "@/features/auth/AuthForm";
import styles from "@/features/home/learning-home.module.css";
import workspace from "@/features/library/workspace.module.css";
import { WorkspaceHeader } from "@/features/library/WorkspaceHeader";

export const metadata: Metadata = {
  title: "学习账号 · BetterLearning",
  description: "登录学习账号，或查看和刷新自己的账号资料。",
};

export default function AccountPage() {
  return (
    <div className={workspace.root}>
      <WorkspaceHeader active="account" showAccount={false} />
      <main tabIndex={-1} id="workspace-content" className={styles.accountMain}>
        <section className={styles.accountForm} aria-label="学习账号">
          <AuthForm />
        </section>
        <p className="mt-5 text-center text-xs leading-6 text-[var(--auth-muted)]">
          同一个账号，在手机、平板和网页间查看自己的题目与作答。
        </p>
      </main>
    </div>
  );
}
