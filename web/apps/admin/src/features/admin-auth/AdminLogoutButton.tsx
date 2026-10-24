"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

export function AdminLogoutButton() {
  const router = useRouter();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");

  async function handleLogout(): Promise<void> {
    if (pending) {
      return;
    }
    setPending(true);
    setError("");
    try {
      const response = await fetch("/api/admin/logout", {
        method: "DELETE",
        headers: { Accept: "application/json" },
      });
      if (response.status === 204 || response.status === 401) {
        router.replace("/login");
        router.refresh();
        return;
      }
      setError("无法确认退出结果，请重试");
    } catch {
      setError("网络连接异常，请重试");
    } finally {
      setPending(false);
    }
  }

  return (
    <div className="flex flex-col gap-2">
      {error.length > 0 ? (
        <p role="alert" className="text-[13px] text-[var(--admin-danger)]">
          {error}
        </p>
      ) : null}
      <button
        type="button"
        onClick={() => void handleLogout()}
        disabled={pending}
        aria-busy={pending}
        className="inline-flex h-11 items-center justify-center rounded-xl border border-[var(--admin-border)] bg-white px-4 text-sm font-medium text-[var(--admin-primary)] transition-colors hover:bg-[var(--admin-primary-soft)] focus-visible:ring-2 focus-visible:ring-[var(--admin-primary)] focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-60"
      >
        {pending ? "正在退出…" : "退出管理员会话"}
      </button>
    </div>
  );
}
