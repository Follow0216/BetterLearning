"use client";

import { useToast } from "@betterlearning/ui-feedback";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState, type FormEvent } from "react";

const INPUT_CLASS =
  "h-12 w-full rounded-xl border border-[var(--admin-border)] bg-[var(--admin-bg)] px-4 text-base text-[var(--admin-text)] placeholder:text-[var(--admin-muted)] focus:border-[var(--admin-primary)] focus:ring-2 focus:ring-[var(--admin-primary)] focus:outline-none disabled:cursor-not-allowed disabled:opacity-60";

const PRIMARY_BUTTON =
  "inline-flex h-12 w-full items-center justify-center rounded-xl bg-[var(--admin-primary)] text-base font-medium text-white transition-colors hover:bg-[var(--admin-primary-hover)] focus-visible:ring-2 focus-visible:ring-[var(--admin-primary)] focus-visible:ring-offset-2 focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-60";

async function readErrorMessage(response: Response): Promise<string> {
  try {
    const payload: unknown = await response.json();
    if (
      typeof payload === "object" &&
      payload !== null &&
      "message" in payload &&
      typeof payload.message === "string" &&
      payload.message.length > 0
    ) {
      return payload.message;
    }
  } catch {
    return "登录失败，请稍后重试";
  }
  return "登录失败，请稍后重试";
}

export function AdminLoginForm() {
  const router = useRouter();
  const { captureScope, clearScope, dismiss, show } = useToast();
  const activeRequest = useRef<AbortController | null>(null);
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [pending, setPending] = useState(false);
  const [error, setError] = useState("");
  const [errorNotified, setErrorNotified] = useState(false);

  useEffect(() => {
    return () => {
      activeRequest.current?.abort();
      clearScope();
    };
  }, [clearScope]);

  async function handleSubmit(event: FormEvent<HTMLFormElement>): Promise<void> {
    event.preventDefault();
    if (pending || activeRequest.current !== null) {
      return;
    }
    const normalizedUsername = username.trim();
    if (normalizedUsername.length === 0 || password.length === 0) {
      setErrorNotified(false);
      setError("请输入用户名和密码");
      return;
    }

    const controller = new AbortController();
    activeRequest.current = controller;
    const scope = captureScope();
    dismiss("admin-login");
    setPending(true);
    setError("");
    setErrorNotified(false);
    try {
      const response = await fetch("/api/admin/login", {
        method: "POST",
        signal: controller.signal,
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify({ username: normalizedUsername, password }),
      });
      if (controller.signal.aborted) {
        return;
      }
      if (response.ok) {
        setPassword("");
        router.replace("/account");
        router.refresh();
        return;
      }
      const message =
        response.status === 403
          ? "该账号不是管理员，无法进入后台"
          : response.status === 401
            ? "用户名或密码不正确"
            : response.status === 503
              ? "认证服务暂不可用，请稍后重试"
              : await readErrorMessage(response);
      if (controller.signal.aborted) {
        return;
      }
      setError(message);
      setErrorNotified(response.status >= 500);
      if (response.status >= 500) {
        show({ key: "admin-login", type: "error", message }, scope);
      }
    } catch {
      if (!controller.signal.aborted) {
        const message = "网络连接异常，请稍后重试";
        setError(message);
        setErrorNotified(true);
        show({ key: "admin-login", type: "error", message }, scope);
      }
    } finally {
      if (!controller.signal.aborted) {
        activeRequest.current = null;
        setPending(false);
      }
    }
  }

  return (
    <form
      className="mt-6 flex flex-col gap-4"
      onSubmit={(event) => void handleSubmit(event)}
      noValidate
    >
      <div>
        <label htmlFor="admin-username" className="text-sm font-medium text-[var(--admin-text)]">
          管理员用户名
        </label>
        <input
          id="admin-username"
          name="username"
          type="text"
          value={username}
          autoComplete="username"
          placeholder="请输入管理员用户名"
          disabled={pending}
          aria-describedby={error.length > 0 ? "admin-login-error" : undefined}
          onChange={(event) => {
            setUsername(event.target.value);
          }}
          className={`mt-1.5 ${INPUT_CLASS}`}
        />
      </div>
      <div>
        <label htmlFor="admin-password" className="text-sm font-medium text-[var(--admin-text)]">
          密码
        </label>
        <input
          id="admin-password"
          name="password"
          type="password"
          value={password}
          autoComplete="current-password"
          placeholder="请输入密码"
          disabled={pending}
          aria-describedby={error.length > 0 ? "admin-login-error" : undefined}
          onChange={(event) => {
            setPassword(event.target.value);
          }}
          className={`mt-1.5 ${INPUT_CLASS}`}
        />
      </div>
      {error.length > 0 ? (
        <p
          id="admin-login-error"
          role={errorNotified ? undefined : "alert"}
          className="text-[13px] leading-5 text-[var(--admin-danger)]"
        >
          {error}
        </p>
      ) : null}
      <button type="submit" disabled={pending} aria-busy={pending} className={PRIMARY_BUTTON}>
        {pending ? "正在登录…" : "登录"}
      </button>
    </form>
  );
}
