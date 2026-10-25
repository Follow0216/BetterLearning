import {
  CanvasEditableDocument, CanvasExtent, CanvasImageBlock, CanvasPoint, CanvasRect,
  CanvasStroke, CanvasView, CANVAS_MAX_POINTS, CANVAS_MAX_STROKES, CANVAS_TILE_SIZE
} from './CanvasModels';
import { valid_canvas_pen, valid_canvas_pressure } from './CanvasPens';
import { AnswerManifest } from '../learning/SolutionModels';

export function clone_canvas_document(doc: CanvasEditableDocument): CanvasEditableDocument {
  return JSON.parse(JSON.stringify(doc)) as CanvasEditableDocument;
}

export function world_point(view: CanvasView, x: number, y: number): CanvasPoint {
  return { x: view.x + x / view.scale, y: view.y + y / view.scale };
}

export function zoom_canvas(view: CanvasView, factor: number, x: number, y: number): CanvasView {
  const anchor: CanvasPoint = world_point(view, x, y);
  const scale: number = Math.max(0.000001, Math.min(8, view.scale * factor));
  return { x: anchor.x - x / scale, y: anchor.y - y / scale, scale: scale };
}

export function fit_canvas(rect: CanvasRect, width: number, height: number): CanvasView {
  const scale: number = Math.max(0.000001, Math.min(1, (width - 48) / rect.width, (height - 48) / rect.height));
  return { x: rect.x - (width / scale - rect.width) / 2, y: rect.y - (height / scale - rect.height) / 2, scale: scale };
}

export function valid_world_number(value: number): boolean {
  return Number.isFinite(value) && Math.abs(value) <= 1000000;
}

