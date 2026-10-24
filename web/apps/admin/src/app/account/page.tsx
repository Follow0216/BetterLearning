import type { Metadata } from "next";
import Link from "next/link";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { Suspense } from "react";

import { AdminLogoutButton } from "@/features/admin-auth/AdminLogoutButton";
import {
  AdminBackendError,
  AdminContractError,
  AdminUnavailableError,
  getAdminSession,
  type AdminUser,
} from "@/lib/api/admin-auth";
import { ADMIN_SESSION_COOKIE_NAME } from "@/lib/api/admin-cookie";

export const metadata: Metadata = {
  title: "管理员身份 · BetterLearning",
  description: "BetterLearning 管理后台当前管理员身份",
  robots: { index: false, follow: false },
};

const CARD =
  "w-full rounded-3xl border border-[var(--admin-border)] bg-white p-7 shadow-[0_18px_50px_-28px_rgba(21,39,68,0.28)]";
const PRIMARY_LINK =
  "inline-flex h-11 items-center justify-center rounded-xl bg-[var(--admin-primary)] px-4 text-sm font-medium text-white transition-colors hover:bg-[var(--admin-primary-hover)] focus-visible:ring-2 focus-visible:ring-[var(--admin-primary)] focus-visible:ring-offset-2 focus-visible:outline-none";
const SECONDARY_LINK =
  "inline-flex h-11 items-center justify-center rounded-xl border border-[var(--admin-border)] bg-white px-4 text-sm font-medium text-[var(--admin-primary)] transition-colors hover:bg-[var(--admin-primary-soft)] focus-visible:ring-2 focus-visible:ring-[var(--admin-primary)] focus-visible:outline-none";

function formatTime(value: string): string {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) {
    return value;
  }
  return new Intl.DateTimeFormat("zh-CN", {
    dateStyle: "medium",
    timeStyle: "short",
    timeZone: "Asia/Shanghai",
  }).format(date);
}

function StateCard({
  title,
  message,
  primaryHref,
  primaryLabel,
  secondaryHref,
  secondaryLabel,
}: {
  title: string;
  message: string;
  primaryHref: string;
  primaryLabel: string;
  secondaryHref?: string;
  secondaryLabel?: string;
}) {
  return (
    <div className={CARD}>
      <h1 className="text-xl leading-7 font-semibold text-[var(--admin-text)]">{title}</h1>
      <p role="alert" className="mt-2 text-sm leading-6 text-[var(--admin-muted)]">
        {message}
      </p>
      <div className="mt-6 flex flex-col gap-2">
        <Link href={primaryHref} className={PRIMARY_LINK}>
          {primaryLabel}
        </Link>
        {secondaryHref === undefined || secondaryLabel === undefined ? null : (
          <Link href={secondaryHref} className={SECONDARY_LINK}>
            {secondaryLabel}
          </Link>
        )}
      </div>
    </div>
  );
}

function IdentityCard({ user }: { user: AdminUser }) {
  const rows = [
    { label: "用户名", value: user.username, mono: false },
    { label: "账号 ID", value: user.accountId, mono: true },
    { label: "角色", value: user.role, mono: true },
    { label: "手机号", value: user.phone ?? "未设置", mono: false },
    { label: "邮箱", value: user.email ?? "未设置", mono: false },
    { label: "创建时间", value: formatTime(user.createdAt), mono: false },
    { label: "更新时间", value: formatTime(user.updatedAt), mono: false },
  ];

  return (
    <div className={CARD}>
      <h1 className="text-xl leading-7 font-semibold text-[var(--admin-text)]">管理员身份</h1>
      <p className="mt-2 text-sm leading-6 text-[var(--admin-muted)]">
        信息来自服务端管理员会话校验，不以浏览器本地状态为准。
      </p>
      <dl className="mt-5 divide-y divide-[var(--admin-divider)] rounded-2xl border border-[var(--admin-border)]">
        {rows.map((row) => (
          <div key={row.label} className="flex flex-wrap gap-2 px-4 py-3">
            <dt className="w-24 shrink-0 text-xs leading-6 text-[var(--admin-muted)]">
              {row.label}
            </dt>
            <dd
              className={`min-w-0 flex-1 text-sm leading-6 break-all text-[var(--admin-text)] ${
                row.mono ? "font-mono" : ""
              }`}
            >
              {row.value}
            </dd>
          </div>
        ))}
      </dl>
      <div className="mt-6">
        <AdminLogoutButton />
      </div>
    </div>
  );
}

async function AccountContent() {
  const store = await cookies();
  const token = store.get(ADMIN_SESSION_COOKIE_NAME)?.value;
  if (token === undefined || token.length === 0) {
    redirect("/login");
  }

  let user: AdminUser | null = null;
  let failure: unknown = null;
  try {
    user = await getAdminSession(token);
  } catch (error) {
    failure = error;
  }

  if (user !== null) {
    return <IdentityCard user={user} />;
  }

  if (failure instanceof AdminBackendError && failure.status === 403) {
    return (
      <StateCard
        title="该账号不是管理员"
        message="普通用户账号不能进入管理后台，请改用服务端初始化的管理员账号登录。"
        primaryHref="/login"
        primaryLabel="使用管理员账号登录"
      />
    );
  }
  if (failure instanceof AdminBackendError && failure.status === 401) {
    return (
      <StateCard
        title="管理员会话已失效"
        message="当前会话无法通过服务端校验，请重新登录。"
        primaryHref="/login"
        primaryLabel="重新登录"
      />
    );
  }
  if (failure instanceof AdminUnavailableError) {
    return (
      <StateCard
        title="认证服务暂不可用"
        message="当前不会视为已登录，也不会展示本地伪造身份，请稍后重试。"
        primaryHref="/account"
        primaryLabel="重试"
        secondaryHref="/login"
        secondaryLabel="前往管理员登录"
      />
    );
  }
  if (failure instanceof AdminContractError) {
    return (
      <StateCard
        title="管理员身份响应异常"
        message="认证服务返回的身份信息无法解析，请稍后重试或联系管理员。"
        primaryHref="/account"
        primaryLabel="重试"
        secondaryHref="/login"
        secondaryLabel="前往管理员登录"
      />
    );
  }
  return (
    <StateCard
      title="加载管理员身份失败"
      message="服务端暂时无法完成身份校验，请稍后重试。"
      primaryHref="/account"
      primaryLabel="重试"
      secondaryHref="/login"
      secondaryLabel="前往管理员登录"
    />
  );
}

export default function AdminAccountPage() {
  return (
    <div className="flex min-h-dvh flex-col bg-[var(--admin-bg)] text-[var(--admin-text)]">
      <header className="border-b border-[var(--admin-border)] bg-white">
        <div className="mx-auto flex h-14 w-full max-w-[1100px] items-center gap-3 px-4 md:px-6">
          <Link
            href="/account"
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

      <main className="mx-auto flex w-full max-w-[760px] flex-1 items-start justify-center px-4 py-10">
        <Suspense
          fallback={
            <div className="w-full">
              <span className="sr-only" role="status">
                正在校验管理员会话…
              </span>
              <div className="h-[360px] animate-pulse rounded-3xl border border-[var(--admin-border)] bg-white" />
            </div>
          }
        >
          <AccountContent />
        </Suspense>
      </main>
    </div>
  );
}