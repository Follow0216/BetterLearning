import { image } from '@kit.ImageKit';
import { LengthMetricsUnit } from '@kit.ArkUI';
import { util } from '@kit.ArkTS';
import {
  CanvasEditableDocument, CanvasLocalFile, CanvasPoint, CanvasRect, CanvasStroke, CanvasTool, CanvasView,
  canvas_image, fit_canvas, stroke_hit, valid_world_number, world_point, zoom_canvas
} from 'common';
import { canvas_intersects, draw_canvas_stroke, stroke_bounds } from './CanvasPainter';

/** 题面与笔迹共用一个世界坐标；缓存只保留当前视口题面，导出不读取该缓存。 */
@Component
export struct CanvasSurface {
  @Prop @Watch('document_changed') document: CanvasEditableDocument | null = null;
  @Prop @Watch('reset') documentKey: string = '';
  rasterFiles: CanvasLocalFile[] = [];
  initialView: CanvasView | null = null;
  @Prop @Watch('read_only_changed') readOnly: boolean = false;
  onDocumentChange: (doc: CanvasEditableDocument) => void = () => {};
  onViewChange: (view: CanvasView) => void = () => {};
  onUndoChange: (canUndo: boolean) => void = () => {};
  onFailure: (message: string) => void = () => {};
  @State private tool: CanvasTool = CanvasTool.PEN;
  @State private undoEnabled: boolean = false;
  @State private zoomText: string = '100%';
  private context: CanvasRenderingContext2D = new CanvasRenderingContext2D(new RenderingContextSettings(true));
  private ready: boolean = false;
  private alive: boolean = true;
  private canvasWidthVp: number = 0;
  private canvasHeightVp: number = 0;
  private view: CanvasView = { x: -24, y: -24, scale: 1 };
  private viewSet: boolean = false;
  private backgroundMap: image.PixelMap | null = null;
  private paintGeneration: number = 0;
  private pointers: Map<number, CanvasPoint> = new Map<number, CanvasPoint>();
  private last: CanvasPoint | null = null;
  private pinchView: CanvasView | null = null;
  private pinchMid: CanvasPoint | null = null;
  private pinchDistance: number = 1;
  private suppress: boolean = false;
  private liveStroke: CanvasStroke | null = null;
  private eraseBefore: CanvasStroke[] | null = null;
  private erased: CanvasStroke[] | null = null;
  private undoHistory: CanvasStroke[][] = [];
  private pointBudget: number = 0;

