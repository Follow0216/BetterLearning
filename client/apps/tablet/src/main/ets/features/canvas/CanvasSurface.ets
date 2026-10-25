import { image } from '@kit.ImageKit';
import { LengthMetricsUnit } from '@kit.ArkUI';
import { util } from '@kit.ArkTS';
import {
  AuthError, AuthResult, CanvasEditableDocument, CanvasLocalFile, CanvasPoint, CanvasRect, CanvasStroke, CanvasTool, CanvasView, CanvasPenKind,
  canvas_image, clone_canvas_document, fit_canvas, stroke_hit, valid_world_number, world_point, zoom_canvas
} from 'common';
import { canvas_intersects, draw_canvas_stroke, stroke_bounds } from './CanvasPainter';
import { CanvasStrokeBudget } from './CanvasStrokeBudget';
import { CanvasPressureSampler, CANVAS_PEN_COLORS, CANVAS_PEN_SIZES, canvas_pen_color, canvas_pen_width, canvas_stored_pressure } from './CanvasPenInput';
import {
  TABLET_COLOR_BACKGROUND, TABLET_COLOR_BORDER, TABLET_COLOR_CARD,
  TABLET_COLOR_PRIMARY, TABLET_COLOR_PRIMARY_DEEP, TABLET_COLOR_PRIMARY_SOFT, TABLET_COLOR_TEXT,
  TABLET_CONTROL_SIZE, TABLET_RADIUS_SMALL
} from '../../theme/TabletTheme';

