import { http } from '@kit.NetworkKit';
import { AuthApi, DEFAULT_DEV_BASE_URL } from '../auth/AuthApi';
import { AuthError, AuthResult } from '../auth/AuthModels';
import { CanvasImageBlock, CanvasLocalFile } from '../canvas/CanvasModels';
import { canvas_read, canvas_sha256, canvas_utf8 } from '../canvas/CanvasFiles';
import { validate_answer_manifest } from '../canvas/CanvasGeometry';
import { AnswerManifest, FrozenAnswerUpload } from '../learning/SolutionModels';
import {
  V2Citation, V2CitationResolution, V2Note, V2NotePage, V2PendingRequest,
  V2SnapshotInput, V2TutorMode, V2TutorRequest, V2TutorRequestDetail,
  V2TutorRequestPage, V2TutorRequestStatus, V2Run, V2RunStatus, V2WorldBounds,
  V2TabletPresence, V2TabletPresenceReceipt, V2TabletAvailability
} from './LearningV2Models';

interface V2CreatePayload { requestId: string; prompt: string; mode: V2TutorMode; snapshotInput: V2SnapshotInput; contextParentRequestId?: string | null; }
interface V2CancelPayload { expectedRunId: string | null; }
interface V2RetryPayload { targetRunId: string; }
const ROOT: string = '/api/v2/learning';
const UUID: RegExp = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
const SHA: RegExp = /^[0-9a-f]{64}$/;
const MAX_QUESTION_ID: string = '9223372036854775807';
export function v2_uuid_valid(value: string): boolean { return typeof value === 'string' && UUID.test(value); }
export function v2_question_id_valid(value: string): boolean {
  return typeof value === 'string' && /^[1-9][0-9]*$/.test(value) &&
    (value.length < MAX_QUESTION_ID.length || value.length === MAX_QUESTION_ID.length && value <= MAX_QUESTION_ID);
}
function fail<T>(message: string): AuthResult<T> { return AuthResult.failure<T>(AuthError.protocol_failure(message)); }
function local<T>(message: string): AuthResult<T> { return AuthResult.failure<T>(AuthError.local_validation(message, {})); }
function valid_date(value: string): boolean {
  return typeof value === 'string' && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(value) &&
    Number.isFinite(Date.parse(value));
}
function valid_snapshot(value: V2SnapshotInput): boolean {
  return !!value && (['AUTO', 'NEW_CAPTURE', 'NO_ANSWER'].includes(value.kind) &&
    (value.sourceRequestId === undefined || value.sourceRequestId === null) ||
    value.kind === 'REUSE_REQUEST' && typeof value.sourceRequestId === 'string' &&
      v2_uuid_valid(value.sourceRequestId));
}
function valid_request(value: V2TutorRequest, questionId: string, requestId: string = ''): boolean {
  return !!value && v2_uuid_valid(value.requestId) && (requestId === '' || value.requestId === requestId) &&
    value.questionId === questionId && typeof value.prompt === 'string' && value.prompt.trim().length > 0 &&
    (value.mode === V2TutorMode.HINT || value.mode === V2TutorMode.FULL_SOLUTION) &&
    valid_snapshot(value.snapshotInput) && value.snapshotInput.kind !== 'AUTO' &&
    (value.inputSelectionReason === undefined || value.inputSelectionReason === null ||
      ['TABLET_AVAILABLE', 'NO_DEVICE', 'OFFLINE', 'OTHER_QUESTION'].includes(value.inputSelectionReason)) &&
    (value.snapshotInput.kind !== 'NO_ANSWER' || value.snapshotOriginRequestId === null &&
      value.snapshotFileRef === null && value.currentRunId !== null &&
      ['NO_DEVICE', 'OFFLINE', 'OTHER_QUESTION'].includes(value.inputSelectionReason ?? '') &&
      value.status !== V2TutorRequestStatus.WAITING_INPUT) &&
    (value.contextParentRequestId === undefined || value.contextParentRequestId === null ||
      v2_uuid_valid(value.contextParentRequestId) && value.contextParentRequestId !== value.requestId) &&
    (value.snapshotOriginRequestId === null || v2_uuid_valid(value.snapshotOriginRequestId)) &&
    (value.snapshotFileRef === null || v2_uuid_valid(value.snapshotFileRef)) &&
    (value.currentRunId === null || v2_uuid_valid(value.currentRunId)) &&
    [V2TutorRequestStatus.WAITING_INPUT, V2TutorRequestStatus.QUEUED, V2TutorRequestStatus.RUNNING,
      V2TutorRequestStatus.CANCELLING, V2TutorRequestStatus.SUCCEEDED, V2TutorRequestStatus.FAILED,
      V2TutorRequestStatus.CANCELLED, V2TutorRequestStatus.TIMED_OUT].includes(value.status) &&
    Number.isSafeInteger(value.revision) && value.revision >= 0 &&
    valid_date(value.createdAt) && valid_date(value.updatedAt) &&
    (value.status !== V2TutorRequestStatus.WAITING_INPUT || value.currentRunId === null);
}
function valid_run(value: V2Run, requestId: string): boolean {
  return !!value && v2_uuid_valid(value.runId) && value.requestId === requestId &&
    Number.isSafeInteger(value.runNo) && value.runNo >= 1 &&
    [V2RunStatus.QUEUED, V2RunStatus.RUNNING, V2RunStatus.CANCELLING, V2RunStatus.SUCCEEDED,
      V2RunStatus.FAILED, V2RunStatus.CANCELLED, V2RunStatus.TIMED_OUT].includes(value.status) &&
    Number.isSafeInteger(value.revision) && value.revision >= 0 && Array.isArray(value.steps);
}
function valid_citation(value: V2Citation): boolean {
  return !!value && v2_uuid_valid(value.citationId) && v2_uuid_valid(value.sourceId) &&
    v2_uuid_valid(value.anchorId) && Number.isSafeInteger(value.version) && value.version >= 1 &&
    typeof value.label === 'string' &&
    ['QUESTION', 'SAVED_ANSWER', 'TUTOR_SNAPSHOT', 'TUTOR_REPLY', 'NOTE'].includes(value.sourceType);
}
function valid_detail(value: V2TutorRequestDetail, questionId: string, requestId: string): boolean {
  if (!value || !valid_request(value.request, questionId, requestId) ||
    !Array.isArray(value.runs) || (value.run !== null && !valid_run(value.run, requestId))) { return false; }
  const ids: Set<string> = new Set<string>();
  let lastRunNo: number = 0;
  for (let i: number = 0; i < value.runs.length; i++) {
    if (!valid_run(value.runs[i], requestId) || ids.has(value.runs[i].runId) ||
      value.runs[i].runNo <= lastRunNo) { return false; }
    ids.add(value.runs[i].runId);
    lastRunNo = value.runs[i].runNo;
  }
  if ((value.request.currentRunId === null) !== (value.run === null) ||
    value.run !== null && (value.request.currentRunId !== value.run.runId ||
      String(value.request.status) !== String(value.run.status) || !ids.has(value.run.runId) ||
      value.runs[value.runs.length - 1].runId !== value.run.runId) ||
    value.run === null && (value.runs.length > 0 || value.request.snapshotFileRef !== null ||
      value.request.status !== V2TutorRequestStatus.WAITING_INPUT &&
      value.request.status !== V2TutorRequestStatus.CANCELLED && value.request.status !== V2TutorRequestStatus.TIMED_OUT)) { return false; }
  if ((value.run !== null && value.run.status === V2RunStatus.SUCCEEDED) !== (value.result !== null)) { return false; }
  if (value.result !== null) {
    if (value.run === null || value.run.status !== V2RunStatus.SUCCEEDED ||
      !v2_uuid_valid(value.result.resultId) || value.result.status !== 'VERIFIED' ||
      value.result.mode !== value.request.mode || typeof value.result.bodyMarkdown !== 'string' ||
      !Array.isArray(value.result.citations) || !value.result.citations.every(valid_citation) ||
      !valid_date(value.result.createdAt)) { return false; }
  }
  return true;
}
function valid_note(value: V2Note): boolean {
  return !!value && v2_uuid_valid(value.noteId) && v2_uuid_valid(value.sourceId) &&
    Number.isSafeInteger(value.version) && value.version >= 1 && typeof value.title === 'string' &&
    typeof value.bodyMarkdown === 'string' && canvas_utf8(value.bodyMarkdown).length <= 65536 &&
    ['INDEX_PENDING', 'READY', 'FAILED', 'WITHDRAWN'].includes(value.indexStatus) &&
    valid_date(value.updatedAt);
}
function valid_bounds(value: V2WorldBounds): boolean {
  return !!value && Number.isFinite(value.x) && Number.isFinite(value.y) &&
    Number.isFinite(value.width) && value.width > 0 && Number.isFinite(value.height) && value.height > 0 &&
    Number.isFinite(value.x + value.width) && Number.isFinite(value.y + value.height);
}
function valid_resolution(value: V2CitationResolution, citationId: string): boolean {
  if (!value || !valid_citation(value.citation) || value.citation.citationId !== citationId ||
    typeof value.sourceTitle !== 'string' || !valid_date(value.sourceCreatedAt) ||
    !valid_date(value.sourceVersionCreatedAt) || !Number.isSafeInteger(value.currentVersion) ||
    value.currentVersion < value.citation.version ||
    !['DRAFT', 'COMPLETED', null].includes(value.answerState) || !SHA.test(value.contentSha256)) { return false; }
  const markdown: boolean = typeof value.markdown === 'string' && !!value.markdownLocator &&
    Number.isSafeInteger(value.markdownLocator.blockOrdinal) && value.markdownLocator.blockOrdinal >= 0 &&
    Number.isSafeInteger(value.markdownLocator.startOffset) && Number.isSafeInteger(value.markdownLocator.endOffset) &&
    value.markdownLocator.startOffset >= 0 && value.markdownLocator.endOffset > value.markdownLocator.startOffset &&
    value.markdownLocator.endOffset <= value.markdown.length &&
    !(value.markdownLocator.startOffset > 0 &&
      value.markdown.charCodeAt(value.markdownLocator.startOffset - 1) >= 0xD800 &&
      value.markdown.charCodeAt(value.markdownLocator.startOffset - 1) <= 0xDBFF &&
      value.markdown.charCodeAt(value.markdownLocator.startOffset) >= 0xDC00 &&
      value.markdown.charCodeAt(value.markdownLocator.startOffset) <= 0xDFFF) &&
    !(value.markdownLocator.endOffset < value.markdown.length &&
      value.markdown.charCodeAt(value.markdownLocator.endOffset - 1) >= 0xD800 &&
      value.markdown.charCodeAt(value.markdownLocator.endOffset - 1) <= 0xDBFF &&
      value.markdown.charCodeAt(value.markdownLocator.endOffset) >= 0xDC00 &&
      value.markdown.charCodeAt(value.markdownLocator.endOffset) <= 0xDFFF);
  const handwriting: boolean = !!value.handwritingLocator && Array.isArray(value.handwritingLocator.segments) &&
    value.handwritingLocator.segments.length > 0 &&
    value.handwritingLocator.segments.every((part) => Number.isSafeInteger(part.blockIndex) && part.blockIndex >= 0 &&
      part.assetUrl === ROOT + '/citations/' + citationId + '/assets/' + part.blockIndex.toString() &&
      Number.isSafeInteger(part.pixelWidth) && part.pixelWidth > 0 &&
      Number.isSafeInteger(part.pixelHeight) && part.pixelHeight > 0 &&
      valid_bounds(part.blockWorldBounds) && valid_bounds(part.regionWorldBounds));
  return value.anchorKind === 'MARKDOWN_BLOCK' ? markdown :
    value.anchorKind === 'HANDWRITING_STEP' ? handwriting :
      value.anchorKind === 'QUESTION_BLOCK' && (markdown !== handwriting);
}
function query(cursor: string | null, limit: number): string {
  const parts: string[] = ['limit=' + limit.toString()];
  if (cursor !== null) { parts.push('cursor=' + encodeURIComponent(cursor)); }
  return '?' + parts.join('&');
}
function request_path(questionId: string): string { return ROOT + '/questions/' + questionId + '/tutor-requests'; }
function error(result: AuthResult<string>): AuthError {
  return result.error ?? AuthError.protocol_failure('V2 响应无效');
}

