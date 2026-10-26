import { common } from '@kit.AbilityKit';
import { webview } from '@kit.ArkWeb';
import { MAX_QUESTION_MARKDOWN_LENGTH } from './LearningModels';
import { V2Citation } from '../multiagent/LearningV2Models';
import { citation_marker_ids } from '../multiagent/CitationMarkers';
import { citationFocusController } from '../multiagent/CitationFocusController';

interface MarkdownCitationLabel { citationId: string; label: string; }
class MarkdownCitationBridge {
  private handler: (citationId: string) => void;
  onHeight: (height: number, revision: number) => void = () => {};
  constructor(handler: (citationId: string) => void) { this.handler = handler; }
  open(citationId: string): void { this.handler(citationId); }
  resize(height: number, revision: number): void { this.onHeight(height, revision); }
}

/** 仅用于拦截并映射本地资源；此域名不会作为远端服务访问。 */
const RENDERER_ORIGIN: string = 'https://question-markdown.betterlearning.invalid/';
const RENDERER_URL: string = RENDERER_ORIGIN + 'index.html';
const RAWFILE_ROOT: string = 'question-markdown/';

function resource_mime(path: string): string {
  if (path.endsWith('.js')) {
    return 'application/javascript';
  }
  if (path.endsWith('.css')) {
    return 'text/css';
  }
  if (path.endsWith('.woff2')) {
    return 'font/woff2';
  }
  if (path.endsWith('.woff')) {
    return 'font/woff';
  }
  if (path.endsWith('.ttf')) {
    return 'font/ttf';
  }
  return 'text/html';
}

function allowed_resource(path: string): boolean {
  return path === 'index.html' || path === 'renderer.js' || path === 'renderer.css' ||
    path === 'vendor/markdown-it.min.js' || path === 'vendor/texmath.js' || path === 'vendor/texmath.css' ||
    path === 'vendor/katex.min.js' || path === 'vendor/katex.min.css' ||
    /^vendor\/fonts\/KaTeX_[A-Za-z0-9_-]+\.(woff2|woff|ttf)$/.test(path);
}

/**
 * Phone/Tablet 共用的本地 Markdown/KaTeX 题面。
 * 父级给出宽高，题面在组件内滚动；只读展示，不承担 M2 画布或作答保存。
 * 所有 Web 请求返回本地白名单资源或明确拒绝，正文不传给 URL、日志或远端。
 */
@Component
export struct QuestionMarkdownView {
  @Prop @Watch('content_changed') bodyMarkdown: string = '';
  @Prop @Watch('content_changed') fontSize: number = 16;
  @Prop @Watch('content_changed') maxLength: number = MAX_QUESTION_MARKDOWN_LENGTH;
  @Prop @Watch('content_changed') highlightBlock: boolean = false;
  @Prop @Watch('content_changed') citations: V2Citation[] = [];
  // 显式启用时交给外层 List 纵向滚动；默认仍保留 Tablet 的定高内部滚动。
  @Prop @Watch('content_changed') autoHeight: boolean = false;
  onContentHeight: (height: number) => void = () => {};
  onCitation: (citationId: string) => void = () => {};
  onRenderError: () => void = () => {
  };
  @State private hasRenderError: boolean = false;
  @State private contentHeight: number = 48;
  private controller: webview.WebviewController = new webview.WebviewController();
  private citationBridge: MarkdownCitationBridge = new MarkdownCitationBridge((citationId: string) => {
    if (this.pageReady && citation_marker_ids(this.bodyMarkdown,this.citations).includes(citationId)) {
      citationFocusController.attach(citationId,this.restoreCitationFocus); this.onCitation(citationId);
    }
  });
  private restoreCitationFocus: (citationId: string) => void = (citationId: string) => {
    if (this.pageReady) {
      this.controller.runJavaScript('window.BetterLearningQuestion.restoreCitationFocus(' + JSON.stringify(citationId) + ')');
    }
  };
  private pageReady: boolean = false;
  private renderRevision: number = 0;
  private lastRenderKey: string = '';

  aboutToAppear(): void {
    this.citationBridge.onHeight = (height: number, revision: number) => {
      if (!this.autoHeight || !this.pageReady || revision !== this.renderRevision ||
        !Number.isFinite(height) || height <= 0) { return; }
      // 极长正文转成同页原文，避免平台 Web 表面尺寸上限造成静默裁切。
      if (height > 20000) { this.report_error(revision); return; }
      const next: number = Math.max(32, Math.ceil(height));
      if (next !== this.contentHeight) {
        this.contentHeight = next;
        this.onContentHeight(next);
      }
    };
  }

  aboutToDisappear(): void {
    citationFocusController.detach(this.restoreCitationFocus);
    this.pageReady = false;
    this.renderRevision++;
  }