  aboutToAppear(): void { this.alive = true; }
  aboutToDisappear(): void {
    this.alive = false; this.paintGeneration++; this.cancel_gesture();
    if (this.backgroundMap !== null) { this.backgroundMap.release(); this.backgroundMap = null; }
  }
  private reset(): void {
    this.cancel_gesture(); this.undoHistory = []; this.update_undo(); this.viewSet = false;
    this.paintGeneration++;
    if (this.backgroundMap !== null) { this.backgroundMap.release(); this.backgroundMap = null; }
    this.configure_view();
  }
  private document_changed(): void { this.paint(); }
  private read_only_changed(): void { this.cancel_gesture(); this.paint(); }
  private update_undo(): void {
    this.undoEnabled = this.undoHistory.length > 0; this.onUndoChange(this.undoEnabled);
  }
  private bounds(): CanvasRect {
    if (this.document === null) { return { x: 0, y: 0, width: 720, height: 1024 }; }
    const q: CanvasRect = this.document.questionLayout.bounds;
    let x: number = q.x; let y: number = q.y; let right: number = x + q.width; let bottom: number = y + q.height;
    for (let i: number = 0; i < this.document.strokes.length; i++) {
      const rect: CanvasRect = stroke_bounds(this.document.strokes[i]);
      x = Math.min(x,rect.x); y = Math.min(y,rect.y); right = Math.max(right,rect.x + rect.width); bottom = Math.max(bottom,rect.y + rect.height);
    }
    return { x: x, y: y, width: right - x, height: bottom - y };
  }
  private configure_view(): void {
    if (!this.ready || !Number.isFinite(this.canvasWidthVp) || !Number.isFinite(this.canvasHeightVp) || this.canvasWidthVp < 1 || this.canvasHeightVp < 1) { return; }
    if (!this.viewSet) {
      this.view = this.initialView === null ? fit_canvas(this.bounds(),this.canvasWidthVp,this.canvasHeightVp) :
        { x: this.initialView.x, y: this.initialView.y, scale: this.initialView.scale };
      this.viewSet = true;
    }
    this.zoomText = Math.round(this.view.scale * 100).toString() + '%';
    this.rebuild_background();
  }
  private async rebuild_background(): Promise<void> {
    if (!this.ready || this.document === null || !Number.isFinite(this.canvasWidthVp) || !Number.isFinite(this.canvasHeightVp) || this.canvasWidthVp < 1 || this.canvasHeightVp < 1) { return; }
    const generation: number = ++this.paintGeneration;
    const doc: CanvasEditableDocument = this.document;
    const view: CanvasView = { x: this.view.x, y: this.view.y, scale: this.view.scale };
    const files: CanvasLocalFile[] = this.rasterFiles.slice();
    const width: number = Math.ceil(this.canvasWidthVp); const height: number = Math.ceil(this.canvasHeightVp);
    try {
      const canvas: OffscreenCanvas = new OffscreenCanvas(width,height,LengthMetricsUnit.PX);
      const context: OffscreenCanvasRenderingContext2D = canvas.getContext('2d');
      context.fillStyle = '#FFFFFF'; context.fillRect(0,0,width,height);
      context.scale(view.scale,view.scale); context.translate(-view.x,-view.y);
      const visible: CanvasRect = { x: view.x, y: view.y, width: width / view.scale, height: height / view.scale };
      for (let i: number = 0; i < doc.questionLayout.rasterBlocks.length; i++) {
        const block = doc.questionLayout.rasterBlocks[i];
        if (!canvas_intersects(visible,block)) { continue; }
        const file: CanvasLocalFile | undefined = files.find((item: CanvasLocalFile) => item.partName === block.partName);
        if (file === undefined) { throw new Error('题面底图缺失'); }
        const map: image.PixelMap = await canvas_image(file.filePath);
        try {
          if (!this.alive || generation !== this.paintGeneration) { return; }
          context.drawImage(map,block.x,block.y,block.width,block.height);
        } finally { await map.release(); }
      }
      if (!this.alive || generation !== this.paintGeneration) { return; }
      const next: image.PixelMap = context.getPixelMap(0,0,width,height);
      const previous: image.PixelMap | null = this.backgroundMap;
      this.backgroundMap = next; this.paint();
      if (previous !== null) { await previous.release(); }
    } catch (error) {
      if (this.alive && generation === this.paintGeneration) { this.onFailure('画布底图读取失败，请重新打开'); }
    }
  }
  private paint(): void {
    if (!this.ready) { return; }
    const context: CanvasRenderingContext2D = this.context;
    context.resetTransform(); context.fillStyle = '#FFFFFF'; context.fillRect(0,0,this.canvasWidthVp,this.canvasHeightVp);
    if (this.backgroundMap !== null) { context.drawImage(this.backgroundMap,0,0,this.canvasWidthVp,this.canvasHeightVp); }
    context.save(); context.scale(this.view.scale,this.view.scale); context.translate(-this.view.x,-this.view.y);
    const strokes: CanvasStroke[] = this.erased ?? this.document?.strokes ?? [];
    const visible: CanvasRect = { x: this.view.x, y: this.view.y, width: this.canvasWidthVp / this.view.scale, height: this.canvasHeightVp / this.view.scale };
    for (let i: number = 0; i < strokes.length; i++) {
      if (canvas_intersects(visible,stroke_bounds(strokes[i]))) { draw_canvas_stroke(context,strokes[i]); }
    }
    if (this.liveStroke !== null) { draw_canvas_stroke(context,this.liveStroke); }
    context.restore();
  }
  private commit(strokes: CanvasStroke[], before: CanvasStroke[]): void {
    if (this.document === null) { return; }
    this.undoHistory.push(before); if (this.undoHistory.length > 50) { this.undoHistory.shift(); }
    this.update_undo();
    this.onDocumentChange({ schemaVersion: 1, questionLayout: this.document.questionLayout, strokes: strokes });
  }
  private undo(): void {
    if (this.readOnly || this.document === null || this.undoHistory.length === 0) { return; }
    this.cancel_gesture();
    const strokes: CanvasStroke[] = this.undoHistory.pop() as CanvasStroke[];
    this.update_undo(); this.onDocumentChange({ schemaVersion: 1, questionLayout: this.document.questionLayout, strokes: strokes });
  }
  private cancel_gesture(): void {
    this.liveStroke = null; this.eraseBefore = null; this.erased = null; this.last = null;
    this.pointers.clear(); this.pinchView = null; this.pinchMid = null; this.suppress = false;
  }
  private point(point: CanvasPoint): CanvasPoint | null {
    const world: CanvasPoint = world_point(this.view,point.x,point.y);
    return valid_world_number(world.x) && valid_world_number(world.y) ? world : null;
  }
  private append_point(point: CanvasPoint): void {
    if (this.liveStroke === null || this.liveStroke.points.length >= this.pointBudget) { return; }
    const world: CanvasPoint | null = this.point(point);
    const previous: CanvasPoint = this.liveStroke.points[this.liveStroke.points.length - 1];
    if (world !== null && Math.hypot(world.x - previous.x,world.y - previous.y) > 0.01) { this.liveStroke.points.push(world); }
  }
  private erase_path(point: CanvasPoint): void {
    const from: CanvasPoint = this.last ?? point;
    const steps: number = Math.min(512,Math.max(1,Math.ceil(Math.hypot(point.x - from.x,point.y - from.y) / 6)));
    for (let i: number = 1; i <= steps; i++) { this.erase({ x: from.x + (point.x - from.x) * i / steps, y: from.y + (point.y - from.y) * i / steps }); }
  }
  private erase(point: CanvasPoint): void {
    if (this.erased === null) { return; }
    const world: CanvasPoint | null = this.point(point);
    if (world !== null) { this.erased = this.erased.filter((stroke: CanvasStroke) => !stroke_hit(stroke,world,12 / this.view.scale)); }
  }
  private change_view(view: CanvasView, persist: boolean): void {
    if (!valid_world_number(view.x) || !valid_world_number(view.y)) { return; }
    this.view = view; this.zoomText = Math.round(view.scale * 100).toString() + '%';
    // 旧视口题面不与新坐标混画；新底图只影响显示，笔迹继续使用世界坐标。
    if (this.backgroundMap !== null) { this.backgroundMap.release(); this.backgroundMap = null; }
    this.paint(); this.rebuild_background(); if (persist) { this.onViewChange(view); }
  }
  private touch(event: TouchEvent): void {
    event.stopPropagation();
    if (event.type === TouchType.Cancel) { this.cancel_gesture(); this.paint(); return; }
    for (let i: number = 0; i < event.changedTouches.length; i++) {
      const touch: TouchObject = event.changedTouches[i];
      this.pointers.set(touch.id,{ x: touch.x, y: touch.y });
    }
    const points: CanvasPoint[] = Array.from(this.pointers.values());
    if (points.length >= 2) {
      this.liveStroke = null; this.eraseBefore = null; this.erased = null; this.suppress = true;
      const mid: CanvasPoint = { x: (points[0].x + points[1].x) / 2, y: (points[0].y + points[1].y) / 2 };
      const distance: number = Math.max(1,Math.hypot(points[0].x - points[1].x,points[0].y - points[1].y));
      if (this.pinchView === null) {
        this.pinchView = { x: this.view.x, y: this.view.y, scale: this.view.scale };
        this.pinchMid = mid; this.pinchDistance = distance;
      } else if (this.pinchMid !== null) {
        const next: CanvasView = zoom_canvas(this.pinchView,distance / this.pinchDistance,this.pinchMid.x,this.pinchMid.y);
        next.x -= (mid.x - this.pinchMid.x) / next.scale; next.y -= (mid.y - this.pinchMid.y) / next.scale;
        this.change_view(next,false);
      }
    } else if (!this.suppress && points.length === 1) {
      const point: CanvasPoint = points[0];
      if (event.type === TouchType.Down) {
        this.last = point;
        if (!this.readOnly && this.tool === CanvasTool.PEN && this.document !== null) {
          const world: CanvasPoint | null = this.point(point);
          let count: number = 0; this.document.strokes.forEach((stroke: CanvasStroke) => { count += stroke.points.length; });
          this.pointBudget = 200000 - count;
          if (world !== null && this.document.strokes.length < 20000 && this.pointBudget > 0) {
            this.liveStroke = { strokeId: util.generateRandomUUID(true).toLowerCase(), color: '#152744', width: 2.5, points: [world] };
          } else { this.onFailure('笔迹已达到范围或数量上限，请减少后再写'); }
        } else if (!this.readOnly && this.tool === CanvasTool.ERASER && this.document !== null) {
          this.eraseBefore = this.document.strokes; this.erased = this.document.strokes.slice(); this.erase(point);
        }
      } else if (event.type === TouchType.Move) {
        if ((this.readOnly || this.tool === CanvasTool.MOVE) && this.last !== null) {
          this.change_view({ x: this.view.x - (point.x - this.last.x) / this.view.scale,
            y: this.view.y - (point.y - this.last.y) / this.view.scale, scale: this.view.scale },false);
        } else if (this.liveStroke !== null) { this.append_point(point); }
        else { this.erase_path(point); }
        this.last = point;
      } else if (event.type === TouchType.Up && this.document !== null) {
        if ((this.readOnly || this.tool === CanvasTool.MOVE) && this.last !== null) {
          this.change_view({ x: this.view.x - (point.x - this.last.x) / this.view.scale,
            y: this.view.y - (point.y - this.last.y) / this.view.scale, scale: this.view.scale },false);
        }
        this.append_point(point); this.erase_path(point);
        if (this.liveStroke !== null) { this.commit(this.document.strokes.concat([this.liveStroke]),this.document.strokes); }
        if (this.erased !== null && this.eraseBefore !== null && this.erased.length !== this.eraseBefore.length) {
          this.commit(this.erased,this.eraseBefore);
        }
        this.liveStroke = null; this.erased = null; this.eraseBefore = null; this.last = null;
      }
    }
    if (event.type === TouchType.Up) {
      event.changedTouches.forEach((touch: TouchObject) => { this.pointers.delete(touch.id); });
      if (this.pointers.size === 0) { this.cancel_gesture(); this.onViewChange(this.view); }
    }
    this.paint();
  }
  build() {
    Column() {
      Row({ space: 8 }) {
        Button('笔').onClick(() => { this.cancel_gesture(); this.tool = CanvasTool.PEN; this.paint(); }).enabled(!this.readOnly)
          .backgroundColor(this.tool === CanvasTool.PEN ? '#246BFD' : '#64748B')
        Button('整笔擦除').onClick(() => { this.cancel_gesture(); this.tool = CanvasTool.ERASER; this.paint(); }).enabled(!this.readOnly)
          .backgroundColor(this.tool === CanvasTool.ERASER ? '#246BFD' : '#64748B')
        Button('移动').onClick(() => { this.cancel_gesture(); this.tool = CanvasTool.MOVE; this.paint(); })
          .backgroundColor(this.tool === CanvasTool.MOVE ? '#246BFD' : '#64748B')
        Button('撤销').onClick(() => { this.undo(); }).enabled(this.undoEnabled && !this.readOnly)
        Blank()
        Button('−').onClick(() => { this.change_view(zoom_canvas(this.view,0.8,this.canvasWidthVp / 2,this.canvasHeightVp / 2),true); })
        Text(this.zoomText).fontSize(12).width(44).textAlign(TextAlign.Center)
        Button('＋').onClick(() => { this.change_view(zoom_canvas(this.view,1.25,this.canvasWidthVp / 2,this.canvasHeightVp / 2),true); })
        Button('全览').onClick(() => { this.change_view(fit_canvas(this.bounds(),this.canvasWidthVp,this.canvasHeightVp),true); })
      }.width('100%').padding(8)
      Canvas(this.context).width('100%').layoutWeight(1)
        .onReady(() => { this.ready = true; this.configure_view(); })
        .onAreaChange((_old: Area, area: Area) => {
          const width: number = typeof area.width === 'number' ? area.width : parseFloat(area.width.toString());
          const height: number = typeof area.height === 'number' ? area.height : parseFloat(area.height.toString());
          if (Number.isFinite(width) && Number.isFinite(height) && width > 0 && height > 0) {
            this.canvasWidthVp = width; this.canvasHeightVp = height; this.configure_view();
          }
        })
        .onTouch((event: TouchEvent) => { this.touch(event); })
    }.width('100%').height('100%').backgroundColor('#FFFFFF')
  }
}