function valid_rect(rect: CanvasRect): boolean {
  return !!rect && valid_world_number(rect.x) && valid_world_number(rect.y) &&
    Number.isFinite(rect.width) && rect.width > 0 && Number.isFinite(rect.height) && rect.height > 0 &&
    valid_world_number(rect.x + rect.width) && valid_world_number(rect.y + rect.height);
}
function contains_rect(outer: CanvasRect, inner: CanvasRect): boolean {
  return inner.x >= outer.x && inner.y >= outer.y && inner.x + inner.width <= outer.x + outer.width &&
    inner.y + inner.height <= outer.y + outer.height;
}
function check_grid(blocks: CanvasImageBlock[], x: number, y: number, width: number, height: number, prefix: string): number {
  const columns: number = Math.ceil(width / CANVAS_TILE_SIZE);
  const count: number = columns * Math.ceil(height / CANVAS_TILE_SIZE);
  if (!Array.isArray(blocks) || count < 1 || count > 256 || blocks.length !== count) { throw new Error('图块不完整'); }
  for (let i: number = 0; i < count; i++) {
    const block: CanvasImageBlock = blocks[i];
    const bx: number = (i % columns) * CANVAS_TILE_SIZE;
    const by: number = Math.floor(i / columns) * CANVAS_TILE_SIZE;
    const bw: number = Math.min(CANVAS_TILE_SIZE, width - bx);
    const bh: number = Math.min(CANVAS_TILE_SIZE, height - by);
    if (!block || block.index !== i || block.partName !== prefix + i.toString().padStart(4, '0') ||
      block.x !== x + bx || block.y !== y + by || block.width !== bw || block.height !== bh ||
      block.pixelWidth !== bw || block.pixelHeight !== bh || !/^[0-9a-f]{64}$/.test(block.sha256)) {
      throw new Error('图块网格或摘要无效');
    }
  }
  return width * height;
}
export function validate_canvas_document(doc: CanvasEditableDocument): void {
  if (!doc || doc.schemaVersion !== 1 || !doc.questionLayout || !Array.isArray(doc.strokes) ||
    doc.strokes.length > CANVAS_MAX_STROKES) { throw new Error('笔迹格式无效'); }
  const layout = doc.questionLayout;
  const bounds: CanvasRect = layout.bounds;
  if (!valid_rect(bounds) || !/^[0-9a-f]{64}$/.test(layout.questionBodySha256) ||
    !Number.isFinite(layout.contentWidth) || layout.contentWidth <= 0 || !Number.isFinite(layout.fontSize) || layout.fontSize <= 0 ||
    typeof layout.rendererId !== 'string' || layout.rendererId.trim().length === 0 || layout.rendererId.length > 64 ||
    !Array.isArray(layout.blocks) || layout.blocks.length === 0) { throw new Error('题面布局无效'); }
  const ids: Set<string> = new Set<string>();
  for (let i: number = 0; i < layout.blocks.length; i++) {
    const block = layout.blocks[i];
    if (!valid_rect(block) || !contains_rect(bounds, block) || typeof block.blockId !== 'string' ||
      block.blockId.length === 0 || ids.has(block.blockId)) { throw new Error('题面逻辑块无效'); }
    ids.add(block.blockId);
  }
  const left: number = Math.floor(bounds.x); const top: number = Math.floor(bounds.y);
  check_grid(layout.rasterBlocks, left, top, Math.ceil(bounds.x + bounds.width) - left,
    Math.ceil(bounds.y + bounds.height) - top, 'question_');
  ids.clear();
  let points: number = 0;
  for (let i: number = 0; i < doc.strokes.length; i++) {
    const stroke: CanvasStroke = doc.strokes[i];
    if (!stroke || typeof stroke.strokeId !== 'string' || stroke.strokeId.length === 0 || ids.has(stroke.strokeId) ||
      !/^#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/.test(stroke.color) ||
      !Number.isFinite(stroke.width) || stroke.width < 0.5 || stroke.width > 64 ||
      !valid_canvas_pen(stroke.penKind) || !Array.isArray(stroke.points) || stroke.points.length === 0) { throw new Error('笔迹数据无效'); }
    ids.add(stroke.strokeId);
    points += stroke.points.length;
    if (points > CANVAS_MAX_POINTS) { throw new Error('笔迹点数超出限制'); }
    for (let j: number = 0; j < stroke.points.length; j++) {
      if (!stroke.points[j] || !valid_world_number(stroke.points[j].x) || !valid_world_number(stroke.points[j].y) ||
        !valid_canvas_pressure(stroke.points[j].pressure)) {
        throw new Error('笔迹坐标超出范围');
      }
    }
  }
}
export function validate_answer_manifest(manifest: AnswerManifest): void {
  const uuid: RegExp = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/;
  if (!manifest || manifest.schemaVersion !== 1 || !uuid.test(manifest.saveRequestId) ||
    (manifest.baseFileRef !== null && !uuid.test(manifest.baseFileRef))) { throw new Error('保存文档标识无效'); }
  validate_canvas_document(manifest.document);
  const canvas: CanvasExtent = manifest.canvas;
  if (!canvas || canvas.coordinateUnit !== 'logical' || !Number.isInteger(canvas.width) || !Number.isInteger(canvas.height) ||
    canvas.width <= 0 || canvas.height <= 0 || !Number.isInteger(canvas.originX) || !Number.isInteger(canvas.originY) ||
    !valid_world_number(canvas.originX) || !valid_world_number(canvas.originY) ||
    !valid_world_number(canvas.originX + canvas.width) || !valid_world_number(canvas.originY + canvas.height)) {
    throw new Error('完整解答范围无效');
  }
  const outer: CanvasRect = { x: canvas.originX, y: canvas.originY, width: canvas.width, height: canvas.height };
  const bounds: CanvasRect = manifest.document.questionLayout.bounds;
  if (!contains_rect(outer, bounds)) { throw new Error('完整解答裁掉了题面'); }
  const left: number = Math.floor(bounds.x); const top: number = Math.floor(bounds.y);
  const pixels: number = check_grid(manifest.blocks, 0, 0, canvas.width, canvas.height, 'chunk_') +
    (Math.ceil(bounds.x + bounds.width) - left) * (Math.ceil(bounds.y + bounds.height) - top);
  if (pixels > 64000000 || manifest.blocks.length + manifest.document.questionLayout.rasterBlocks.length > 256) {
    throw new Error('完整解答超过图块限制');
  }
  for (let i: number = 0; i < manifest.document.strokes.length; i++) {
    const stroke: CanvasStroke = manifest.document.strokes[i];
    for (let j: number = 0; j < stroke.points.length; j++) {
      const point: CanvasPoint = stroke.points[j]; const radius: number = stroke.width / 2;
      if (!contains_rect(outer, { x: point.x - radius, y: point.y - radius, width: stroke.width, height: stroke.width })) {
        throw new Error('完整解答裁掉了笔迹');
      }
    }
  }
}