/** 单次真实 HTTP 调用；写操作不会自动重发，失败后由调用方查询 requestId。 */
export class LearningV2Api {
  private transport: AuthApi;
  constructor(baseUrl: string = DEFAULT_DEV_BASE_URL) { this.transport = new AuthApi(baseUrl); }
  static create_default(): LearningV2Api { return new LearningV2Api(); }

  async updateTabletPresence(token: string, presence: V2TabletPresence): Promise<AuthResult<V2TabletPresenceReceipt>> {
    if (!v2_uuid_valid(presence.sessionId) || !Number.isSafeInteger(presence.sequence) || presence.sequence < 1 ||
      typeof presence.active !== 'boolean' || (presence.active ?
        presence.questionId === null || !v2_question_id_valid(presence.questionId) : presence.questionId !== null)) {
      return local<V2TabletPresenceReceipt>('平板在线状态参数无效');
    }
    const result: AuthResult<string> = await this.transport.request_resource(http.RequestMethod.POST,
      ROOT + '/tablet-presence', token, JSON.stringify(presence), 200, 15000);
    if (!result.isSuccess || result.data === null) { return AuthResult.failure<V2TabletPresenceReceipt>(error(result)); }
    try {
      const value: V2TabletPresenceReceipt = JSON.parse(result.data) as V2TabletPresenceReceipt;
      if (!value || value.sessionId !== presence.sessionId || value.sequence !== presence.sequence ||
        value.questionId !== presence.questionId || value.active !== presence.active || !valid_date(value.leaseExpiresAt)) {
        return fail<V2TabletPresenceReceipt>('平板在线回执已过期或与上报不一致');
      }
      return AuthResult.success<V2TabletPresenceReceipt>(value);
    } catch (e) { return fail<V2TabletPresenceReceipt>('平板在线回执格式无效'); }
  }

