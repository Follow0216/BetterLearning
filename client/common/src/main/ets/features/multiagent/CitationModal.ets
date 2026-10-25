import { image } from '@kit.ImageKit';
import { AuthError, AuthResult } from '../auth/AuthModels';
import { authSession } from '../auth/AuthSession';
import { canvas_image_bytes } from '../canvas/CanvasImages';
import { canvas_sha256, canvas_utf8 } from '../canvas/CanvasFiles';
import { QuestionMarkdownView } from '../learning/QuestionMarkdownView';
import { LearningV2Api } from './LearningV2Api';
import { V2CitationResolution, V2HandwritingSegment } from './LearningV2Models';
import { CitationRect, CitationTextRange, CitationViewport, citation_fit, citation_intersection, citation_text_range, citation_world_extent } from './CitationGeometry';

interface CitationImage { segment: V2HandwritingSegment; map: image.PixelMap; }

/** One immutable, freshly authorized citation. Assets remain only in memory while open. */
@CustomDialog
export struct CitationModal {
  controller?: CustomDialogController;
  citationId: string = '';
  onUnauthorized: () => void = () => {};
  onClose: () => void = () => {};
  @State private resolution: V2CitationResolution | null = null;
  @State private images: CitationImage[] = [];
  @State private loading: boolean = true;
  @State private errorText: string = '';
  @State private before: string = '';
  @State private selected: string = '';
  @State private after: string = '';
  @State private context: boolean = false;
  @State private zoom: number = 1;
  @State private offsetX: number = 0;
  @State private offsetY: number = 0;
  @State private viewScale: number = 1;
  private viewportWidth: number = 320;
  private viewportHeight: number = 280;
  private panX: number = 0;
  private panY: number = 0;
  private generation: number = 0;
  private sessionTimer: number = -1;
  private token: string = '';
  private api: LearningV2Api = LearningV2Api.create_default();

