"use client";

import Link from "next/link";
import { useCallback, useEffect, useRef, useState, type FormEvent, type RefObject } from "react";

import type { AuthUserProfile } from "@/lib/api/auth-contract";
import { announceSessionChange, subscribeSessionChanges } from "./session-events";

import { validateLoginForm, validateRegisterForm, type AuthFieldName } from "./auth-validation";

type Stage = "checking" | "form" | "profile" | "retry";
type Mode = "login" | "register";

interface ApiFailure {
  status: number;
  code: string;
  message: string;
  traceId: string;
  fieldErrors: Record<string, string>;
}

interface SessionResponse {
  user?: unknown;
}

interface TextFieldProps {
  id: string;
  label: string;
  name: string;
  type: "text" | "password";
  value: string;
  placeholder: string;
  autoComplete: string;
  error?: string;
  disabled: boolean;
  inputRef: RefObject<HTMLInputElement | null>;
  onChange: (value: string) => void;
  toggle?: {
    visible: boolean;
    onToggle: () => void;
  };
}

const FIELD_IDS: Record<AuthFieldName, string> = {
  username: "auth-username",
  password: "auth-password",
  confirmPassword: "auth-confirm-password",
};

const PRIMARY_BUTTON =
  "inline-flex h-[52px] w-full items-center justify-center rounded-2xl bg-[var(--auth-primary)] text-base font-medium text-white transition-colors hover:bg-[var(--auth-primary-hover)] focus-visible:ring-2 focus-visible:ring-[var(--auth-primary)] focus-visible:ring-offset-2 focus-visible:ring-offset-white focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-60";

const SECONDARY_BUTTON =
  "inline-flex h-[52px] w-full items-center justify-center rounded-2xl border border-[var(--auth-border)] bg-white text-base font-medium text-[var(--auth-primary)] transition-colors hover:bg-[var(--auth-primary-soft)] focus-visible:ring-2 focus-visible:ring-[var(--auth-primary)] focus-visible:ring-offset-2 focus-visible:ring-offset-white focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-60";

const INPUT_CLASS =
  "h-[52px] w-full rounded-2xl border border-[var(--auth-border)] bg-[var(--auth-bg)] px-4 text-base text-[var(--auth-text)] placeholder:text-[var(--auth-muted)] focus:border-[var(--auth-primary)] focus:ring-2 focus:ring-[var(--auth-primary)] focus:outline-none disabled:cursor-not-allowed disabled:opacity-60";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function toUserProfile(value: unknown): AuthUserProfile | null {
  if (!isRecord(value)) {
    return null;
  }
  const { accountId, username, phone, email, role, createdAt, updatedAt } = value;
  if (typeof accountId !== "string" || accountId.length === 0) {
    return null;
  }
  if (typeof username !== "string" || username.length === 0) {
    return null;
  }
  if (role !== "USER" && role !== "ADMIN") {
    return null;
  }
  return {
    accountId,
    username,
    phone: typeof phone === "string" ? phone : null,
    email: typeof email === "string" ? email : null,
    role,
    createdAt: typeof createdAt === "string" ? createdAt : "",
    updatedAt: typeof updatedAt === "string" ? updatedAt : "",
  };
}

async function readFailure(response: Response): Promise<ApiFailure> {
  let code = "";
  let message = "";
  let traceId = "";
  let fieldErrors: Record<string, string> = {};
  try {
    const payload: unknown = await response.json();
    if (isRecord(payload)) {
      code = typeof payload.code === "string" ? payload.code : "";
      message = typeof payload.message === "string" ? payload.message : "";
      traceId = typeof payload.traceId === "string" ? payload.traceId : "";
      if (isRecord(payload.fieldErrors)) {
        const mapped: Record<string, string> = {};
        for (const [key, value] of Object.entries(payload.fieldErrors)) {
          if (typeof value === "string") {
            mapped[key] = value;
          }
        }
        fieldErrors = mapped;
      }
    }
  } catch {
    // 响应不是 JSON 时使用下方默认文案。
  }
  return { status: response.status, code, message, traceId, fieldErrors };
}

async function readUser(response: Response): Promise<AuthUserProfile | null> {
  try {
    const payload = (await response.json()) as SessionResponse;
    return toUserProfile(payload.user);
  } catch {
    return null;
  }
}

