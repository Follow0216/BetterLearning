import type { NextRequest } from "next/server";

import {
  AdminBackendError,
  AdminContractError,
  AdminUnavailableError,
  loginAdmin,
} from "@/lib/api/admin-auth";
import { setAdminSessionCookie } from "@/lib/api/admin-cookie";
import { guardAdminMutation } from "@/lib/api/admin-guard";
import { errorNoStore, jsonNoStore, webError } from "@/lib/api/admin-response";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export async function POST(request: NextRequest): Promise<Response> {
  const blocked = guardAdminMutation(request, { requireJson: true });
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

  try {
    const result = await loginAdmin(username, password);
    const response = jsonNoStore({ user: result.user });
    setAdminSessionCookie(response, result.token, result.maxAgeSeconds);
    return response;
  } catch (error) {
    if (error instanceof AdminBackendError) {
      return errorNoStore(error.status, error.payload);
    }
    if (error instanceof AdminUnavailableError) {
      return errorNoStore(
        503,
        webError(
          "C0001",
          error.timedOut ? "认证服务响应超时，请稍后重试" : "无法连接认证服务，请稍后重试",
        ),
      );
    }
    if (error instanceof AdminContractError) {
      return errorNoStore(502, webError("W0001", "认证服务响应格式错误，请稍后重试"));
    }
    return errorNoStore(500, webError("W0003", "登录处理失败，请稍后重试"));
  }
}