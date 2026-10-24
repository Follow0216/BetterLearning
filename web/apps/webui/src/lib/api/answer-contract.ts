/**
 * M2 当前作答只读契约：仅解析 Web 展示所需的必要字段。
 *
 * 可同时被 Server Component、Route Handler 与 Client Component 引用；
 * 不解析编辑用 document/strokes/questionLayout，也不使用服务端返回的文件 URL。
 */

import { LibraryContractError } from "./library-contract";

export type SavedAnswerState = "NOT_STARTED" | "IN_PROGRESS" | "COMPLETED";

export interface AnswerBlock {
  index: number;
  x: number;
  y: number;
  width: number;
  height: number;
}

export interface AnswerCanvas {
  width: number;
  height: number;
}

export interface AnswerManifest {
  schemaVersion: 1;
  canvas: AnswerCanvas;
  blocks: AnswerBlock[];
}

export interface SavedAnswer {
  questionId: string;
  state: SavedAnswerState;
  fileRef: string | null;
  savedAt: string | null;
  completedAt: string | null;
  manifest: AnswerManifest | null;
}

export const MAX_ANSWER_BLOCKS = 256;

const ANSWER_FILE_REF = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const GEOMETRY_EPSILON = 1e-6;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function readNonEmptyString(source: Record<string, unknown>, key: string): string {
  const value = source[key];
  if (typeof value !== "string" || value.length === 0) {
    throw new LibraryContractError(`作答字段 ${key} 格式错误`);
  }
  return value;
}

function readNullableTimestamp(source: Record<string, unknown>, key: string): string | null {
  const value = source[key];
  if (value === null) {
    return null;
  }
  if (typeof value !== "string" || Number.isNaN(Date.parse(value))) {
    throw new LibraryContractError(`作答字段 ${key} 不是合法时间`);
  }
  return value;
}

function readFiniteNumber(source: Record<string, unknown>, key: string): number {
  const value = source[key];
  if (typeof value !== "number" || !Number.isFinite(value)) {
    throw new LibraryContractError(`作答字段 ${key} 不是有限数值`);
  }
  return value;
}

function readCanvasSize(source: Record<string, unknown>, key: string): number {
  const value = readFiniteNumber(source, key);
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new LibraryContractError(`作答画布 ${key} 超出范围`);
  }
  return value;
}

function parseBlock(value: unknown): AnswerBlock {
  if (!isRecord(value)) {
    throw new LibraryContractError("作答图块描述格式错误");
  }
  const index = readFiniteNumber(value, "index");
  if (!Number.isSafeInteger(index) || index < 0 || index >= MAX_ANSWER_BLOCKS) {
    throw new LibraryContractError("作答图块 index 超出范围");
  }
  const x = readFiniteNumber(value, "x");
  const y = readFiniteNumber(value, "y");
  const width = readFiniteNumber(value, "width");
  const height = readFiniteNumber(value, "height");
  if (x < 0 || y < 0 || width <= 0 || height <= 0) {
    throw new LibraryContractError("作答图块几何不合法");
  }
  return { index, x, y, width, height };
}

function parseManifest(value: unknown): AnswerManifest {
  if (!isRecord(value)) {
    throw new LibraryContractError("作答 manifest 格式错误");
  }
  if (value.schemaVersion !== 1) {
    throw new LibraryContractError("作答 manifest 版本不受支持");
  }
  const canvas = value.canvas;
  if (!isRecord(canvas)) {
    throw new LibraryContractError("作答画布描述格式错误");
  }
  const canvasWidth = readCanvasSize(canvas, "width");
  const canvasHeight = readCanvasSize(canvas, "height");

  const rawBlocks = value.blocks;
  if (!Array.isArray(rawBlocks) || rawBlocks.length === 0 || rawBlocks.length > MAX_ANSWER_BLOCKS) {
    throw new LibraryContractError("作答图块数量不合法");
  }
  const blocks = rawBlocks.map(parseBlock).sort((left, right) => left.index - right.index);

  let coveredArea = 0;
  blocks.forEach((block, position) => {
    if (block.index !== position) {
      throw new LibraryContractError("作答图块编号不连续");
    }
    if (
      block.x + block.width > canvasWidth + GEOMETRY_EPSILON ||
      block.y + block.height > canvasHeight + GEOMETRY_EPSILON
    ) {
      throw new LibraryContractError("作答图块超出画布范围");
    }
    coveredArea += block.width * block.height;
  });

  const canvasArea = canvasWidth * canvasHeight;
  const tolerance = Math.max(1, canvasArea) * GEOMETRY_EPSILON;
  if (Math.abs(coveredArea - canvasArea) > tolerance) {
    throw new LibraryContractError("作答图块未完整覆盖画布");
  }

  return { schemaVersion: 1, canvas: { width: canvasWidth, height: canvasHeight }, blocks };
}

export function isValidAnswerFileRef(value: string): boolean {
  return ANSWER_FILE_REF.test(value);
}

export function parseAnswerBlockIndex(value: string): number | null {
  if (!/^(0|[1-9]\d{0,2})$/.test(value)) {
    return null;
  }
  const index = Number(value);
  return index < MAX_ANSWER_BLOCKS ? index : null;
}

export function parseSavedAnswer(value: unknown, expectedQuestionId: string): SavedAnswer {
  if (!isRecord(value)) {
    throw new LibraryContractError("当前作答响应格式错误");
  }
  const questionId = readNonEmptyString(value, "questionId");
  if (questionId !== expectedQuestionId) {
    throw new LibraryContractError("当前作答不属于该题目");
  }

  const state = value.state;
  if (state !== "NOT_STARTED" && state !== "IN_PROGRESS" && state !== "COMPLETED") {
    throw new LibraryContractError("作答状态不在枚举范围内");
  }

  const rawFileRef = value.fileRef;
  let fileRef: string | null;
  if (rawFileRef === null) {
    fileRef = null;
  } else if (typeof rawFileRef === "string" && isValidAnswerFileRef(rawFileRef)) {
    fileRef = rawFileRef;
  } else {
    throw new LibraryContractError("作答 fileRef 格式错误");
  }

  const savedAt = readNullableTimestamp(value, "savedAt");
  const completedAt = readNullableTimestamp(value, "completedAt");
  const rawManifest = value.manifest;
  const manifest = rawManifest === null ? null : parseManifest(rawManifest);

  if (state === "NOT_STARTED") {
    if (fileRef !== null || savedAt !== null || completedAt !== null || manifest !== null) {
      throw new LibraryContractError("未开始作答不应包含已保存内容");
    }
  } else {
    if (fileRef === null || savedAt === null || manifest === null) {
      throw new LibraryContractError("已保存作答缺少必要字段");
    }
    if ((state === "COMPLETED") !== (completedAt !== null)) {
      throw new LibraryContractError("作答完成时间与状态不一致");
    }
  }

  return { questionId, state, fileRef, savedAt, completedAt, manifest };
}
