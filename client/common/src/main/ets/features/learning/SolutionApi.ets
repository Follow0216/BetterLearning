import { http } from '@kit.NetworkKit';
import { AuthApi, DEFAULT_DEV_BASE_URL } from '../auth/AuthApi';
import { AuthError, AuthResult, UserProfile } from '../auth/AuthModels';
import { authSession } from '../auth/AuthSession';
import { CanvasImageBlock, CanvasLocalFile } from '../canvas/CanvasModels';
import { canvas_exists, canvas_read, canvas_sha256, canvas_utf8, canvas_write } from '../canvas/CanvasFiles';
import { validate_answer_manifest } from '../canvas/CanvasGeometry';
import { AnswerManifest, AnswerState, FrozenAnswerUpload, SolutionDetail } from './SolutionModels';

function id_valid(id: string): boolean { return /^[1-9][0-9]{0,18}$/.test(id); }
function ref_valid(ref: string): boolean { return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(ref); }
function answer_path(id: string): string { return '/api/v1/learning/questions/' + id + '/answer'; }
function parse_answer(body: string, questionId: string, needManifest: boolean): SolutionDetail {
  const data: SolutionDetail = JSON.parse(body) as SolutionDetail;
  if (!data || data.questionId !== questionId ||
    (data.state !== AnswerState.NOT_STARTED && data.state !== AnswerState.IN_PROGRESS && data.state !== AnswerState.COMPLETED)) {
    throw new Error('作答响应格式无效');
  }
  if (data.state === AnswerState.NOT_STARTED) {
    if (data.fileRef !== null || data.manifest !== null || data.savedAt !== null || data.completedAt !== null) { throw new Error('空作答响应格式无效'); }
  } else {
    if (typeof data.fileRef !== 'string' || !ref_valid(data.fileRef) || typeof data.savedAt !== 'string') {
      throw new Error('作答文件引用无效');
    }
    if ((data.state === AnswerState.IN_PROGRESS && data.completedAt !== null) ||
      (data.state === AnswerState.COMPLETED && typeof data.completedAt !== 'string')) { throw new Error('完成时间无效'); }
    if (needManifest && (!data.manifest || !data.manifest.questionSnapshot || data.manifest.questionSnapshot.questionId !== questionId)) { throw new Error('完整作答缺少文档'); }
    if (data.manifest) {
      validate_answer_manifest(data.manifest);
      if (!Array.isArray(data.manifest.blocks) || data.manifest.blocks.length === 0 || data.manifest.blocks.length > 256) {
        throw new Error('完整解答块无效');
      }
    }
  }
  if (data.manifest === undefined) { data.manifest = null; }
  return data;
}
function request_error(result: AuthResult<string>): AuthError {
  return result.error ?? AuthError.protocol_failure('作答响应无效');
}

/** 真实当前作答与鉴权文件缓存。不提供自动覆盖、自动重试或匿名文件 URL。 */
export class SolutionApi {
  private transport: AuthApi;
  constructor(baseUrl: string = DEFAULT_DEV_BASE_URL) { this.transport = new AuthApi(baseUrl); }
  static create_default(): SolutionApi { return new SolutionApi(); }

  async getAnswer(token: string, questionId: string): Promise<AuthResult<SolutionDetail>> {
    if (!id_valid(questionId)) { return AuthResult.failure<SolutionDetail>(AuthError.local_validation('题号无效', {})); }
    const result: AuthResult<string> = await this.transport.request_resource(http.RequestMethod.GET, answer_path(questionId), token);
    if (!result.isSuccess || result.data === null) { return AuthResult.failure<SolutionDetail>(request_error(result)); }
    try { return AuthResult.success<SolutionDetail>(parse_answer(result.data, questionId, true)); }
    catch (error) { return AuthResult.failure<SolutionDetail>(AuthError.protocol_failure('完整作答响应格式无效')); }
  }

  async saveAnswer(token: string, questionId: string, upload: FrozenAnswerUpload): Promise<AuthResult<SolutionDetail>> {
    if (!id_valid(questionId)) { return AuthResult.failure<SolutionDetail>(AuthError.local_validation('题号无效', {})); }
    const bytes: Uint8Array = canvas_utf8(upload.manifestJson);
    if (bytes.length > 8388608) { return AuthResult.failure<SolutionDetail>(AuthError.local_validation('作答文档超过8MiB限制', {})); }
    try {
      const manifest: AnswerManifest = JSON.parse(upload.manifestJson) as AnswerManifest;
      validate_answer_manifest(manifest);
      const descriptors: CanvasImageBlock[] = manifest.blocks.concat(manifest.document.questionLayout.rasterBlocks);
      if (descriptors.length === 0 || descriptors.length > 256 || descriptors.length !== upload.files.length) {
        throw new Error('保存包图块数无效');
      }
      const names: Set<string> = new Set<string>();
      let total: number = 0;
      for (let i: number = 0; i < descriptors.length; i++) {
        const descriptor: CanvasImageBlock = descriptors[i];
        const file: CanvasLocalFile | undefined = upload.files.find((item) => item.partName === descriptor.partName);
        if (!file || names.has(descriptor.partName)) { throw new Error('保存包图块对应无效'); }
        names.add(descriptor.partName);
        const png: Uint8Array = canvas_read(file.filePath);
        total += png.length;
        if (total > 67108864 || await canvas_sha256(png) !== descriptor.sha256) { throw new Error('保存包字节已变化'); }
      }
    } catch (error) {
      return AuthResult.failure<SolutionDetail>(AuthError.local_validation('原保存包校验失败，本机草稿已保留', {}));
    }
    const parts: http.MultiFormData[] = [{ name: 'manifest', contentType: 'application/json',
      remoteFileName: 'manifest.json', data: bytes.buffer as ArrayBuffer }];
    for (let i: number = 0; i < upload.files.length; i++) {
      parts.push({ name: upload.files[i].partName, contentType: 'image/png', remoteFileName: upload.files[i].partName + '.png',
        filePath: upload.files[i].filePath });
    }
    const result: AuthResult<string> = await this.transport.request_multipart(answer_path(questionId), token, parts);
    if (!result.isSuccess || result.data === null) { return AuthResult.failure<SolutionDetail>(request_error(result)); }
    try { return AuthResult.success<SolutionDetail>(parse_answer(result.data, questionId, false)); }
    catch (error) { return AuthResult.failure<SolutionDetail>(AuthError.protocol_failure('作答保存回执无效，请用原包重试')); }
  }

