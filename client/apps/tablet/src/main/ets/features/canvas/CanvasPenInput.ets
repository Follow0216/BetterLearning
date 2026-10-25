import { CanvasPenKind, CANVAS_DEFAULT_PRESSURE } from 'common';

export const CANVAS_PEN_COLORS: string[] = ['#152744', '#2563EB', '#DC2626', '#15803D', '#EAB308'];
export const CANVAS_PEN_SIZES: number[] = [0, 1, 2];

export function canvas_pen_width(kind: CanvasPenKind, size: number): number {
  if (kind === CanvasPenKind.HIGHLIGHTER) { return [10,16,24][size]; }
  if (kind === CanvasPenKind.FOUNTAIN) { return [2.5,4,6][size]; }
  return [1.5,2.5,4][size];
}
export function canvas_pen_color(kind: CanvasPenKind, color: string): string {
  // 共享颜色协议为 #AARRGGBB；荧光笔整条 path 只绘制一次，避免采样处重复叠色。
  return kind === CanvasPenKind.HIGHLIGHTER ? '#4D' + color.substring(1) : color;
}
/** 只量化持久化值；采样器内部平滑仍保留精度，最大宽度误差为0.035%。 */
export function canvas_stored_pressure(pressure: number): number {
  return Math.round(Math.max(0,Math.min(1,pressure)) * 1000) / 1000;
}
/** 仅接收已通过 Surface 指笔门禁的 BaseEvent.pressure；绝不用压力猜输入来源。 */
export class CanvasPressureSampler {
  private value: number = CANVAS_DEFAULT_PRESSURE;
  reset(): void { this.value = CANVAS_DEFAULT_PRESSURE; }
  begin(raw: number | undefined): number {
    this.value = this.normalized(raw) ?? CANVAS_DEFAULT_PRESSURE;
    return this.value;
  }
  next(raw: number | undefined): number {
    // 0 是 SDK 默认/缺失值，短暂缺样（尤其 Up）沿用上个值，不突然变细/变粗。
    const target: number = this.normalized(raw) ?? this.value;
    const delta: number = Math.max(-0.18,Math.min(0.18,(target - this.value) * 0.45));
    this.value += delta;
    return this.value;
  }
  private normalized(raw: number | undefined): number | null {
    // SDK BaseEvent 声明通常 0..1，部分硬件可大于 1；不套用 TouchObject 的 65535 范围。
    return raw === undefined || !Number.isFinite(raw) || raw <= 0 ? null : Math.min(1,raw);
  }
}
