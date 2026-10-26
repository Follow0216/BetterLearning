export type SourceType = "QUESTION" | "SAVED_ANSWER" | "TUTOR_SNAPSHOT" | "TUTOR_REPLY" | "NOTE";
export type IndexStatus = "INDEX_PENDING" | "READY" | "FAILED" | "WITHDRAWN";
export type TutorStatus =
  | "WAITING_INPUT"
  | "QUEUED"
  | "RUNNING"
  | "CANCELLING"
  | "SUCCEEDED"
  | "FAILED"
  | "CANCELLED"
  | "TIMED_OUT";
export type TutorMode = "HINT" | "FULL_SOLUTION";
export type InputSelectionReason = "TABLET_AVAILABLE" | "NO_DEVICE" | "OFFLINE" | "OTHER_QUESTION";
export interface CursorPage<T> {
  items: T[];
  nextCursor: string | null;
}
export interface Note {
  noteId: string;
  sourceId: string;
  version: number;
  title: string;
  bodyMarkdown: string;
  indexStatus: IndexStatus;
  updatedAt: string;
}
export interface Source {
  sourceId: string;
  type: SourceType;
  originId: string;
  status: "ACTIVE" | "DELETED";
  createdAt: string;
  currentVersion: number;
  indexStatus: IndexStatus;
  answerState?: "DRAFT" | "COMPLETED" | null;
}
export interface TutorRequest {
  requestId: string;
  questionId: string;
  prompt: string;
  mode: TutorMode;
  snapshotInput:
    { kind: "NEW_CAPTURE" | "NO_ANSWER" } | { kind: "REUSE_REQUEST"; sourceRequestId: string };
  inputSelectionReason?: InputSelectionReason | null;
  snapshotOriginRequestId: string | null;
  snapshotFileRef: string | null;
  currentRunId: string | null;
  status: TutorStatus;
  revision: number;
  createdAt: string;
  updatedAt: string;
}
export interface Step {
  stepId: string;
  role: "RETRIEVE" | "SOLVE" | "DIAGNOSE" | "TEACH" | "REVIEW";
  status: string;
  revision: number;
}
export interface Attempt {
  attemptId: string;
  stepId: string;
  attemptNo: number;
  status: string;
  providerCallState: string;
  fence: number;
  startedAt: string;
  endedAt?: string | null;
}
export interface Run {
  runId: string;
  requestId: string;
  runNo: number;
  retryOfRunId?: string | null;
  status: Exclude<TutorStatus, "WAITING_INPUT">;
  reason?: string;
  revision: number;
  cancelRequestedAt?: string | null;
  steps: Step[];
  attempts?: Attempt[];
}
export interface Citation {
  citationId: string;
  sourceId: string;
  version: number;
  anchorId: string;
  label: string;
  sourceType: SourceType;
}
export interface Evidence {
  evidenceId: string;
  runId: string;
  stepId: string;
  type: "CURRENT_SNAPSHOT" | "HISTORY" | "DERIVATION";
  sourceId?: string;
  version?: number;
  anchorId?: string;
  claim: string;
  confidence?: number;
}
export interface TutorResult {
  resultId: string;
  mode: TutorMode;
  status: "VERIFIED";
  bodyMarkdown: string;
  citations: Citation[];
  evidence?: Evidence[];
  createdAt: string;
}
export interface TutorDetail {
  request: TutorRequest;
  run: Run | null;
  runs: Run[];
  result: TutorResult | null;
}
export type TutorRequestDetail = TutorDetail;
export interface WorldBounds {
  x: number;
  y: number;
  width: number;
  height: number;
}
export interface MarkdownLocator {
  blockOrdinal: number;
  startOffset: number;
  endOffset: number;
}
export interface HandwritingSegment {
  blockIndex: number;
  assetUrl: string;
  blockWorldBounds: WorldBounds;
  pixelWidth: number;
  pixelHeight: number;
  regionWorldBounds: WorldBounds;
  strokeIds?: string[];
}
export interface HandwritingLocator {
  segments: HandwritingSegment[];
}
export interface CitationResolution {
  citation: Citation;
  sourceTitle: string;
  sourceCreatedAt: string;
  sourceVersionCreatedAt: string;
  currentVersion: number;
  answerState: "DRAFT" | "COMPLETED" | null;
  anchorKind: "MARKDOWN_BLOCK" | "HANDWRITING_STEP" | "QUESTION_BLOCK";
  contentSha256: string;
  markdown?: string;
  quotedText?: string;
  markdownLocator?: MarkdownLocator;
  handwritingLocator?: HandwritingLocator;
}
export class LearningContractError extends Error {
  constructor() {
    super("学习服务响应格式错误");
    this.name = "LearningContractError";
  }
}
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const QUESTION_ID = /^[1-9][0-9]{0,18}$/;
const SHA256 = /^[0-9a-f]{64}$/;
export const isUuid = (value: string): boolean => UUID.test(value);
const fail = (): never => {
  throw new LearningContractError();
};
const obj = (v: unknown): Record<string, unknown> =>
  typeof v === "object" && v !== null && !Array.isArray(v)
    ? (v as Record<string, unknown>)
    : fail();
