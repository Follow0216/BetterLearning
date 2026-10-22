import type { NextRequest } from "next/server";

import { AuthContractError } from "@/lib/api/auth-contract";
import { guardMutation } from "@/lib/api/auth-guard";
import { errorNoStore, jsonNoStore, webError } from "@/lib/api/auth-response";
import {
  AuthBackendError,
  AuthBackendUnavailableError,
  registerWithBackend,
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
  const confirmPassword = typeof body.confirmPassword === "string" ? body.confirmPassword : "";
  if (username.length === 0 || password.length === 0 || confirmPassword.length === 0) {
    return errorNoStore(400, webError("A0400", "请完整填写注册信息"));
  }
  if (username.length > 256 || password.length > 1024 || confirmPassword.length > 1024) {
    return errorNoStore(400, webError("A0400", "输入内容过长"));
  }

  try {
    const user = await registerWithBackend(username, password, confirmPassword);
    return jsonNoStore({ user }, 201);
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
    return errorNoStore(500, webError("W0003", "注册处理失败，请稍后重试"));
  }
}
