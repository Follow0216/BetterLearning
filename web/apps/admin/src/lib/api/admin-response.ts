import "server-only";

import { NextResponse } from "next/server";

export interface ApiErrorPayload {
  code: string;
  message: string;
  traceId: string;
}

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

export function errorNoStore(status: number, payload: ApiErrorPayload): NextResponse {
  return jsonNoStore(payload, status);
}

export function webError(code: string, message: string): ApiErrorPayload {
  return { code, message, traceId: "" };
}
