import { fileIo } from '@kit.CoreFileKit';
import { util } from '@kit.ArkTS';
import { cryptoFramework } from '@kit.CryptoArchitectureKit';

export function canvas_utf8(text: string): Uint8Array { return util.TextEncoder.create().encodeInto(text); }
export function canvas_text(bytes: Uint8Array): string { return util.TextDecoder.create('utf-8', { fatal: true }).decodeToString(bytes); }
export function canvas_exists(path: string): boolean { return fileIo.accessSync(path); }
export function canvas_mkdir(path: string): void {
  if (!canvas_exists(path)) { fileIo.mkdirSync(path, true); }
}
export function canvas_read(path: string, maxBytes: number = 16777216): Uint8Array {
  const size: number = fileIo.statSync(path).size;
  if (!Number.isSafeInteger(size) || size < 0 || size > maxBytes) { throw new Error('本机文件大小无效'); }
  const data: Uint8Array = new Uint8Array(size);
  const file: fileIo.File = fileIo.openSync(path, fileIo.OpenMode.READ_ONLY);
  try {
    let offset: number = 0;
    while (offset < size) {
      const buffer: ArrayBuffer = new ArrayBuffer(size - offset);
      const count: number = fileIo.readSync(file.fd, buffer);
      if (count <= 0) { throw new Error('本机文件读取不完整'); }
      data.set(new Uint8Array(buffer, 0, count), offset); offset += count;
    }
  } finally { fileIo.closeSync(file); }
  return data;
}
/** 文件按原始字节写完并同步后才发布，不能将 put/排队当作已保存。 */
export function canvas_write(path: string, bytes: Uint8Array): void {
  canvas_mkdir(path.substring(0, path.lastIndexOf('/')));
  const temporary: string = path + '.pending-' + util.generateRandomUUID(true);
  const file: fileIo.File = fileIo.openSync(temporary,
    fileIo.OpenMode.WRITE_ONLY | fileIo.OpenMode.CREATE | fileIo.OpenMode.TRUNC);
  let complete: boolean = false;
  try {
    let offset: number = 0;
    while (offset < bytes.byteLength) {
      const remaining: Uint8Array = bytes.slice(offset);
      const count: number = fileIo.writeSync(file.fd, remaining.buffer as ArrayBuffer);
      if (count <= 0) { throw new Error('本机文件写入不完整'); }
      offset += count;
    }
    fileIo.fsyncSync(file.fd); complete = true;
  } finally {
    fileIo.closeSync(file);
    if (!complete && canvas_exists(temporary)) { fileIo.unlinkSync(temporary); }
  }
  try { fileIo.renameSync(temporary, path); }
  catch (error) {
    if (canvas_exists(temporary)) { fileIo.unlinkSync(temporary); }
    throw new Error('本机文件发布失败');
  }
}
export async function canvas_sha256(bytes: Uint8Array): Promise<string> {
  const digest: cryptoFramework.Md = cryptoFramework.createMd('SHA256');
  await digest.update({ data: bytes });
  const result: cryptoFramework.DataBlob = await digest.digest();
  let hex: string = '';
  for (let i: number = 0; i < result.data.length; i++) { hex += result.data[i].toString(16).padStart(2, '0'); }
  return hex;
}
