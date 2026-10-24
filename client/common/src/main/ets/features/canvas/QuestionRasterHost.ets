import { common } from '@kit.AbilityKit';
import { LengthMetricsUnit } from '@kit.ArkUI';
import { webview } from '@kit.ArkWeb';
import { image } from '@kit.ImageKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { CanvasImageBlock, CanvasLocalFile, CanvasQuestionLayout, CanvasRect, CanvasTextBlock,
  CANVAS_CONTENT_WIDTH, CANVAS_FONT_SIZE, CANVAS_RENDERER_ID, CANVAS_TILE_SIZE } from './CanvasModels';
import { canvas_sha256, canvas_utf8, canvas_write } from './CanvasFiles';
import { canvas_png } from './CanvasImages';

const ORIGIN: string = 'https://question-markdown.betterlearning.invalid/';
const URL: string = ORIGIN + 'canvas-raster.html';
export interface QuestionRasterResult { layout: CanvasQuestionLayout; files: CanvasLocalFile[]; }
interface RasterStatus { batch: string; ready: boolean; error: string; bounds: CanvasRect; blocks: CanvasTextBlock[]; tileReady: number; }
interface SnapshotAttempt { map: image.PixelMap | null; retryable: boolean; reason: string; code: number; }
function json_result(value: string): RasterStatus {
  const first: Object = JSON.parse(value) as Object;
  return (typeof first === 'string' ? JSON.parse(first as string) : first) as RasterStatus;
}

/** 有界原生 Web 栅格宿主；只在布局准备时挂载，保持实际绘制能力。 */
@Component
export struct QuestionRasterHost {
  @Prop @Watch('restart') bodyMarkdown: string = '';
  @Prop @Watch('restart') directory: string = '';
  onReady: (result: QuestionRasterResult) => void = () => {};
  onFailure: (message: string) => void = () => {};
  requestActive: () => boolean = () => true;
  private controller: webview.WebviewController = new webview.WebviewController();
  private alive: boolean = true;
  private generation: number = 0;
  private started: boolean = false;
  private pageReady: boolean = false;
  private firstPainted: boolean = false;
  private pageVisible: boolean = false;
  private areaVisible: boolean = false;
  private hostSized: boolean = false;
  private sequence: Promise<void> = Promise.resolve();
  private restart(): void {
    this.generation++; this.started = false;
    if (this.pageReady && this.alive && this.requestActive()) { this.sequence = this.sequence.then(() => this.prepare()); }
  }

