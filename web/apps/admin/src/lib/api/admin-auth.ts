import "server-only";

import type { ApiErrorPayload } from "./admin-response";

const DEFAULT_BASE_URL = "http://127.0.0.1:18081";
const REQUEST_TIMEOUT_MS = 15_000;

const ALLOWED_PATHS: ReadonlySet<string> = new Set([
  "/api/v1/sessions",
  "/api/v1/sessions/current",
  "/api/v1/admin/session",
]);

export interface AdminUser {
  accountId: string;
  username: string;
  phone: string | null;
  email: string | null;
  role: "ADMIN";
  createdAt: string;
  updatedAt: string;
}

export interface AdminLoginResult {
  token: string;
  maxAgeSeconds: number;
  user: AdminUser;
}

interface UserShape {
  accountId: string;
  username: string;
  phone: string | null;
  email: string | null;
  role: string;
  createdAt: string;
  updatedAt: string;
}

interface SessionShape {
  accessToken: string;
  expiresIn: number;
}

export class AdminBackendError extends Error {
  readonly status: number;
  readonly payload: ApiErrorPayload;

  constructor(status: number, payload: ApiErrorPayload) {
    super(payload.message);
    this.name = "AdminBackendError";
    this.status = status;
    this.payload = payload;
  }
}

export class AdminUnavailableError extends Error {
  readonly timedOut: boolean;

  constructor(timedOut: boolean) {
    super(timedOut ? "认证服务响应超时" : "认证服务不可用");
    this.name = "AdminUnavailableError";
    this.timedOut = timedOut;
  }
}

export class AdminContractError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "AdminContractError";
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

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function readString(source: Record<string, unknown>, key: string): string {
  const value = source[key];
  if (typeof value !== "string" || value.length === 0) {
    throw new AdminContractError(`响应字段 ${key} 缺失或类型错误`);
  }
  return value;
}

function readNullableString(source: Record<string, unknown>, key: string): string | null {
  const value = source[key];
  if (value === null || value === undefined) {
    return null;
  }
  if (typeof value !== "string") {
    throw new AdminContractError(`响应字段 ${key} 类型错误`);
  }
  return value;
}

function readTimestamp(source: Record<string, unknown>, key: string): string {
  const value = readString(source, key);
  if (Number.isNaN(Date.parse(value))) {
    throw new AdminContractError(`响应字段 ${key} 不是合法时间`);
  }
  return value;
}

function parseUserShape(value: unknown): UserShape {
  if (!isRecord(value)) {
    throw new AdminContractError("用户信息格式错误");
  }
  const accountId = readString(value, "accountId");
  if (!/^[1-9]\d*$/.test(accountId)) {
    throw new AdminContractError("用户信息 accountId 格式错误");
  }
  return {
    accountId,
    username: readString(value, "username"),
    phone: readNullableString(value, "phone"),
    email: readNullableString(value, "email"),
    role: readString(value, "role"),
    createdAt: readTimestamp(value, "createdAt"),
    updatedAt: readTimestamp(value, "updatedAt"),
  };
}

function toAdminUser(shape: UserShape): AdminUser {
  if (shape.role !== "ADMIN") {
    throw new AdminBackendError(403, {
      code: "A0300",
      message: "该账号不是管理员",
      traceId: "",
    });
  }
  return { ...shape, role: "ADMIN" };
}

function parseSessionShape(value: unknown): SessionShape {
  if (!isRecord(value)) {
    throw new AdminContractError("登录响应格式错误");
  }
  const accessToken = readString(value, "accessToken");
  if (readString(value, "tokenType") !== "Bearer") {
    throw new AdminContractError("登录响应 tokenType 不是 Bearer");
  }
  const expiresIn = value.expiresIn;
  if (typeof expiresIn !== "number" || !Number.isFinite(expiresIn) || expiresIn <= 0) {
    throw new AdminContractError("登录响应 expiresIn 格式错误");
  }
  return { accessToken, expiresIn: Math.floor(expiresIn) };
}

function parseErrorPayload(value: unknown): ApiErrorPayload | null {
  if (!isRecord(value)) {
    return null;
  }
  const { code, message, traceId } = value;
  if (typeof code !== "string" || typeof message !== "string" || typeof traceId !== "string") {
    return null;
  }
  return { code, message, traceId };
}

interface BackendRequest {
  method: "GET" | "POST" | "DELETE";
  pathname: string;
  body?: string;
  accessToken?: string;
}

async function callBackend(request: BackendRequest): Promise<Response> {
  if (!ALLOWED_PATHS.has(request.pathname)) {
    throw new Error("非白名单管理员接口");
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
    throw new AdminUnavailableError(timedOut);
  }
}

async function readJson(response: Response): Promise<unknown> {
  try {
    return (await response.json()) as unknown;
  } catch {
    throw new AdminContractError("认证服务响应不是合法 JSON");
  }
}

async function toBackendError(response: Response): Promise<AdminBackendError> {
  let payload: ApiErrorPayload | null = null;
  try {
    payload = parseErrorPayload(await readJson(response));
  } catch {
    payload = null;
  }
  return new AdminBackendError(
    response.status,
    payload ?? { code: "W0001", message: "认证服务返回异常，请稍后重试", traceId: "" },
  );
}

export async function loginAdmin(username: string, password: string): Promise<AdminLoginResult> {
  const startedAt = Date.now();
  const response = await callBackend({
    method: "POST",
    pathname: "/api/v1/sessions",
    body: JSON.stringify({ username, password }),
  });
  if (response.status !== 201) {
    throw await toBackendError(response);
  }
  const session = parseSessionShape(await readJson(response));
  const elapsedSeconds = Math.max(0, Math.ceil((Date.now() - startedAt) / 1000));
  const maxAgeSeconds = Math.floor(session.expiresIn - elapsedSeconds);
  if (maxAgeSeconds < 1) {
    throw new AdminBackendError(502, {
      code: "W0001",
      message: "管理员会话有效期异常，请重试",
      traceId: "",
    });
  }

  const profileResponse = await callBackend({
    method: "GET",
    pathname: "/api/v1/admin/session",
    accessToken: session.accessToken,
  });
  if (profileResponse.status !== 200) {
    throw await toBackendError(profileResponse);
  }
  const user = toAdminUser(parseUserShape(await readJson(profileResponse)));
  return { token: session.accessToken, maxAgeSeconds, user };
}

export async function getAdminSession(accessToken: string): Promise<AdminUser> {
  const response = await callBackend({
    method: "GET",
    pathname: "/api/v1/admin/session",
    accessToken,
  });
  if (response.status !== 200) {
    throw await toBackendError(response);
  }
  return toAdminUser(parseUserShape(await readJson(response)));
}

export async function logoutAdmin(accessToken: string): Promise<void> {
  const response = await callBackend({
    method: "DELETE",
    pathname: "/api/v1/sessions/current",
    accessToken,
  });
  if (response.status === 204 || response.status === 401) {
    return;
  }
  throw await toBackendError(response);
}