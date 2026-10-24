import "server-only";

import type { NextResponse } from "next/server";

import { clearSessionCookie, errorNoStore, webError } from "./auth-response";
import type { LibraryFailure } from "./library-server";

export type JsonObjectResult =
  { ok: true; value: Record<string, unknown> } | { ok: false; response: NextResponse };

export async function readJsonObject(request: Request): Promise<JsonObjectResult> {
  let body: unknown;
  try {
    body = (await request.json()) as unknown;
  } catch {
    return { ok: false, response: errorNoStore(400, webError("A0427", "请求体格式错误")) };
  }
  if (typeof body !== "object" || body === null || Array.isArray(body)) {
    return { ok: false, response: errorNoStore(400, webError("A0400", "请求参数不合法")) };
  }
  return { ok: true, value: body as Record<string, unknown> };
}

export function hasUnknownFields(
  value: Record<string, unknown>,
  allowed: readonly string[],
): boolean {
  return Object.keys(value).some((key) => !allowed.includes(key));
}

export function libraryFailureResponse(failure: LibraryFailure): NextResponse {
  const payload =
    failure.traceId === undefined
      ? webError(failure.code, failure.message)
      : { code: failure.code, message: failure.message, traceId: failure.traceId };
  const response = errorNoStore(failure.status, payload);
  if (failure.kind === "unauthenticated") {
    clearSessionCookie(response);
  }
  return response;
}