  async setAnswerState(token: string, questionId: string, fileRef: string, state: AnswerState): Promise<AuthResult<SolutionDetail>> {
    if (!id_valid(questionId) || !ref_valid(fileRef) || state === AnswerState.NOT_STARTED) {
      return AuthResult.failure<SolutionDetail>(AuthError.local_validation('作答状态参数无效', {}));
    }
    const payload: Record<string, string> = { state: state, fileRef: fileRef };
    const result: AuthResult<string> = await this.transport.request_resource(http.RequestMethod.PATCH,
      '/api/v1/learning/questions/' + questionId + '/answer-state', token, JSON.stringify(payload));
    if (!result.isSuccess || result.data === null) { return AuthResult.failure<SolutionDetail>(request_error(result)); }
    try { return AuthResult.success<SolutionDetail>(parse_answer(result.data, questionId, false)); }
    catch (error) { return AuthResult.failure<SolutionDetail>(AuthError.protocol_failure('作答状态响应无效')); }
  }

  async downloadBlock(token: string, accountId: string, questionId: string, fileRef: string,
    block: CanvasImageBlock, filesDir: string, questionRaster: boolean = false): Promise<AuthResult<string>> {
    const currentUser: UserProfile | null = authSession.read_user();
    if (!authSession.matches_access_token(token) || currentUser === null || currentUser.accountId !== accountId) {
      return AuthResult.failure<string>(AuthError.local_validation('登录状态已变化', {}));
    }
    if (!id_valid(accountId) || !id_valid(questionId) || !ref_valid(fileRef) || !Number.isInteger(block.index) ||
      block.index < 0 || !/^[0-9a-f]{64}$/.test(block.sha256)) {
      return AuthResult.failure<string>(AuthError.protocol_failure('解答图块描述无效'));
    }
    const path: string = filesDir + '/m2-answers/' + accountId + '/' + questionId + '/' + fileRef + '/' +
      (questionRaster ? 'question_' : 'chunk_') + block.index.toString() + '.png';
    try {
      if (canvas_exists(path) && await canvas_sha256(canvas_read(path)) === block.sha256) {
        // 缓存只能复用当前受保护引用，旧引用不能绕过服务端的 current-fileRef 语义。
        const latest: AuthResult<SolutionDetail> = await this.getAnswer(token, questionId);
        if (!latest.isSuccess || latest.data === null) {
          return AuthResult.failure<string>(latest.error ?? AuthError.protocol_failure('当前解答校验失败'));
        }
        if (latest.data.fileRef !== fileRef || latest.data.manifest === null) {
          return AuthResult.failure<string>(AuthError.http_failure(404, null));
        }
        const currentBlocks: CanvasImageBlock[] = questionRaster ?
          latest.data.manifest.document.questionLayout.rasterBlocks : latest.data.manifest.blocks;
        const current: CanvasImageBlock | undefined = currentBlocks.find((item) => item.index === block.index);
        if (!current || current.sha256 !== block.sha256) {
          return AuthResult.failure<string>(AuthError.protocol_failure('当前解答图块已变化，请刷新'));
        }
        if (!authSession.matches_access_token(token) || !authSession.has_valid_session()) {
          return AuthResult.failure<string>(AuthError.local_validation('登录状态已变化', {}));
        }
        return AuthResult.success<string>('file://' + path);
      }
      const route: string = answer_path(questionId) + '/files/' + fileRef + '/' +
        (questionRaster ? 'question-blocks/' : 'blocks/') + block.index.toString();
      const result: AuthResult<ArrayBuffer> = await this.transport.request_binary(route, token);
      if (!result.isSuccess || result.data === null) {
        return AuthResult.failure<string>(result.error ?? AuthError.protocol_failure('解答图块缺失'));
      }
      const bytes: Uint8Array = new Uint8Array(result.data);
      if (bytes.length > 16777216 || bytes.length < 8 || bytes[0] !== 137 || bytes[1] !== 80 ||
        await canvas_sha256(bytes) !== block.sha256) {
        return AuthResult.failure<string>(AuthError.protocol_failure('解答图块完整性校验失败'));
      }
      if (!authSession.matches_access_token(token) || !authSession.has_valid_session()) {
        return AuthResult.failure<string>(AuthError.local_validation('登录状态已变化', {}));
      }
      canvas_write(path, bytes);
      return AuthResult.success<string>('file://' + path);
    } catch (error) {
      return AuthResult.failure<string>(AuthError.protocol_failure('解答文件读取或缓存失败，请重试'));
    }
  }
}
