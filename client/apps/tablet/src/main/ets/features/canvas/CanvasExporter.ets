import { image } from '@kit.ImageKit';
import { LengthMetricsUnit } from '@kit.ArkUI';
import { util } from '@kit.ArkTS';
import {
  AnswerManifest, CanvasEditableDocument, CanvasExtent, CanvasImageBlock, CanvasLocalFile,
  CanvasRect, CanvasStroke, FrozenAnswerUpload, canvas_extent, canvas_grid, clone_canvas_document,
  canvas_image_bytes, canvas_png, canvas_read, canvas_sha256, canvas_utf8, canvas_write
} from 'common';
import { canvas_intersects, draw_canvas_stroke, stroke_bounds } from './CanvasPainter';

function clean_block(block: CanvasImageBlock): CanvasImageBlock {
  return { index: block.index, partName: block.partName, x: block.x, y: block.y, width: block.width,
    height: block.height, pixelWidth: block.pixelWidth, pixelHeight: block.pixelHeight, sha256: block.sha256 };
}
export async function export_canvas(document: CanvasEditableDocument, rasterFiles: CanvasLocalFile[],
  baseFileRef: string | null, directory: string, active: () => boolean): Promise<FrozenAnswerUpload> {
  const doc: CanvasEditableDocument = clone_canvas_document(document);
  doc.questionLayout.rasterBlocks = doc.questionLayout.rasterBlocks.map((block: CanvasImageBlock) => clean_block(block));
  const extent: CanvasExtent = canvas_extent(doc);
  const blocks: CanvasImageBlock[] = canvas_grid(extent);
  const files: CanvasLocalFile[] = [];
  const requestId: string = util.generateRandomUUID(true).toLowerCase();
  let totalBytes: number = 0; let totalPixels: number = extent.width * extent.height;
  for (let i: number = 0; i < doc.questionLayout.rasterBlocks.length; i++) {
    const block: CanvasImageBlock = doc.questionLayout.rasterBlocks[i];
    const file: CanvasLocalFile | undefined = rasterFiles.find((item: CanvasLocalFile) => item.partName === block.partName);
    if (file === undefined) { throw new Error('固定题面底图缺失，未保存不完整作答'); }
    const bytes: Uint8Array = canvas_read(file.filePath);
    if (await canvas_sha256(bytes) !== block.sha256) { throw new Error('固定题面底图校验失败'); }
    totalBytes += bytes.length; totalPixels += block.pixelWidth * block.pixelHeight;
    if (!active()) { throw new Error('画布已切换，保存已停止'); }
    const frozenPath: string = directory + '/uploads/' + requestId + '/' + block.partName + '.png';
    canvas_write(frozenPath,bytes);
    files.push({ partName: file.partName, filePath: frozenPath });
  }
  if (totalPixels > 64000000 || files.length + blocks.length > 256) { throw new Error('完整解答超过图块或像素上限，未裁切保存'); }
  const bounds: CanvasRect[] = doc.strokes.map((stroke: CanvasStroke) => stroke_bounds(stroke));
  for (let i: number = 0; i < blocks.length; i++) {
    if (!active()) { throw new Error('画布已切换，保存已停止'); }
    const block: CanvasImageBlock = blocks[i];
    const target: CanvasRect = { x: extent.originX + block.x, y: extent.originY + block.y, width: block.width, height: block.height };
    const canvas: OffscreenCanvas = new OffscreenCanvas(block.pixelWidth, block.pixelHeight, LengthMetricsUnit.PX);
    const context: OffscreenCanvasRenderingContext2D = canvas.getContext('2d');
    context.fillStyle = '#FFFFFF'; context.fillRect(0,0,block.pixelWidth,block.pixelHeight);
    context.save(); context.translate(-target.x,-target.y);
    for (let j: number = 0; j < doc.questionLayout.rasterBlocks.length; j++) {
      const raster: CanvasImageBlock = doc.questionLayout.rasterBlocks[j];
      if (!canvas_intersects(target,raster)) { continue; }
      const file: CanvasLocalFile | undefined = files.find((item: CanvasLocalFile) => item.partName === raster.partName);
      if (file === undefined) { throw new Error('固定题面底图缺失'); }
      const bytes: Uint8Array = canvas_read(file.filePath);
      if (await canvas_sha256(bytes) !== raster.sha256) { throw new Error('冻结题面底图校验失败'); }
      const map: image.PixelMap = await canvas_image_bytes(bytes);
      try {
        if (!active()) { throw new Error('画布已切换，保存已停止'); }
        const info: image.ImageInfo = await map.getImageInfo();
        if (info.size.width !== raster.pixelWidth || info.size.height !== raster.pixelHeight) { throw new Error('底图实际尺寸不符'); }
        context.drawImage(map,raster.x,raster.y,raster.width,raster.height); } finally { await map.release(); }
    }
    for (let j: number = 0; j < doc.strokes.length; j++) {
      if (canvas_intersects(target,bounds[j])) { draw_canvas_stroke(context,doc.strokes[j]); }
    }
    context.restore();
    const map: image.PixelMap = context.getPixelMap(0,0,block.pixelWidth,block.pixelHeight);
    let png: Uint8Array;
    try {
      const info: image.ImageInfo = await map.getImageInfo();
      if (info.size.width !== block.pixelWidth || info.size.height !== block.pixelHeight) { throw new Error('导出像素尺寸无效'); }
      png = await canvas_png(map);
    } finally { await map.release(); }
    totalBytes += png.length;
    if (totalBytes > 67108864) { throw new Error('完整作答PNG总量超过64MiB，未部分保存'); }
    block.sha256 = await canvas_sha256(png);
    const path: string = directory + '/uploads/' + requestId + '/' + block.partName + '.png';
    if (!active()) { throw new Error('画布已切换，保存已停止'); }
    canvas_write(path,png); files.push({ partName: block.partName, filePath: path });
  }
  const manifest: AnswerManifest = { schemaVersion: 1, saveRequestId: requestId, baseFileRef: baseFileRef,
    canvas: extent, document: doc, blocks: blocks };
  const json: string = JSON.stringify(manifest);
  if (canvas_utf8(json).length > 8388608) { throw new Error('完整作答JSON超过8MiB'); }
  if (!active()) { throw new Error('画布已切换，保存已停止'); }
  canvas_write(directory + '/uploads/' + requestId + '/manifest.json',canvas_utf8(json));
  return { manifestJson: json, files: files };
}