  async getTabletAvailability(token: string, questionId: string): Promise<AuthResult<V2TabletAvailability>> {
    if (!v2_question_id_valid(questionId)) { return local<V2TabletAvailability>('题号无效'); }
    const result: AuthResult<string> = await this.transport.request_resource(http.RequestMethod.GET,
      ROOT + '/questions/' + questionId + '/tablet-availability', token);
    if (!result.isSuccess || result.data === null) { return AuthResult.failure<V2TabletAvailability>(error(result)); }
    try {
      const value: V2TabletAvailability = JSON.parse(result.data) as V2TabletAvailability;
      if (!value || typeof value.available !== 'boolean' ||
        !['TABLET_AVAILABLE', 'NO_DEVICE', 'OFFLINE', 'OTHER_QUESTION'].includes(value.reason) ||
        value.available !== (value.reason === 'TABLET_AVAILABLE') ||
        !(value.leaseExpiresAt === null || valid_date(value.leaseExpiresAt)) ||
        value.available && value.leaseExpiresAt === null) { return fail<V2TabletAvailability>('平板可用状态格式无效'); }
      return AuthResult.success<V2TabletAvailability>(value);
    } catch (e) { return fail<V2TabletAvailability>('平板可用状态格式无效'); }
  }

  async createRequest(token: string, questionId: string, requestId: string, prompt: string,
    mode: V2TutorMode = V2TutorMode.HINT, snapshotInput: V2SnapshotInput = { kind: 'AUTO' },
    contextParentRequestId: string | null = null): Promise<AuthResult<V2TutorRequest>> {
    if (!v2_question_id_valid(questionId) || !v2_uuid_valid(requestId) || prompt.trim().length === 0 ||
      prompt.length > 4000 || !valid_snapshot(snapshotInput) || snapshotInput.kind === 'NO_ANSWER' ||
      contextParentRequestId !== null && (!v2_uuid_valid(contextParentRequestId) || contextParentRequestId === requestId) ||
      ![V2TutorMode.HINT, V2TutorMode.FULL_SOLUTION].includes(mode)) { return local<V2TutorRequest>('发问参数无效'); }
    const payload: V2CreatePayload = { requestId: requestId, prompt: prompt, mode: mode, snapshotInput: snapshotInput };
    if (contextParentRequestId !== null) { payload.contextParentRequestId = contextParentRequestId; }
    const body: string = JSON.stringify(payload);
    const result: AuthResult<string> = await this.transport.request_resource(http.RequestMethod.POST, request_path(questionId),
      token, body, 200, 15000, { 'Idempotency-Key': requestId });
    if (!result.isSuccess || result.data === null) { return AuthResult.failure<V2TutorRequest>(error(result)); }
    try {
      const value: V2TutorRequest = JSON.parse(result.data) as V2TutorRequest;
      if (!valid_request(value, questionId, requestId) || value.prompt !== prompt || value.mode !== mode ||
        (snapshotInput.kind === 'AUTO' ?
          !(value.snapshotInput.kind === 'NEW_CAPTURE' && value.inputSelectionReason === 'TABLET_AVAILABLE' ||
            value.snapshotInput.kind === 'NO_ANSWER' &&
            ['NO_DEVICE', 'OFFLINE', 'OTHER_QUESTION'].includes(value.inputSelectionReason ?? '')) :
          value.snapshotInput.kind !== snapshotInput.kind) ||
        (value.contextParentRequestId ?? null) !== contextParentRequestId ||
        (snapshotInput.kind === 'REUSE_REQUEST' && value.snapshotInput.kind === 'REUSE_REQUEST' &&
          value.snapshotInput.sourceRequestId !== snapshotInput.sourceRequestId)) { return fail<V2TutorRequest>('发问回执与请求不匹配'); }
      return AuthResult.success<V2TutorRequest>(value);
    } catch (e) { return fail<V2TutorRequest>('发问响应格式无效'); }
  }

