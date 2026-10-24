"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

import { BlIcon } from "@/components/icons/BlIcon";

export function LogoutButton() {
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
      const response = await fetch("/api/auth/logout", {
        method: "DELETE",
        headers: { Accept: "application/json" },
      });
      if (response.status === 204 || response.status === 401) {
        router.replace("/");
        router.refresh();
        return;
      }
      setError("退出失败，请重试");
    } catch {
      setError("网络异常，请重试");
    } finally {
      setPending(false);
    }
  }

  return (
    <span className="inline-flex items-center gap-2">
      {error.length > 0 ? (
        <span role="alert" className="text-xs text-[var(--auth-danger)]">
          {error}
        </span>
      ) : null}
      <button
        type="button"
        onClick={() => void handleLogout()}
        disabled={pending}
        aria-busy={pending}
        className="inline-flex h-9 items-center justify-center gap-1.5 rounded-lg px-2 text-sm font-medium text-[var(--auth-muted)] transition-colors hover:text-[var(--auth-text)] focus-visible:ring-2 focus-visible:ring-[var(--auth-primary)] focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-60"
      >
        <BlIcon name="logout" size={16} />
        {pending ? "正在退出…" : "退出"}
      </button>
    </span>
  );
}