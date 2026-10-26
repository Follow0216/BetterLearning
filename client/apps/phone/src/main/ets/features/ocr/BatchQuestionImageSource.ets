import { fileIo } from '@kit.CoreFileKit';
import { image } from '@kit.ImageKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { BatchOcrImage, canvas_write } from 'common';

const ownedImages: Map<string, string> = new Map<string, string>();
/** 相册URI只读；用户选择顺序即上传顺序，批量入口不复用会销毁原图的单题裁剪helper。 */
export async function selectBatchImages(context: Context, maximum: number): Promise<string[]> {
  if (!context || maximum < 1 || maximum > 4) { throw new Error('图片选择数量无效'); }
  const options: photoAccessHelper.PhotoSelectOptions = new photoAccessHelper.PhotoSelectOptions();
  options.MIMEType = photoAccessHelper.PhotoViewMIMETypes.IMAGE_TYPE;
  options.maxSelectNumber = maximum;
  const result: photoAccessHelper.PhotoSelectResult = await new photoAccessHelper.PhotoViewPicker().select(options);
  if (result.photoUris.length > maximum) { throw new Error('每批最多4张图片'); }
  return result.photoUris;
}
function dimensions(width: number, height: number): boolean {
  return Number.isSafeInteger(width) && Number.isSafeInteger(height) && width > 0 && height > 0 &&
    width <= 16000 && height <= 16000 && width * height <= 16000000;
}
/** 校验真实内容及解码尺寸，原样复制字节；绝不缩小长图或重编码丢失信息。 */
export async function copyBatchImage(context: Context, uri: string, isCurrent: () => boolean): Promise<BatchOcrImage> {
  const file: fileIo.File = fileIo.openSync(uri, fileIo.OpenMode.READ_ONLY);
  let bytes: Uint8Array;
  try {
    const size: number = fileIo.statSync(file.fd).size;
    if (!Number.isSafeInteger(size) || size <= 0 || size > 16777216) { throw new Error('单图须不超过16 MiB'); }
    bytes = new Uint8Array(size);
    let offset: number = 0;
    while (offset < size) {
      const part: ArrayBuffer = new ArrayBuffer(size - offset);
      const count: number = fileIo.readSync(file.fd, part);
      if (count <= 0) { throw new Error('图片读取不完整'); }
      bytes.set(new Uint8Array(part, 0, count), offset);
      offset += count;
    }
  } finally { fileIo.closeSync(file); }
  const png: boolean = bytes.length >= 8 && bytes[0] === 137 && bytes[1] === 80 && bytes[2] === 78 && bytes[3] === 71 &&
    bytes[4] === 13 && bytes[5] === 10 && bytes[6] === 26 && bytes[7] === 10;
  const jpeg: boolean = bytes.length >= 4 && bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255;
  if (!png && !jpeg) { throw new Error('仅支持真实PNG或JPEG图片'); }
  const data: ArrayBuffer = bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer;
  const source: image.ImageSource = image.createImageSource(data);
  let width: number = 0;
  let height: number = 0;
  try {
    const info: image.ImageInfo = await source.getImageInfo();
    if (!isCurrent()) { throw new Error('图片选择已失效'); }
    width = info.size.width;
    height = info.size.height;
    if (!dimensions(width, height)) { throw new Error('单图须不超过1600万像素，最长边16000像素'); }
    const map: image.PixelMap = await source.createPixelMap();
    try {
      if (!isCurrent()) { throw new Error('图片选择已失效'); }
      const actual: image.ImageInfo = await map.getImageInfo();
      if (!isCurrent()) { throw new Error('图片选择已失效'); }
      if (!dimensions(actual.size.width, actual.size.height) || actual.size.width !== width || actual.size.height !== height) {
        throw new Error('图片实际尺寸不符合限制');
      }
    } finally { await map.release(); }
    if (!isCurrent()) { throw new Error('图片选择已失效'); }
  } finally { await source.release(); }
  if (!isCurrent()) { throw new Error('图片选择已失效'); }
  const directory: string = fileIo.mkdtempSync(context.cacheDir + '/ocr-batch-XXXXXX');
  const path: string = directory + (png ? '/original.png' : '/original.jpg');
  ownedImages.set(path, directory);
  try {
    canvas_write(path, bytes);
    return { path: path, contentType: png ? 'image/png' : 'image/jpeg', width: width, height: height, byteLength: bytes.length };
  } catch (error) {
    discardBatchImage(path);
    throw error as Error;
  }
}
/** 只能删除本helper登记的独立cache目录，永不删除图库/调用者路径。 */
export function discardBatchImage(path: string): void {
  const directory: string | undefined = ownedImages.get(path);
  if (directory === undefined) { return; }
  if (fileIo.accessSync(directory)) { fileIo.rmdirSync(directory); }
  ownedImages.delete(path);
}