function eyeIcon(visible: boolean) {
  if (visible) {
    return (
      <svg aria-hidden="true" viewBox="0 0 24 24" className="h-5 w-5" fill="none">
        <path
          d="M3 3l18 18M10.6 10.7a2 2 0 002.8 2.8M9.9 5.2A9.7 9.7 0 0112 5c5 0 8.5 4.2 9.5 7-.4 1.1-1.1 2.3-2.1 3.4M6.2 6.3C4.4 7.6 3.2 9.4 2.5 12c1 2.8 4.5 7 9.5 7 1.3 0 2.5-.3 3.6-.7"
          stroke="currentColor"
          strokeWidth="1.7"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
    );
  }
  return (
    <svg aria-hidden="true" viewBox="0 0 24 24" className="h-5 w-5" fill="none">
      <path
        d="M2.5 12C3.5 9.2 7 5 12 5s8.5 4.2 9.5 7c-1 2.8-4.5 7-9.5 7s-8.5-4.2-9.5-7z"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <circle cx="12" cy="12" r="3" stroke="currentColor" strokeWidth="1.7" />
    </svg>
  );
}

function TextField({
  id,
  label,
  name,
  type,
  value,
  placeholder,
  autoComplete,
  error,
  disabled,
  inputRef,
  onChange,
  toggle,
}: TextFieldProps) {
  const errorId = `${id}-error`;
  return (
    <div className="flex flex-col gap-2">
      <label htmlFor={id} className="text-sm font-medium text-[var(--auth-text)]">
        {label}
      </label>
      <div className="relative">
        <input
          ref={inputRef}
          id={id}
          name={name}
          type={type}
          value={value}
          placeholder={placeholder}
          autoComplete={autoComplete}
          autoCapitalize="none"
          spellCheck={false}
          disabled={disabled}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? errorId : undefined}
          onChange={(event) => {
            onChange(event.target.value);
          }}
          className={toggle ? `${INPUT_CLASS} pr-14` : INPUT_CLASS}
        />
        {toggle ? (
          <button
            type="button"
            onClick={toggle.onToggle}
            aria-label={toggle.visible ? "隐藏密码" : "显示密码"}
            aria-pressed={toggle.visible}
            disabled={disabled}
            className="absolute inset-y-0 right-0 flex w-12 items-center justify-center rounded-r-2xl text-[var(--auth-muted)] transition-colors hover:text-[var(--auth-text)] focus-visible:ring-2 focus-visible:ring-[var(--auth-primary)] focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-60"
          >
            {eyeIcon(toggle.visible)}
          </button>
        ) : null}
      </div>
      {error ? (
        <p id={errorId} role="alert" className="text-[13px] leading-5 text-[var(--auth-danger)]">
          {error}
        </p>
      ) : null}
    </div>
  );
}

