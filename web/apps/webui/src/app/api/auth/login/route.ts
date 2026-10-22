import type { NextRequest } from "next/server";

import { AuthContractError } from "@/lib/api/auth-contract";
import { guardMutation } from "@/lib/api/auth-guard";
import { errorNoStore, jsonNoStore, setSessionCookie, webError } from "@/lib/api/auth-response";
import {
  AuthBackendError,
  AuthBackendUnavailableError,
  loginWithBackend,
} from "@/lib/api/auth-server";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export async function POST(request: NextRequest): Promise<Response> {
  const blocked = guardMutation(request, { requireJson: true });
  if (blocked) {
    return errorNoStore(blocked.status, blocked.payload);
  }

  let body: unknown;
  try {
    body = (await request.json()) as unknown;
  } catch {
    return errorNoStore(400, webError("A0427", "请求体格式错误"));
  }
  if (!isRecord(body)) {
    return errorNoStore(400, webError("A0400", "请求参数不合法"));
  }

  const username = typeof body.username === "string" ? body.username.trim() : "";
  const password = typeof body.password === "string" ? body.password : "";
  if (username.length === 0 || password.length === 0) {
    return errorNoStore(400, webError("A0400", "请输入用户名和密码"));
  }
  if (username.length > 256 || password.length > 1024) {
    return errorNoStore(400, webError("A0400", "输入内容过长"));
  }

  const startedAt = Date.now();
  try {
    const session = await loginWithBackend(username, password);
    const elapsedSeconds = Math.max(0, Math.ceil((Date.now() - startedAt) / 1000));
    const maxAge = Math.floor(session.expiresIn - elapsedSeconds);
    if (maxAge < 1) {
      return errorNoStore(502, webError("W0001", "登录会话有效期异常，请重试"));
    }
    const response = jsonNoStore({ user: session.user });
    setSessionCookie(response, session.accessToken, maxAge);
    return response;
  } catch (error) {
    if (error instanceof AuthBackendError) {
      return errorNoStore(error.status, error.payload);
    }
    if (error instanceof AuthBackendUnavailableError) {
      return errorNoStore(
        503,
        webError(
          "C0001",
          error.timedOut ? "认证服务响应超时，请稍后重试" : "无法连接认证服务，请稍后重试",
        ),
      );
    }
    if (error instanceof AuthContractError) {
      return errorNoStore(502, webError("W0001", "认证服务响应格式错误，请稍后重试"));
    }
    return errorNoStore(500, webError("W0003", "登录处理失败，请稍后重试"));
  }
}
