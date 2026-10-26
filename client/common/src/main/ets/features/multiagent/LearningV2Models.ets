/** P0 V2 OpenAPI 的客户端只读模型；字段名与线协议保持一致。 */
export enum V2TutorMode { HINT = 'HINT', FULL_SOLUTION = 'FULL_SOLUTION' }
export enum V2TutorRequestStatus {
  WAITING_INPUT = 'WAITING_INPUT', QUEUED = 'QUEUED', RUNNING = 'RUNNING',
  CANCELLING = 'CANCELLING', SUCCEEDED = 'SUCCEEDED', FAILED = 'FAILED',
  CANCELLED = 'CANCELLED', TIMED_OUT = 'TIMED_OUT'
}
export enum V2RunStatus {
  QUEUED = 'QUEUED', RUNNING = 'RUNNING', CANCELLING = 'CANCELLING',
  SUCCEEDED = 'SUCCEEDED', FAILED = 'FAILED', CANCELLED = 'CANCELLED', TIMED_OUT = 'TIMED_OUT'
}
export interface V2SnapshotInput { kind: string; sourceRequestId?: string; }
export interface V2TutorRequest {
  requestId: string; questionId: string; prompt: string; mode: V2TutorMode;
  snapshotInput: V2SnapshotInput; contextParentRequestId?: string | null; snapshotOriginRequestId: string | null;
  snapshotFileRef: string | null; currentRunId: string | null;
  status: V2TutorRequestStatus; revision: number; createdAt: string; updatedAt: string;
}
export interface V2TutorRequestPage { items: V2TutorRequest[]; nextCursor: string | null; }
export interface V2PendingRequest { request: V2TutorRequest | null; }
export interface V2Step { stepId: string; role: string; status: string; revision: number; }
export interface V2Attempt {
  attemptId: string; stepId: string; attemptNo: number; status: string;
  providerCallState: string;
  fence: number; startedAt: string; endedAt?: string | null;
}
export interface V2Run {
  runId: string; requestId: string; runNo: number; retryOfRunId?: string | null;
  status: V2RunStatus; reason?: string; revision: number; cancelRequestedAt?: string | null;
  steps: V2Step[]; attempts?: V2Attempt[];
}
export interface V2Evidence {
  evidenceId: string; runId: string; stepId: string; type: string;
  sourceId?: string; version?: number; anchorId?: string; claim: string; confidence?: number;
}
export type V2SourceType = string;
export interface V2Citation {
  citationId: string; sourceId: string; version: number; anchorId: string;
  label: string; sourceType: V2SourceType;
}
export interface V2TutorResult {
  resultId: string; mode: V2TutorMode; status: string; bodyMarkdown: string;
  citations: V2Citation[]; evidence?: V2Evidence[]; createdAt: string;
}
export interface V2TutorRequestDetail {
  request: V2TutorRequest; run: V2Run | null; runs: V2Run[]; result: V2TutorResult | null;
}
export interface V2Note {
  noteId: string; sourceId: string; version: number; title: string; bodyMarkdown: string;
  indexStatus: string; updatedAt: string;
}
export interface V2NotePage { items: V2Note[]; nextCursor?: string | null; }
export interface V2WorldBounds { x: number; y: number; width: number; height: number; }
export interface V2MarkdownLocator { blockOrdinal: number; startOffset: number; endOffset: number; }
export interface V2HandwritingSegment {
  blockIndex: number; assetUrl: string; blockWorldBounds: V2WorldBounds;
  pixelWidth: number; pixelHeight: number; regionWorldBounds: V2WorldBounds; strokeIds?: string[];
}
export interface V2HandwritingLocator { segments: V2HandwritingSegment[]; }
export interface V2CitationResolution {
  citation: V2Citation; sourceTitle: string; sourceCreatedAt: string; sourceVersionCreatedAt: string;
  currentVersion: number; answerState: string | null;
  anchorKind: string;
  contentSha256: string; markdown?: string; quotedText?: string;
  markdownLocator?: V2MarkdownLocator; handwritingLocator?: V2HandwritingLocator;
}
