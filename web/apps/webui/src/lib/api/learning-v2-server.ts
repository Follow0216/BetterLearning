import "server-only";

import {
  clearSessionCookie,
  errorNoStore,
  jsonNoStore,
  readSessionToken,
  webError,
} from "./auth-response";
import { guardMutation } from "./auth-guard";
import {
  isUuid,
  LearningContractError,
  parseCitationResolution,
  parseCursorPage,
  parseNote,
  parseSource,
  parseTutorDetail,
  parseTutorRequest,
  type Note,
  type Source,
  type CursorPage,
  type TutorRequest,
  type TutorDetail,
  type CitationResolution,
} from "./learning-v2-contract";
import type { NextRequest } from "next/server";
import { NextResponse } from "next/server";

type Method = "GET" | "POST" | "PUT" | "DELETE";
type Kind = "note" | "notes" | "source" | "sources" | "requests" | "request" | "resolve" | "asset";
interface Match {
  kind: Kind;
  path: string;
  query: URLSearchParams;
}
const QUESTION = /^[1-9][0-9]{0,18}$/;
const BLOCK = /^(0|[1-9][0-9]*)$/;
const BACKEND_TIMEOUT = 15_000;
function matchRequest(request: NextRequest, segments: string[], method: Method): Match | null {
  const [a, b, c, d, e, f] = segments;
  let kind: Kind | null = null;
  if (a === "notes" && segments.length === 1 && (method === "GET" || method === "POST"))
    kind = "notes";
  else if (
    a === "notes" &&
    segments.length === 2 &&
    isUuid(b) &&
    ["GET", "PUT", "DELETE"].includes(method)
  )
    kind = "note";
  else if (a === "sources" && segments.length === 1 && method === "GET") kind = "sources";
  else if (a === "sources" && segments.length === 2 && isUuid(b) && method === "GET")
    kind = "source";
  else if (
    a === "questions" &&
    QUESTION.test(b) &&
    c === "tutor-requests" &&
    segments.length === 3 &&
    method === "GET"
  )
    kind = "requests";
  else if (
    a === "questions" &&
    QUESTION.test(b) &&
    c === "tutor-requests" &&
    segments.length === 4 &&
    isUuid(d) &&
    method === "GET"
  )
    kind = "request";
  else if (
    a === "citations" &&
    isUuid(b) &&
    c === "resolve" &&
    segments.length === 3 &&
    method === "GET"
  )
    kind = "resolve";
  else if (
    a === "citations" &&
    isUuid(b) &&
    c === "assets" &&
    BLOCK.test(d) &&
    segments.length === 4 &&
    method === "GET" &&
    Number.isSafeInteger(Number(d))
  )
    kind = "asset";
  if (!kind || e !== undefined || f !== undefined) return null;
  const input = request.nextUrl.searchParams;
  const allowed =
    kind === "sources"
      ? ["type", "cursor", "limit"]
      : (kind === "notes" && method === "GET") || kind === "requests"
        ? ["cursor", "limit"]
        : [];
  if (
    Array.from(input.keys()).some((key) => !allowed.includes(key)) ||
    Array.from(input.keys()).some((key) => input.getAll(key).length !== 1)
  )
    return null;
  if (
    input.has("limit") &&
    (!/^[1-9][0-9]*$/.test(input.get("limit") ?? "") || Number(input.get("limit")) > 100)
  )
    return null;
  if (
    input.has("type") &&
    !["QUESTION", "SAVED_ANSWER", "TUTOR_SNAPSHOT", "TUTOR_REPLY", "NOTE"].includes(
      input.get("type") ?? "",
    )
  )
    return null;
  return { kind, path: `/api/v2/learning/${segments.join("/")}`, query: input };
}
function baseUrl(): string {
  const configured = process.env.AUTH_API_BASE_URL?.trim();
  if (!configured && process.env.NODE_ENV === "production") throw new Error("missing base URL");
  const url = new URL(configured || "http://127.0.0.1:18081");
  if (
    !["http:", "https:"].includes(url.protocol) ||
    url.username ||
    url.password ||
    url.search ||
    url.hash ||
    (url.pathname !== "/" && url.pathname !== "")
  )
    throw new Error("invalid base URL");
  return url.origin;
}
function safeError(status: number, code: string, traceId = ""): NextResponse {
  const message =
    status === 401
      ? "登录状态已失效，请重新登录"
      : status === 403
        ? "无权访问该资料"
        : status === 404
          ? "资料不存在或不可访问"
          : status === 409
            ? "资料状态已变化，请刷新后重试"
            : status === 412
              ? "笔记版本已变化，请刷新后重试"
              : status >= 500
                ? "学习服务暂时不可用"
                : "请求未完成，请检查后重试";
  const response = errorNoStore(status, { code, message, traceId });
  if (status === 401) clearSessionCookie(response);
  return response;
}
function parsePayload(kind: Kind, value: unknown) {
  switch (kind) {
    case "notes":
      return parseCursorPage(value, parseNote);
    case "note":
      return parseNote(value);
    case "sources":
      return parseCursorPage(value, parseSource);
    case "source":
      return parseSource(value);
    case "requests":
      return parseCursorPage(value, parseTutorRequest);
    case "request":
      return parseTutorDetail(value);
    case "resolve":
      return parseCitationResolution(value);
    case "asset":
      throw new LearningContractError();
  }
}
export async function handleLearningRequest(
  request: NextRequest,
  segments: string[],
  method: Method,
): Promise<Response> {
  const matched = matchRequest(request, segments, method);
  if (!matched) return errorNoStore(404, webError("A0400", "接口不存在"));
  if (method !== "GET") {
    const blocked = guardMutation(request, { requireJson: method !== "DELETE" });
    if (blocked) return errorNoStore(blocked.status, blocked.payload);
  }
  const token = readSessionToken(request);
  if (!token) return safeError(401, "A0200");
  const headers = new Headers({
    Authorization: `Bearer ${token}`,
    Accept: matched.kind === "asset" ? "image/png" : "application/json",
  });
  let body: string | undefined;
  if (method === "POST" || method === "PUT") {
    let value: unknown;
    try {
      value = await request.json();
    } catch {
      return safeError(400, "A0427");
    }
    if (typeof value !== "object" || value === null || Array.isArray(value))
      return safeError(400, "A0400");
    const note = value as Record<string, unknown>;
    if (
      Object.keys(note).some((key) => !["title", "bodyMarkdown"].includes(key)) ||
      typeof note.title !== "string" ||
      !note.title.trim() ||
      typeof note.bodyMarkdown !== "string" ||
      !note.bodyMarkdown.trim() ||
      new TextEncoder().encode(note.bodyMarkdown).length > 65536
    )
      return safeError(400, "A0400");
    body = JSON.stringify({ title: note.title, bodyMarkdown: note.bodyMarkdown });
    headers.set("Content-Type", "application/json");
  }
  if (method === "POST") {
    const key = request.headers.get("idempotency-key");
    if (!key || !isUuid(key)) return safeError(400, "A0400");
    headers.set("Idempotency-Key", key);
  }
  if (method === "PUT" || method === "DELETE") {
    const ifMatch = request.headers.get("if-match");
    if (!ifMatch || ifMatch.length > 256 || /[\r\n]/.test(ifMatch)) return safeError(400, "A0400");
    headers.set("If-Match", ifMatch);
  }
  let backend: Response;
  try {
    backend = await fetch(
      `${baseUrl()}${matched.path}${matched.query.size ? `?${matched.query.toString()}` : ""}`,
      {
        method,
        headers,
        body,
        cache: "no-store",
        redirect: "manual",
        signal: AbortSignal.timeout(BACKEND_TIMEOUT),
      },
    );
  } catch {
    return safeError(502, "W0001");
  }
  if (!backend.ok) {
    let payload: unknown;
    try {
      payload = await backend.json();
    } catch {
      payload = null;
    }
    const error =
      typeof payload === "object" && payload !== null && !Array.isArray(payload)
        ? (payload as Record<string, unknown>)
        : {};
    const status = [400, 401, 403, 404, 409, 412, 413, 415, 422, 429].includes(backend.status)
      ? backend.status
      : 502;
    const code =
      typeof error.code === "string" && /^[ABC][0-9]{4}$/.test(error.code) ? error.code : "W0001";
    const traceId =
      typeof error.traceId === "string" && /^[A-Za-z0-9_-]{0,128}$/.test(error.traceId)
        ? error.traceId
        : "";
    return safeError(status, code, traceId);
  }
  if (method === "DELETE")
    return new NextResponse(null, { status: 204, headers: { "Cache-Control": "no-store" } });
  if (matched.kind === "asset") {
    if (backend.headers.get("content-type")?.split(";")[0]?.toLowerCase() !== "image/png")
      return safeError(502, "W0001");
    const bytes = await backend.arrayBuffer();
    return new NextResponse(bytes, {
      status: 200,
      headers: {
        "Content-Type": "image/png",
        "Cache-Control": "no-store",
        "X-Content-Type-Options": "nosniff",
      },
    });
  }
  let data: unknown;
  try {
    data =
      matched.kind === "notes" && method === "POST"
        ? parseNote(await backend.json())
        : parsePayload(matched.kind, await backend.json());
    // 后端响应必须属于请求的实体/题目；错误身份不可作为当前页面数据展示。
    if (
      (matched.kind === "note" && (data as Note).noteId !== segments[1]) ||
      (matched.kind === "source" && (data as Source).sourceId !== segments[1]) ||
      (matched.kind === "requests" &&
        (data as CursorPage<TutorRequest>).items.some((item) => item.questionId !== segments[1])) ||
      (matched.kind === "request" &&
        ((data as TutorDetail).request.questionId !== segments[1] ||
          (data as TutorDetail).request.requestId !== segments[3])) ||
      (matched.kind === "resolve" &&
        (data as CitationResolution).citation.citationId !== segments[1])
    )
      throw new LearningContractError();
  } catch {
    return safeError(502, "W0001");
  }
  const response = jsonNoStore(data, backend.status === 201 ? 201 : 200);
  const etag = backend.headers.get("etag");
  if (etag && matched.kind === "note") response.headers.set("ETag", etag);
  return response;
}
