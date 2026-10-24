/**
 * M3 辅导轮次只读契约：仅解析 Web 展示所需的轮次与冻结作答字段。
 *
 * 可同时被 Server Component、Route Handler 与 Client Component 引用；
 * 不解析编辑用 document/questionSnapshot，也不使用后端返回的图片 URL。
 */

import { isValidAnswerFileRef, parseManifest, type AnswerManifest } from "./answer-contract";
import { LibraryContractError } from "./library-contract";

export type TutorTurnStatus = "WAITING_ANSWER" | "GENERATING" | "SUCCEEDED" | "FAILED";

export interface TutorTurn {
  turnId: string;
  requestId: string;
  questionId: string;
  prompt: string;
  status: TutorTurnStatus;
  fileRef: string | null;
  answerMarkdown: string | null;
  failureCode: string | null;
  failureMessage: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface TutorTurnList {
  items: TutorTurn[];
}

export interface TutorTurnSummary {
  turnId: string;
  prompt: string;
  status: TutorTurnStatus;
  fileRef: string | null;
  failureCode: string | null;
  failureMessage: string | null;
  createdAt: string;
  updatedAt: string;
}

export const MAX_TUTOR_TURNS = 50;

const UUID_PATTERN =
  /^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function readRequiredString(source: Record<string, unknown>, key: string): string {
  const value = source[key];
  if (typeof value !== "string" || value.length === 0) {
    throw new LibraryContractError(`辅导轮次字段 ${key} 格式错误`);
  }
  return value;
}

function readNullableString(source: Record<string, unknown>, key: string): string | null {
  const value = source[key];
  if (value === null || value === undefined) {
    return null;
  }
  if (typeof value !== "string") {
    throw new LibraryContractError(`辅导轮次字段 ${key} 格式错误`);
  }
  return value;
}

function readTimestamp(source: Record<string, unknown>, key: string): string {
  const value = readRequiredString(source, key);
  if (Number.isNaN(Date.parse(value))) {
    throw new LibraryContractError(`辅导轮次字段 ${key} 不是合法时间`);
  }
  return value;
}

function readStatus(value: unknown): TutorTurnStatus {
  if (
    value === "WAITING_ANSWER" ||
    value === "GENERATING" ||
    value === "SUCCEEDED" ||
    value === "FAILED"
  ) {
    return value;
  }
  throw new LibraryContractError("辅导轮次状态不在枚举范围内");
}

function readFileRef(source: Record<string, unknown>): string | null {
  const value = source.fileRef;
  if (value === null || value === undefined) {
    return null;
  }
  if (typeof value !== "string" || !isValidAnswerFileRef(value)) {
    throw new LibraryContractError("辅导轮次 fileRef 格式错误");
  }
  return value;
}

export function isValidTutorTurnId(value: string): boolean {
  return UUID_PATTERN.test(value);
}

function parseTurn(value: unknown, expectedQuestionId: string): TutorTurn {
  if (!isRecord(value)) {
    throw new LibraryContractError("辅导轮次响应格式错误");
  }

  const turnId = readRequiredString(value, "turnId");
  if (!isValidTutorTurnId(turnId)) {
    throw new LibraryContractError("辅导轮次 turnId 格式错误");
  }
  const requestId = readRequiredString(value, "requestId");
  if (!isValidTutorTurnId(requestId)) {
    throw new LibraryContractError("辅导轮次 requestId 格式错误");
  }
  const questionId = readRequiredString(value, "questionId");
  if (questionId !== expectedQuestionId) {
    throw new LibraryContractError("辅导轮次不属于该题目");
  }

  const prompt = readRequiredString(value, "prompt");
  const status = readStatus(value.status);
  const fileRef = readFileRef(value);
  const answerMarkdown = readNullableString(value, "answerMarkdown");
  const failureCode = readNullableString(value, "failureCode");
  const failureMessage = readNullableString(value, "failureMessage");
  const createdAt = readTimestamp(value, "createdAt");
  const updatedAt = readTimestamp(value, "updatedAt");

  if (answerMarkdown !== null && (failureCode !== null || failureMessage !== null)) {
    throw new LibraryContractError("辅导轮次回答与失败信息不能同时存在");
  }
  if (status === "SUCCEEDED") {
    if (fileRef === null || answerMarkdown === null) {
      throw new LibraryContractError("已完成轮次缺少冻结文件或回答内容");
    }
  } else if (status === "FAILED") {
    if (
      answerMarkdown !== null ||
      failureCode === null ||
      failureCode.length === 0 ||
      failureMessage === null ||
      failureMessage.length === 0
    ) {
      throw new LibraryContractError("失败轮次的失败信息不完整");
    }
  } else if (answerMarkdown !== null || failureCode !== null || failureMessage !== null) {
    throw new LibraryContractError("未完成轮次不应包含回答或失败信息");
  }

  return {
    turnId,
    requestId,
    questionId,
    prompt,
    status,
    fileRef,
    answerMarkdown,
    failureCode,
    failureMessage,
    createdAt,
    updatedAt,
  };
}

export function parseTutorTurnList(value: unknown, expectedQuestionId: string): TutorTurnList {
  if (!isRecord(value)) {
    throw new LibraryContractError("辅导轮次列表格式错误");
  }
  const rawItems = value.items;
  if (!Array.isArray(rawItems) || rawItems.length > MAX_TUTOR_TURNS) {
    throw new LibraryContractError("辅导轮次数量不合法");
  }

  const seen = new Set<string>();
  const items = rawItems.map((item) => {
    const turn = parseTurn(item, expectedQuestionId);
    if (seen.has(turn.turnId)) {
      throw new LibraryContractError("辅导轮次 turnId 重复");
    }
    seen.add(turn.turnId);
    return turn;
  });

  return { items };
}

export function parseTutorReadManifest(value: unknown, expectedTurnId: string): AnswerManifest {
  if (!isRecord(value)) {
    throw new LibraryContractError("辅导作答 manifest 格式错误");
  }
  const saveRequestId = value.saveRequestId;
  if (typeof saveRequestId !== "string" || saveRequestId.length === 0) {
    throw new LibraryContractError("辅导作答 saveRequestId 格式错误");
  }
  if (saveRequestId !== expectedTurnId) {
    throw new LibraryContractError("辅导作答不属于该轮次");
  }
  if (value.baseFileRef !== null) {
    throw new LibraryContractError("辅导作答不应包含基础作答文件");
  }
  return parseManifest(value);
}

export function toTutorTurnSummary(turn: TutorTurn): TutorTurnSummary {
  return {
    turnId: turn.turnId,
    prompt: turn.prompt,
    status: turn.status,
    fileRef: turn.fileRef,
    failureCode: turn.failureCode,
    failureMessage: turn.failureMessage,
    createdAt: turn.createdAt,
    updatedAt: turn.updatedAt,
  };
}