  async listRequests(token: string, questionId: string, cursor: string | null = null,
    limit: number = 20): Promise<AuthResult<V2TutorRequestPage>> {
    if (!v2_question_id_valid(questionId) || !Number.isInteger(limit) || limit < 1 || limit > 100 ||
      cursor !== null && (cursor.length === 0 || cursor.length > 4096)) { return local<V2TutorRequestPage>('列表参数无效'); }
    const result: AuthResult<string> = await this.transport.request_resource(http.RequestMethod.GET,
      request_path(questionId) + query(cursor, limit), token);
    if (!result.isSuccess || result.data === null) { return AuthResult.failure<V2TutorRequestPage>(error(result)); }
    try {
      const value: V2TutorRequestPage = JSON.parse(result.data) as V2TutorRequestPage;
      if (!value || !Array.isArray(value.items) || value.items.length > limit ||
        !value.items.every((item) => valid_request(item, questionId)) ||
        !(value.nextCursor === null || typeof value.nextCursor === 'string')) { return fail<V2TutorRequestPage>('请求列表格式无效'); }
      return AuthResult.success<V2TutorRequestPage>(value);
    } catch (e) { return fail<V2TutorRequestPage>('请求列表格式无效'); }
  }

  async getRequest(token: string, questionId: string, requestId: string): Promise<AuthResult<V2TutorRequestDetail>> {
    return this.detail_call(token, questionId, requestId, '', http.RequestMethod.GET);
  }
  async getPending(token: string, questionId: string): Promise<AuthResult<V2PendingRequest>> {
    if (!v2_question_id_valid(questionId)) { return local<V2PendingRequest>('题号无效'); }
    const result: AuthResult<string> = await this.transport.request_resource(http.RequestMethod.GET,
      request_path(questionId) + '/pending', token);
    if (!result.isSuccess || result.data === null) { return AuthResult.failure<V2PendingRequest>(error(result)); }
    try {
      const value: V2PendingRequest = JSON.parse(result.data) as V2PendingRequest;
      if (!value || !(value.request === null || valid_request(value.request, questionId) &&
        value.request.status === V2TutorRequestStatus.WAITING_INPUT &&
        value.request.snapshotFileRef === null)) { return fail<V2PendingRequest>('待冻结响应格式无效'); }
      return AuthResult.success<V2PendingRequest>(value);
    } catch (e) { return fail<V2PendingRequest>('待冻结响应格式无效'); }
  }

