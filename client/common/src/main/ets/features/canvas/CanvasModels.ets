/** M2 唯一共享画布格式；世界坐标可负，合成块坐标相对 canvas 原点非负。 */
export const CANVAS_TILE_SIZE: number = 1024;
export const CANVAS_RENDERER_ID: string = 'bl-m2-markdown15.0.3-texmath1.0.0-katex0.19.1';
export const CANVAS_CONTENT_WIDTH: number = 720;
export const CANVAS_FONT_SIZE: number = 18;
export const CANVAS_MAX_POINTS: number = 200000;
export const CANVAS_MAX_STROKES: number = 20000;

export interface CanvasPoint { x: number; y: number; }
export interface CanvasView { x: number; y: number; scale: number; }
export interface CanvasRect { x: number; y: number; width: number; height: number; }
export interface CanvasStroke { strokeId: string; color: string; width: number; points: CanvasPoint[]; }
export interface CanvasTextBlock extends CanvasRect { blockId: string; }
export interface CanvasImageBlock extends CanvasRect {
  index: number;
  partName: string;
  pixelWidth: number;
  pixelHeight: number;
  sha256: string;
  url?: string;
}
export interface CanvasQuestionLayout {
  questionBodySha256: string;
  contentWidth: number;
  fontSize: number;
  rendererId: string;
  bounds: CanvasRect;
  blocks: CanvasTextBlock[];
  rasterBlocks: CanvasImageBlock[];
}
export interface CanvasEditableDocument {
  schemaVersion: number;
  questionLayout: CanvasQuestionLayout;
  strokes: CanvasStroke[];
}
export interface CanvasExtent {
  width: number;
  height: number;
  originX: number;
  originY: number;
  coordinateUnit: string;
}
/** filePath 是设备私有文件，不进入远端 manifest。 */
export interface CanvasLocalFile { partName: string; filePath: string; }
export enum CanvasTool { PEN = 'pen', ERASER = 'eraser', MOVE = 'move' }
