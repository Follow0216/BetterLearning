import { http } from '@kit.NetworkKit';
import { image } from '@kit.ImageKit';
import { AuthApi, DEFAULT_DEV_BASE_URL } from '../auth/AuthApi';
import { AuthError, AuthResult } from '../auth/AuthModels';
import { authSession } from '../auth/AuthSession';
import { canvas_read } from '../canvas/CanvasFiles';
import { BatchOcrImage, BatchOcrJob, batch_ocr_uuid, parse_batch_ocr_job } from './BatchOcrModels';

const PATH: string = '/api/v1/learning/ocr/batches/';
/** POST仅由显式操作触发；结果未知后只GET恢复，明确未受理由上层提供手动开始，绝不自动重发。 */
export class BatchOcrApi {
  private transport: AuthApi;
  constructor(baseUrl: string = DEFAULT_DEV_BASE_URL) { this.transport = new AuthApi(baseUrl); }
  static create_default(): BatchOcrApi { return new BatchOcrApi(); }

  async start(token: string, requestId: string, images: BatchOcrImage[], onStage: (stage: string) => void = () => {},
    isCurrent: () => boolean = () => true): Promise<AuthResult<BatchOcrJob>> {
    if (!isCurrent() || !authSession.matches_access_token(token) || !batch_ocr_uuid(requestId) || images.length < 1 || images.length > 4) { return this.invalid(); }
    onStage('PREPARING');
    const parts: http.MultiFormData[] = [];
    let total: number = 0;
    try {
      for (let i: number = 0; i < images.length; i++) {
        if (!isCurrent() || !authSession.matches_access_token(token)) { return this.invalid(); }
        const input: BatchOcrImage = images[i];
        if (!input.path.startsWith('/') || input.path.includes('/../')) { return this.invalid(); }
        const bytes: Uint8Array = canvas_read(input.path);
        total += bytes.byteLength;
        if (bytes.byteLength < 4 || bytes.byteLength > 16777216 || total > 33554432) { return this.invalid(); }
        const png: boolean = bytes.byteLength >= 8 && bytes[0] === 137 && bytes[1] === 80 && bytes[2] === 78 && bytes[3] === 71 &&
          bytes[4] === 13 && bytes[5] === 10 && bytes[6] === 26 && bytes[7] === 10;
        const jpeg: boolean = bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255;
        if ((!png && !jpeg) || input.contentType !== (png ? 'image/png' : 'image/jpeg') || input.byteLength !== bytes.byteLength) { return this.invalid(); }
        const data: ArrayBuffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
        const source: image.ImageSource = image.createImageSource(data);
        try {
          const info: image.ImageInfo = await source.getImageInfo();
          if (!isCurrent() || !authSession.matches_access_token(token)) { return this.invalid(); }
          if (info.size.width !== input.width || info.size.height !== input.height || input.width <= 0 || input.height <= 0 ||
            input.width > 16000 || input.height > 16000 || input.width * input.height > 16000000) { return this.invalid(); }
          const map: image.PixelMap = await source.createPixelMap();
          const stillCurrent: boolean = isCurrent() && authSession.matches_access_token(token);
          await map.release();
          if (!stillCurrent || !isCurrent() || !authSession.matches_access_token(token)) { return this.invalid(); }
        } finally { await source.release(); }
        if (!isCurrent() || !authSession.matches_access_token(token)) { return this.invalid(); }
        parts.push({ name: 'image' + i.toString(), contentType: input.contentType,
          remoteFileName: 'image' + i.toString() + (png ? '.png' : '.jpg'), data: data });
      }
      if (!isCurrent() || !authSession.matches_access_token(token)) { return this.invalid(); }
      onStage('UPLOADING');
      if (!isCurrent() || !authSession.matches_access_token(token)) { return this.invalid(); }
      return this.parse(await this.transport.request_multipart(PATH + requestId, token, parts, 30000,
        '识别提交响应未知，请查询原批次进度；不会自动重新识别'), requestId);
    } catch (error) { return this.invalid(); }
  }
  async recover(token: string, requestId: string): Promise<AuthResult<BatchOcrJob>> {
    if (!batch_ocr_uuid(requestId)) { return this.invalid(); }
    return this.parse(await this.transport.request_resource(http.RequestMethod.GET, PATH + requestId, token), requestId);
  }
  async cancel(token: string, requestId: string): Promise<AuthResult<BatchOcrJob>> {
    if (!batch_ocr_uuid(requestId)) { return this.invalid(); }
    return this.parse(await this.transport.request_resource(http.RequestMethod.DELETE, PATH + requestId, token), requestId);
  }
  private invalid(): AuthResult<BatchOcrJob> {
    return AuthResult.failure<BatchOcrJob>(AuthError.local_validation('图片或登录状态已变化，请保留材料后检查', {}));
  }
  private parse(result: AuthResult<string>, requestId: string): AuthResult<BatchOcrJob> {
    if (!result.isSuccess || result.data === null) {
      return AuthResult.failure<BatchOcrJob>(result.error ?? AuthError.protocol_failure('批量识别响应无效'));
    }
    const job: BatchOcrJob | null = parse_batch_ocr_job(result.data, requestId);
    return job === null ? AuthResult.failure<BatchOcrJob>(AuthError.protocol_failure('批量识别结果格式无效，请查询原批次')) :
      AuthResult.success<BatchOcrJob>(job);
  }
}
