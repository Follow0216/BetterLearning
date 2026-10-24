import { http } from '@kit.NetworkKit';
import { AuthApi, DEFAULT_DEV_BASE_URL } from '../auth/AuthApi';
import { AuthError, AuthResult, UserProfile } from '../auth/AuthModels';
import { authSession } from '../auth/AuthSession';
import { CanvasImageBlock, CanvasLocalFile } from '../canvas/CanvasModels';
import { canvas_exists, canvas_read, canvas_sha256, canvas_utf8, canvas_write } from '../canvas/CanvasFiles';
import { validate_answer_manifest } from '../canvas/CanvasGeometry';
import { AnswerManifest, FrozenAnswerUpload } from '../learning/SolutionModels';
import { TutorPending, TutorTurn, TutorTurnStatus } from './TutorModels';

const MODEL_READ_TIMEOUT: number = 150000;
interface CreateTurnPayload { requestId: string; prompt: string; }
interface TurnListPayload { items: TutorTurn[]; }
export function tutor_id_valid(value: string): boolean {
  const max: string = '9223372036854775807';
  return typeof value === 'string' && /^[1-9][0-9]*$/.test(value) &&
    (value.length < max.length || (value.length === max.length && value <= max));
}
export function tutor_uuid_valid(value: string): boolean {
  return typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value);
}
function turns_path(questionId: string): string { return '/api/v1/learning/questions/' + questionId + '/tutor-turns'; }
function nullable_text(value: string | null): boolean { return value === null || typeof value === 'string'; }
function date_valid(value: string): boolean {
  return typeof value === 'string' && /(?:Z|[+-][0-9]{2}:[0-9]{2})$/.test(value) && Number.isFinite(Date.parse(value));
}
function valid_turn(turn: TutorTurn, questionId: string, turnId: string = ''): TutorTurn {
  if (!turn || turn.questionId !== questionId || !tutor_uuid_valid(turn.turnId) || !tutor_uuid_valid(turn.requestId) ||
    (turnId.length > 0 && turn.turnId !== turnId) || typeof turn.prompt !== 'string' ||
    turn.prompt.trim().length === 0 || turn.prompt.length > 4000 || !date_valid(turn.createdAt) || !date_valid(turn.updatedAt) ||
    !(turn.fileRef === null || tutor_uuid_valid(turn.fileRef)) || !nullable_text(turn.answerMarkdown) ||
    !nullable_text(turn.failureCode) || !nullable_text(turn.failureMessage)) { throw new Error('辅导轮次格式无效'); }
  if (turn.status === TutorTurnStatus.WAITING_ANSWER || turn.status === TutorTurnStatus.GENERATING) {
    if ((turn.status === TutorTurnStatus.GENERATING && turn.fileRef === null) || turn.answerMarkdown !== null ||
      turn.failureCode !== null || turn.failureMessage !== null) { throw new Error('辅导等待状态无效'); }
  } else if (turn.status === TutorTurnStatus.SUCCEEDED) {
    if (turn.fileRef === null || turn.answerMarkdown === null || turn.answerMarkdown.trim().length === 0 ||
      turn.failureCode !== null || turn.failureMessage !== null) { throw new Error('辅导结果无效'); }
  } else if (turn.status === TutorTurnStatus.FAILED) {
    if (turn.answerMarkdown !== null || turn.failureCode === null || turn.failureCode.trim().length === 0 ||
      turn.failureCode.length > 64 || turn.failureMessage === null || turn.failureMessage.trim().length === 0 ||
      turn.failureMessage.length > 256) { throw new Error('辅导失败状态无效'); }
  } else { throw new Error('辅导状态无效'); }
  return turn;
}
function request_error(result: AuthResult<string>): AuthError {
  return result.error ?? AuthError.protocol_failure('辅导响应无效');
}