const str = (v: unknown): string => (typeof v === "string" ? v : fail());
const uuid = (v: unknown): string => {
  const s = str(v);
  return UUID.test(s) ? s : fail();
};
const integer = (v: unknown, min = 0): number =>
  typeof v === "number" && Number.isSafeInteger(v) && v >= min ? v : fail();
const finite = (v: unknown, positive = false): number =>
  typeof v === "number" && Number.isFinite(v) && (!positive || v > 0) ? v : fail();
const enumeration = <T extends string>(v: unknown, allowed: readonly T[]): T =>
  allowed.includes(v as T) ? (v as T) : fail();
const nullable = <T>(v: unknown, parser: (value: unknown) => T): T | null =>
  v === null ? null : parser(v);
const optional = <T>(v: unknown, parser: (value: unknown) => T): T | undefined =>
  v === undefined ? undefined : parser(v);
const list = <T>(v: unknown, parser: (value: unknown) => T): T[] =>
  Array.isArray(v) ? v.map(parser) : fail();
const date = (v: unknown): string => {
  const s = str(v);
  return s.length > 0 && !Number.isNaN(Date.parse(s)) ? s : fail();
};
const sourceType = (v: unknown): SourceType =>
  enumeration(v, ["QUESTION", "SAVED_ANSWER", "TUTOR_SNAPSHOT", "TUTOR_REPLY", "NOTE"]);
const indexStatus = (v: unknown): IndexStatus =>
  enumeration(v, ["INDEX_PENDING", "READY", "FAILED", "WITHDRAWN"]);
const tutorStatus = (v: unknown): TutorStatus =>
  enumeration(v, [
    "WAITING_INPUT",
    "QUEUED",
    "RUNNING",
    "CANCELLING",
    "SUCCEEDED",
    "FAILED",
    "CANCELLED",
    "TIMED_OUT",
  ]);
const answerState = (v: unknown) =>
  nullable(v, (x) => enumeration(x, ["DRAFT", "COMPLETED"] as const));
