import "server-only";

import { cookies } from "next/headers";

import { SESSION_COOKIE_NAME } from "./auth-response";
import {
  LibraryContractError,
  isValidCollectionId,
  isValidQuestionId,
  parseCollectionList,
  parseQuestionDetail,
  parseQuestionPage,
  tryParseErrorPayload,
  type ApiErrorPayload,
  type CollectionList,
  type QuestionDetail,
  type QuestionPage,
} from "./library-contract";

const DEFAULT_BASE_URL = "http://127.0.0.1:18081";
const REQUEST_TIMEOUT_MS = 15_000;

export type LibraryFailureKind =
  "unauthenticated" | "forbidden" | "not-found" | "unavailable" | "contract" | "error";

export interface LibraryFailure {
  kind: LibraryFailureKind;
  status: number;
  code: string;
  message: string;
  traceId?: string;
}

export type LibraryResult<T> = { ok: true; data: T } | { ok: false; failure: LibraryFailure };

export interface QuestionQuery {
  collectionId?: string;
  page: number;
  pageSize: number;
}

function resolveBaseUrl(): string {
  const configured = process.env.AUTH_API_BASE_URL?.trim();
  if (!configured) {
    if (process.env.NODE_ENV === "production") {
      throw new Error("缺少服务端环境变量 AUTH_API_BASE_URL");
    }
    return DEFAULT_BASE_URL;
  }
  let url: URL;
  try {
    url = new URL(configured);
  } catch {
    throw new Error("AUTH_API_BASE_URL 不是合法 URL");
  }
  if (url.protocol !== "http:" && url.protocol !== "https:") {
    throw new Error("AUTH_API_BASE_URL 只支持 http/https");
  }
  const path = url.pathname === "/" ? "" : url.pathname.replace(/\/+$/, "");
  return `${url.origin}${path}`;
}

async function readSessionToken(): Promise<string | null> {
  const store = await cookies();
  const token = store.get(SESSION_COOKIE_NAME)?.value;
  return token !== undefined && token.length > 0 ? token : null;
}

function failure(
  kind: LibraryFailureKind,
  status: number,
  code: string,
  message: string,
): LibraryFailure {
  return { kind, status, code, message };
}

function contractFailure(): LibraryFailure {
  return failure("contract", 502, "W0001", "题库服务响应格式错误，请稍后重试");
}

async function readJson(response: Response): Promise<unknown> {
  try {
    return (await response.json()) as unknown;
  } catch {
    throw new LibraryContractError("题库服务响应不是合法 JSON");
  }
}

async function toFailure(response: Response): Promise<LibraryFailure> {
  let payload: ApiErrorPayload | null = null;
  try {
    payload = tryParseErrorPayload(await readJson(response));
  } catch {
    payload = null;
  }
  const code = payload?.code ?? "W0001";
  const traceId = payload?.traceId;

  if (response.status === 401) {
    return {
      ...failure("unauthenticated", 401, code, "登录状态已失效，请重新登录"),
      ...(traceId === undefined ? {} : { traceId }),
    };
  }
  if (response.status === 403) {
    return {
      ...failure("forbidden", 403, code, "当前账号没有访问该内容的权限"),
      ...(traceId === undefined ? {} : { traceId }),
    };
  }
  if (response.status === 404) {
    return {
      ...failure("not-found", 404, code, payload?.message ?? "未找到该题目或题集"),
      ...(traceId === undefined ? {} : { traceId }),
    };
  }
  if (response.status === 503) {
    return {
      ...failure("unavailable", 503, code, "题库服务暂不可用，请稍后重试"),
      ...(traceId === undefined ? {} : { traceId }),
    };
  }
  return {
    ...failure("error", response.status, code, payload?.message ?? "加载题库失败，请稍后重试"),
    ...(traceId === undefined ? {} : { traceId }),
  };
}

async function requestJson(
  pathname: string,
  token: string,
): Promise<{ ok: true; value: unknown } | { ok: false; failure: LibraryFailure }> {
  let url: string;
  try {
    url = `${resolveBaseUrl()}${pathname}`;
  } catch {
    return {
      ok: false,
      failure: failure("error", 500, "W0002", "题库服务配置缺失或不合法，请联系管理员"),
    };
  }

  let response: Response;
  try {
    response = await fetch(url, {
      method: "GET",
      headers: { Accept: "application/json", Authorization: `Bearer ${token}` },
      cache: "no-store",
      redirect: "manual",
      signal: AbortSignal.timeout(REQUEST_TIMEOUT_MS),
    });
  } catch (error) {
    const timedOut = error instanceof Error && error.name === "TimeoutError";
    return {
      ok: false,
      failure: failure(
        "unavailable",
        503,
        "C0001",
        timedOut ? "题库服务响应超时，请重试" : "无法连接题库服务，请稍后重试",
      ),
    };
  }

  if (response.status !== 200) {
    return { ok: false, failure: await toFailure(response) };
  }
  try {
    return { ok: true, value: await readJson(response) };
  } catch {
    return { ok: false, failure: contractFailure() };
  }
}

export async function getQuestionPage(query: QuestionQuery): Promise<LibraryResult<QuestionPage>> {
  if (
    query.collectionId !== undefined &&
    query.collectionId !== "" &&
    !isValidCollectionId(query.collectionId)
  ) {
    return { ok: false, failure: failure("error", 400, "A0400", "题库浏览参数不合法") };
  }
  const token = await readSessionToken();
  if (token === null) {
    return {
      ok: false,
      failure: failure("unauthenticated", 401, "A0200", "登录状态已失效，请重新登录"),
    };
  }

  const params = new URLSearchParams({
    page: String(query.page),
    pageSize: String(query.pageSize),
  });
  if (query.collectionId !== undefined) {
    params.set("collectionId", query.collectionId);
  }

  const result = await requestJson(`/api/v1/learning/questions?${params.toString()}`, token);
  if (!result.ok) {
    return result;
  }
  try {
    return { ok: true, data: parseQuestionPage(result.value) };
  } catch {
    return { ok: false, failure: contractFailure() };
  }
}

export async function getQuestionDetail(
  questionId: string,
): Promise<LibraryResult<QuestionDetail>> {
  if (!isValidQuestionId(questionId)) {
    return { ok: false, failure: failure("error", 400, "A0400", "题目编号不合法") };
  }
  const token = await readSessionToken();
  if (token === null) {
    return {
      ok: false,
      failure: failure("unauthenticated", 401, "A0200", "登录状态已失效，请重新登录"),
    };
  }

  const result = await requestJson(
    `/api/v1/learning/questions/${encodeURIComponent(questionId)}`,
    token,
  );
  if (!result.ok) {
    return result;
  }
  try {
    return { ok: true, data: parseQuestionDetail(result.value) };
  } catch {
    return { ok: false, failure: contractFailure() };
  }
}

export async function getCollections(): Promise<LibraryResult<CollectionList>> {
  const token = await readSessionToken();
  if (token === null) {
    return {
      ok: false,
      failure: failure("unauthenticated", 401, "A0200", "登录状态已失效，请重新登录"),
    };
  }

  const result = await requestJson("/api/v1/learning/collections", token);
  if (!result.ok) {
    return result;
  }
  try {
    return { ok: true, data: parseCollectionList(result.value) };
  } catch {
    return { ok: false, failure: contractFailure() };
  }
}
