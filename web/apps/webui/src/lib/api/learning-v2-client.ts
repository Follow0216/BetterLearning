import {
  LearningContractError,
  parseCitationResolution,
  parseCursorPage,
  parseNote,
  parseSource,
  parseTutorDetail,
  parseTutorRequest,
} from "./learning-v2-contract";

export class LearningApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly traceId: string,
  ) {
    super(message);
    this.name = "LearningApiError";
  }
}
function localPath(path: string): string {
  const relative = path.startsWith("/api/learning/") ? path.slice("/api/learning/".length) : path;
  if (
    !/^(notes|sources|questions|citations)(?:[/?]|$)/.test(relative) ||
    relative.includes("\\") ||
    relative.includes("#") ||
    relative.split(/[/?]/).some((part) => part === "." || part === "..") ||
    /%2f|%5c|%2e/i.test(relative)
  )
    throw new LearningApiError(400, "A0400", "接口路径无效", "");
  const url = new URL(`/api/learning/${relative}`, "http://localhost");
  if (url.pathname.split("/").includes(".."))
    throw new LearningApiError(400, "A0400", "接口路径无效", "");
  return `${url.pathname}${url.search}`;
}
function parseByPath(path: string, method: string, value: unknown): unknown {
  const segments = new URL(path, "http://localhost").pathname.split("/").filter(Boolean).slice(2);
  if (segments[0] === "notes")
    return segments.length === 1 && method === "GET"
      ? parseCursorPage(value, parseNote)
      : parseNote(value);
  if (segments[0] === "sources")
    return segments.length === 1 ? parseCursorPage(value, parseSource) : parseSource(value);
  if (segments[0] === "questions")
    return segments.length === 3
      ? parseCursorPage(value, parseTutorRequest)
      : parseTutorDetail(value);
  if (segments[0] === "citations" && segments[2] === "resolve")
    return parseCitationResolution(value);
  throw new LearningContractError();
}
export async function learningRequest<T>(
  path: string,
  options: RequestInit = {},
): Promise<{ data: T; etag: string | null }> {
  const local = localPath(path);
  const method = (options.method ?? "GET").toUpperCase();
  const headers = new Headers(options.headers);
  headers.set("Accept", "application/json");
  if (options.body !== undefined && options.body !== null && !headers.has("Content-Type"))
    headers.set("Content-Type", "application/json");
  let response: Response;
  try {
    response = await fetch(local, {
      ...options,
      method,
      headers,
      cache: "no-store",
      credentials: "same-origin",
    });
  } catch {
    throw new LearningApiError(0, "W0001", "网络连接失败，请稍后重试", "");
  }
  if (response.status === 401 && typeof window !== "undefined") {
    window.dispatchEvent(new Event("bl:session-ended"));
    const channel = new BroadcastChannel("bl-session");
    channel.postMessage("ended");
    channel.close();
    window.location.replace("/");
  }
  if (!response.ok) {
    let payload: unknown;
    try {
      payload = await response.json();
    } catch {
      payload = null;
    }
    const record =
      typeof payload === "object" && payload !== null && !Array.isArray(payload)
        ? (payload as Record<string, unknown>)
        : {};
    throw new LearningApiError(
      response.status,
      typeof record.code === "string" ? record.code : "W0001",
      typeof record.message === "string" ? record.message : "请求失败，请稍后重试",
      typeof record.traceId === "string" ? record.traceId : "",
    );
  }
  if (response.status === 204) return { data: undefined as T, etag: null };
  let value: unknown;
  try {
    value = await response.json();
  } catch {
    throw new LearningApiError(502, "W0001", "学习服务响应格式错误", "");
  }
  try {
    return { data: parseByPath(local, method, value) as T, etag: response.headers.get("etag") };
  } catch {
    throw new LearningApiError(502, "W0001", "学习服务响应格式错误", "");
  }
}