export function parseNote(v: unknown): Note {
  const o = obj(v);
  return {
    noteId: uuid(o.noteId),
    sourceId: uuid(o.sourceId),
    version: integer(o.version, 1),
    title: str(o.title),
    bodyMarkdown: str(o.bodyMarkdown),
    indexStatus: indexStatus(o.indexStatus),
    updatedAt: date(o.updatedAt),
  };
}
export function parseSource(v: unknown): Source {
  const o = obj(v);
  return {
    sourceId: uuid(o.sourceId),
    type: sourceType(o.type),
    originId: str(o.originId),
    status: enumeration(o.status, ["ACTIVE", "DELETED"]),
    createdAt: date(o.createdAt),
    currentVersion: integer(o.currentVersion, 1),
    indexStatus: indexStatus(o.indexStatus),
    answerState: optional(o.answerState, answerState),
  };
}
export function parseCursorPage<T>(v: unknown, parser: (value: unknown) => T): CursorPage<T> {
  const o = obj(v);
  return {
    items: list(o.items, parser),
    nextCursor: o.nextCursor === undefined ? null : nullable(o.nextCursor, str),
  };
}
export function parseTutorRequest(v: unknown): TutorRequest {
  const o = obj(v),
    input = obj(o.snapshotInput),
    kind = enumeration(input.kind, ["NEW_CAPTURE", "REUSE_REQUEST", "NO_ANSWER"]);
  const questionId = str(o.questionId);
  if (!QUESTION_ID.test(questionId)) fail();
  const status = tutorStatus(o.status);
  const currentRunId = nullable(o.currentRunId, uuid);
  const snapshotOriginRequestId = nullable(o.snapshotOriginRequestId, uuid);
  const snapshotFileRef = nullable(o.snapshotFileRef, uuid);
  const inputSelectionReason = optional(o.inputSelectionReason, (value) =>
    nullable(value, (reason) =>
      enumeration<InputSelectionReason>(reason, [
        "TABLET_AVAILABLE",
        "NO_DEVICE",
        "OFFLINE",
        "OTHER_QUESTION",
      ]),
    ),
  );
  if (
    kind === "NO_ANSWER" &&
    (snapshotOriginRequestId !== null ||
      snapshotFileRef !== null ||
      input.sourceRequestId != null ||
      currentRunId === null ||
      status === "WAITING_INPUT" ||
      !inputSelectionReason ||
      inputSelectionReason === "TABLET_AVAILABLE")
  )
    fail();
  if (status === "WAITING_INPUT" && currentRunId !== null) fail();
  if (["QUEUED", "RUNNING", "CANCELLING", "SUCCEEDED"].includes(status) && currentRunId === null)
    fail();
  return {
    requestId: uuid(o.requestId),
    questionId,
    prompt: str(o.prompt),
    mode: enumeration(o.mode, ["HINT", "FULL_SOLUTION"]),
    snapshotInput:
      kind === "REUSE_REQUEST" ? { kind, sourceRequestId: uuid(input.sourceRequestId) } : { kind },
    inputSelectionReason,
    snapshotOriginRequestId,
    snapshotFileRef,
    currentRunId,
    status,
    revision: integer(o.revision),
    createdAt: date(o.createdAt),
    updatedAt: date(o.updatedAt),
  };
}
const parseStep = (v: unknown): Step => {
  const o = obj(v);
  return {
    stepId: uuid(o.stepId),
    role: enumeration(o.role, ["RETRIEVE", "SOLVE", "DIAGNOSE", "TEACH", "REVIEW"]),
    status: enumeration(o.status, [
      "PENDING",
      "RUNNING",
      "SUCCEEDED",
      "FAILED",
      "CANCELLED",
      "UNKNOWN",
    ]),
    revision: integer(o.revision),
  };
};
const parseAttempt = (v: unknown): Attempt => {
  const o = obj(v);
  return {
    attemptId: uuid(o.attemptId),
    stepId: uuid(o.stepId),
    attemptNo: integer(o.attemptNo, 1),
    status: enumeration(o.status, [
      "PENDING",
      "RUNNING",
      "SUCCEEDED",
      "FAILED",
      "CANCELLED",
      "UNKNOWN",
    ]),
    providerCallState: enumeration(o.providerCallState, [
      "NOT_SENT",
      "CALL_INTENT",
      "CONFIRMED",
      "UNKNOWN",
    ]),
    fence: integer(o.fence, 1),
    startedAt: date(o.startedAt),
    endedAt: optional(o.endedAt, (x) => nullable(x, date)),
  };
};
const parseRun = (v: unknown): Run => {
  const o = obj(v);
  const status = tutorStatus(o.status);
  if (status === "WAITING_INPUT") fail();
  return {
    runId: uuid(o.runId),
    requestId: uuid(o.requestId),
    runNo: integer(o.runNo, 1),
    retryOfRunId: optional(o.retryOfRunId, (x) => nullable(x, uuid)),
    status: status as Exclude<TutorStatus, "WAITING_INPUT">,
    reason: optional(o.reason, (x) =>
      enumeration(x, [
        "OUTCOME_UNKNOWN",
        "MODEL_UNAVAILABLE",
        "INPUT_TIMEOUT",
        "QUEUE_TIMEOUT",
        "RUN_TIMEOUT",
        "BUDGET_EXCEEDED",
        "REVIEW_REJECTED",
      ]),
    ),
    revision: integer(o.revision),
    cancelRequestedAt: optional(o.cancelRequestedAt, (x) => nullable(x, date)),
    steps: list(o.steps, parseStep),
    attempts: optional(o.attempts, (x) => list(x, parseAttempt)),
  };
};
export function parseCitation(v: unknown): Citation {
  const o = obj(v);
  return {
    citationId: uuid(o.citationId),
    sourceId: uuid(o.sourceId),
    version: integer(o.version, 1),
    anchorId: uuid(o.anchorId),
    label: str(o.label),
    sourceType: sourceType(o.sourceType),
  };
}
const parseEvidence = (v: unknown): Evidence => {
  const o = obj(v);
  return {
    evidenceId: uuid(o.evidenceId),
    runId: uuid(o.runId),
    stepId: uuid(o.stepId),
    type: enumeration(o.type, ["CURRENT_SNAPSHOT", "HISTORY", "DERIVATION"]),
    sourceId: optional(o.sourceId, uuid),
    version: optional(o.version, (x) => integer(x, 1)),
    anchorId: optional(o.anchorId, uuid),
    claim: str(o.claim),
    confidence: optional(o.confidence, (x) => {
      const n = finite(x);
      return n >= 0 && n <= 1 ? n : fail();
    }),
  };
};
const parseResult = (v: unknown): TutorResult => {
  const o = obj(v);
  if (o.status !== "VERIFIED") fail();
  return {
    resultId: uuid(o.resultId),
    mode: enumeration(o.mode, ["HINT", "FULL_SOLUTION"]),
    status: "VERIFIED",
    bodyMarkdown: str(o.bodyMarkdown),
    citations: list(o.citations, parseCitation),
    evidence: optional(o.evidence, (x) => list(x, parseEvidence)),
    createdAt: date(o.createdAt),
  };
};
export function parseTutorDetail(v: unknown): TutorDetail {
  const o = obj(v),
    request = parseTutorRequest(o.request),
    run = nullable(o.run, parseRun),
    runs = list(o.runs, parseRun),
    result = nullable(o.result, parseResult);
  if (
    run?.runId !== (request.currentRunId ?? undefined) ||
    runs.some(
      (r, i) => r.requestId !== request.requestId || (i > 0 && r.runNo <= runs[i - 1].runNo),
    ) ||
    (run && !runs.some((r) => r.runId === run.runId))
  )
    fail();
  if (
    (run &&
      (run.requestId !== request.requestId ||
        run.status !== request.status ||
        !runs.some(
          (item) =>
            item.runId === run.runId &&
            item.revision === run.revision &&
            item.status === run.status,
        ))) ||
    (result !== null && (run?.status !== "SUCCEEDED" || result.mode !== request.mode)) ||
    (run?.status === "SUCCEEDED" && result === null)
  )
    fail();
  return { request, run, runs, result };
}
const bounds = (v: unknown): WorldBounds => {
  const o = obj(v);
  return {
    x: finite(o.x),
    y: finite(o.y),
    width: finite(o.width, true),
    height: finite(o.height, true),
  };
};
const markdownLocator = (v: unknown, markdown: string): MarkdownLocator => {
  const o = obj(v),
    startOffset = integer(o.startOffset),
    endOffset = integer(o.endOffset, 1);
  if (startOffset >= endOffset || endOffset > markdown.length) fail();
  const splitsPair = (i: number) =>
    i > 0 &&
    i < markdown.length &&
    /[\uD800-\uDBFF]/.test(markdown[i - 1]) &&
    /[\uDC00-\uDFFF]/.test(markdown[i]);
  if (splitsPair(startOffset) || splitsPair(endOffset)) fail();
  return { blockOrdinal: integer(o.blockOrdinal), startOffset, endOffset };
};
const segment = (v: unknown): HandwritingSegment => {
  const o = obj(v),
    blockIndex = integer(o.blockIndex),
    blockWorldBounds = bounds(o.blockWorldBounds),
    regionWorldBounds = bounds(o.regionWorldBounds),
    pixelWidth = integer(o.pixelWidth, 1),
    pixelHeight = integer(o.pixelHeight, 1);
  const within = (a: number, n: number, b: number, m: number) => a >= b && a + n <= b + m;
  if (
    !within(
      regionWorldBounds.x,
      regionWorldBounds.width,
      blockWorldBounds.x,
      blockWorldBounds.width,
    ) ||
    !within(
      regionWorldBounds.y,
      regionWorldBounds.height,
      blockWorldBounds.y,
      blockWorldBounds.height,
    )
  )
    fail();
  return {
    blockIndex,
    assetUrl: str(o.assetUrl),
    blockWorldBounds,
    pixelWidth,
    pixelHeight,
    regionWorldBounds,
    strokeIds: optional(o.strokeIds, (x) => list(x, str)),
  };
};
export function parseCitationResolution(v: unknown): CitationResolution {
  const o = obj(v),
    citation = parseCitation(o.citation),
    anchorKind = enumeration(o.anchorKind, [
      "MARKDOWN_BLOCK",
      "HANDWRITING_STEP",
      "QUESTION_BLOCK",
    ]),
    markdown = optional(o.markdown, str),
    markdownData =
      o.markdownLocator === undefined
        ? undefined
        : markdownLocator(o.markdownLocator, markdown ?? fail()),
    segments =
      o.handwritingLocator === undefined
        ? undefined
        : list(obj(o.handwritingLocator).segments, segment);
  if (
    (anchorKind === "MARKDOWN_BLOCK" && (markdown === undefined || markdownData === undefined)) ||
    (anchorKind === "HANDWRITING_STEP" && (!segments || segments.length === 0)) ||
    (anchorKind === "QUESTION_BLOCK" &&
      markdownData === undefined &&
      (!segments || segments.length === 0))
  )
    fail();
  for (const segment of segments ?? []) {
    const canonical = `/api/v2/learning/citations/${citation.citationId}/assets/${segment.blockIndex}`;
    let asset: URL;
    try {
      asset = new URL(segment.assetUrl, "http://citation.local");
    } catch {
      fail();
    }
    if (
      !["http:", "https:"].includes(asset!.protocol) ||
      asset!.username ||
      asset!.password ||
      asset!.search ||
      asset!.hash ||
      asset!.pathname !== canonical
    )
      fail();
    // URI-reference 可以是绝对 URL；只保留授权接口路径，客户端从不访问返回的主机。
    segment.assetUrl = canonical;
  }
  const sha = str(o.contentSha256);
  if (!SHA256.test(sha) || citation.version > integer(o.currentVersion, 1)) fail();
  return {
    citation,
    sourceTitle: str(o.sourceTitle),
    sourceCreatedAt: date(o.sourceCreatedAt),
    sourceVersionCreatedAt: date(o.sourceVersionCreatedAt),
    currentVersion: integer(o.currentVersion, 1),
    answerState: answerState(o.answerState),
    anchorKind,
    contentSha256: sha,
    markdown,
    quotedText: optional(o.quotedText, str),
    markdownLocator: markdownData,
    handwritingLocator: segments && { segments },
  };
}

/** Build the local BFF asset route from authorized citation identity, never from assetUrl. */
export function citationAssetPath(citationId: string, blockIndex: number): string {
  if (!isUuid(citationId) || !Number.isSafeInteger(blockIndex) || blockIndex < 0) {
    throw new LearningContractError();
  }
  return `/api/learning/citations/${citationId}/assets/${blockIndex}`;
}
