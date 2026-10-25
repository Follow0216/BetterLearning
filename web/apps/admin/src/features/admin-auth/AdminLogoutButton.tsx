"use client";

import { useToast } from "@betterlearning/ui-feedback";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";

export function AdminLogoutButton() {
  const router = useRouter();
  const { captureScope, clearScope, dismiss, show } = useToast();
  const activeRequest = useRef<AbortController | null>(null);
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    return () => {
      activeRequest.current?.abort();
      clearScope();
    };
  }, [clearScope]);

  async function handleLogout(): Promise<void> {
    if (pending || activeRequest.current !== null) {
      return;
    }
    const controller = new AbortController();
    activeRequest.current = controller;
    const scope = captureScope();
    dismiss("admin-logout");
    setPending(true);
    setError("");
    try {
      const response = await fetch("/api/admin/logout", {
        method: "DELETE",
        signal: controller.signal,
        headers: { Accept: "application/json" },
      });
      if (controller.signal.aborted) {
        return;
      }
      if (response.status === 204 || response.status === 401) {
        router.replace("/login");
        router.refresh();
        return;
      }
      const message = "无法确认退出结果，请重试";
      setError(message);
      show({ key: "admin-logout", type: "error", message }, scope);
    } catch {
      if (!controller.signal.aborted) {
        const message = "网络连接异常，请重试";
        setError(message);
        show({ key: "admin-logout", type: "error", message }, scope);
      }
    } finally {
      if (!controller.signal.aborted) {
        activeRequest.current = null;
        setPending(false);
      }
    }
  }

  return (
    <div className="flex flex-col gap-2">
      {error.length > 0 ? (
        <p id="admin-logout-error" className="text-[13px] text-[var(--admin-danger)]">
          {error}
        </p>
      ) : null}
      <button
        type="button"
        onClick={() => void handleLogout()}
        disabled={pending}
        aria-busy={pending}
        aria-describedby={error.length > 0 ? "admin-logout-error" : undefined}
        className="inline-flex h-11 items-center justify-center rounded-xl border border-[var(--admin-border)] bg-white px-4 text-sm font-medium text-[var(--admin-primary)] transition-colors hover:bg-[var(--admin-primary-soft)] focus-visible:ring-2 focus-visible:ring-[var(--admin-primary)] focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-60"
      >
        {pending ? "正在退出…" : "退出管理员会话"}
      </button>
    </div>
  );
}
