import { image } from '@kit.ImageKit';

export async function canvas_png(map: image.PixelMap): Promise<Uint8Array> {
  const packer: image.ImagePacker = image.createImagePacker();
  try {
    const data: ArrayBuffer = await packer.packToData(map,
      { format: 'image/png', quality: 100, bufferSize: 16777216 });
    if (data.byteLength > 16777216) { throw new Error('单个解答图块超过16MiB'); }
    return new Uint8Array(data);
  } finally { await packer.release(); }
}

async function decode_canvas_image(source: image.ImageSource): Promise<image.PixelMap> {
  try {
    const info: image.ImageInfo = await source.getImageInfo();
    if (info.size.width <= 0 || info.size.height <= 0 || info.size.width > 16384 || info.size.height > 16384 ||
      info.size.width * info.size.height > 16777216) { throw new Error('图块像素超过限制'); }
    const map: image.PixelMap = await source.createPixelMap();
    try {
      const actual: image.ImageInfo = await map.getImageInfo();
      if (actual.size.width !== info.size.width || actual.size.height !== info.size.height) { throw new Error('图块解码尺寸变化'); }
      return map;
    } catch (error) { await map.release(); throw error; }
  } finally { await source.release(); }
}
export async function canvas_image(path: string): Promise<image.PixelMap> {
  return decode_canvas_image(image.createImageSource(path.startsWith('file://') ? path.substring(7) : path));
}
export async function canvas_image_bytes(bytes: Uint8Array): Promise<image.PixelMap> {
  return decode_canvas_image(image.createImageSource(bytes.buffer.slice(bytes.byteOffset,bytes.byteOffset + bytes.byteLength) as ArrayBuffer));
}