export function AuthForm() {
  const [stage, setStage] = useState<Stage>("checking");
  const [mode, setMode] = useState<Mode>("login");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [passwordVisible, setPasswordVisible] = useState(false);
  const [errors, setErrors] = useState<Partial<Record<AuthFieldName, string>>>({});
  const [formError, setFormError] = useState("");
  const [formNotice, setFormNotice] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [loggingOut, setLoggingOut] = useState(false);
  const [profile, setProfile] = useState<AuthUserProfile | null>(null);
  const [profileError, setProfileError] = useState("");
  const [retryMessage, setRetryMessage] = useState("");

  const ownAnnouncement = useRef(false);
  const notifySessionChange = (kind: "changed" | "ended") => {
    ownAnnouncement.current = true;
    try {
      announceSessionChange(kind);
    } finally {
      ownAnnouncement.current = false;
    }
  };

  const usernameRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);
  const confirmPasswordRef = useRef<HTMLInputElement>(null);
  const sequenceRef = useRef(0);
  const controllerRef = useRef<AbortController | null>(null);

  const beginRequest = useCallback(() => {
    sequenceRef.current += 1;
    controllerRef.current?.abort();
    const controller = new AbortController();
    controllerRef.current = controller;
    return { sequence: sequenceRef.current, signal: controller.signal };
  }, []);

  const isCurrent = useCallback((sequence: number) => sequenceRef.current === sequence, []);

  const loadSession = useCallback(
    async (options?: { loginJustCompleted?: boolean }) => {
      const { sequence, signal } = beginRequest();

      try {
        const response = await fetch("/api/auth/session", {
          method: "GET",
          cache: "no-store",
          headers: { Accept: "application/json" },
          signal,
        });
        if (!isCurrent(sequence)) {
          return;
        }
        if (response.status === 401) {
          setProfile(null);
          setStage("form");
          if (options?.loginJustCompleted) {
            setFormError("登录状态校验失败，请重新登录");
          }
          return;
        }
        if (!response.ok) {
          const failure = await readFailure(response);
          if (!isCurrent(sequence)) {
            return;
          }
          setRetryMessage(
            failure.status === 503
              ? "服务暂时不可用，请稍后重试"
              : failure.message || "加载账号信息失败",
          );
          setStage("retry");
          return;
        }
        const user = await readUser(response);
        if (!isCurrent(sequence)) {
          return;
        }
        if (user === null) {
          setRetryMessage("账号信息格式异常，请稍后重试");
          setStage("retry");
          return;
        }
        setProfile(user);
        setStage("profile");
      } catch {
        if (!isCurrent(sequence)) {
          return;
        }
        setRetryMessage("网络连接异常，请稍后重试");
        setStage("retry");
      }
    },
    [beginRequest, isCurrent],
  );

  useEffect(() => {
    const timer = setTimeout(() => {
      void loadSession();
    }, 0);
    return () => {
      clearTimeout(timer);
      controllerRef.current?.abort();
    };
  }, [loadSession]);

  useEffect(
    () =>
      subscribeSessionChanges(() => {
        if (ownAnnouncement.current) return;
        setProfile(null);
        setStage("checking");
        void loadSession();
      }),
    [loadSession],
  );

  const focusFirstError = (nextErrors: Partial<Record<AuthFieldName, string>>) => {
    if (nextErrors.username) {
      usernameRef.current?.focus();
    } else if (nextErrors.password) {
      passwordRef.current?.focus();
    } else if (nextErrors.confirmPassword) {
      confirmPasswordRef.current?.focus();
    }
  };

  const applyFailure = (failure: ApiFailure) => {
    const mapped: Partial<Record<AuthFieldName, string>> = {};
    for (const field of ["username", "password", "confirmPassword"] as const) {
      const message = failure.fieldErrors[field];
      if (typeof message === "string" && message.length > 0) {
        mapped[field] = message;
      }
    }
    setErrors(mapped);
    if (failure.status === 503) {
      setFormError("服务暂时不可用，请稍后重试");
      return;
    }
    if (failure.status === 429) {
      setFormError("操作过于频繁，请稍后再试");
      return;
    }
    setFormError(failure.message || "操作失败，请稍后重试");
  };

  const handleLogin = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (submitting) {
      return;
    }
    const nextErrors = validateLoginForm(username, password);
    setErrors(nextErrors);
    setFormError("");
    setFormNotice("");
    if (Object.keys(nextErrors).length > 0) {
      focusFirstError(nextErrors);
      return;
    }

    const { sequence, signal } = beginRequest();
    setSubmitting(true);
    try {
      const response = await fetch("/api/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify({ username: username.trim(), password }),
        signal,
      });
      if (!isCurrent(sequence)) {
        return;
      }
      if (!response.ok) {
        applyFailure(await readFailure(response));
        return;
      }
      notifySessionChange("changed");
      setPassword("");
      setConfirmPassword("");
      setErrors({});
      setStage("checking");
      setSubmitting(false);
      await loadSession({ loginJustCompleted: true });
    } catch {
      if (!isCurrent(sequence)) {
        return;
      }
      setFormError("网络连接异常，请稍后重试");
    } finally {
      setSubmitting(false);
    }
  };

  const handleRegister = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (submitting) {
      return;
    }
    const nextErrors = validateRegisterForm(username, password, confirmPassword);
    setErrors(nextErrors);
    setFormError("");
    setFormNotice("");
    if (Object.keys(nextErrors).length > 0) {
      focusFirstError(nextErrors);
      return;
    }

    const normalized = username.trim();
    const { sequence, signal } = beginRequest();
    setSubmitting(true);
    try {
      const response = await fetch("/api/auth/register", {
        method: "POST",
        headers: { "Content-Type": "application/json", Accept: "application/json" },
        body: JSON.stringify({ username: normalized, password, confirmPassword }),
        signal,
      });
      if (!isCurrent(sequence)) {
        return;
      }
      if (!response.ok) {
        applyFailure(await readFailure(response));
        return;
      }
      setMode("login");
      setUsername(normalized);
      setPassword("");
      setConfirmPassword("");
      setPasswordVisible(false);
      setErrors({});
      setFormError("");
      setFormNotice("注册成功，请登录");
    } catch {
      if (!isCurrent(sequence)) {
        return;
      }
      setFormError("网络连接异常，请稍后重试");
    } finally {
      if (isCurrent(sequence)) {
        setSubmitting(false);
      }
    }
  };

  const handleRefresh = async () => {
    if (refreshing) {
      return;
    }
    const { sequence, signal } = beginRequest();
    setRefreshing(true);
    setProfileError("");
    try {
      const response = await fetch("/api/auth/session", {
        method: "GET",
        cache: "no-store",
        headers: { Accept: "application/json" },
        signal,
      });
      if (!isCurrent(sequence)) {
        return;
      }
      if (response.status === 401) {
        setProfile(null);
        setStage("form");
        setFormNotice("登录状态已失效，请重新登录");
        return;
      }
      if (!response.ok) {
        const failure = await readFailure(response);
        if (!isCurrent(sequence)) {
          return;
        }
        setProfileError(
          failure.status === 503 ? "服务暂时不可用，请稍后重试" : failure.message || "加载资料失败",
        );
        return;
      }
      const user = await readUser(response);
      if (!isCurrent(sequence)) {
        return;
      }
      if (user === null) {
        setProfileError("账号信息格式异常，请稍后重试");
        return;
      }
      setProfile(user);
      setProfileError("");
    } catch {
      if (isCurrent(sequence)) {
        setProfileError("网络连接异常，请稍后重试");
      }
    } finally {
      setRefreshing(false);
    }
  };

  const handleLogout = async () => {
    if (loggingOut) {
      return;
    }
    const { sequence, signal } = beginRequest();
    setLoggingOut(true);
    setProfileError("");
    try {
      const response = await fetch("/api/auth/logout", {
        method: "DELETE",
        headers: { Accept: "application/json" },
        signal,
      });
      if (!isCurrent(sequence)) {
        return;
      }
      if (response.status === 204 || response.status === 401) {
        notifySessionChange("ended");
        setProfile(null);
        setStage("form");
        setMode("login");
        setUsername("");
        setPassword("");
        setConfirmPassword("");
        setPasswordVisible(false);
        setErrors({});
        setFormError("");
        setFormNotice("已退出登录");
        return;
      }
      setProfileError("无法确认退出结果，请重试");
    } catch {
      if (isCurrent(sequence)) {
        setProfileError("无法确认退出结果，请重试");
      }
    } finally {
      if (isCurrent(sequence)) {
        setLoggingOut(false);
      }
    }
  };

  const switchMode = () => {
    if (submitting) {
      return;
    }
    sequenceRef.current += 1;
    controllerRef.current?.abort();
    setMode(mode === "login" ? "register" : "login");
    setPassword("");
    setConfirmPassword("");
    setPasswordVisible(false);
    setErrors({});
    setFormError("");
    setFormNotice("");
  };

  if (stage === "checking") {
    return (
      <div
        className="flex min-h-[360px] flex-col items-center justify-center gap-3"
        aria-live="polite"
        aria-busy="true"
      >
        <span
          aria-hidden="true"
          className="h-8 w-8 animate-spin rounded-full border-2 border-[var(--auth-border)] border-t-[var(--auth-primary)]"
        />
        <p className="text-sm text-[var(--auth-muted)]">正在检查登录状态…</p>
      </div>
    );
  }

  if (stage === "retry") {
    return (
      <div className="flex min-h-[360px] flex-col items-center justify-center gap-4 text-center">
        <h1 className="text-[28px] leading-9 font-semibold text-[var(--auth-text)]">
          暂时无法加载账号信息
        </h1>
        <p role="alert" className="text-sm leading-6 text-[var(--auth-muted)]">
          {retryMessage || "请稍后重试"}
        </p>
        <button
          type="button"
          onClick={() => {
            setStage("checking");
            void loadSession();
          }}
          className={PRIMARY_BUTTON}
        >
          重试
        </button>
      </div>
    );
  }

  if (stage === "profile" && profile !== null) {
    return (
      <div>
        <h1 className="text-[28px] leading-9 font-semibold text-[var(--auth-text)]">已登录</h1>
        <p className="mt-2 text-base text-[var(--auth-muted)]">当前账号</p>
        <dl className="mt-6 flex flex-col gap-4">
          <div>
            <dt className="text-sm font-medium text-[var(--auth-muted)]">用户名</dt>
            <dd className="mt-1 text-base break-all text-[var(--auth-text)]">{profile.username}</dd>
          </div>
          <div>
            <dt className="text-sm font-medium text-[var(--auth-muted)]">账号 ID</dt>
            <dd className="mt-1 font-mono text-base break-all text-[var(--auth-text)]">
              {profile.accountId}
            </dd>
          </div>
        </dl>
        {profileError ? (
          <p role="alert" className="mt-4 text-[13px] leading-5 text-[var(--auth-danger)]">
            {profileError}
          </p>
        ) : null}
        <div className="mt-8 flex flex-col gap-3">
          <Link href="/workspace" className={PRIMARY_BUTTON}>
            进入题库
          </Link>
          <button
            type="button"
            onClick={() => void handleRefresh()}
            disabled={refreshing || loggingOut}
            aria-busy={refreshing}
            className={SECONDARY_BUTTON}
          >
            {refreshing ? "正在刷新…" : "刷新资料"}
          </button>
          <button
            type="button"
            onClick={() => void handleLogout()}
            disabled={loggingOut || refreshing}
            aria-busy={loggingOut}
            className={SECONDARY_BUTTON}
          >
            {loggingOut ? "正在退出…" : "退出登录"}
          </button>
        </div>
      </div>
    );
  }

  const isRegister = mode === "register";
  return (
    <div>
      <h1 className="text-[28px] leading-9 font-semibold text-[var(--auth-text)]">
        {isRegister ? "创建账号" : "欢迎回来"}
      </h1>
      {isRegister ? null : (
        <p className="mt-2 text-base text-[var(--auth-muted)]">登录你的学习账号</p>
      )}

      {formNotice ? (
        <p
          role="status"
          className="mt-4 rounded-2xl bg-[var(--auth-primary-soft)] px-4 py-3 text-[13px] leading-5 text-[var(--auth-primary)]"
        >
          {formNotice}
        </p>
      ) : null}

      <form
        className="mt-6 flex flex-col gap-4"
        onSubmit={(event) => {
          void (isRegister ? handleRegister(event) : handleLogin(event));
        }}
        noValidate
      >
        <TextField
          id={FIELD_IDS.username}
          label="用户名"
          name="username"
          type="text"
          value={username}
          placeholder="请输入用户名"
          autoComplete="username"
          error={errors.username}
          disabled={submitting}
          inputRef={usernameRef}
          onChange={(value) => {
            setUsername(value);
          }}
        />
        <TextField
          id={FIELD_IDS.password}
          label="密码"
          name="password"
          type={passwordVisible ? "text" : "password"}
          value={password}
          placeholder="请输入密码"
          autoComplete={isRegister ? "new-password" : "current-password"}
          error={errors.password}
          disabled={submitting}
          inputRef={passwordRef}
          onChange={(value) => {
            setPassword(value);
          }}
          toggle={{
            visible: passwordVisible,
            onToggle: () => {
              setPasswordVisible((current) => !current);
            },
          }}
        />
        {isRegister ? (
          <TextField
            id={FIELD_IDS.confirmPassword}
            label="确认密码"
            name="confirmPassword"
            type={passwordVisible ? "text" : "password"}
            value={confirmPassword}
            placeholder="请再次输入密码"
            autoComplete="new-password"
            error={errors.confirmPassword}
            disabled={submitting}
            inputRef={confirmPasswordRef}
            onChange={(value) => {
              setConfirmPassword(value);
            }}
          />
        ) : null}

        {formError ? (
          <p role="alert" className="text-[13px] leading-5 text-[var(--auth-danger)]">
            {formError}
          </p>
        ) : null}

        <button
          type="submit"
          disabled={submitting}
          aria-busy={submitting}
          className={`${PRIMARY_BUTTON} mt-2`}
        >
          {submitting ? (isRegister ? "正在注册…" : "正在登录…") : isRegister ? "注册" : "登录"}
        </button>
      </form>

      <div className="mt-4 flex items-center justify-center gap-1 text-sm text-[var(--auth-muted)]">
        <span>{isRegister ? "已有账号？" : "还没有账号？"}</span>
        <button
          type="button"
          onClick={switchMode}
          disabled={submitting}
          className="inline-flex min-h-[44px] items-center rounded-lg px-2 font-medium text-[var(--auth-primary)] transition-colors hover:bg-[var(--auth-primary-soft)] focus-visible:ring-2 focus-visible:ring-[var(--auth-primary)] focus-visible:outline-none disabled:cursor-not-allowed disabled:opacity-60"
        >
          {isRegister ? "返回登录" : "立即注册"}
        </button>
      </div>
    </div>
  );
}