  private local_response(url: string, method: string): WebResourceResponse {
    const response: WebResourceResponse = new WebResourceResponse();
    response.setResponseEncoding('utf-8');
    response.setResponseHeader([
      { headerKey: 'Cache-Control', headerValue: 'no-store' },
      { headerKey: 'X-Content-Type-Options', headerValue: 'nosniff' }
    ]);
    const path: string = url.startsWith(RENDERER_ORIGIN) ? url.substring(RENDERER_ORIGIN.length) : '';
    if (method !== 'GET' || !allowed_resource(path)) {
      response.setResponseCode(403);
      response.setReasonMessage('Forbidden');
      response.setResponseMimeType('text/plain');
      response.setResponseData('');
      response.setResponseIsReady(true);
      return response;
    }
    try {
      const context: common.Context | undefined = this.getUIContext().getHostContext();
      if (context === undefined) {
        throw new Error('Renderer context unavailable');
      }
      const bytes: Uint8Array = context.resourceManager.getRawFileContentSync(RAWFILE_ROOT + path);
      response.setResponseCode(200);
      response.setReasonMessage('OK');
      response.setResponseMimeType(resource_mime(path));
      response.setResponseData(bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength) as ArrayBuffer);
    } catch (error) {
      response.setResponseCode(404);
      response.setReasonMessage('Not Found');
      response.setResponseMimeType('text/plain');
      response.setResponseData('');
    }
    response.setResponseIsReady(true);
    return response;
  }

  private content_changed(): void {
    this.render_content();
  }

  private async render_content(): Promise<void> {
    if (!this.pageReady) {
      return;
    }
    if (this.bodyMarkdown.length > Math.min(65536,Math.max(1,this.maxLength))) {
      this.report_error(++this.renderRevision);
      return;
    }
    const size: number = Number.isFinite(this.fontSize) ? Math.max(12, Math.min(32, this.fontSize)) : 16;
    // JSON 序列化仅作为 JS 字符串值传参，不把题面插入 HTML 或模板字符串。
    const markdownLiteral: string = JSON.stringify(this.bodyMarkdown)
      .split(String.fromCharCode(0x2028)).join('\\u2028').split(String.fromCharCode(0x2029)).join('\\u2029');
    const labels: MarkdownCitationLabel[] = this.citations.map((citation: V2Citation): MarkdownCitationLabel =>
      ({ citationId: citation.citationId, label: citation.label }));
    const citationsLiteral: string = JSON.stringify(labels).split(String.fromCharCode(0x2028)).join('\\u2028')
      .split(String.fromCharCode(0x2029)).join('\\u2029');
    const renderKey: string = markdownLiteral + '\n' + citationsLiteral + '\n' + size.toString() + ':' + this.highlightBlock.toString() + ':' + this.autoHeight.toString();
    if (renderKey === this.lastRenderKey && !this.hasRenderError) { return; }
    const revision: number = ++this.renderRevision;
    this.hasRenderError = false;
    const script: string = 'window.BetterLearningQuestion.render(' + markdownLiteral + ',' + size.toString() + ',' + (this.highlightBlock ? 'true' : 'false') + ',' + citationsLiteral + ',' + this.autoHeight.toString() + ',' + revision.toString() + ')';
    try {
      const result: string = await this.controller.runJavaScript(script);
      if (revision !== this.renderRevision || !this.pageReady) {
        return;
      }
      if (result !== 'true') {
        this.report_error(revision);
      } else { this.lastRenderKey = renderKey; }
    } catch (error) {
      this.report_error(revision);
    }
  }

  private report_error(revision: number): void {
    if (revision !== this.renderRevision || !this.pageReady) {
      return;
    }
    this.hasRenderError = true;
    this.onRenderError();
  }

  build() {
    Column() {
      if (this.hasRenderError && this.autoHeight) {
        Column({ space: 8 }) {
          Text('已显示完整原文').fontSize(11).fontColor('#64748B')
          Text(this.bodyMarkdown).fontSize(this.fontSize).fontColor('#0F172A')
            .width('100%').copyOption(CopyOptions.LocalDevice)
        }
        .width('100%').padding(12).alignItems(HorizontalAlign.Start).backgroundColor('#FFFFFF')
        .onAreaChange((_oldArea: Area, newArea: Area) => { this.onContentHeight(Number(newArea.height)); })
      } else {
        Stack() {
      Web({ src: RENDERER_URL, controller: this.controller })
        .width('100%')
        .height('100%')
        .backgroundColor('#FFFFFF')
        .javaScriptAccess(true)
        .javaScriptProxy({ object: this.citationBridge, name: 'BetterLearningCitations', methodList: ['open', 'resize'], controller: this.controller })
        .domStorageAccess(false)
        .databaseAccess(false)
        .fileAccess(false)
        .onlineImageAccess(false)
        .imageAccess(false)
        .mixedMode(MixedMode.None)
        .zoomAccess(false)
        .verticalScrollBarAccess(!this.autoHeight)
        .nestedScroll({ scrollForward: this.autoHeight ? NestedScrollMode.PARENT_FIRST : NestedScrollMode.SELF_FIRST,
          scrollBackward: this.autoHeight ? NestedScrollMode.PARENT_FIRST : NestedScrollMode.SELF_FIRST })
        .onInterceptRequest((event) => {
          return this.local_response(event.request.getRequestUrl(), event.request.getRequestMethod());
        })
        .onLoadIntercept((event) => {
          return event.data.getRequestUrl() !== RENDERER_URL;
        })
        .onPageEnd(() => {
          this.pageReady = true;
          this.lastRenderKey = '';
          this.render_content();
        })
        .onErrorReceive((event) => {
          if (event.request.isMainFrame()) {
            this.pageReady = true;
            this.report_error(++this.renderRevision);
          }
        })
        .onPermissionRequest((event) => {
          event.request.deny();
        })
          if (this.hasRenderError) {
        Column({ space: 12 }) {
          Text('题面暂时无法显示')
            .fontSize(16)
            .fontColor('#0F172A')
          Text('请重试加载题面')
            .fontSize(14)
            .fontColor('#64748B')
          Button('重试')
            .onClick(() => {
              this.hasRenderError = false;
              this.pageReady = false;
              this.renderRevision++;
              this.controller.refresh();
            })
        }
        .width('100%')
        .height('100%')
        .justifyContent(FlexAlign.Center)
        .backgroundColor('#FFFFFF')
          }
        }
        .width('100%').height(this.autoHeight ? this.contentHeight : '100%')
      }
    }
    .width('100%')
    .height(this.autoHeight ? 'auto' : '100%')
  }
}