export interface CanvasFreezeHandle {
  captureAtPenUp: () => Promise<AuthResult<CanvasEditableDocument>>;
  cancel: () => void;
}

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
  onFreezeHandle: (handle: CanvasFreezeHandle, attached: boolean) => void = () => {};
  private committedDocument: CanvasEditableDocument | null = null;
  private waitingFreeze: ((result: AuthResult<CanvasEditableDocument>) => void) | null = null;
  private freezeHandle: CanvasFreezeHandle = {
    captureAtPenUp: () => this.capture_at_pen_up(), cancel: () => this.cancel_freeze()
  };
  @State private tool: CanvasTool = CanvasTool.PEN;
  @State private penKind: CanvasPenKind = CanvasPenKind.FOUNTAIN;
  @State private penColor: string = '#152744';
  @State private penSize: number = 1;
  private pressureSampler: CanvasPressureSampler = new CanvasPressureSampler();
  private strokeBudget: CanvasStrokeBudget = new CanvasStrokeBudget();
  private strokeStopped: boolean = false;
  @State private undoEnabled: boolean = false;
  @State private zoomText: string = '100%';
  @State private chromeWidthVp: number = 0;
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
  private pointerSources: Map<number, SourceTool> = new Map<number, SourceTool>();
  private editPointerId: number | null = null;
  private navigationKey: string = '';
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

  aboutToAppear(): void {
    this.alive = true; this.committedDocument = this.document; this.onFreezeHandle(this.freezeHandle,true);
  }
  aboutToDisappear(): void {
    this.alive = false; this.paintGeneration++; this.cancel_gesture(); this.strokeBudget.clear(); this.onFreezeHandle(this.freezeHandle,false);
    if (this.backgroundMap !== null) { this.backgroundMap.release(); this.backgroundMap = null; }
  }
  private reset(): void {
    this.cancel_gesture(); this.strokeBudget.clear(); this.committedDocument = this.document; this.undoHistory = []; this.update_undo(); this.viewSet = false;
    this.paintGeneration++;
    if (this.backgroundMap !== null) { this.backgroundMap.release(); this.backgroundMap = null; }
    this.configure_view();
  }
  private document_changed(): void {
    // 同一挂载中的 Prop 是父层提交回声；编辑基底以同步提交副本为准，避免连续笔回流滞后。
    if (this.committedDocument === null) { this.committedDocument = this.document; }
    this.paint();
  }
  private read_only_changed(): void { this.cancel_gesture(); this.paint(); }
  private update_undo(): void {
    this.undoEnabled = this.undoHistory.length > 0; this.onUndoChange(this.undoEnabled);
  }
  private bounds(): CanvasRect {
    if (this.committedDocument === null) { return { x: 0, y: 0, width: 720, height: 1024 }; }
    const q: CanvasRect = this.committedDocument.questionLayout.bounds;
    let x: number = q.x; let y: number = q.y; let right: number = x + q.width; let bottom: number = y + q.height;
    for (let i: number = 0; i < this.committedDocument.strokes.length; i++) {
      const rect: CanvasRect = stroke_bounds(this.committedDocument.strokes[i]);
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
    if (!this.ready || this.committedDocument === null || !Number.isFinite(this.canvasWidthVp) || !Number.isFinite(this.canvasHeightVp) || this.canvasWidthVp < 1 || this.canvasHeightVp < 1) { return; }
    const generation: number = ++this.paintGeneration;
    const doc: CanvasEditableDocument = this.committedDocument;
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
    this.paint_paper(context);
    context.save(); context.scale(this.view.scale,this.view.scale); context.translate(-this.view.x,-this.view.y);
    const strokes: CanvasStroke[] = this.erased ?? this.committedDocument?.strokes ?? [];
    const visible: CanvasRect = { x: this.view.x, y: this.view.y, width: this.canvasWidthVp / this.view.scale, height: this.canvasHeightVp / this.view.scale };
    for (let i: number = 0; i < strokes.length; i++) {
      if (canvas_intersects(visible,stroke_bounds(strokes[i]))) { draw_canvas_stroke(context,strokes[i]); }
    }
    if (this.liveStroke !== null) { draw_canvas_stroke(context,this.liveStroke); }
    context.restore();
  }
  /** 仅为屏幕纸面着色和绘制点阵，不修改固定题面 PNG 或导出画布。 */
  private paint_paper(context: CanvasRenderingContext2D): void {
    context.save();
    context.globalAlpha = 0.012; context.fillStyle = TABLET_COLOR_PRIMARY;
    context.fillRect(0,0,this.canvasWidthVp,this.canvasHeightVp);
    context.globalAlpha = 1; context.fillStyle = '#CFDDF2';
    const step: number = 24;
    // 显示网格不参与世界坐标计算，平移时仅调整视觉相位。
    const offsetX: number = ((-this.view.x * this.view.scale) % step + step) % step;
    const offsetY: number = ((-this.view.y * this.view.scale) % step + step) % step;
    context.beginPath();
    for (let x: number = offsetX; x < this.canvasWidthVp; x += step) {
      for (let y: number = offsetY; y < this.canvasHeightVp; y += step) {
        context.moveTo(x + 0.7,y); context.arc(x,y,0.7,0,Math.PI * 2);
      }
    }
    context.fill(); context.restore();
  }
  private commit(strokes: CanvasStroke[], before: CanvasStroke[]): void {
    if (this.committedDocument === null) { return; }
    this.undoHistory.push(before); if (this.undoHistory.length > 50) { this.undoHistory.shift(); }
    this.update_undo();
    const next: CanvasEditableDocument = { schemaVersion: 1, questionLayout: this.committedDocument.questionLayout, strokes: strokes };
    this.committedDocument = next; this.strokeBudget.retain(strokes); this.onDocumentChange(next);
  }
  private undo(): void {
    if (this.readOnly || this.committedDocument === null || this.undoHistory.length === 0) { return; }
    this.cancel_gesture();
    const strokes: CanvasStroke[] = this.undoHistory.pop() as CanvasStroke[];
    this.update_undo();
    const next: CanvasEditableDocument = { schemaVersion: 1, questionLayout: this.committedDocument.questionLayout, strokes: strokes };
    this.committedDocument = next; this.strokeBudget.retain(strokes); this.onDocumentChange(next);
  }
  private capture_at_pen_up(): Promise<AuthResult<CanvasEditableDocument>> {
    // Promise executor 在调用栈内执行：检查当前笔与登记等待之间没有 await/Prop 传播竞态。
    return new Promise<AuthResult<CanvasEditableDocument>>((resolve: (result: AuthResult<CanvasEditableDocument>) => void) => {
      if (!this.alive || this.committedDocument === null) {
        resolve(AuthResult.failure<CanvasEditableDocument>(AuthError.local_validation('画布尚未准备完成', {}))); return;
      }
      if (this.waitingFreeze !== null) {
        resolve(AuthResult.failure<CanvasEditableDocument>(AuthError.local_validation('正在等待当前笔结束', {}))); return;
      }
      const editing: boolean = this.editPointerId !== null && this.pointers.has(this.editPointerId) &&
        this.pointerSources.get(this.editPointerId) === SourceTool.Pen && !this.suppress && !this.readOnly && this.tool !== CanvasTool.MOVE;
      this.waitingFreeze = resolve;
      if (!editing) { this.fulfill_freeze(); }
    });
  }
  private fulfill_freeze(): void {
    const resolve: ((result: AuthResult<CanvasEditableDocument>) => void) | null = this.waitingFreeze;
    if (resolve === null) { return; }
    this.waitingFreeze = null;
    try {
      if (this.committedDocument === null) { throw new Error('画布尚未准备完成'); }
      resolve(AuthResult.success<CanvasEditableDocument>(clone_canvas_document(this.committedDocument)));
    } catch (error) {
      resolve(AuthResult.failure<CanvasEditableDocument>(AuthError.local_validation('画布冻结失败，请重试', {})));
    }
  }
  private cancel_freeze(): void {
    const resolve: ((result: AuthResult<CanvasEditableDocument>) => void) | null = this.waitingFreeze;
    this.waitingFreeze = null;
    if (resolve !== null) { resolve(AuthResult.failure<CanvasEditableDocument>(AuthError.local_validation('本笔已取消，请重新请求冻结', {}))); }
  }
  private discard_edit(cancelFreeze: boolean = true): void {
    if (cancelFreeze) { this.cancel_freeze(); }
    this.liveStroke = null; this.eraseBefore = null; this.erased = null; this.pressureSampler.reset();
    this.strokeBudget.finish(); this.strokeStopped = false;
  }
  private cancel_gesture(): void {
    this.discard_edit(); this.last = null;
    this.pointers.clear(); this.pointerSources.clear(); this.editPointerId = null;
    this.pinchView = null; this.pinchMid = null; this.navigationKey = ''; this.suppress = false;
  }
  private control_touch(event: TouchEvent): void {
    event.stopPropagation(); this.cancel_gesture(); this.paint();
  }
  private point(point: CanvasPoint): CanvasPoint | null {
    const world: CanvasPoint = world_point(this.view,point.x,point.y);
    return valid_world_number(world.x) && valid_world_number(world.y) ? world : null;
  }
  private append_point(point: CanvasPoint, pressure: number): void {
    if (this.liveStroke === null || this.strokeStopped) { return; }
    if (this.liveStroke.points.length >= this.pointBudget) { this.stop_stroke(); return; }
    const world: CanvasPoint | null = this.point(point);
    const previous: CanvasPoint = this.liveStroke.points[this.liveStroke.points.length - 1];
    if (world !== null) {
      if (this.liveStroke.penKind === CanvasPenKind.FOUNTAIN) { world.pressure = canvas_stored_pressure(pressure); }
      if (Math.hypot(world.x - previous.x,world.y - previous.y) > 0.01 ||
        Math.abs((world.pressure ?? 0) - (previous.pressure ?? 0)) > 0.01) {
        if (!this.strokeBudget.append(world)) { this.stop_stroke(); return; }
        this.liveStroke.points.push(world);
      }
    }
  }
  private stop_stroke(): void {
    this.strokeStopped = true;
    this.onFailure('作答容量已满，新的笔迹已停止；已有笔迹已保留，请抬笔后擦除部分内容再写');
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
    if (event.changedTouches.length === 0) { this.cancel_gesture(); this.paint(); return; }
    // 只有触屏设备内明确的 Pen 才可写；鼠标、未知或冲突标记均仅导航。
    const sourceTool: SourceTool = event.source === SourceType.TouchScreen ? event.sourceTool : SourceTool.Unknown;
    const single: boolean = event.changedTouches.length === 1;
    const wasEmpty: boolean = this.pointers.size === 0;
    for (let i: number = 0; i < event.changedTouches.length; i++) {
      const touch: TouchObject = event.changedTouches[i];
      if (!Number.isFinite(touch.x) || !Number.isFinite(touch.y)) {
        this.cancel_gesture(); this.paint(); return;
      }
      if (event.type === TouchType.Down && !this.pointers.has(touch.id)) {
        // sourceTool 属于整个事件；多 changedTouches 无逐点来源保证，只能用于导航。
        this.pointerSources.set(touch.id, single ? sourceTool : SourceTool.Unknown);
      } else if (event.type === TouchType.Down || !this.pointers.has(touch.id) || !single ||
        this.pointerSources.get(touch.id) !== sourceTool) {
        // 丢失 Down、来源变化或批量变化都不能取得/延续书写权限。
        this.pointerSources.set(touch.id, SourceTool.Unknown); this.suppress = true;
      }
      this.pointers.set(touch.id,{ x: touch.x, y: touch.y });
    }
    // touches 与 changedTouches 采样不同；只补登记未观察到的接触，不覆盖已采样坐标或推断来源。
    for (let i: number = 0; i < event.touches.length; i++) {
      const touch: TouchObject = event.touches[i];
      if (!this.pointers.has(touch.id)) {
        this.pointers.set(touch.id,{ x: touch.x, y: touch.y });
        this.pointerSources.set(touch.id, SourceTool.Unknown); this.suppress = true;
      }
    }
    if (!single || this.pointers.size !== 1) { this.suppress = true; }
    if (this.suppress || sourceTool !== SourceTool.Pen) { this.editPointerId = null; }
    if (event.type === TouchType.Down && single && wasEmpty && !this.suppress &&
      sourceTool === SourceTool.Pen) {
      this.editPointerId = event.changedTouches[0].id;
    }
    const changed: TouchObject = event.changedTouches[0];
    const editing: boolean = this.editPointerId === changed.id && single && !this.suppress &&
      sourceTool === SourceTool.Pen && !this.readOnly && this.tool !== CanvasTool.MOVE;
    const point: CanvasPoint = { x: changed.x, y: changed.y };
    if (editing) {
      this.navigationKey = ''; this.pinchView = null; this.pinchMid = null;
      if (event.type === TouchType.Down) {
        this.last = point;
        if (this.tool === CanvasTool.PEN && this.committedDocument !== null) {
          const world: CanvasPoint | null = this.point(point);
          let count: number = 0; this.committedDocument.strokes.forEach((stroke: CanvasStroke) => { count += stroke.points.length; });
          this.pointBudget = 200000 - count;
          if (world !== null && this.committedDocument.strokes.length < 20000 && this.pointBudget > 0) {
            const pressure: number = this.pressureSampler.begin(event.pressure);
            if (this.penKind === CanvasPenKind.FOUNTAIN) { world.pressure = canvas_stored_pressure(pressure); }
            this.liveStroke = { strokeId: util.generateRandomUUID(true).toLowerCase(),
              color: canvas_pen_color(this.penKind,this.penColor), width: canvas_pen_width(this.penKind,this.penSize),
              penKind: this.penKind, points: [world] };
            if (!this.strokeBudget.begin(this.committedDocument,this.liveStroke)) {
              this.liveStroke = null; this.stop_stroke();
            }
          } else { this.onFailure('笔迹已达到范围或数量上限，请减少后再写'); }
        } else if (this.tool === CanvasTool.ERASER && this.committedDocument !== null) {
          this.eraseBefore = this.committedDocument.strokes; this.erased = this.committedDocument.strokes.slice(); this.erase(point);
        }
      } else if (event.type === TouchType.Move) {
        if (this.liveStroke !== null) { this.append_point(point,this.pressureSampler.next(event.pressure)); }
        else { this.erase_path(point); }
        this.last = point;
      } else if (event.type === TouchType.Up && this.committedDocument !== null) {
        this.append_point(point,this.pressureSampler.next(event.pressure)); this.erase_path(point);
        if (this.liveStroke !== null) { this.commit(this.committedDocument.strokes.concat([this.liveStroke]),this.committedDocument.strokes); }
        if (this.erased !== null && this.eraseBefore !== null && this.erased.length !== this.eraseBefore.length) {
          this.commit(this.erased,this.eraseBefore);
        }
        if (this.strokeStopped) {
          this.onFailure('作答容量已满，已有笔迹已保留；请擦除部分内容后继续');
        }
        this.discard_edit(false); this.last = null; this.fulfill_freeze();
      }
    } else {
      this.discard_edit();
      const ids: number[] = [];
      const points: CanvasPoint[] = [];
      this.pointers.forEach((position: CanvasPoint, id: number) => {
        // 手指、未知、鼠标只导航；移动工具或只读状态也允许笔导航。
        if (this.pointerSources.get(id) !== SourceTool.Pen || this.readOnly || this.tool === CanvasTool.MOVE) {
          ids.push(id); points.push(position);
        }
      });
      const key: string = ids.join(':');
      if (key !== this.navigationKey) {
        this.last = null; this.pinchView = null; this.pinchMid = null; this.navigationKey = key;
      }
      if (points.length >= 2) {
        this.last = null;
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
      } else if (points.length === 1) {
        if (this.last !== null) {
          this.change_view({ x: this.view.x - (points[0].x - this.last.x) / this.view.scale,
            y: this.view.y - (points[0].y - this.last.y) / this.view.scale, scale: this.view.scale },false);
        }
        this.last = points[0];
      }
    }
    if (event.type === TouchType.Up) {
      event.changedTouches.forEach((touch: TouchObject) => {
        this.pointers.delete(touch.id); this.pointerSources.delete(touch.id);
      });
      this.last = null; this.pinchView = null; this.pinchMid = null; this.navigationKey = '';
      if (this.pointers.size === 0) { this.cancel_gesture(); this.onViewChange(this.view); }
    }
    this.paint();
  }
  private tool_selected(key: CanvasTool): boolean { return key === (this.readOnly ? CanvasTool.MOVE : this.tool); }
  @Builder
  tool_button(key: CanvasTool, icon: Resource, label: string) {
    Button() {
      Column({ space: 3 }) {
        Image(icon).width(18).height(18).objectFit(ImageFit.Contain)
          .fillColor(this.tool_selected(key) ? TABLET_COLOR_PRIMARY_DEEP : TABLET_COLOR_TEXT)
        Text(label).fontSize(10)
          .fontColor(this.tool_selected(key) ? TABLET_COLOR_PRIMARY_DEEP : TABLET_COLOR_TEXT)
      }
      .opacity(this.readOnly && key !== CanvasTool.MOVE ? 0.35 : 1)
    }
    .type(ButtonType.Normal).width(52).height(TABLET_CONTROL_SIZE).padding(0)
    .backgroundColor(this.tool_selected(key) ? TABLET_COLOR_PRIMARY_SOFT : Color.Transparent)
    .border({ width: 1, color: this.tool_selected(key) ? '#C5D7FC' : Color.Transparent })
    .borderRadius(TABLET_RADIUS_SMALL)
    .accessibilityText(key === CanvasTool.ERASER ? '整笔擦除' : label)
    .accessibilityDescription(this.tool_selected(key) ? '已选中' : '选择' +
      (key === CanvasTool.ERASER ? '整笔擦除' : label))
    .enabled(!this.readOnly || key === CanvasTool.MOVE)
    .onClick(() => { this.cancel_gesture(); this.tool = key; this.paint(); })
  }
  @Builder
  pen_button(kind: CanvasPenKind, label: string) {
    Button(label).type(ButtonType.Normal).width(56).height(TABLET_CONTROL_SIZE).padding(0).fontSize(12)
      .fontColor(this.tool === CanvasTool.PEN && this.penKind === kind ? TABLET_COLOR_PRIMARY_DEEP : TABLET_COLOR_TEXT)
      .backgroundColor(this.tool === CanvasTool.PEN && this.penKind === kind ? TABLET_COLOR_PRIMARY_SOFT : Color.Transparent)
      .borderRadius(TABLET_RADIUS_SMALL).enabled(!this.readOnly)
      .accessibilityText(label).accessibilityDescription(kind === CanvasPenKind.FOUNTAIN ? '压力控制笔宽' : '固定笔宽')
      .onClick(() => { this.cancel_gesture(); this.penKind = kind; this.tool = CanvasTool.PEN; this.paint(); })
  }
  @Builder
  tool_card() {
    Column({ space: 4 }) {
      Scroll() {
        Row({ space: 4 }) {
          this.pen_button(CanvasPenKind.BALLPOINT,'圆珠笔')
          this.pen_button(CanvasPenKind.FOUNTAIN,'钢笔')
          this.pen_button(CanvasPenKind.HIGHLIGHTER,'荧光笔')
          this.tool_button(CanvasTool.ERASER,$r('app.media.bl_eraser'),'擦除')
          this.tool_button(CanvasTool.MOVE,$r('app.media.bl_move'),'移动')
          Button() {
            Column({ space: 3 }) {
              Image($r('app.media.bl_undo')).width(18).height(18).objectFit(ImageFit.Contain).fillColor(TABLET_COLOR_TEXT)
              Text('撤销').fontSize(10).fontColor(TABLET_COLOR_TEXT)
            }.opacity(this.undoEnabled && !this.readOnly ? 1 : 0.3)
          }
          .type(ButtonType.Normal).width(52).height(TABLET_CONTROL_SIZE).padding(0)
          .backgroundColor(Color.Transparent).borderRadius(TABLET_RADIUS_SMALL)
          .accessibilityText('撤销').accessibilityDescription('撤销上一次笔迹操作')
          .onClick(() => { this.undo(); }).enabled(this.undoEnabled && !this.readOnly)
        }.height(TABLET_CONTROL_SIZE)
      }.scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Off).edgeEffect(EdgeEffect.None).width('100%')
      Scroll() {
        Row({ space: 6 }) {
          ForEach(CANVAS_PEN_COLORS,(color: string) => {
            Button() { Circle().width(18).height(18).fill(color) }
              .type(ButtonType.Normal).width(30).height(32).padding(0).backgroundColor(Color.Transparent)
              .border({ width: this.penColor === color ? 2 : 0, color: TABLET_COLOR_PRIMARY }).borderRadius(8)
              .accessibilityText('画笔颜色 ' + color).enabled(!this.readOnly)
              .onClick(() => { this.cancel_gesture(); this.penColor = color; this.tool = CanvasTool.PEN; this.paint(); })
          },(color: string) => color)
          Divider().vertical(true).height(20).color(TABLET_COLOR_BORDER)
          ForEach(CANVAS_PEN_SIZES,(size: number) => {
            Button(size === 0 ? '细' : size === 1 ? '中' : '粗')
              .type(ButtonType.Normal).width(36).height(32).padding(0).fontSize(12)
              .backgroundColor(this.penSize === size ? TABLET_COLOR_PRIMARY_SOFT : Color.Transparent)
              .fontColor(TABLET_COLOR_TEXT).borderRadius(8).enabled(!this.readOnly)
              .accessibilityText('画笔粗细 ' + (size === 0 ? '细' : size === 1 ? '中' : '粗'))
              .onClick(() => { this.cancel_gesture(); this.penSize = size; this.tool = CanvasTool.PEN; this.paint(); })
          },(size: number) => size.toString())
        }.height(32)
      }.scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Off).edgeEffect(EdgeEffect.None).width('100%')
    }
    .hitTestBehavior(HitTestMode.Default)
    .onTouch((event: TouchEvent) => { this.control_touch(event); })
    .width(Math.max(TABLET_CONTROL_SIZE,Math.min(384,this.chromeWidthVp - 32))).height(TABLET_CONTROL_SIZE + 52)
    .padding(8).backgroundColor(TABLET_COLOR_CARD).borderRadius(16)
    .border({ width: 1, color: TABLET_COLOR_BORDER })
    .shadow({ radius: 18, color: '#14246BFF', offsetY: 6 })
    .layoutGravity(LocalizedAlignment.BOTTOM).margin({ bottom: 16 })
  }
  @Builder
  zoom_card() {
    Scroll() {
      Row() {
        Button() { Image($r('app.media.bl_zoom_out')).width(18).height(18).objectFit(ImageFit.Contain) }
          .type(ButtonType.Normal).width(TABLET_CONTROL_SIZE).height(TABLET_CONTROL_SIZE).padding(0)
          .backgroundColor(Color.Transparent).borderRadius(TABLET_RADIUS_SMALL).accessibilityText('缩小')
          .onClick(() => { this.change_view(zoom_canvas(this.view,0.8,this.canvasWidthVp / 2,this.canvasHeightVp / 2),true); })
        Text(this.zoomText).fontSize(13).fontColor(TABLET_COLOR_PRIMARY_DEEP).fontWeight(FontWeight.Medium)
          .width(TABLET_CONTROL_SIZE).height(TABLET_CONTROL_SIZE).textAlign(TextAlign.Center).maxLines(1)
          .accessibilityText('当前缩放比例 ' + this.zoomText)
        Button() { Image($r('app.media.bl_zoom_in')).width(18).height(18).objectFit(ImageFit.Contain) }
          .type(ButtonType.Normal).width(TABLET_CONTROL_SIZE).height(TABLET_CONTROL_SIZE).padding(0)
          .backgroundColor(Color.Transparent).borderRadius(TABLET_RADIUS_SMALL).accessibilityText('放大')
          .onClick(() => { this.change_view(zoom_canvas(this.view,1.25,this.canvasWidthVp / 2,this.canvasHeightVp / 2),true); })
        Divider().vertical(true).height(24).color(TABLET_COLOR_BORDER).margin({ left: 6, right: 6 })
        Button() { Image($r('app.media.bl_fit')).width(18).height(18).objectFit(ImageFit.Contain) }
          .type(ButtonType.Normal).width(TABLET_CONTROL_SIZE).height(TABLET_CONTROL_SIZE).padding(0)
          .backgroundColor(Color.Transparent).borderRadius(TABLET_RADIUS_SMALL).accessibilityText('查看全貌')
          .onClick(() => { this.change_view(fit_canvas(this.bounds(),this.canvasWidthVp,this.canvasHeightVp),true); })
      }.height(TABLET_CONTROL_SIZE)
    }
    .scrollable(ScrollDirection.Horizontal).scrollBar(BarState.Off).edgeEffect(EdgeEffect.None)
    .hitTestBehavior(HitTestMode.Default)
    .onTouch((event: TouchEvent) => { this.control_touch(event); })
    .width(Math.max(TABLET_CONTROL_SIZE,Math.min(208,this.chromeWidthVp - 32))).height(TABLET_CONTROL_SIZE + 16)
    .padding(8).backgroundColor(TABLET_COLOR_CARD).borderRadius(14)
    .border({ width: 1, color: TABLET_COLOR_BORDER })
    .shadow({ radius: 12, color: '#10246BFF', offsetY: 4 })
    .layoutGravity(LocalizedAlignment.BOTTOM_END).margin({ right: 16, bottom: this.chromeWidthVp < 1000 ? 144 : 16 })
  }
  build() {
    Stack({ alignContent: Alignment.Bottom }) {
      Canvas(this.context).width('100%').height('100%')
        .onReady(() => { this.ready = true; this.configure_view(); })
        .onAreaChange((_old: Area, area: Area) => {
          const width: number = typeof area.width === 'number' ? area.width : parseFloat(area.width.toString());
          const height: number = typeof area.height === 'number' ? area.height : parseFloat(area.height.toString());
          if (Number.isFinite(width) && Number.isFinite(height) && width > 0 && height > 0) {
            this.chromeWidthVp = width;
            this.canvasWidthVp = width; this.canvasHeightVp = height; this.configure_view();
          }
        })
        .onTouch((event: TouchEvent) => { this.touch(event); })
      if (this.chromeWidthVp > 0) {
        this.tool_card()
        this.zoom_card()
      }
    }.width('100%').height('100%').backgroundColor(TABLET_COLOR_BACKGROUND)
  }
}
