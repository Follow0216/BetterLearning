import "server-only";

import { NextResponse, type NextRequest } from "next/server";

import type { AuthErrorPayload } from "./auth-contract";

/** 会话 Cookie 名称；令牌只存在于此 Cookie，不进入 JSON、DOM 或浏览器存储。 */
export const SESSION_COOKIE_NAME = "bl_session";

export function jsonNoStore(body: unknown, status = 200): NextResponse {
  const response = NextResponse.json(body, { status });
  response.headers.set("Cache-Control", "no-store");
  return response;
}

export function emptyNoStore(status: number): NextResponse {
  const response = new NextResponse(null, { status });
  response.headers.set("Cache-Control", "no-store");
  return response;
}

export function errorNoStore(status: number, payload: AuthErrorPayload): NextResponse {
  return jsonNoStore(payload, status);
}

export function webError(code: string, message: string): AuthErrorPayload {
  return { code, message, traceId: "" };
}

/** 写入 HttpOnly 会话 Cookie；maxAge 已按后端 expiresIn 扣除请求耗时。 */
export function setSessionCookie(
  response: NextResponse,
  accessToken: string,
  maxAgeSeconds: number,
): void {
  response.cookies.set(SESSION_COOKIE_NAME, accessToken, {
    httpOnly: true,
    sameSite: "lax",
    path: "/",
    secure: process.env.NODE_ENV === "production",
    maxAge: maxAgeSeconds,
  });
}

export function clearSessionCookie(response: NextResponse): void {
  response.cookies.set(SESSION_COOKIE_NAME, "", {
    httpOnly: true,
    sameSite: "lax",
    path: "/",
    secure: process.env.NODE_ENV === "production",
    maxAge: 0,
  });
}

export function readSessionToken(request: NextRequest): string | null {
  const token = request.cookies.get(SESSION_COOKIE_NAME)?.value;
  return token && token.length > 0 ? token : null;
}
