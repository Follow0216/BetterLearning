import { http } from '@kit.NetworkKit';
import { image } from '@kit.ImageKit';
import { AuthApi, DEFAULT_DEV_BASE_URL } from '../auth/AuthApi';
import { AuthError, AuthResult } from '../auth/AuthModels';
import { canvas_read } from '../canvas/CanvasFiles';
import { OcrResult } from './TutorModels';

/** 只识别用户已裁剪的本机 PNG/JPEG，不创建题目、不自动重试。 */
export class OcrApi {
  private transport: AuthApi;
  constructor(baseUrl: string = DEFAULT_DEV_BASE_URL) { this.transport = new AuthApi(baseUrl); }
  static create_default(): OcrApi { return new OcrApi(); }
  async recognize(token: string, imagePath: string): Promise<AuthResult<OcrResult>> {
    try {
      const path: string = imagePath.startsWith('file://') ? imagePath.substring(7) : imagePath;
      if (!path.startsWith('/') || path.includes('/../')) { throw new Error('请选择本机裁剪图片'); }
      const bytes: Uint8Array = canvas_read(path);
      const png: boolean = bytes.length >= 8 && bytes[0] === 137 && bytes[1] === 80 && bytes[2] === 78 && bytes[3] === 71 &&
        bytes[4] === 13 && bytes[5] === 10 && bytes[6] === 26 && bytes[7] === 10;
      const jpeg: boolean = bytes.length >= 4 && bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255;
      if (!png && !jpeg) { throw new Error('图片必须为PNG或JPEG'); }
      const data: ArrayBuffer = bytes.buffer.slice(bytes.byteOffset,bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
      const source: image.ImageSource = image.createImageSource(data);
      try {
        const info: image.ImageInfo = await source.getImageInfo();
        if (info.size.width <= 0 || info.size.height <= 0 || info.size.width * info.size.height > 16777216) { throw new Error('图片像素超过限制'); }
        const map: image.PixelMap = await source.createPixelMap();
        await map.release();
      } finally { await source.release(); }
      const parts: http.MultiFormData[] = [{ name: 'image', contentType: png ? 'image/png' : 'image/jpeg',
        remoteFileName: png ? 'image.png' : 'image.jpg', data: data }];
      const result: AuthResult<string> = await this.transport.request_multipart('/api/v1/learning/ocr',token,parts,150000,'识别连接失败，请保留图片后重试');
      if (!result.isSuccess || result.data === null) { return AuthResult.failure<OcrResult>(result.error ?? AuthError.protocol_failure('识别响应无效')); }
      try {
        const payload: OcrResult = JSON.parse(result.data) as OcrResult;
        if (!payload || typeof payload.bodyMarkdown !== 'string' || payload.bodyMarkdown.trim().length === 0 || payload.bodyMarkdown.length > 20000) {
          throw new Error('识别结果无效');
        }
        return AuthResult.success<OcrResult>(payload);
      } catch (error) { return AuthResult.failure<OcrResult>(AuthError.protocol_failure('识别结果格式无效')); }
    } catch (error) { return AuthResult.failure<OcrResult>(AuthError.local_validation('图片读取或识别结果校验失败，请保留图片后重试', {})); }
  }
}
