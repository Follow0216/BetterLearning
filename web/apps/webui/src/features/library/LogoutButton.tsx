"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";

import { useFeedbackRequest } from "@/components/feedback/use-feedback-request";
import { BlIcon } from "@/components/icons/BlIcon";

export function LogoutButton() {
  const router = useRouter();
  const { toast, begin } = useFeedbackRequest();
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");

  async function handleLogout(): Promise<void> {
    if (pending) {
      return;
    }
    const request = begin();
    if (!request) return;
    setPending(true);
    setError("");
    try {
      const response = await fetch("/api/auth/logout", {
        signal: request.signal,
        method: "DELETE",
        headers: { Accept: "application/json" },
      });
      if (!request.isCurrent()) return;
      if (response.status === 204 || response.status === 401) {
        toast.clearScope();
        router.replace("/");
        router.refresh();
        return;
      }
      setError("退出失败，请重试");
      toast.show(
        { key: "auth:logout", type: "error", message: "退出未完成", description: "请稍后重试" },
        request.scope,
      );
    } catch {
      if (!request.isCurrent()) return;
      setError("网络异常，请重试");
      toast.show(
        { key: "auth:logout", type: "error", message: "退出未完成", description: "请稍后重试" },
        request.scope,
      );
    } finally {
      if (request.finish()) setPending(false);
    }
  }

  return (
    <span className="inline-flex items-center gap-2">
      {error.length > 0 ? (
        <span id="library-logout-error" className="text-xs text-[var(--auth-danger)]">
          {error}
        </span>
      ) : null}
      <button
        type="button"
        onClick={() => void handleLogout()}
        disabled={pending}
        aria-busy={pending}
        aria-describedby={error ? "library-logout-error" : undefined}
        aria-label={pending ? "正在退出" : "退出"}
        title="退出"
        className="inline-flex h-9 items-center justify-center gap-1.5 rounded-lg px-2 text-sm font-medium text-[var(--auth-muted)] transition-colors hover:text-[var(--auth-text)] focus-visible:ring-2 focus-visible:ring-[var(--auth-primary)] focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-60"
      >
        <BlIcon name="logout" size={16} />
        <span className="hidden sm:inline">{pending ? "正在退出…" : "退出"}</span>
      </button>
    </span>
  );
}
