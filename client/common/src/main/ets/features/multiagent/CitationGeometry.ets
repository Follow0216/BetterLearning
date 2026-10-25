import { V2HandwritingSegment, V2WorldBounds } from './LearningV2Models';

export interface CitationTextRange { before: string; selected: string; after: string; }
export type CitationRect = V2WorldBounds;
export interface CitationViewport { scale: number; offsetX: number; offsetY: number; }

function split_surrogate(text: string, offset: number): boolean {
  if (offset <= 0 || offset >= text.length) { return false; }
  const left: number = text.charCodeAt(offset - 1);
  const right: number = text.charCodeAt(offset);
  return left >= 0xD800 && left <= 0xDBFF && right >= 0xDC00 && right <= 0xDFFF;
}
/** Offsets address the exact returned block; never normalize or guess a similar range. */
export function citation_text_range(text: string, start: number, end: number): CitationTextRange {
  if (typeof text !== 'string' || !Number.isInteger(start) || !Number.isInteger(end) || start < 0 ||
    end <= start || end > text.length || split_surrogate(text,start) || split_surrogate(text,end)) {
    throw new Error('引用文字定位无效');
  }
  return { before: text.substring(0,start), selected: text.substring(start,end), after: text.substring(end) };
}
function valid_rect(rect: CitationRect): boolean {
  return !!rect && Number.isFinite(rect.x) && Number.isFinite(rect.y) && Number.isFinite(rect.width) &&
    Number.isFinite(rect.height) && rect.width > 0 && rect.height > 0 &&
    Number.isFinite(rect.x + rect.width) && Number.isFinite(rect.y + rect.height);
}
export function citation_intersection(a: CitationRect, b: CitationRect): CitationRect {
  if (!valid_rect(a) || !valid_rect(b)) { throw new Error('引用图块边界无效'); }
  const x: number = Math.max(a.x,b.x); const y: number = Math.max(a.y,b.y);
  const right: number = Math.min(a.x + a.width,b.x + b.width);
  const bottom: number = Math.min(a.y + a.height,b.y + b.height);
  if (right <= x || bottom <= y) { throw new Error('步骤不在引用图块内'); }
  return { x: x, y: y, width: right - x, height: bottom - y };
}
/** Mapping uses each block's own actual density, including 1x/2x question rasters. */
export function citation_pixel_region(segment: V2HandwritingSegment): CitationRect {
  if (!Number.isInteger(segment.pixelWidth) || !Number.isInteger(segment.pixelHeight) ||
    segment.pixelWidth < 1 || segment.pixelHeight < 1 || segment.pixelWidth > 16384 || segment.pixelHeight > 16384) {
    throw new Error('引用图块像素无效');
  }
  const block: CitationRect = segment.blockWorldBounds;
  const target: CitationRect = citation_intersection(block,segment.regionWorldBounds);
  return { x: (target.x - block.x) * segment.pixelWidth / block.width,
    y: (target.y - block.y) * segment.pixelHeight / block.height,
    width: target.width * segment.pixelWidth / block.width,
    height: target.height * segment.pixelHeight / block.height };
}
export function citation_world_extent(segments: V2HandwritingSegment[], context: boolean = false): CitationRect {
  if (segments.length === 0 || segments.length > 256) { throw new Error('引用步骤图块不完整'); }
  let left: number = Infinity; let top: number = Infinity; let right: number = -Infinity; let bottom: number = -Infinity;
  const indexes: Set<number> = new Set<number>();
  for (let i: number = 0; i < segments.length; i++) {
    const segment: V2HandwritingSegment = segments[i];
    if (!Number.isInteger(segment.blockIndex) || segment.blockIndex < 0 || indexes.has(segment.blockIndex)) {
      throw new Error('引用图块编号无效');
    }
    indexes.add(segment.blockIndex); citation_pixel_region(segment);
    const region: CitationRect = context ? segment.blockWorldBounds :
      citation_intersection(segment.blockWorldBounds,segment.regionWorldBounds);
    left = Math.min(left,region.x); top = Math.min(top,region.y);
    right = Math.max(right,region.x + region.width); bottom = Math.max(bottom,region.y + region.height);
  }
  return { x: left, y: top, width: right - left, height: bottom - top };
}
export function citation_fit(extent: CitationRect, width: number, height: number, zoom: number = 1): CitationViewport {
  if (!valid_rect(extent) || !Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0 ||
    !Number.isFinite(zoom) || zoom < 1 || zoom > 8) { throw new Error('引用视图尺寸无效'); }
  const scale: number = Math.min((Math.max(width - 24,1)) / extent.width,(Math.max(height - 24,1)) / extent.height) * zoom;
  return { scale: scale, offsetX: (width - extent.width * scale) / 2 - extent.x * scale,
    offsetY: (height - extent.height * scale) / 2 - extent.y * scale };
}