  aboutToAppear(): void {
    webview.WebviewController.enableWholeWebPageDrawing(); this.alive = true; this.started = false;
    this.pageReady = false; this.firstPainted = false; this.pageVisible = false;
    this.areaVisible = false; this.hostSized = false; this.generation++;
  }
  aboutToDisappear(): void {
    this.alive = false; this.generation++; this.started = false; this.pageReady = false;
    this.firstPainted = false; this.pageVisible = false; this.areaVisible = false; this.hostSized = false;
  }
  private resource(url: string): WebResourceResponse {
    const response: WebResourceResponse = new WebResourceResponse();
    const path: string = url.startsWith(ORIGIN) ? url.substring(ORIGIN.length) : '';
    const allowed: boolean = /^(canvas-raster\.(html|js|css)|renderer\.(js|css)|vendor\/(markdown-it\.min\.js|texmath\.(js|css)|katex\.min\.(js|css)|fonts\/KaTeX_[A-Za-z0-9_-]+\.(woff2|woff|ttf)))$/.test(path);
    response.setResponseEncoding('utf-8');
    response.setResponseHeader([{ headerKey: 'Cache-Control', headerValue: 'no-store' }]);
    try {
      const context: common.Context | undefined = this.getUIContext().getHostContext();
      if (!allowed || context === undefined) { throw new Error('Forbidden'); }
      const bytes: Uint8Array = context.resourceManager.getRawFileContentSync('question-markdown/' + path);
      response.setResponseCode(200); response.setReasonMessage('OK');
      const mime: string = path.endsWith('.js') ? 'application/javascript' : path.endsWith('.css') ? 'text/css' :
        path.endsWith('.woff2') ? 'font/woff2' : path.endsWith('.woff') ? 'font/woff' : path.endsWith('.ttf') ? 'font/ttf' : 'text/html';
      response.setResponseMimeType(mime);
      response.setResponseData(bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer);
    } catch (error) {
      response.setResponseCode(403); response.setReasonMessage('Forbidden');
      response.setResponseMimeType('text/plain'); response.setResponseData('');
    }
    response.setResponseIsReady(true); return response;
  }
  private async wait_status(tile: number, batch: string, active: () => boolean, deadline: number): Promise<RasterStatus> {
    for (let i: number = 0; i < 200 && active() && Date.now() < deadline; i++) {
      const status: RasterStatus = json_result(await this.controller.runJavaScript('JSON.stringify(window.BetterLearningRaster.status())'));
      if (!active() || Date.now() >= deadline) { throw new Error('题面生成已停止'); }
      if (status.batch === batch) {
        if (status.error.length > 0) { throw new Error(status.error); }
        if (status.ready && (tile < 0 || status.tileReady === tile)) { return status; }
      }
      await new Promise<void>((resolve: () => void) => { setTimeout(resolve, 50); });
    }
    throw new Error('题面渲染等待超时');
  }
  private async wait_capture_ready(active: () => boolean, deadline: number): Promise<void> {
    const signalUntil: number = Date.now() + 1000;
    const until: number = Math.min(deadline,Date.now() + 8000);
    while (active() && Date.now() < until) {
      if (this.pageReady && this.pageVisible && this.areaVisible && this.hostSized) {
        if (this.firstPainted) { return; }
        // 首绘事件优先；事件缺席时仍由原生快照判定，不能让空白初始 DOM 永久卡住。
        if (Date.now() >= signalUntil) {
          hilog.warn(0x0000,'M2Raster','paint_signal_pending; bounded_native_snapshot_probe'); return;
        }
      }
      await new Promise<void>((resolve: () => void) => { setTimeout(resolve,50); });
    }
    hilog.warn(0x0000,'M2Raster','paint_gate failed painted=%{public}s visible=%{public}s area=%{public}s sized=%{public}s',
      this.firstPainted.toString(),this.pageVisible.toString(),this.areaVisible.toString(),this.hostSized.toString());
    throw new Error('题面绘制尚未就绪');
  }
  private capture_once(width: number, height: number, id: string, active: () => boolean): Promise<SnapshotAttempt> {
    return new Promise<SnapshotAttempt>((resolve: (result: SnapshotAttempt) => void) => {
      let settled: boolean = false;
      const timer: number = setTimeout(() => {
        if (!settled) { settled = true; resolve({ map: null, retryable: false, reason: 'callback_timeout', code: 0 }); }
      },3000);
      try {
        this.controller.webPageSnapshot({ id: id, size: { width: width.toString() + 'vp', height: height.toString() + 'vp' } },
          (error: BusinessError, result: webview.SnapshotResult) => {
            const map: image.PixelMap | null = result?.imagePixelMap ?? null;
            if (settled || !active()) {
              if (map !== null) { map.release().catch(() => {}); }
              if (!settled) { settled = true; clearTimeout(timer); resolve({ map: null, retryable: false, reason: 'cancelled', code: 0 }); }
              return;
            }
            settled = true; clearTimeout(timer);
            if (error || !result || result.status === false || map === null) {
              if (map !== null) { map.release().catch(() => {}); }
              const message: string = error && typeof error.message === 'string' ? error.message : '';
              const notPainted: boolean = /not.*paint.*ready|paint.*not.*ready/i.test(message);
              // 此平台对未首绘可能只返回 status=false；其他原生错误不重试。
              resolve({ map: null, retryable: notPainted || (!error && result?.status === false),
                reason: notPainted ? 'Web is not painted ready for snapshot' : result?.status === false ? 'status_false' : 'native_error',
                code: error && typeof error.code === 'number' ? error.code : 0 });
            } else { resolve({ map: map, retryable: false, reason: 'ok', code: 0 }); }
          });
      } catch (error) {
        if (!settled) {
          settled = true; clearTimeout(timer);
          const native: BusinessError = error as BusinessError;
          const notPainted: boolean = typeof native.message === 'string' && /not.*paint.*ready|paint.*not.*ready/i.test(native.message);
          resolve({ map: null, retryable: notPainted, reason: notPainted ? 'Web is not painted ready for snapshot' : 'native_throw',
            code: typeof native.code === 'number' ? native.code : 0 });
        }
      }
    });
  }
  private async capture(width: number, height: number, tile: number, batch: string,
    active: () => boolean, deadline: number): Promise<image.PixelMap> {
    for (let attempt: number = 0; attempt < 4; attempt++) {
      await this.wait_capture_ready(active,deadline);
      if (!active() || Date.now() >= deadline) { throw new Error('题面生成已停止'); }
      const result: SnapshotAttempt = await this.capture_once(width,height,'m2-' + batch + '-' + tile,active);
      if (!active()) { if (result.map !== null) { await result.map.release().catch(() => {}); } throw new Error('题面生成已停止'); }
      if (result.map !== null) { return result.map; }
      hilog.warn(0x0000,'M2Raster','snapshot tile=%{public}d attempt=%{public}d code=%{public}d reason=%{public}s',
        tile,attempt + 1,result.code,result.reason);
      if (!result.retryable || attempt === 3 || Date.now() >= deadline) { throw new Error('题面快照失败'); }
      await new Promise<void>((resolve: () => void) => { setTimeout(resolve,(attempt + 1) * 100); });
    }
    throw new Error('题面快照失败');
  }
  private async prepare(): Promise<void> {
    if (this.started || !this.alive || !this.pageReady || !this.requestActive()) { return; }
    this.started = true;
    const generation: number = this.generation;
    const body: string = this.bodyMarkdown; const directory: string = this.directory;
    const active: () => boolean = () => this.alive && this.requestActive() && generation === this.generation && body === this.bodyMarkdown && directory === this.directory;
    const batch: string = generation.toString(); const deadline: number = Date.now() + 120000;
    try {
      const literal: string = JSON.stringify(body).split(String.fromCharCode(0x2028)).join('\\u2028')
        .split(String.fromCharCode(0x2029)).join('\\u2029');
      await this.controller.runJavaScript('window.BetterLearningRaster.prepare(' + literal + ',' + CANVAS_CONTENT_WIDTH + ',' + CANVAS_FONT_SIZE + ',' + JSON.stringify(batch) + ')');
      const status: RasterStatus = await this.wait_status(-1,batch,active,deadline);
      const bounds: CanvasRect = status.bounds;
      const count: number = Math.ceil(bounds.width / CANVAS_TILE_SIZE) * Math.ceil(bounds.height / CANVAS_TILE_SIZE);
      if (!Number.isFinite(count) || count > 256 || bounds.width * bounds.height > 64000000) { throw new Error('完整题面过大，不能截断保存'); }
      const blocks: CanvasImageBlock[] = []; const files: CanvasLocalFile[] = [];
      const bodyHash: string = await canvas_sha256(canvas_utf8(body));
      for (let y: number = bounds.y; y < bounds.y + bounds.height; y += CANVAS_TILE_SIZE) {
        for (let x: number = bounds.x; x < bounds.x + bounds.width; x += CANVAS_TILE_SIZE) {
          if (!active()) { return; }
          if (Date.now() >= deadline) { throw new Error('题面生成超时'); }
          const width: number = Math.min(CANVAS_TILE_SIZE, bounds.x + bounds.width - x);
          const height: number = Math.min(CANVAS_TILE_SIZE, bounds.y + bounds.height - y);
          const index: number = blocks.length;
          await this.controller.runJavaScript('window.BetterLearningRaster.tile(' + [x,y,width,height,index].join(',') + ',' + JSON.stringify(batch) + ')');
          await this.wait_status(index,batch,active,deadline);
          const snapshot: image.PixelMap = await this.capture(width,height,index,batch,active,deadline);
          let png: Uint8Array;
          try {
            const info: image.ImageInfo = await snapshot.getImageInfo();
            if (!active() || Date.now() >= deadline) { throw new Error('题面生成已停止'); }
            if (info.size.width < width || info.size.height < height || Math.abs(info.size.width / width - info.size.height / height) > 0.05) {
              throw new Error('题面快照尺寸不符合布局');
            }
            const canvas: OffscreenCanvas = new OffscreenCanvas(width, height, LengthMetricsUnit.PX);
            const context: OffscreenCanvasRenderingContext2D = canvas.getContext('2d');
            context.fillStyle = '#FFFFFF'; context.fillRect(0,0,width,height); context.drawImage(snapshot,0,0,width,height);
            const normalized: image.PixelMap = context.getPixelMap(0,0,width,height);
            try { png = await canvas_png(normalized); } finally { await normalized.release(); }
          } finally { await snapshot.release(); }
          if (!active() || Date.now() >= deadline) { throw new Error('题面生成已停止'); }
          const sha: string = await canvas_sha256(png);
          const path: string = directory + '/rasters/' + sha + '.png';
          if (!active()) { return; }
          if (Date.now() >= deadline) { throw new Error('题面生成超时'); }
          canvas_write(path, png);
          const name: string = 'question_' + index.toString().padStart(4,'0');
          blocks.push({ index: index, partName: name, x: x, y: y, width: width, height: height,
            pixelWidth: width, pixelHeight: height, sha256: sha });
          files.push({ partName: name, filePath: path });
        }
      }
      if (active()) {
        this.onReady({ layout: { questionBodySha256: bodyHash, contentWidth: CANVAS_CONTENT_WIDTH,
          fontSize: CANVAS_FONT_SIZE, rendererId: CANVAS_RENDERER_ID, bounds: bounds, blocks: status.blocks, rasterBlocks: blocks }, files: files });
      }
    } catch (error) {
      if (active()) {
        hilog.warn(0x0000,'M2Raster','prepare_failed painted=%{public}s pageVisible=%{public}s sized=%{public}s',
          this.firstPainted.toString(),this.pageVisible.toString(),this.hostSized.toString());
        this.onFailure('题面加载失败，请重试');
      }
    }
  }
  build() {
    Web({ src: URL, controller: this.controller })
      .width(CANVAS_TILE_SIZE).height(CANVAS_TILE_SIZE)
      .onAreaChange((_old: Area, area: Area) => {
        const width: number = typeof area.width === 'number' ? area.width : parseFloat(area.width.toString());
        const height: number = typeof area.height === 'number' ? area.height : parseFloat(area.height.toString());
        this.hostSized = Number.isFinite(width) && Number.isFinite(height) && width > 0 && height > 0;
      })
      .onVisibleAreaChange([0,0.01], (visible: boolean, ratio: number) => { this.areaVisible = visible && ratio > 0; })
      .javaScriptAccess(true).domStorageAccess(false).databaseAccess(false).fileAccess(false)
      .onlineImageAccess(false).imageAccess(false).mixedMode(MixedMode.None)
      .onInterceptRequest((event) => this.resource(event.request.getRequestUrl()))
      .onLoadIntercept((event) => event.data.getRequestUrl() !== URL)
      .onPermissionRequest((event) => { event.request.deny(); })
      .onPageBegin(() => { this.pageReady = false; this.firstPainted = false; this.pageVisible = false; this.generation++; this.started = false; })
      // 空 article 可能尚无内容首绘：先在 pageEnd 渲染 DOM，首绘门禁放在捕获前。
      .onPageEnd(() => { this.pageReady = true; this.restart(); })
      .onPageVisible(() => { this.pageVisible = true; })
      .onFirstContentfulPaint(() => { this.firstPainted = true; })
      .onFirstMeaningfulPaint(() => { this.firstPainted = true; })
      .onRenderExited(() => {
        this.generation++; this.pageReady = false; this.firstPainted = false;
        if (this.alive && this.requestActive()) { this.onFailure('题面加载失败，请重试'); }
      })
      .onErrorReceive(() => { if (this.alive && this.requestActive()) { this.onFailure('题面加载失败，请重试'); } })
  }
}
