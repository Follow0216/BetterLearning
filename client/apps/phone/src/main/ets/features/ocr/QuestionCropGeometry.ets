import { PixelRect } from './QuestionImageSource';

const MAX_PIXELS: number = 16 * 1024 * 1024;
const PADDING_VP: number = 24;

export interface QuestionCropRect extends PixelRect {}

export interface QuestionCropFit {
  scale: number;
  offsetX: number;
  offsetY: number;
  width: number;
  height: number;
}

export enum QuestionCropDrag {
  MOVE = 'MOVE',
  TOP_LEFT = 'TOP_LEFT',
  TOP_RIGHT = 'TOP_RIGHT',
  BOTTOM_LEFT = 'BOTTOM_LEFT',
  BOTTOM_RIGHT = 'BOTTOM_RIGHT'
}

function validImageSize(width: number, height: number): boolean {
  return Number.isFinite(width) && Number.isFinite(height) &&
    Number.isInteger(width) && Number.isInteger(height) &&
    width > 0 && height > 0 && width * height <= MAX_PIXELS;
}

function zeroRect(): QuestionCropRect {
  return { x: 0, y: 0, width: 0, height: 0 };
}

function copyRect(rect: QuestionCropRect): QuestionCropRect {
  return { x: rect.x, y: rect.y, width: rect.width, height: rect.height };
}

function clamp(value: number, minimum: number, maximum: number): number {
  return Math.max(minimum, Math.min(maximum, value));
}

export function questionCropFit(imageWidth: number, imageHeight: number,
  viewportWidth: number, viewportHeight: number): QuestionCropFit {
  const empty: QuestionCropFit = { scale: 0, offsetX: 0, offsetY: 0, width: 0, height: 0 };
  if (!validImageSize(imageWidth, imageHeight) ||
    !Number.isFinite(viewportWidth) || !Number.isFinite(viewportHeight) ||
    viewportWidth <= PADDING_VP * 2 || viewportHeight <= PADDING_VP * 2) {
    return empty;
  }
  const scale: number = Math.min((viewportWidth - PADDING_VP * 2) / imageWidth,
    (viewportHeight - PADDING_VP * 2) / imageHeight);
  if (!Number.isFinite(scale) || scale <= 0) {
    return empty;
  }
  const width: number = imageWidth * scale;
  const height: number = imageHeight * scale;
  return { scale: scale, offsetX: (viewportWidth - width) / 2,
    offsetY: (viewportHeight - height) / 2, width: width, height: height };
}

export function questionCropFullRect(imageWidth: number, imageHeight: number): QuestionCropRect {
  if (!validImageSize(imageWidth, imageHeight)) {
    return zeroRect();
  }
  return { x: 0, y: 0, width: imageWidth, height: imageHeight };
}

export function questionCropValid(rect: QuestionCropRect, imageWidth: number, imageHeight: number): boolean {
  return validImageSize(imageWidth, imageHeight) &&
    Number.isFinite(rect.x) && Number.isFinite(rect.y) &&
    Number.isFinite(rect.width) && Number.isFinite(rect.height) &&
    Number.isInteger(rect.x) && Number.isInteger(rect.y) &&
    Number.isInteger(rect.width) && Number.isInteger(rect.height) &&
    rect.x >= 0 && rect.y >= 0 && rect.width >= 1 && rect.height >= 1 &&
    rect.x + rect.width <= imageWidth && rect.y + rect.height <= imageHeight;
}

// Pan offsets are vp from the gesture's start, never deltas from the previous update.
export function questionCropDrag(base: QuestionCropRect, kind: QuestionCropDrag,
  offsetXvp: number, offsetYvp: number, scale: number,
  imageWidth: number, imageHeight: number): QuestionCropRect {
  if (!questionCropValid(base, imageWidth, imageHeight)) {
    return questionCropFullRect(imageWidth, imageHeight);
  }
  const unchanged: QuestionCropRect = copyRect(base);
  if (!Number.isFinite(offsetXvp) || !Number.isFinite(offsetYvp) ||
    !Number.isFinite(scale) || scale <= 0) {
    return unchanged;
  }
  const deltaX: number = Math.round(offsetXvp / scale);
  const deltaY: number = Math.round(offsetYvp / scale);
  if (!Number.isFinite(deltaX) || !Number.isFinite(deltaY)) {
    return unchanged;
  }
  if (kind === QuestionCropDrag.MOVE) {
    return { x: clamp(base.x + deltaX, 0, imageWidth - base.width),
      y: clamp(base.y + deltaY, 0, imageHeight - base.height),
      width: base.width, height: base.height };
  }
  if (kind === QuestionCropDrag.TOP_LEFT) {
    const right: number = base.x + base.width;
    const bottom: number = base.y + base.height;
    const x: number = clamp(base.x + deltaX, 0, right - 1);
    const y: number = clamp(base.y + deltaY, 0, bottom - 1);
    return { x: x, y: y, width: right - x, height: bottom - y };
  }
  if (kind === QuestionCropDrag.TOP_RIGHT) {
    const right: number = clamp(base.x + base.width + deltaX, base.x + 1, imageWidth);
    const bottom: number = base.y + base.height;
    const y: number = clamp(base.y + deltaY, 0, bottom - 1);
    return { x: base.x, y: y, width: right - base.x, height: bottom - y };
  }
  if (kind === QuestionCropDrag.BOTTOM_LEFT) {
    const right: number = base.x + base.width;
    const x: number = clamp(base.x + deltaX, 0, right - 1);
    const bottom: number = clamp(base.y + base.height + deltaY, base.y + 1, imageHeight);
    return { x: x, y: base.y, width: right - x, height: bottom - base.y };
  }
  if (kind === QuestionCropDrag.BOTTOM_RIGHT) {
    const right: number = clamp(base.x + base.width + deltaX, base.x + 1, imageWidth);
    const bottom: number = clamp(base.y + base.height + deltaY, base.y + 1, imageHeight);
    return { x: base.x, y: base.y, width: right - base.x, height: bottom - base.y };
  }
  return unchanged;
}
