import type { NextRequest } from "next/server";

import {
  AdminBackendError,
  AdminContractError,
  AdminUnavailableError,
  getAdminSession,
} from "@/lib/api/admin-auth";
import { clearAdminSessionCookie, readAdminToken } from "@/lib/api/admin-cookie";
import { errorNoStore, jsonNoStore, webError } from "@/lib/api/admin-response";

export async function GET(request: NextRequest): Promise<Response> {
  const token = readAdminToken(request);
  if (token === null) {
    return errorNoStore(401, webError("A0200", "未登录"));
  }

  try {
    const user = await getAdminSession(token);
    return jsonNoStore({ user });
  } catch (error) {
    if (error instanceof AdminBackendError) {
      if (error.status === 401 || error.status === 403) {
        const response = errorNoStore(error.status, error.payload);
        clearAdminSessionCookie(response);
        return response;
      }
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
    return errorNoStore(500, webError("W0003", "加载管理员身份失败，请稍后重试"));
  }
}
