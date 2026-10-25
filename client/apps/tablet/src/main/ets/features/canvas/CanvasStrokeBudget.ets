import { CanvasEditableDocument, CanvasPoint, CanvasQuestionLayout, CanvasStroke, canvas_utf8 } from 'common';

// 为manifest外层、最多256个chunk_描述符和哈希预留128KiB，不提高8MiB协议上限。
export const CANVAS_EDITABLE_JSON_LIMIT: number = 8388608 - 131072;

/** 按实际UTF-8 JSON增量计数；不可通过到达点数上限前写满JSON而制造不可上传的新笔迹。 */
export class CanvasStrokeBudget {
  private layout: CanvasQuestionLayout | null = null;
  private emptyBytes: number = 0;
  private documentBytes: number = 0;
  private sizes: Map<CanvasStroke, number> = new Map<CanvasStroke, number>();
  private stroke: CanvasStroke | null = null;
  private remaining: number = 0;
  private limit: number;
  constructor(limit: number = CANVAS_EDITABLE_JSON_LIMIT) { this.limit = limit; }

  begin(document: CanvasEditableDocument, stroke: CanvasStroke): boolean {
    this.finish(); this.sync(document);
    this.stroke = stroke;
    const strokeBytes: number = canvas_utf8(JSON.stringify(stroke)).length;
    this.remaining = this.limit - this.documentBytes - strokeBytes - (document.strokes.length > 0 ? 1 : 0);
    if (this.remaining < 0) { this.finish(); return false; }
    return true;
  }
  append(point: CanvasPoint): boolean {
    const added: number = canvas_utf8(JSON.stringify(point)).length + 1;
    if (this.stroke === null || added > this.remaining) { return false; }
    this.remaining -= added;
    return true;
  }
  finish(): void {
    this.stroke = null; this.remaining = 0;
  }
  /** 仅用于切题/卸载；正常抬笔继续复用已提交笔迹尺寸。 */
  clear(): void {
    this.finish(); this.sizes.clear(); this.layout = null; this.emptyBytes = 0; this.documentBytes = 0;
  }
  /** 擦除/撤销只裁掉已移除对象，保留其他笔画的尺寸缓存，不重新扫描点。 */
  retain(strokes: CanvasStroke[]): void {
    const kept: Set<CanvasStroke> = new Set<CanvasStroke>(strokes);
    this.sizes.forEach((_size: number, stroke: CanvasStroke) => {
      if (!kept.has(stroke)) { this.sizes.delete(stroke); }
    });
  }
  private sync(document: CanvasEditableDocument): void {
    if (this.layout !== document.questionLayout) {
      const empty: CanvasEditableDocument = { schemaVersion: document.schemaVersion,
        questionLayout: document.questionLayout, strokes: [] };
      this.emptyBytes = canvas_utf8(JSON.stringify(empty)).length; this.layout = document.questionLayout;
    }
    let bytes: number = this.emptyBytes + Math.max(0,document.strokes.length - 1);
    const sizes: Map<CanvasStroke, number> = new Map<CanvasStroke, number>();
    for (let i: number = 0; i < document.strokes.length; i++) {
      const stroke: CanvasStroke = document.strokes[i];
      const size: number = this.sizes.get(stroke) ?? canvas_utf8(JSON.stringify(stroke)).length;
      sizes.set(stroke,size); bytes += size;
    }
    this.sizes = sizes; this.documentBytes = bytes;
  }
}
