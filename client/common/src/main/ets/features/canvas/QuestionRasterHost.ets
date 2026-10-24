import { common } from '@kit.AbilityKit';
import { LengthMetricsUnit } from '@kit.ArkUI';
import { webview } from '@kit.ArkWeb';
import { image } from '@kit.ImageKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { CanvasImageBlock, CanvasLocalFile, CanvasQuestionLayout, CanvasRect, CanvasTextBlock,
  CANVAS_CONTENT_WIDTH, CANVAS_FONT_SIZE, CANVAS_RENDERER_ID, CANVAS_TILE_SIZE } from './CanvasModels';
import { canvas_sha256, canvas_utf8, canvas_write } from './CanvasFiles';
import { canvas_png } from './CanvasImages';

const ORIGIN: string = 'https://question-markdown.betterlearning.invalid/';
const URL: string = ORIGIN + 'canvas-raster.html';
export interface QuestionRasterResult { layout: CanvasQuestionLayout; files: CanvasLocalFile[]; }
interface RasterStatus { ready: boolean; error: string; bounds: CanvasRect; blocks: CanvasTextBlock[]; tileReady: number; }
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
  private controller: webview.WebviewController = new webview.WebviewController();
  private alive: boolean = true;
  private generation: number = 0;
  private started: boolean = false;
  private pageReady: boolean = false;
  private sequence: Promise<void> = Promise.resolve();
  private restart(): void {
    this.generation++; this.started = false;
    if (this.pageReady && this.alive) { this.sequence = this.sequence.then(() => this.prepare()); }
  }

  aboutToAppear(): void { webview.WebviewController.enableWholeWebPageDrawing(); this.alive = true; this.started = false; this.generation++; }
  aboutToDisappear(): void { this.alive = false; this.generation++; }
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
  private async wait_status(tile: number = -1, active: () => boolean = () => this.alive): Promise<RasterStatus> {
    for (let i: number = 0; i < 200 && active(); i++) {
      const status: RasterStatus = json_result(await this.controller.runJavaScript('JSON.stringify(window.BetterLearningRaster.status())'));
      if (status.error.length > 0) { throw new Error(status.error); }
      if (status.ready && (tile < 0 || status.tileReady === tile)) { return status; }
      await new Promise<void>((resolve: () => void) => { setTimeout(resolve, 50); });
    }
    throw new Error('题面渲染等待超时');
  }
  private async capture(width: number, height: number): Promise<image.PixelMap> {
    return new Promise<image.PixelMap>((resolve, reject) => {
      this.controller.webPageSnapshot({ size: { width: width.toString() + 'vp', height: height.toString() + 'vp' } },
        (error: BusinessError, result: webview.SnapshotResult) => {
          if (error || !result || result.status === false || result.imagePixelMap === undefined) { reject(new Error('题面完整绘制失败')); }
          else { resolve(result.imagePixelMap); }
        });
    });
  }
  private async prepare(): Promise<void> {
    if (this.started) { return; }
    this.started = true;
    const generation: number = this.generation;
    const body: string = this.bodyMarkdown; const directory: string = this.directory;
    const active: () => boolean = () => this.alive && generation === this.generation && body === this.bodyMarkdown && directory === this.directory;
    try {
      const literal: string = JSON.stringify(body).split(String.fromCharCode(0x2028)).join('\\u2028')
        .split(String.fromCharCode(0x2029)).join('\\u2029');
      await this.controller.runJavaScript('window.BetterLearningRaster.prepare(' + literal + ',' + CANVAS_CONTENT_WIDTH + ',' + CANVAS_FONT_SIZE + ')');
      const status: RasterStatus = await this.wait_status(-1,active);
      const bounds: CanvasRect = status.bounds;
      const count: number = Math.ceil(bounds.width / CANVAS_TILE_SIZE) * Math.ceil(bounds.height / CANVAS_TILE_SIZE);
      if (!Number.isFinite(count) || count > 256 || bounds.width * bounds.height > 64000000) { throw new Error('完整题面过大，不能截断保存'); }
      const blocks: CanvasImageBlock[] = []; const files: CanvasLocalFile[] = [];
      const bodyHash: string = await canvas_sha256(canvas_utf8(body));
      for (let y: number = bounds.y; y < bounds.y + bounds.height; y += CANVAS_TILE_SIZE) {
        for (let x: number = bounds.x; x < bounds.x + bounds.width; x += CANVAS_TILE_SIZE) {
          if (!active()) { return; }
          const width: number = Math.min(CANVAS_TILE_SIZE, bounds.x + bounds.width - x);
          const height: number = Math.min(CANVAS_TILE_SIZE, bounds.y + bounds.height - y);
          const index: number = blocks.length;
          await this.controller.runJavaScript('window.BetterLearningRaster.tile(' + [x,y,width,height,index].join(',') + ')');
          await this.wait_status(index,active);
          const snapshot: image.PixelMap = await this.capture(width, height);
          let png: Uint8Array;
          try {
            const info: image.ImageInfo = await snapshot.getImageInfo();
            if (info.size.width < width || info.size.height < height || Math.abs(info.size.width / width - info.size.height / height) > 0.05) {
              throw new Error('题面快照尺寸不符合布局');
            }
            const canvas: OffscreenCanvas = new OffscreenCanvas(width, height, LengthMetricsUnit.PX);
            const context: OffscreenCanvasRenderingContext2D = canvas.getContext('2d');
            context.fillStyle = '#FFFFFF'; context.fillRect(0,0,width,height); context.drawImage(snapshot,0,0,width,height);
            const normalized: image.PixelMap = context.getPixelMap(0,0,width,height);
            try { png = await canvas_png(normalized); } finally { await normalized.release(); }
          } finally { await snapshot.release(); }
          const sha: string = await canvas_sha256(png);
          const path: string = directory + '/rasters/' + sha + '.png';
          if (!active()) { return; }
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
    } catch (error) { if (active()) { this.onFailure('固定题面生成失败，请重试；未使用不完整截图'); } }
  }
  build() {
    Web({ src: URL, controller: this.controller })
      .width(CANVAS_TILE_SIZE).height(CANVAS_TILE_SIZE)
      .javaScriptAccess(true).domStorageAccess(false).databaseAccess(false).fileAccess(false)
      .onlineImageAccess(false).imageAccess(false).mixedMode(MixedMode.None)
      .onInterceptRequest((event) => this.resource(event.request.getRequestUrl()))
      .onLoadIntercept((event) => event.data.getRequestUrl() !== URL)
      .onPermissionRequest((event) => { event.request.deny(); })
      .onPageEnd(() => { this.pageReady = true; this.restart(); })
      .onErrorReceive(() => { if (this.alive) { this.onFailure('题面资源加载失败，请重试'); } })
  }
}