  async uploadSnapshot(token: string, questionId: string, requestId: string, upload: FrozenAnswerUpload,
    active: () => boolean = () => true): Promise<AuthResult<V2TutorRequestDetail>> {
    if (!v2_question_id_valid(questionId) || !v2_uuid_valid(requestId)) { return local<V2TutorRequestDetail>('快照标识无效'); }
    if (!active()) { return local<V2TutorRequestDetail>('快照发送已取消'); }
    try {
      const bytes: Uint8Array = canvas_utf8(upload.manifestJson);
      if (bytes.length > 8388608) { return local<V2TutorRequestDetail>('快照文档过大'); }
      const manifest: AnswerManifest = JSON.parse(upload.manifestJson) as AnswerManifest;
      validate_answer_manifest(manifest);
      if (manifest.saveRequestId !== requestId || manifest.baseFileRef !== null) { return local<V2TutorRequestDetail>('快照与请求不匹配'); }
      const blocks: CanvasImageBlock[] = manifest.blocks.concat(manifest.document.questionLayout.rasterBlocks);
      if (blocks.length !== upload.files.length || blocks.length > 256) { return local<V2TutorRequestDetail>('快照图块不完整'); }
      const names: Set<string> = new Set<string>();
      let total: number = 0;
      for (let i: number = 0; i < blocks.length; i++) {
        const block: CanvasImageBlock = blocks[i];
        const file: CanvasLocalFile | undefined = upload.files.find((item: CanvasLocalFile) => item.partName === block.partName);
        if (!file || names.has(block.partName)) { return local<V2TutorRequestDetail>('快照图块与清单不匹配'); }
        names.add(block.partName);
        const png: Uint8Array = canvas_read(file.filePath);
        total += png.length;
        if (total > 67108864 || png.length < 8 || png[0] !== 137 || png[1] !== 80 ||
          png[2] !== 78 || png[3] !== 71 || png[4] !== 13 || png[5] !== 10 ||
          png[6] !== 26 || png[7] !== 10 || await canvas_sha256(png) !== block.sha256) { return local<V2TutorRequestDetail>('快照原包校验失败'); }
        if (!active()) { return local<V2TutorRequestDetail>('快照发送已取消'); }
      }
      const parts: http.MultiFormData[] = [{
        name: 'manifest', contentType: 'application/json', remoteFileName: 'manifest.json', data: bytes.buffer as ArrayBuffer
      }];
      for (let i: number = 0; i < upload.files.length; i++) {
        const file: CanvasLocalFile = upload.files[i];
        parts.push({ name: file.partName, contentType: 'image/png', remoteFileName: file.partName + '.png', filePath: file.filePath });
      }
      if (!active()) { return local<V2TutorRequestDetail>('快照发送已取消'); }
      const result: AuthResult<string> = await this.transport.request_multipart(
        request_path(questionId) + '/' + requestId + '/snapshot', token, parts, 120000,
        '快照发送状态未知，请先查询请求状态');
      if (!result.isSuccess || result.data === null) { return AuthResult.failure<V2TutorRequestDetail>(error(result)); }
      return this.parse_detail(result.data, questionId, requestId);
    } catch (e) { return local<V2TutorRequestDetail>('快照原包无效，已保留本地文件'); }
  }

