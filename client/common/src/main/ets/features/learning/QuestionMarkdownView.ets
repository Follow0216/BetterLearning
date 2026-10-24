import { common } from '@kit.AbilityKit';
import { webview } from '@kit.ArkWeb';
import { MAX_QUESTION_MARKDOWN_LENGTH } from './LearningModels';

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
  onRenderError: () => void = () => {
  };
  @State private hasRenderError: boolean = false;
  private controller: webview.WebviewController = new webview.WebviewController();
  private pageReady: boolean = false;
  private renderRevision: number = 0;

  aboutToDisappear(): void {
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
    const revision: number = ++this.renderRevision;
    if (this.bodyMarkdown.length > MAX_QUESTION_MARKDOWN_LENGTH) {
      this.report_error(revision);
      return;
    }
    this.hasRenderError = false;
    const size: number = Number.isFinite(this.fontSize) ? Math.max(12, Math.min(32, this.fontSize)) : 16;
    // JSON 序列化仅作为 JS 字符串值传参，不把题面插入 HTML 或模板字符串。
    const markdownLiteral: string = JSON.stringify(this.bodyMarkdown)
      .split(String.fromCharCode(0x2028)).join('\\u2028').split(String.fromCharCode(0x2029)).join('\\u2029');
    const script: string = 'window.BetterLearningQuestion.render(' + markdownLiteral + ',' + size.toString() + ')';
    try {
      const result: string = await this.controller.runJavaScript(script);
      if (revision !== this.renderRevision || !this.pageReady) {
        return;
      }
      if (result !== 'true') {
        this.report_error(revision);
      }
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
    Stack() {
      Web({ src: RENDERER_URL, controller: this.controller })
        .width('100%')
        .height('100%')
        .backgroundColor('#FFFFFF')
        .javaScriptAccess(true)
        .domStorageAccess(false)
        .databaseAccess(false)
        .fileAccess(false)
        .onlineImageAccess(false)
        .imageAccess(false)
        .mixedMode(MixedMode.None)
        .zoomAccess(false)
        .onInterceptRequest((event) => {
          return this.local_response(event.request.getRequestUrl(), event.request.getRequestMethod());
        })
        .onLoadIntercept((event) => {
          return event.data.getRequestUrl() !== RENDERER_URL;
        })
        .onPageEnd(() => {
          this.pageReady = true;
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
    .width('100%')
    .height('100%')
  }
}
