import "server-only";

import type { NextRequest } from "next/server";

import type { AuthErrorPayload } from "./auth-contract";

export interface GuardFailure {
  status: number;
  payload: AuthErrorPayload;
}

interface ExpectedOrigin {
  origin: string | null;
  reason: "none" | "missing-config" | "invalid-config" | "invalid-host";
}

function reject(code: string, message: string, status: number): GuardFailure {
  return { status, payload: { code, message, traceId: "" } };
}

/**
 * 严格解析完整 origin：只接受 http/https，且不得带 userinfo、路径、query 或 hash。
 *
 * 末尾单个 "/" 视为等价写法。解析结果必须与输入规范化后完全一致，避免
 * `http://evil.example/path` 之类被 URL 解析成 origin 后混入白名单。
 */
export function parseStrictOrigin(value: string): string | null {
  const trimmed = value.trim();
  if (trimmed.length === 0 || trimmed.toLowerCase() === "null") {
    return null;
  }
  let url: URL;
  try {
    url = new URL(trimmed);
  } catch {
    return null;
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") {
    return null;
  }
  if (url.username !== "" || url.password !== "") {
    return null;
  }
  if (url.pathname !== "/" || url.search !== "" || url.hash !== "") {
    return null;
  }
  const normalizedInput = trimmed.endsWith("/") ? trimmed.slice(0, -1) : trimmed;
  if (url.origin !== normalizedInput.toLowerCase()) {
    return null;
  }
  return url.origin;
}

/**
 * 由直接 Host 与请求 URL 的协议重建外部 origin（仅开发回退使用）。
 *
 * 不读取 X-Forwarded-Host / X-Forwarded-Proto / X-Forwarded-For；Host 必须是无路径、
 * 无 userinfo 的 host[:port]。
 */
function originFromHost(request: NextRequest): string | null {
  const host = request.headers.get("host");
  if (host === null) {
    return null;
  }
  const hostPattern =
    /^(?:[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?|\[[0-9A-Fa-f:.]+\])(?::\d{1,5})?$/;
  if (!hostPattern.test(host)) {
    return null;
  }
  let protocol: string;
  try {
    protocol = new URL(request.url).protocol;
  } catch {
    return null;
  }
  if (protocol !== "http:" && protocol !== "https:") {
    return null;
  }
  return parseStrictOrigin(`${protocol}//${host}`);
}

function expectedOrigin(request: NextRequest): ExpectedOrigin {
  const configured = process.env.APP_ORIGIN?.trim();
  if (configured !== undefined && configured.length > 0) {
    const origin = parseStrictOrigin(configured);
    return origin === null
      ? { origin: null, reason: "invalid-config" }
      : { origin, reason: "none" };
  }
  if (process.env.NODE_ENV === "production") {
    return { origin: null, reason: "missing-config" };
  }
  const origin = originFromHost(request);
  return origin === null ? { origin: null, reason: "invalid-host" } : { origin, reason: "none" };
}

/**
 * 状态变更请求的防护：Origin 必须与本应用一致，POST 还要求 JSON Content-Type。
 *
 * 生产必须显式配置 APP_ORIGIN 并精确匹配；非生产未配置时按实际 Host 重建同源，
 * 仅用于本地开发，不接受通配或子串匹配，也不信任任何 X-Forwarded-* 头。
 */
export function guardMutation(
  request: NextRequest,
  options: { requireJson: boolean },
): GuardFailure | null {
  const expected = expectedOrigin(request);
  if (expected.origin === null) {
    if (expected.reason === "invalid-config") {
      return reject("W0002", "APP_ORIGIN 配置不合法，必须是完整的 http/https origin", 500);
    }
    if (expected.reason === "invalid-host") {
      return reject("A0403", "无法确认请求来源，请求被拒绝", 403);
    }
    return reject("W0002", "服务端缺少 APP_ORIGIN 配置", 500);
  }

  const originHeader = request.headers.get("origin");
  if (!originHeader) {
    return reject("A0403", "缺少 Origin 请求头，请求被拒绝", 403);
  }
  const requestOrigin = parseStrictOrigin(originHeader);
  if (requestOrigin === null || requestOrigin !== expected.origin) {
    return reject("A0403", "跨站请求被拒绝", 403);
  }

  if (options.requireJson) {
    const contentType = request.headers.get("content-type");
    const mime = contentType?.split(";")[0]?.trim().toLowerCase();
    if (mime !== "application/json") {
      return reject("A0428", "请求体必须为 JSON", 415);
    }
  }
  return null;
}