  async cancelRequest(token: string, questionId: string, requestId: string,
    expectedRunId: string | null): Promise<AuthResult<V2TutorRequestDetail>> {
    if (expectedRunId !== null && !v2_uuid_valid(expectedRunId)) { return local<V2TutorRequestDetail>('取消目标无效'); }
    const payload: V2CancelPayload = { expectedRunId: expectedRunId };
    return this.detail_call(token, questionId, requestId, '/cancel', http.RequestMethod.POST,
      JSON.stringify(payload));
  }
  async retryRequest(token: string, questionId: string, requestId: string, targetRunId: string,
    idempotencyKey: string): Promise<AuthResult<V2TutorRequestDetail>> {
    if (!v2_uuid_valid(targetRunId) || !v2_uuid_valid(idempotencyKey)) { return local<V2TutorRequestDetail>('重试参数无效'); }
    const payload: V2RetryPayload = { targetRunId: targetRunId };
    return this.detail_call(token, questionId, requestId, '/retry', http.RequestMethod.POST,
      JSON.stringify(payload), { 'Idempotency-Key': idempotencyKey });
  }

  async listNotes(token: string, cursor: string | null = null, limit: number = 20): Promise<AuthResult<V2NotePage>> {
    if (!Number.isInteger(limit) || limit < 1 || limit > 100 ||
      cursor !== null && (cursor.length === 0 || cursor.length > 4096)) { return local<V2NotePage>('笔记列表参数无效'); }
    const result: AuthResult<string> = await this.transport.request_resource(http.RequestMethod.GET,
      ROOT + '/notes' + query(cursor, limit), token);
    if (!result.isSuccess || result.data === null) { return AuthResult.failure<V2NotePage>(error(result)); }
    try {
      const value: V2NotePage = JSON.parse(result.data) as V2NotePage;
      if (!value || !Array.isArray(value.items) || value.items.length > limit ||
        !value.items.every(valid_note) ||
        !(value.nextCursor === undefined || value.nextCursor === null || typeof value.nextCursor === 'string')) {
        return fail<V2NotePage>('笔记列表格式无效');
      }
      return AuthResult.success<V2NotePage>(value);
    } catch (e) { return fail<V2NotePage>('笔记列表格式无效'); }
  }
  async getNote(token: string, noteId: string): Promise<AuthResult<V2Note>> {
    if (!v2_uuid_valid(noteId)) { return local<V2Note>('笔记标识无效'); }
    const result: AuthResult<string> = await this.transport.request_resource(http.RequestMethod.GET,
      ROOT + '/notes/' + noteId, token);
    if (!result.isSuccess || result.data === null) { return AuthResult.failure<V2Note>(error(result)); }
    try {
      const value: V2Note = JSON.parse(result.data) as V2Note;
      return valid_note(value) && value.noteId === noteId ? AuthResult.success<V2Note>(value) : fail<V2Note>('笔记响应格式无效');
    } catch (e) { return fail<V2Note>('笔记响应格式无效'); }
  }
  async resolveCitation(token: string, citationId: string): Promise<AuthResult<V2CitationResolution>> {
    if (!v2_uuid_valid(citationId)) { return local<V2CitationResolution>('引用标识无效'); }
    const result: AuthResult<string> = await this.transport.request_resource(http.RequestMethod.GET,
      ROOT + '/citations/' + citationId + '/resolve', token);
    if (!result.isSuccess || result.data === null) { return AuthResult.failure<V2CitationResolution>(error(result)); }
    try {
      const value: V2CitationResolution = JSON.parse(result.data) as V2CitationResolution;
      return valid_resolution(value, citationId) ?
        AuthResult.success<V2CitationResolution>(value) : fail<V2CitationResolution>('引用定位响应无效');
    } catch (e) { return fail<V2CitationResolution>('引用定位响应无效'); }
  }
  async downloadCitationAsset(token: string, citationId: string, blockIndex: number): Promise<AuthResult<ArrayBuffer>> {
    if (!v2_uuid_valid(citationId) || !Number.isSafeInteger(blockIndex) || blockIndex < 0) {
      return local<ArrayBuffer>('引用图块参数无效');
    }
    const result: AuthResult<ArrayBuffer> = await this.transport.request_binary(
      ROOT + '/citations/' + citationId + '/assets/' + blockIndex.toString(), token);
    if (!result.isSuccess || result.data === null) { return result; }
    const bytes: Uint8Array = new Uint8Array(result.data);
    if (bytes.length < 8 || bytes[0] !== 137 || bytes[1] !== 80 || bytes[2] !== 78 || bytes[3] !== 71 ||
      bytes[4] !== 13 || bytes[5] !== 10 || bytes[6] !== 26 || bytes[7] !== 10) {
      return fail<ArrayBuffer>('引用图块格式无效');
    }
    return result;
  }
  private async detail_call(token: string, questionId: string, requestId: string, suffix: string,
    method: http.RequestMethod, body: string = '', headers: Record<string, string> = {}): Promise<AuthResult<V2TutorRequestDetail>> {
    if (!v2_question_id_valid(questionId) || !v2_uuid_valid(requestId)) { return local<V2TutorRequestDetail>('请求标识无效'); }
    const result: AuthResult<string> = await this.transport.request_resource(method,
      request_path(questionId) + '/' + requestId + suffix, token, body, 200, 15000, headers);
    if (!result.isSuccess || result.data === null) { return AuthResult.failure<V2TutorRequestDetail>(error(result)); }
    return this.parse_detail(result.data, questionId, requestId);
  }
  private parse_detail(body: string, questionId: string, requestId: string): AuthResult<V2TutorRequestDetail> {
    try {
      const value: V2TutorRequestDetail = JSON.parse(body) as V2TutorRequestDetail;
      return valid_detail(value, questionId, requestId) ?
        AuthResult.success<V2TutorRequestDetail>(value) : fail<V2TutorRequestDetail>('请求详情响应无效');
    } catch (e) { return fail<V2TutorRequestDetail>('请求详情响应无效'); }
  }
}
