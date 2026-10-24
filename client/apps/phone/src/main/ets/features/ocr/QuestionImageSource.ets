import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { camera, cameraPicker } from '@kit.CameraKit';
import { fileIo, fileUri } from '@kit.CoreFileKit';
import { image } from '@kit.ImageKit';
import { photoAccessHelper } from '@kit.MediaLibraryKit';
import { canvas_write } from 'common';

const CAMERA_LOG_DOMAIN: number = 0xB120;
const CAMERA_LOG_TAG: string = 'BLImageSource';

const MAX_IMAGE_BYTES: number = 16 * 1024 * 1024;
const MAX_PIXELS: number = 16 * 1024 * 1024;
const pendingPhotos: Map<string, string> = new Map<string, string>();
const pendingCrops: Map<string, string> = new Map<string, string>();

// Rect coordinates are integer pixels of the unscaled PixelMap returned by loadPreview.
export interface PixelRect {
  x: number;
  y: number;
  width: number;
  height: number;
}

function validSize(width: number, height: number): boolean {
  return Number.isFinite(width) && Number.isFinite(height) &&
    Number.isInteger(width) && Number.isInteger(height) &&
    width > 0 && height > 0 && width * height <= MAX_PIXELS;
}

function pathExists(path: string): boolean {
  try {
    return fileIo.accessSync(path);
  } catch (error) {
    if ((error as BusinessError).code === 13900002) {
      return false;
    }
    throw error as Error;
  }
}

function clearPhoto(uri: string): void {
  const directory: string | undefined = pendingPhotos.get(uri);
  if (directory === undefined) {
    return;
  }
  const path: string = directory + '/photo.jpg';
  if (pathExists(path)) {
    fileIo.unlinkSync(path);
  }
  if (pathExists(directory)) {
    fileIo.rmdirSync(directory);
  }
  pendingPhotos.delete(uri);
}

// Call if a camera URI is abandoned before loadPreview. Gallery URIs are left alone.
export function discardPendingPhoto(uri: string): void {
  clearPhoto(uri);
}

export async function selectImage(context: Context): Promise<string | undefined> {
  if (!context) {
    throw new Error('页面上下文不可用');
  }
  const options: photoAccessHelper.PhotoSelectOptions = new photoAccessHelper.PhotoSelectOptions();
  options.MIMEType = photoAccessHelper.PhotoViewMIMETypes.IMAGE_TYPE;
  options.maxSelectNumber = 1;
  const picker: photoAccessHelper.PhotoViewPicker = new photoAccessHelper.PhotoViewPicker();
  const result: photoAccessHelper.PhotoSelectResult = await picker.select(options);
  return result.photoUris.length === 0 ? undefined : result.photoUris[0];
}

function cameraFailure(stage: string, error: Error): void {
  const nativeCode: number = error === null || error === undefined ? -1 : (error as BusinessError).code;
  const code: number = typeof nativeCode === 'number' && Number.isFinite(nativeCode) ? nativeCode : -1;
  const type: string = error instanceof TypeError ? 'TypeError' : error instanceof Error ? 'Error' : 'NativeError';
  hilog.error(CAMERA_LOG_DOMAIN, CAMERA_LOG_TAG, 'stage=%{public}s code=%{public}d type=%{public}s', stage, code, type);
}

export async function takePhoto(context: Context): Promise<string | undefined> {
  let directory: string = '';
  let path: string = '';
  let stage: string = 'context';
  let created: boolean = false;
  let accepted: boolean = false;
  try {
    hilog.info(CAMERA_LOG_DOMAIN, CAMERA_LOG_TAG, 'stage=context entry');
    stage = 'temp-directory';
    directory = fileIo.mkdtempSync(context.cacheDir + '/ocr-photo-XXXXXX');
    path = directory + '/photo.jpg';
    stage = 'create-file';
    const file: fileIo.File = fileIo.openSync(path, fileIo.OpenMode.READ_WRITE | fileIo.OpenMode.CREATE);
    created = true;
    stage = 'close-file';
    fileIo.closeSync(file);
    stage = 'file-uri';
    const uri: string = new fileUri.FileUri(path).toString();
    stage = 'profile-constructor';
    hilog.info(CAMERA_LOG_DOMAIN, CAMERA_LOG_TAG, 'stage=profile-constructor start');
    const profile: cameraPicker.PickerProfile = new cameraPicker.PickerProfile();
    stage = 'profile-fields';
    profile.cameraPosition = camera.CameraPosition.CAMERA_POSITION_BACK;
    profile.saveUri = uri;
    stage = 'pick-call';
    const pending: Promise<cameraPicker.PickerResult> = cameraPicker.pick(context,
      [cameraPicker.PickerMediaType.PHOTO], profile);
    hilog.info(CAMERA_LOG_DOMAIN, CAMERA_LOG_TAG, 'stage=pick-call returnType=%{public}s', typeof pending);
    const result: cameraPicker.PickerResult = await pending;
    stage = 'pick-result';
    const present: boolean = result !== null && result !== undefined;
    hilog.info(CAMERA_LOG_DOMAIN, CAMERA_LOG_TAG, 'stage=pick-result present=%{public}s code=%{public}d',
      present ? 'yes' : 'no', present ? result.resultCode : -1);
    if (result.resultCode !== 0 || result.resultUri.length === 0) {
      return undefined;
    }
    pendingPhotos.set(result.resultUri, directory);
    accepted = true;
    return result.resultUri;
  } catch (error) {
    cameraFailure(stage, error as Error);
    throw error as Error;
  } finally {
    if (!accepted && directory.length > 0) {
      try {
        if (created && pathExists(path)) {
          fileIo.unlinkSync(path);
        }
        if (pathExists(directory)) {
          fileIo.rmdirSync(directory);
        }
      } catch (cleanupError) {
        cameraFailure('cleanup', cleanupError as Error);
        throw cleanupError as Error;
      }
    }
  }
}

