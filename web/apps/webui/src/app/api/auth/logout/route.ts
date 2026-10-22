import type { NextRequest } from "next/server";

import { AuthContractError } from "@/lib/api/auth-contract";
import { guardMutation } from "@/lib/api/auth-guard";
import {
  clearSessionCookie,
  emptyNoStore,
  errorNoStore,
  readSessionToken,
  webError,
} from "@/lib/api/auth-response";
import {
  AuthBackendError,
  AuthBackendUnavailableError,
  logoutWithBackend,
} from "@/lib/api/auth-server";

export async function DELETE(request: NextRequest): Promise<Response> {
  const blocked = guardMutation(request, { requireJson: false });
  if (blocked) {
    return errorNoStore(blocked.status, blocked.payload);
  }

  const token = readSessionToken(request);
  if (token === null) {
    const response = emptyNoStore(204);
    clearSessionCookie(response);
    return response;
  }

  try {
    await logoutWithBackend(token);
    const response = emptyNoStore(204);
    clearSessionCookie(response);
    return response;
  } catch (error) {
    if (error instanceof AuthBackendError) {
      if (error.status === 401) {
        const response = emptyNoStore(204);
        clearSessionCookie(response);
        return response;
      }
      return errorNoStore(error.status, error.payload);
    }
    if (error instanceof AuthBackendUnavailableError) {
      return errorNoStore(
        503,
        webError("C0001", error.timedOut ? "无法确认退出结果，请重试" : "无法确认退出结果，请重试"),
      );
    }
    if (error instanceof AuthContractError) {
      return errorNoStore(502, webError("W0001", "无法确认退出结果，请重试"));
    }
    return errorNoStore(500, webError("W0003", "无法确认退出结果，请重试"));
  }
}