  aboutToAppear(): void {
    this.load();
    this.sessionTimer = setInterval(() => {
      if (!authSession.has_valid_session() || !authSession.matches_access_token(this.token)) {
        this.generation++; this.clearContent(); this.errorText = '登录状态已变化，请重新打开引用';
      }
    },1000);
  }
  aboutToDisappear(): void {
    this.generation++; clearInterval(this.sessionTimer); this.clearContent(); this.token = '';
  }
  private clearContent(): void {
    const old: CitationImage[] = this.images;
    this.images = []; this.resolution = null; this.before = ''; this.selected = ''; this.after = '';
    old.forEach((item: CitationImage) => { item.map.release(); });
  }
  private active(generation: number, token: string): boolean {
    return generation === this.generation && authSession.has_valid_session() && authSession.matches_access_token(token);
  }
  private fail(error: AuthError | null, token: string): void {
    this.clearContent();
    if (error !== null && error.is_unauthorized()) {
      if (authSession.clear_session_if_token(token)) { this.onUnauthorized(); }
      this.errorText = '登录已失效，请重新登录';
    } else if (error !== null && (error.httpStatus === 403 || error.httpStatus === 404)) {
      this.errorText = '来源已删除或无权访问';
    } else if (error !== null && (error.httpStatus === 409 || error.kind === 'protocol')) {
      this.errorText = '引用版本或定位已失效，无法展示原证据';
    } else { this.errorText = '当前离线或引用加载失败，请重试'; }
  }
  private async load(): Promise<void> {
    const generation: number = ++this.generation;
    this.clearContent(); this.loading = true; this.errorText = ''; this.context = false; this.zoom = 1;
    const token: string | null = authSession.read_access_token();
    this.token = token ?? '';
    if (token === null) { this.loading = false; this.errorText = '请登录后查看引用'; this.onUnauthorized(); return; }
    let result: AuthResult<V2CitationResolution>;
    try { result = await this.api.resolveCitation(token,this.citationId); }
    catch (error) {
      if (this.active(generation,token)) { this.loading = false; this.fail(null,token); }
      return;
    }
    if (!this.active(generation,token)) { return; }
    if (!result.isSuccess || result.data === null) { this.loading = false; this.fail(result.error,token); return; }
    const resolution: V2CitationResolution = result.data;
    const loaded: CitationImage[] = [];
    try {
      if (resolution.markdown !== undefined && resolution.markdownLocator !== undefined) {
        const range: CitationTextRange = citation_text_range(resolution.markdown,
          resolution.markdownLocator.startOffset,resolution.markdownLocator.endOffset);
        if (await canvas_sha256(canvas_utf8(resolution.markdown)) !== resolution.contentSha256) {
          throw new Error('引用摘要不匹配');
        }
        if (!this.active(generation,token)) { return; }
        this.before = range.before; this.selected = range.selected; this.after = range.after;
      } else if (resolution.handwritingLocator !== undefined) {
        const segments: V2HandwritingSegment[] = resolution.handwritingLocator.segments;
        citation_world_extent(segments);
        let pixels: number = 0;
        for (let i: number = 0; i < segments.length; i++) {
          const segment: V2HandwritingSegment = segments[i];
          pixels += segment.pixelWidth * segment.pixelHeight;
          if (pixels > 33554432) { throw new Error('引用原图像素超限'); }
          const asset: AuthResult<ArrayBuffer> = await this.api.downloadCitationAsset(token,this.citationId,segment.blockIndex);
          if (!this.active(generation,token)) { loaded.forEach((item: CitationImage) => { item.map.release(); }); return; }
          if (!asset.isSuccess || asset.data === null) {
            loaded.forEach((item: CitationImage) => { item.map.release(); });
            this.loading = false; this.fail(asset.error,token); return;
          }
          const map: image.PixelMap = await canvas_image_bytes(new Uint8Array(asset.data));
          const info: image.ImageInfo = await map.getImageInfo();
          if (!this.active(generation,token)) { map.release(); loaded.forEach((item: CitationImage) => { item.map.release(); }); return; }
          if (info.size.width !== segment.pixelWidth || info.size.height !== segment.pixelHeight) {
            map.release(); throw new Error('引用原图尺寸不匹配');
          }
          loaded.push({ segment: segment, map: map });
        }
      } else { throw new Error('引用缺少定位'); }
      if (!this.active(generation,token)) { loaded.forEach((item: CitationImage) => { item.map.release(); }); return; }
      this.images = loaded; this.resolution = resolution; this.fit(); this.loading = false;
    } catch (error) {
      loaded.forEach((item: CitationImage) => { item.map.release(); });
      if (this.active(generation,token)) { this.loading = false; this.fail(AuthError.protocol_failure('引用无效'),token); }
    }
  }
  private fit(): void {
    if (this.resolution === null || this.resolution.handwritingLocator === undefined) { return; }
    try {
      const view: CitationViewport = citation_fit(citation_world_extent(this.resolution.handwritingLocator.segments,this.context),
        this.viewportWidth,this.viewportHeight,this.zoom);
      this.viewScale = view.scale; this.offsetX = view.offsetX; this.offsetY = view.offsetY;
    } catch (error) { this.errorText = '引用步骤无法定位'; }
  }
  private highlightRect(segment: V2HandwritingSegment): CitationRect {
    return citation_intersection(segment.blockWorldBounds,segment.regionWorldBounds);
  }
  private sourceLabel(): string {
    if (this.resolution === null) { return ''; }
    const type: string = this.resolution.citation.sourceType;
    if (type === 'SAVED_ANSWER') { return '用户历史作答 · ' + (this.resolution.answerState === 'COMPLETED' ? '已完成' : '草稿'); }
    if (type === 'TUTOR_REPLY') { return '历史辅导建议'; }
    if (type === 'NOTE') { return '用户笔记'; }
    return type === 'QUESTION' ? '题目' : '辅导冻结快照';
  }
  @Builder
  handwriting() {
    Column({ space: 8 }) {
      Row({ space: 8 }) {
        Button(this.context ? '定位步骤' : '查看原图上下文').fontSize(12).onClick(() => {
          this.context = !this.context; this.zoom = 1; this.fit();
        })
        Button('−').fontSize(14).onClick(() => { this.zoom = Math.max(1,this.zoom / 1.5); this.fit(); })
        Button('+').fontSize(14).onClick(() => { this.zoom = Math.min(8,this.zoom * 1.5); this.fit(); })
      }
      Stack({ alignContent: Alignment.TopStart }) {
        ForEach(this.images,(item: CitationImage) => {
          Image(item.map).draggable(false).objectFit(ImageFit.Fill)
            .width(item.segment.blockWorldBounds.width * this.viewScale)
            .height(item.segment.blockWorldBounds.height * this.viewScale)
            .position({ x: item.segment.blockWorldBounds.x * this.viewScale + this.offsetX,
              y: item.segment.blockWorldBounds.y * this.viewScale + this.offsetY })
            .accessibilityText('引用步骤原图块 ' + item.segment.blockIndex.toString())
        },(item: CitationImage) => item.segment.blockIndex.toString())
        ForEach(this.images,(item: CitationImage) => {
          Row().width(this.highlightRect(item.segment).width * this.viewScale)
            .height(this.highlightRect(item.segment).height * this.viewScale)
            .position({ x: this.highlightRect(item.segment).x * this.viewScale + this.offsetX,
              y: this.highlightRect(item.segment).y * this.viewScale + this.offsetY })
            .backgroundColor('#22FACC15').border({ width: 2, color: '#CA8A04' }).hitTestBehavior(HitTestMode.None)
        },(item: CitationImage) => 'highlight-' + item.segment.blockIndex.toString())
      }
      .width('100%').height(280).clip(true).backgroundColor('#F1F5F9')
      .onAreaChange((_old: Area,area: Area) => {
        this.viewportWidth = Number(area.width); this.viewportHeight = Number(area.height); this.fit();
      })
      .gesture(PanGesture().onActionStart(() => { this.panX = this.offsetX; this.panY = this.offsetY; })
        .onActionUpdate((event: GestureEvent) => { this.offsetX = this.panX + event.offsetX; this.offsetY = this.panY + event.offsetY; }))
      Text('黄色区域为所引步骤，可缩放、拖动查看跨图块原图。').fontSize(12).fontColor('#64748B')
      if (this.resolution !== null && this.resolution.quotedText !== undefined) {
        Text('OCR 识别文字（以原图为准）：' + this.resolution.quotedText).fontSize(13)
      }
    }.width('100%')
  }
  build() {
    Column({ space: 12 }) {
      Row() {
        Text('引用来源').fontSize(20).fontWeight(FontWeight.Bold).layoutWeight(1)
        Button('关闭').id('citation-close-' + this.citationId).defaultFocus(true).onClick(() => { this.onClose(); })
      }.width('100%')
      if (this.loading) { LoadingProgress().width(36).height(36) }
      if (this.errorText.length > 0) {
        Text(this.errorText).fontColor('#B91C1C').fontSize(14)
        Button('重新加载引用').onClick(() => { this.load(); })
      }
      if (this.resolution !== null) {
        Scroll() {
          Column({ space: 10 }) {
            Text(this.resolution.sourceTitle).fontSize(18).fontWeight(FontWeight.Medium).width('100%')
            Text(this.sourceLabel()).fontSize(13).fontColor('#475569').width('100%')
            Text('所引版本 v' + this.resolution.citation.version.toString() + ' · ' + this.resolution.sourceVersionCreatedAt)
              .fontSize(12).fontColor('#64748B').width('100%')
            Text('来源创建：' + this.resolution.sourceCreatedAt).fontSize(12).fontColor('#64748B').width('100%')
            if (this.resolution.currentVersion > this.resolution.citation.version) {
              Text('存在较新版本；此处展示回答当时引用的原版本。').fontSize(13).fontColor('#92400E').width('100%')
            }
            Text(this.resolution.citation.label).fontSize(14).width('100%')
            if (this.resolution.markdown !== undefined) {
              QuestionMarkdownView({ bodyMarkdown: this.resolution.markdown, maxLength: 65536, highlightBlock: true })
                .height(230).width('100%')
              Text('原文定位（可长按复制公式源码）').fontSize(12).fontColor('#64748B').width('100%')
              Text() {
                Span(this.before)
                Span(this.selected).textBackgroundStyle({ color: '#FEF08A' }).fontWeight(FontWeight.Bold)
                Span(this.after)
              }.fontSize(14).copyOption(CopyOptions.InApp).width('100%')
            } else { this.handwriting() }
          }.width('100%').alignItems(HorizontalAlign.Start)
        }.layoutWeight(1).width('100%')
      }
    }.padding(18).width('96%').height('88%').backgroundColor('#FFFFFF').borderRadius(18)
  }
}