// The caller owns the returned PixelMap and must call await preview.release().
export async function loadPreview(uri: string): Promise<image.PixelMap> {
  if (uri.length === 0) {
    throw new Error('图片 URI 为空');
  }
  let file: fileIo.File | undefined;
  let source: image.ImageSource | undefined;
  let preview: image.PixelMap | undefined;
  try {
    try {
      file = fileIo.openSync(uri, fileIo.OpenMode.READ_ONLY);
      const bytes: number = fileIo.statSync(file.fd).size;
      if (!Number.isFinite(bytes) || bytes <= 0 || bytes > MAX_IMAGE_BYTES) {
        throw new Error('原图超过 16 MiB 或为空');
      }
      source = image.createImageSource(file.fd);
      const header: image.ImageInfo = await source.getImageInfo();
      if (!validSize(header.size.width, header.size.height)) {
        throw new Error('原图超过 16M 像素或尺寸无效');
      }
      preview = await source.createPixelMap({ editable: true });
      const actual: image.ImageInfo = await preview.getImageInfo();
      if (!validSize(actual.size.width, actual.size.height)) {
        throw new Error('解码图超过 16M 像素或尺寸无效');
      }
    } finally {
      try {
        if (source !== undefined) {
          await source.release();
        }
      } finally {
        try {
          if (file !== undefined) {
            fileIo.closeSync(file);
          }
        } finally {
          clearPhoto(uri);
        }
      }
    }
  } catch (error) {
    if (preview !== undefined) {
      await preview.release();
    }
    throw error as Error;
  }
  if (preview === undefined) {
    throw new Error('图片解码失败');
  }
  return preview;
}

// Returns a new PNG byte buffer. Do not mutate it while an OCR request is in flight.
export async function crop(preview: image.PixelMap, rect: PixelRect): Promise<Uint8Array> {
  const info: image.ImageInfo = await preview.getImageInfo();
  const width: number = info.size.width;
  const height: number = info.size.height;
  if (!validSize(width, height) || !validSize(rect.width, rect.height) ||
    !Number.isFinite(rect.x) || !Number.isFinite(rect.y) ||
    !Number.isInteger(rect.x) || !Number.isInteger(rect.y) ||
    rect.x < 0 || rect.y < 0 || rect.x + rect.width > width || rect.y + rect.height > height) {
    throw new Error('裁剪矩形必须位于原图像素范围内');
  }
  const copy: image.PixelMap = await preview.clone();
  try {
    if (!copy.isEditable) {
      throw new Error('裁剪副本不可编辑');
    }
    await copy.crop({ x: rect.x, y: rect.y,
      size: { width: rect.width, height: rect.height } });
    const packer: image.ImagePacker = image.createImagePacker();
    try {
      const png: ArrayBuffer = await packer.packToData(copy,
        { format: 'image/png', quality: 100, bufferSize: MAX_IMAGE_BYTES + 1 });
      if (png.byteLength === 0 || png.byteLength > MAX_IMAGE_BYTES) {
        throw new Error('裁剪 PNG 超过 16 MiB 或为空');
      }
      return new Uint8Array(png);
    } finally {
      await packer.release();
    }
  } finally {
    await copy.release();
  }
}

// Only paths returned by cropToFile are accepted; the whole unique directory is ours.
export function discardCroppedImage(path: string): void {
  const directory: string | undefined = pendingCrops.get(path);
  if (directory === undefined) {
    return;
  }
  if (pathExists(directory)) {
    fileIo.rmdirSync(directory);
  }
  pendingCrops.delete(path);
}

export class CroppedImageCleanupError extends Error {
  readonly path: string;

  constructor(path: string) {
    super('裁剪图写入失败，临时文件待清理');
    this.path = path;
  }
}

// Keep this file until confirmed M1 handoff or explicit user/account abandonment.
export async function cropToFile(context: Context, preview: image.PixelMap, rect: PixelRect): Promise<string> {
  const png: Uint8Array = await crop(preview, rect);
  const directory: string = fileIo.mkdtempSync(context.cacheDir + '/ocr-crop-XXXXXX');
  const path: string = directory + '/crop.png';
  pendingCrops.set(path, directory);
  try {
    canvas_write(path, png);
    return path;
  } catch (error) {
    try {
      discardCroppedImage(path);
    } catch (cleanupError) {
      throw new CroppedImageCleanupError(path);
    }
    throw error as Error;
  }
}