/** 只执行单次真实请求，不持有令牌、不轮询、不自动重试模型、不修改会话。 */
export class TutorApi {
  private transport: AuthApi;
  constructor(baseUrl: string = DEFAULT_DEV_BASE_URL) { this.transport = new AuthApi(baseUrl); }
  static create_default(): TutorApi { return new TutorApi(); }
  async createTurn(token: string, questionId: string, requestId: string, prompt: string): Promise<AuthResult<TutorTurn>> {
    if (!tutor_id_valid(questionId) || !tutor_uuid_valid(requestId) || prompt.trim().length === 0 || prompt.length > 4000) {
      return AuthResult.failure<TutorTurn>(AuthError.local_validation('题号、请求标识或提问无效', {}));
    }
    const payload: CreateTurnPayload = { requestId: requestId, prompt: prompt };
    const result: AuthResult<TutorTurn> = await this.turn_request(token,questionId,'',http.RequestMethod.POST,'',JSON.stringify(payload));
    if (result.isSuccess && result.data !== null && (result.data.requestId !== requestId || result.data.prompt !== prompt)) {
      return AuthResult.failure<TutorTurn>(AuthError.protocol_failure('辅导发问回执不匹配'));
    }
    return result;
  }
  async listTurns(token: string, questionId: string): Promise<AuthResult<TutorTurn[]>> {
    if (!tutor_id_valid(questionId)) { return AuthResult.failure<TutorTurn[]>(AuthError.local_validation('题号无效', {})); }
    const result: AuthResult<string> = await this.transport.request_resource(http.RequestMethod.GET,turns_path(questionId),token);
    if (!result.isSuccess || result.data === null) { return AuthResult.failure<TutorTurn[]>(request_error(result)); }
    try {
      const payload: TurnListPayload = JSON.parse(result.data) as TurnListPayload;
      if (!payload || !Array.isArray(payload.items) || payload.items.length > 50) { throw new Error('轮次列表无效'); }
      const ids: Set<string> = new Set<string>();
      for (let i: number = 0; i < payload.items.length; i++) {
        valid_turn(payload.items[i],questionId);
        if (ids.has(payload.items[i].turnId)) { throw new Error('重复轮次'); }
        ids.add(payload.items[i].turnId);
      }
      return AuthResult.success<TutorTurn[]>(payload.items);
    } catch (error) { return AuthResult.failure<TutorTurn[]>(AuthError.protocol_failure('辅导列表格式无效')); }
  }
  async getTurn(token: string, questionId: string, turnId: string): Promise<AuthResult<TutorTurn>> {
    return this.turn_request(token,questionId,turnId,http.RequestMethod.GET);
  }
  async getPending(token: string, questionId: string): Promise<AuthResult<TutorPending>> {
    if (!tutor_id_valid(questionId)) { return AuthResult.failure<TutorPending>(AuthError.local_validation('题号无效', {})); }
    const result: AuthResult<string> = await this.transport.request_resource(http.RequestMethod.GET,turns_path(questionId) + '/pending',token);
    if (!result.isSuccess || result.data === null) { return AuthResult.failure<TutorPending>(request_error(result)); }
    try {
      const payload: TutorPending = JSON.parse(result.data) as TutorPending;
      if (!payload) { throw new Error('等待轮次无效'); }
      if (payload.turn !== null) {
        valid_turn(payload.turn,questionId);
        if (payload.turn.status !== TutorTurnStatus.WAITING_ANSWER || payload.turn.fileRef !== null) { throw new Error('等待轮次状态无效'); }
      }
      return AuthResult.success<TutorPending>(payload);
    } catch (error) { return AuthResult.failure<TutorPending>(AuthError.protocol_failure('等待轮次格式无效')); }
  }
  async retryTurn(token: string, questionId: string, turnId: string): Promise<AuthResult<TutorTurn>> {
    return this.turn_request(token,questionId,turnId,http.RequestMethod.POST,'/retry','{}',MODEL_READ_TIMEOUT);
  }
  async cancelTurn(token: string, questionId: string, turnId: string): Promise<AuthResult<TutorTurn>> {
    return this.turn_request(token,questionId,turnId,http.RequestMethod.POST,'/cancel','{}');
  }
  async uploadFrozenAnswer(token: string, questionId: string, turnId: string, upload: FrozenAnswerUpload): Promise<AuthResult<TutorTurn>> {
    if (!tutor_id_valid(questionId) || !tutor_uuid_valid(turnId)) {
      return AuthResult.failure<TutorTurn>(AuthError.local_validation('辅导轮次标识无效', {}));
    }
    try {
      const json: Uint8Array = canvas_utf8(upload.manifestJson);
      if (json.length > 8388608) { throw new Error('冻结文档过大'); }
      const manifest: AnswerManifest = JSON.parse(upload.manifestJson) as AnswerManifest;
      validate_answer_manifest(manifest);
      if (manifest.saveRequestId !== turnId || manifest.baseFileRef !== null) { throw new Error('冻结文档轮次不匹配'); }
      const blocks: CanvasImageBlock[] = manifest.blocks.concat(manifest.document.questionLayout.rasterBlocks);
      if (blocks.length !== upload.files.length || blocks.length > 256) { throw new Error('冻结图块不完整'); }
      let bytes: number = 0;
      const names: Set<string> = new Set<string>();
      for (let i: number = 0; i < blocks.length; i++) {
        const block: CanvasImageBlock = blocks[i];
        const file: CanvasLocalFile | undefined = upload.files.find((item: CanvasLocalFile) => item.partName === block.partName);
        if (!file || names.has(block.partName)) { throw new Error('冻结图块无效'); }
        names.add(block.partName);
        const png: Uint8Array = canvas_read(file.filePath); bytes += png.length;
        if (bytes > 67108864 || await canvas_sha256(png) !== block.sha256) { throw new Error('冻结原包校验失败'); }
      }
      const parts: http.MultiFormData[] = [{ name: 'manifest', contentType: 'application/json', remoteFileName: 'manifest.json', data: json.buffer as ArrayBuffer }];
      for (let i: number = 0; i < upload.files.length; i++) {
        const file: CanvasLocalFile = upload.files[i];
        parts.push({ name: file.partName, contentType: 'image/png', remoteFileName: file.partName + '.png', filePath: file.filePath });
      }
      const result: AuthResult<string> = await this.transport.request_multipart(turns_path(questionId) + '/' + turnId + '/answer',
        token,parts,MODEL_READ_TIMEOUT,'求助连接失败，请检查本轮状态后重试');
      if (!result.isSuccess || result.data === null) { return AuthResult.failure<TutorTurn>(request_error(result)); }
      return AuthResult.success<TutorTurn>(valid_turn(JSON.parse(result.data) as TutorTurn,questionId,turnId));
    } catch (error) { return AuthResult.failure<TutorTurn>(AuthError.protocol_failure('冻结作答或辅导回执校验失败，原包已保留')); }
  }
  async getFrozenAnswer(token: string, questionId: string, turnId: string): Promise<AuthResult<AnswerManifest>> {
    if (!tutor_id_valid(questionId) || !tutor_uuid_valid(turnId)) {
      return AuthResult.failure<AnswerManifest>(AuthError.local_validation('辅导轮次标识无效', {}));
    }
    const result: AuthResult<string> = await this.transport.request_resource(http.RequestMethod.GET,
      turns_path(questionId) + '/' + turnId + '/answer',token);
    if (!result.isSuccess || result.data === null) { return AuthResult.failure<AnswerManifest>(request_error(result)); }
    try {
      const manifest: AnswerManifest = JSON.parse(result.data) as AnswerManifest;
      validate_answer_manifest(manifest);
      if (manifest.saveRequestId !== turnId || manifest.baseFileRef !== null || !manifest.questionSnapshot ||
        manifest.questionSnapshot.questionId !== questionId || typeof manifest.questionSnapshot.bodyMarkdown !== 'string' ||
        await canvas_sha256(canvas_utf8(manifest.questionSnapshot.bodyMarkdown)) !== manifest.document.questionLayout.questionBodySha256) {
        throw new Error('冻结题干或轮次不匹配');
      }
      return AuthResult.success<AnswerManifest>(manifest);
    } catch (error) { return AuthResult.failure<AnswerManifest>(AuthError.protocol_failure('冻结作答响应无效')); }
  }
  async downloadFrozenBlock(token: string, accountId: string, questionId: string, turnId: string, fileRef: string,
    block: CanvasImageBlock, filesDir: string, questionRaster: boolean = false): Promise<AuthResult<string>> {
    const user: UserProfile | null = authSession.read_user();
    if (user === null || user.accountId !== accountId || !authSession.matches_access_token(token)) {
      return AuthResult.failure<string>(AuthError.local_validation('登录状态已变化', {}));
    }
    if (!tutor_id_valid(accountId) || !tutor_id_valid(questionId) || !tutor_uuid_valid(turnId) || !tutor_uuid_valid(fileRef) ||
      !Number.isInteger(block.index) || block.index < 0 || !/^[0-9a-f]{64}$/.test(block.sha256)) {
      return AuthResult.failure<string>(AuthError.protocol_failure('冻结图块描述无效'));
    }
    const path: string = filesDir + '/m3-tutor/' + accountId + '/' + questionId + '/' + turnId + '/' + fileRef + '/' +
      (questionRaster ? 'question_' : 'chunk_') + block.index.toString() + '.png';
    try {
      if (canvas_exists(path) && await canvas_sha256(canvas_read(path)) === block.sha256) {
        const current: AuthResult<TutorTurn> = await this.getTurn(token,questionId,turnId);
        if (!current.isSuccess || current.data === null) { return AuthResult.failure<string>(current.error ?? AuthError.protocol_failure('轮次校验失败')); }
        if (current.data.fileRef !== fileRef) { return AuthResult.failure<string>(AuthError.http_failure(404,null)); }
      } else {
        const route: string = turns_path(questionId) + '/' + turnId + '/answer/files/' + fileRef + '/' +
          (questionRaster ? 'question-blocks/' : 'blocks/') + block.index.toString();
        const result: AuthResult<ArrayBuffer> = await this.transport.request_binary(route,token);
        if (!result.isSuccess || result.data === null) { return AuthResult.failure<string>(result.error ?? AuthError.protocol_failure('冻结图块缺失')); }
        const bytes: Uint8Array = new Uint8Array(result.data);
        if (bytes.length < 8 || bytes[0] !== 137 || bytes[1] !== 80 || await canvas_sha256(bytes) !== block.sha256) {
          return AuthResult.failure<string>(AuthError.protocol_failure('冻结图块摘要校验失败'));
        }
        if (!authSession.matches_access_token(token) || !authSession.has_valid_session()) {
          return AuthResult.failure<string>(AuthError.local_validation('登录状态已变化', {}));
        }
        canvas_write(path,bytes);
      }
      if (!authSession.matches_access_token(token) || !authSession.has_valid_session()) {
        return AuthResult.failure<string>(AuthError.local_validation('登录状态已变化', {}));
      }
      return AuthResult.success<string>('file://' + path);
    } catch (error) { return AuthResult.failure<string>(AuthError.protocol_failure('冻结文件读取或缓存失败')); }
  }
  private async turn_request(token: string, questionId: string, turnId: string, method: http.RequestMethod,
    suffix: string = '', body: string = '', timeout: number = 15000): Promise<AuthResult<TutorTurn>> {
    if (!tutor_id_valid(questionId) || (turnId.length > 0 && !tutor_uuid_valid(turnId)) ||
      (turnId.length === 0 && method === http.RequestMethod.GET)) {
      return AuthResult.failure<TutorTurn>(AuthError.local_validation('题号或轮次标识无效', {}));
    }
    const path: string = turns_path(questionId) + (turnId.length > 0 ? '/' + turnId : '') + suffix;
    const result: AuthResult<string> = await this.transport.request_resource(method,path,token,body,200,timeout);
    if (!result.isSuccess || result.data === null) { return AuthResult.failure<TutorTurn>(request_error(result)); }
    try { return AuthResult.success<TutorTurn>(valid_turn(JSON.parse(result.data) as TutorTurn,questionId,turnId)); }
    catch (error) { return AuthResult.failure<TutorTurn>(AuthError.protocol_failure('辅导轮次响应无效')); }
  }
}
