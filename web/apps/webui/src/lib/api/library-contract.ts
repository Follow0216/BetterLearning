/**
 * M1 题库接口契约：严格对应 C1 维护的 HTTP 契约。
 *
 * 仅包含类型与纯解析函数，可同时被 Server Component 与 Route Handler 引用。
 * 不在客户端保存或传递访问令牌。
 */

export type QuestionSubject = "HIGHER_MATHEMATICS" | "OTHER";

export const SUBJECT_LABELS: Record<QuestionSubject, string> = {
  HIGHER_MATHEMATICS: "高等数学",
  OTHER: "其他",
};

export const INBOX_COLLECTION_ID = "inbox";

export interface QuestionSummary {
  questionId: string;
  collectionId: string;
  subject: QuestionSubject;
  summary: string;
  createdAt: string;
}

export interface QuestionDetail extends QuestionSummary {
  bodyMarkdown: string;
}

export interface QuestionPage {
  items: QuestionSummary[];
  page: number;
  pageSize: number;
  total: number;
}

export interface CollectionSummary {
  collectionId: string;
  name: string;
  system: boolean;
  questionCount: number;
}

export interface CollectionList {
  items: CollectionSummary[];
}

export interface ApiErrorPayload {
  code: string;
  message: string;
  traceId: string;
}

export class LibraryContractError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "LibraryContractError";
  }
}

const POSITIVE_DECIMAL = /^[1-9]\d*$/;
const MAX_LONG_DECIMAL = "9223372036854775807";

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function readString(source: Record<string, unknown>, key: string): string {
  const value = source[key];
  if (typeof value !== "string") {
    throw new LibraryContractError(`响应字段 ${key} 类型错误`);
  }
  return value;
}

function readNonEmptyString(source: Record<string, unknown>, key: string): string {
  const value = readString(source, key);
  if (value.length === 0) {
    throw new LibraryContractError(`响应字段 ${key} 为空`);
  }
  return value;
}

function readTimestamp(source: Record<string, unknown>, key: string): string {
  const value = readNonEmptyString(source, key);
  if (Number.isNaN(Date.parse(value))) {
    throw new LibraryContractError(`响应字段 ${key} 不是合法时间`);
  }
  return value;
}

function readInteger(
  source: Record<string, unknown>,
  key: string,
  minimum: number,
  maximum: number,
): number {
  const value = source[key];
  if (
    typeof value !== "number" ||
    !Number.isSafeInteger(value) ||
    value < minimum ||
    value > maximum
  ) {
    throw new LibraryContractError(`响应字段 ${key} 数值超出范围`);
  }
  return value;
}

function readBoolean(source: Record<string, unknown>, key: string): boolean {
  const value = source[key];
  if (typeof value !== "boolean") {
    throw new LibraryContractError(`响应字段 ${key} 类型错误`);
  }
  return value;
}

export function isValidQuestionId(value: string): boolean {
  if (!POSITIVE_DECIMAL.test(value)) {
    return false;
  }
  if (value.length !== MAX_LONG_DECIMAL.length) {
    return value.length < MAX_LONG_DECIMAL.length;
  }
  return value <= MAX_LONG_DECIMAL;
}

export function isValidCollectionId(value: string): boolean {
  return value === INBOX_COLLECTION_ID || isValidQuestionId(value);
}

function readSubject(source: Record<string, unknown>): QuestionSubject {
  const value = source.subject;
  if (value !== "HIGHER_MATHEMATICS" && value !== "OTHER") {
    throw new LibraryContractError("响应字段 subject 不在枚举范围内");
  }
  return value;
}

function readCollectionId(source: Record<string, unknown>): string {
  const value = readNonEmptyString(source, "collectionId");
  if (!isValidCollectionId(value)) {
    throw new LibraryContractError("响应字段 collectionId 格式错误");
  }
  return value;
}

export function parseQuestionSummary(value: unknown): QuestionSummary {
  if (!isRecord(value)) {
    throw new LibraryContractError("题目列表项格式错误");
  }
  const questionId = readNonEmptyString(value, "questionId");
  if (!isValidQuestionId(questionId)) {
    throw new LibraryContractError("响应字段 questionId 格式错误");
  }
  return {
    questionId,
    collectionId: readCollectionId(value),
    subject: readSubject(value),
    summary: readString(value, "summary"),
    createdAt: readTimestamp(value, "createdAt"),
  };
}

export function parseQuestionDetail(value: unknown): QuestionDetail {
  const summary = parseQuestionSummary(value);
  if (!isRecord(value)) {
    throw new LibraryContractError("题目详情格式错误");
  }
  return {
    ...summary,
    bodyMarkdown: readString(value, "bodyMarkdown"),
  };
}

export function parseQuestionPage(value: unknown): QuestionPage {
  if (!isRecord(value)) {
    throw new LibraryContractError("题目分页响应格式错误");
  }
  const items = value.items;
  if (!Array.isArray(items)) {
    throw new LibraryContractError("响应字段 items 类型错误");
  }
  return {
    items: items.map((item) => parseQuestionSummary(item)),
    page: readInteger(value, "page", 1, 1_000_000),
    pageSize: readInteger(value, "pageSize", 1, 100),
    total: readInteger(value, "total", 0, Number.MAX_SAFE_INTEGER),
  };
}

export function parseCollectionList(value: unknown): CollectionList {
  if (!isRecord(value)) {
    throw new LibraryContractError("题集列表响应格式错误");
  }
  const items = value.items;
  if (!Array.isArray(items)) {
    throw new LibraryContractError("响应字段 items 类型错误");
  }
  return {
    items: items.map((item) => {
      if (!isRecord(item)) {
        throw new LibraryContractError("题集列表项格式错误");
      }
      const collectionId = readNonEmptyString(item, "collectionId");
      if (!isValidCollectionId(collectionId)) {
        throw new LibraryContractError("响应字段 collectionId 格式错误");
      }
      return {
        collectionId,
        name: readNonEmptyString(item, "name"),
        system: readBoolean(item, "system"),
        questionCount: readInteger(item, "questionCount", 0, Number.MAX_SAFE_INTEGER),
      };
    }),
  };
}

export function parseErrorPayload(value: unknown): ApiErrorPayload {
  if (!isRecord(value)) {
    throw new LibraryContractError("错误响应格式错误");
  }
  return {
    code: readNonEmptyString(value, "code"),
    message: readNonEmptyString(value, "message"),
    traceId: readString(value, "traceId"),
  };
}

export function tryParseErrorPayload(value: unknown): ApiErrorPayload | null {
  try {
    return parseErrorPayload(value);
  } catch {
    return null;
  }
}
