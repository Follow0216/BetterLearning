import type { NextRequest } from "next/server";

import {
  AdminBackendError,
  AdminContractError,
  AdminUnavailableError,
  logoutAdmin,
} from "@/lib/api/admin-auth";
import { clearAdminSessionCookie, readAdminToken } from "@/lib/api/admin-cookie";
import { guardAdminMutation } from "@/lib/api/admin-guard";
import { emptyNoStore, errorNoStore, webError } from "@/lib/api/admin-response";

export async function DELETE(request: NextRequest): Promise<Response> {
  const blocked = guardAdminMutation(request, { requireJson: false });
  if (blocked) {
    return errorNoStore(blocked.status, blocked.payload);
  }

  const token = readAdminToken(request);
  if (token === null) {
    const response = emptyNoStore(204);
    clearAdminSessionCookie(response);
    return response;
  }

  try {
    await logoutAdmin(token);
    const response = emptyNoStore(204);
    clearAdminSessionCookie(response);
    return response;
  } catch (error) {
    if (error instanceof AdminBackendError) {
      if (error.status === 401) {
        const response = emptyNoStore(204);
        clearAdminSessionCookie(response);
        return response;
      }
      return errorNoStore(error.status, error.payload);
    }
    if (error instanceof AdminUnavailableError) {
      return errorNoStore(503, webError("C0001", "无法确认退出结果，请重试"));
    }
    if (error instanceof AdminContractError) {
      return errorNoStore(502, webError("W0001", "无法确认退出结果，请重试"));
    }
    return errorNoStore(500, webError("W0003", "无法确认退出结果，请重试"));
  }
}