/** 全部题面与全部笔迹半笔宽外延，视口不参与导出范围。 */
export function canvas_extent(doc: CanvasEditableDocument): CanvasExtent {
  validate_canvas_document(doc);
  const q: CanvasRect = doc.questionLayout.bounds;
  let left: number = q.x;
  let top: number = q.y;
  let right: number = q.x + q.width;
  let bottom: number = q.y + q.height;
  for (let i: number = 0; i < doc.strokes.length; i++) {
    const stroke: CanvasStroke = doc.strokes[i];
    const radius: number = stroke.width / 2;
    for (let j: number = 0; j < stroke.points.length; j++) {
      const point: CanvasPoint = stroke.points[j];
      left = Math.min(left, point.x - radius); top = Math.min(top, point.y - radius);
      right = Math.max(right, point.x + radius); bottom = Math.max(bottom, point.y + radius);
    }
  }
  left = Math.floor(left - 16); top = Math.floor(top - 16);
  right = Math.ceil(right + 16); bottom = Math.ceil(bottom + 16);
  const width: number = right - left;
  const height: number = bottom - top;
  if (!Number.isSafeInteger(width) || !Number.isSafeInteger(height) || width * height > 64000000 ||
    Math.ceil(width / CANVAS_TILE_SIZE) * Math.ceil(height / CANVAS_TILE_SIZE) > 256) {
    throw new Error('完整解答范围过大，请减少空白间距后重试；未裁切保存');
  }
  return { width: width, height: height, originX: left, originY: top, coordinateUnit: 'logical' };
}

export function canvas_grid(extent: CanvasExtent): CanvasImageBlock[] {
  const result: CanvasImageBlock[] = [];
  const columns: number = Math.ceil(extent.width / CANVAS_TILE_SIZE);
  const rows: number = Math.ceil(extent.height / CANVAS_TILE_SIZE);
  for (let row: number = 0; row < rows; row++) {
    for (let col: number = 0; col < columns; col++) {
      const x: number = col * CANVAS_TILE_SIZE; const y: number = row * CANVAS_TILE_SIZE;
      const width: number = Math.min(CANVAS_TILE_SIZE, extent.width - x);
      const height: number = Math.min(CANVAS_TILE_SIZE, extent.height - y);
      const index: number = result.length;
      result.push({ index: index, partName: 'chunk_' + index.toString().padStart(4, '0'), x: x, y: y,
        width: width, height: height, pixelWidth: width, pixelHeight: height, sha256: '' });
    }
  }
  return result;
}

function segment_distance(point: CanvasPoint, a: CanvasPoint, b: CanvasPoint): number {
  const dx: number = b.x - a.x; const dy: number = b.y - a.y;
  const length: number = dx * dx + dy * dy;
  const t: number = length === 0 ? 0 : Math.max(0, Math.min(1, ((point.x - a.x) * dx + (point.y - a.y) * dy) / length));
  return Math.hypot(point.x - a.x - t * dx, point.y - a.y - t * dy);
}

export function stroke_hit(stroke: CanvasStroke, point: CanvasPoint, radius: number): boolean {
  if (stroke.points.length === 1) { return segment_distance(point, stroke.points[0], stroke.points[0]) <= radius + stroke.width / 2; }
  for (let i: number = 1; i < stroke.points.length; i++) {
    if (segment_distance(point, stroke.points[i - 1], stroke.points[i]) <= radius + stroke.width / 2) { return true; }
  }
  return false;
}
