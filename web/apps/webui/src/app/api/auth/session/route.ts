import type { NextRequest } from "next/server";

import { AuthContractError } from "@/lib/api/auth-contract";
import {
  clearSessionCookie,
  errorNoStore,
  jsonNoStore,
  readSessionToken,
  webError,
} from "@/lib/api/auth-response";
import {
  AuthBackendError,
  AuthBackendUnavailableError,
  getProfileFromBackend,
} from "@/lib/api/auth-server";

export async function GET(request: NextRequest): Promise<Response> {
  const token = readSessionToken(request);
  if (token === null) {
    return errorNoStore(401, webError("A0200", "未登录"));
  }

  try {
    const user = await getProfileFromBackend(token);
    return jsonNoStore({ user });
  } catch (error) {
    if (error instanceof AuthBackendError) {
      if (error.status === 401) {
        const response = errorNoStore(401, error.payload);
        clearSessionCookie(response);
        return response;
      }
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
    return errorNoStore(500, webError("W0003", "加载账号信息失败，请稍后重试"));
  }
}
