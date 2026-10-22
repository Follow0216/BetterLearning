import "server-only";

import {
  AuthContractError,
  type AuthErrorPayload,
  type AuthSessionPayload,
  type AuthUserProfile,
  parseSessionPayload,
  parseUserProfile,
  tryParseErrorPayload,
} from "./auth-contract";

/** 开发环境默认后端地址；生产必须显式配置 AUTH_API_BASE_URL。 */
const DEFAULT_BASE_URL = "http://127.0.0.1:18081";

/** 后端请求超时，避免浏览器请求长时间挂起。 */
const REQUEST_TIMEOUT_MS = 15_000;

/** 只允许访问这四个认证接口，防止 base URL 被用于任意转发。 */
const ALLOWED_PATHS: ReadonlySet<string> = new Set([
  "/api/v1/sessions",
  "/api/v1/sessions/current",
  "/api/v1/users",
  "/api/v1/users/me",
]);

/** 后端返回非 2xx 时携带的已归一化错误。 */
export class AuthBackendError extends Error {
  readonly status: number;
  readonly payload: AuthErrorPayload;

  constructor(status: number, payload: AuthErrorPayload) {
    super(payload.message);
    this.name = "AuthBackendError";
    this.status = status;
    this.payload = payload;
  }
}

/** 后端不可达或超时；与业务错误区分，便于上层保留登录态。 */
export class AuthBackendUnavailableError extends Error {
  readonly timedOut: boolean;

  constructor(timedOut: boolean) {
    super(timedOut ? "认证服务响应超时" : "认证服务不可用");
    this.name = "AuthBackendUnavailableError";
    this.timedOut = timedOut;
  }
}

function resolveBaseUrl(): string {
  const configured = process.env.AUTH_API_BASE_URL?.trim();
  if (!configured) {
    if (process.env.NODE_ENV === "production") {
      throw new Error("缺少服务端环境变量 AUTH_API_BASE_URL");
    }
    return DEFAULT_BASE_URL;
  }
  let url: URL;
  try {
    url = new URL(configured);
  } catch {
    throw new Error("AUTH_API_BASE_URL 不是合法 URL");
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") {
    throw new Error("AUTH_API_BASE_URL 只支持 http/https");
  }
  const path = url.pathname === "/" ? "" : url.pathname.replace(/\/+$/, "");
  return `${url.origin}${path}`;
}

interface BackendRequest {
  method: "GET" | "POST" | "DELETE";
  pathname: string;
  body?: string;
  accessToken?: string;
}

async function callBackend(request: BackendRequest): Promise<Response> {
  if (!ALLOWED_PATHS.has(request.pathname)) {
    throw new Error("非白名单认证接口");
  }
  const headers: Record<string, string> = { Accept: "application/json" };
  if (request.body !== undefined) {
    headers["Content-Type"] = "application/json";
  }
  if (request.accessToken !== undefined) {
    headers.Authorization = `Bearer ${request.accessToken}`;
  }
  try {
    return await fetch(`${resolveBaseUrl()}${request.pathname}`, {
      method: request.method,
      headers,
      body: request.body,
      cache: "no-store",
      redirect: "manual",
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
  } catch (error) {
    const timedOut = error instanceof Error && error.name === "TimeoutError";
    throw new AuthBackendUnavailableError(timedOut);
  }
}

async function readJson(response: Response): Promise<unknown> {
  try {
    return (await response.json()) as unknown;
  } catch {
    throw new AuthContractError("认证服务响应不是合法 JSON");
  }
}

async function toBackendError(response: Response): Promise<AuthBackendError> {
  let payload: AuthErrorPayload | null = null;
  try {
    payload = tryParseErrorPayload(await readJson(response));
  } catch {
    payload = null;
  }
  return new AuthBackendError(
    response.status,
    payload ?? { code: "W0001", message: "认证服务返回异常，请稍后重试", traceId: "" },
  );
}

/** 登录：POST /api/v1/sessions，严格校验 SessionVO。 */
export async function loginWithBackend(
  username: string,
  password: string,
): Promise<AuthSessionPayload> {
  const response = await callBackend({
    method: "POST",
    pathname: "/api/v1/sessions",
    body: JSON.stringify({ username, password }),
  });
  if (response.status !== 201) {
    throw await toBackendError(response);
  }
  return parseSessionPayload(await readJson(response));
}

/** 注册：POST /api/v1/users，返回 UserVO，不签发令牌。 */
export async function registerWithBackend(
  username: string,
  password: string,
  confirmPassword: string,
): Promise<AuthUserProfile> {
  const response = await callBackend({
    method: "POST",
    pathname: "/api/v1/users",
    body: JSON.stringify({ username, password, confirmPassword }),
  });
  if (response.status !== 201) {
    throw await toBackendError(response);
  }
  return parseUserProfile(await readJson(response));
}

/** 当前资料：GET /api/v1/users/me。 */
export async function getProfileFromBackend(accessToken: string): Promise<AuthUserProfile> {
  const response = await callBackend({
    method: "GET",
    pathname: "/api/v1/users/me",
    accessToken,
  });
  if (response.status !== 200) {
    throw await toBackendError(response);
  }
  return parseUserProfile(await readJson(response));
}

/** 退出：DELETE /api/v1/sessions/current，成功为 204 且无响应体。 */
export async function logoutWithBackend(accessToken: string): Promise<void> {
  const response = await callBackend({
    method: "DELETE",
    pathname: "/api/v1/sessions/current",
    accessToken,
  });
  if (response.status === 204) {
    return;
  }
  throw await toBackendError(response);
}
