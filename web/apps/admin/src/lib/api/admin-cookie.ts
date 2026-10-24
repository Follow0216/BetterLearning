import "server-only";

import type { NextRequest, NextResponse } from "next/server";

/**
 * 管理员会话 Cookie 独立于用户端 bl_session。
 * localhost 上不同端口共享 Cookie，复用同名 Cookie 会互相覆盖。
 */
export const ADMIN_SESSION_COOKIE_NAME = "bl_admin_session";

export function readAdminToken(request: NextRequest): string | null {
  const token = request.cookies.get(ADMIN_SESSION_COOKIE_NAME)?.value;
  return token !== undefined && token.length > 0 ? token : null;
}

export function setAdminSessionCookie(
  response: NextResponse,
  accessToken: string,
  maxAgeSeconds: number,
): void {
  response.cookies.set(ADMIN_SESSION_COOKIE_NAME, accessToken, {
    httpOnly: true,
    sameSite: "lax",
    path: "/",
    secure: process.env.NODE_ENV === "production",
    maxAge: maxAgeSeconds,
  });
}

export function clearAdminSessionCookie(response: NextResponse): void {
  response.cookies.set(ADMIN_SESSION_COOKIE_NAME, "", {
    httpOnly: true,
    sameSite: "lax",
    path: "/",
    secure: process.env.NODE_ENV === "production",
    maxAge: 0,
  });
}
