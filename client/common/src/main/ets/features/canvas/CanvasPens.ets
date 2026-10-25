import { CanvasPenKind, CanvasPoint, CanvasStroke } from './CanvasModels';

export const CANVAS_DEFAULT_PRESSURE: number = 0.6;

export function valid_canvas_pen(kind: CanvasPenKind | null | undefined): boolean {
  return kind === undefined || kind === null || kind === CanvasPenKind.BALLPOINT ||
    kind === CanvasPenKind.FOUNTAIN || kind === CanvasPenKind.HIGHLIGHTER;
}
export function valid_canvas_pressure(pressure: number | null | undefined): boolean {
  return pressure === undefined || pressure === null ||
    (typeof pressure === 'number' && Number.isFinite(pressure) && pressure >= 0 && pressure <= 1);
}
/** 旧笔迹无 penKind 时始终定宽；width 是最大宽度，压感不会扩大导出范围。 */
export function canvas_point_width(stroke: CanvasStroke, point: CanvasPoint): number {
  if (stroke.penKind !== CanvasPenKind.FOUNTAIN) { return stroke.width; }
  const pressure: number = point.pressure === undefined || point.pressure === null ?
    CANVAS_DEFAULT_PRESSURE : Math.max(0,Math.min(1,point.pressure));
  return stroke.width * (0.3 + 0.7 * pressure);
}
