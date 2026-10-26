import { batchImageDisplayUri } from '../features/ocr/BatchQuestionImageSource';
import { AnswerViewTransform, fitAnswerView, zoomAnswerViewAt } from '../model/AnswerTileGeometry';
import {
  PHONE_COLOR_BORDER, PHONE_COLOR_CARD, PHONE_COLOR_DANGER, PHONE_COLOR_MUTED,
  PHONE_COLOR_PRIMARY, PHONE_COLOR_PRIMARY_DEEP, PHONE_COLOR_SOFT, PHONE_COLOR_TITLE
} from '../theme/PhoneTheme';

/** 只查看原缓存图片。复用现有作答查看器的纯几何，不触发HTTP、OCR或文件清理。 */
@Component
export struct BatchOcrSourceViewer {
  @Prop sourcePath: string = '';
  @Prop sourceWidth: number = 1;
  @Prop sourceHeight: number = 1;
  @Prop title: string = '查看来源原图';
  onClose: () => void = () => {};
  @State viewScale: number = 1;
  @State offsetX: number = 0;
  @State offsetY: number = 0;
  @State viewportWidth: number = 0;
  @State viewportHeight: number = 0;
  @State loaded: boolean = false;
  @State loadError: string = '';
  @State loadKey: number = 0;
  private alive: boolean = false;
  private readingScale: number = 1;
  private minimumScale: number = 1;
  private panBaseX: number = 0;
  private panBaseY: number = 0;

  aboutToAppear(): void {
    this.alive = true;
    if (this.sourcePath.length === 0 || !Number.isSafeInteger(this.sourceWidth) || !Number.isSafeInteger(this.sourceHeight) ||
      this.sourceWidth < 1 || this.sourceHeight < 1 || this.sourceWidth > 16000 || this.sourceHeight > 16000 ||
      this.sourceWidth * this.sourceHeight > 16000000) { this.loadError = '原图资料不完整，请返回来源列表。'; }
  }
  aboutToDisappear(): void { this.alive = false; }

  build() {
    Column({ space: 10 }) {
      Text(this.title).fontSize(18).fontWeight(FontWeight.Bold).fontColor(PHONE_COLOR_TITLE).width('100%')
        .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
      Text('拖动可横向、纵向移动，也可用下方方向按钮核对细小公式。')
        .fontSize(12).lineHeight(18).fontColor(PHONE_COLOR_MUTED).width('100%')
      Stack({ alignContent: Alignment.TopStart }) {
        if (this.loadError.length === 0) {
          ForEach([this.loadKey], (key: number) => {
            Image(batchImageDisplayUri(this.sourcePath)).width(this.sourceWidth * this.viewScale).height(this.sourceHeight * this.viewScale)
              .position({ x: this.offsetX, y: this.offsetY }).objectFit(ImageFit.Fill).draggable(false)
              .opacity(this.loaded ? 1 : 0).accessibilityText(this.title + '，可放大并横向、纵向移动核对')
              .onComplete((event) => {
                if (this.alive && key === this.loadKey && event !== undefined && event.loadingStatus === 1) { this.loaded = true; }
              })
              .onError(() => {
                if (this.alive && key === this.loadKey) { this.loaded = false; this.loadError = '原图加载失败。可重新加载或返回来源列表；本次查看不会修改草稿或清理原图。'; }
              })
          }, (key: number) => key.toString())
        }
        if (!this.loaded || this.loadError.length > 0) {
          Column({ space: 12 }) {
            if (this.loadError.length > 0) {
              Text(this.loadError).fontSize(14).lineHeight(22).fontColor(PHONE_COLOR_DANGER).textAlign(TextAlign.Center)
              Button('重新加载原图').height(44).fontSize(14).fontColor(PHONE_COLOR_PRIMARY_DEEP).backgroundColor(PHONE_COLOR_SOFT)
                .onClick(() => { this.loaded = false; this.loadError = ''; this.loadKey++; })
            } else {
              LoadingProgress().width(28).height(28).color(PHONE_COLOR_PRIMARY)
              Text('正在加载原图…').fontSize(14).fontColor(PHONE_COLOR_MUTED)
            }
          }.width('100%').height('100%').padding(20).justifyContent(FlexAlign.Center)
        }
      }.width('100%').layoutWeight(1).backgroundColor(PHONE_COLOR_CARD)
      .border({ width: 1, color: PHONE_COLOR_BORDER }).borderRadius(14).clip(true)
      .onAreaChange((_oldArea: Area, area: Area) => {
        const width: number = this.length(area.width);
        const height: number = this.length(area.height);
        if (width > 0 && height > 0 && (width !== this.viewportWidth || height !== this.viewportHeight)) {
          this.viewportWidth = width; this.viewportHeight = height; this.fitWidth();
        }
      })
      .gesture(PanGesture({ fingers: 1 })
        .onActionStart(() => { this.panBaseX = this.offsetX; this.panBaseY = this.offsetY; })
        .onActionUpdate((event: GestureEvent) => {
          if (this.ready()) { this.apply(this.viewScale, this.panBaseX + event.offsetX, this.panBaseY + event.offsetY); }
        }))
      Text('当前倍率 ' + (this.viewScale / Math.max(this.readingScale, 0.000001)).toFixed(1) + '×（相对适合宽度）')
        .fontSize(12).fontColor(PHONE_COLOR_PRIMARY_DEEP).width('100%').textAlign(TextAlign.Center)
      Row({ space: 8 }) {
        Button('缩小').layoutWeight(1).height(44).fontSize(14).fontColor(PHONE_COLOR_PRIMARY_DEEP).backgroundColor(PHONE_COLOR_SOFT)
          .enabled(this.ready() && this.viewScale > this.minimumScale).accessibilityText('缩小来源原图').onClick(() => { this.zoom(0.75); })
        Button('适合宽度').layoutWeight(1).height(44).fontSize(13).fontColor(PHONE_COLOR_PRIMARY_DEEP).backgroundColor(PHONE_COLOR_SOFT)
          .enabled(this.ready()).onClick(() => { this.fitWidth(); })
        Button('放大').layoutWeight(1).height(44).fontSize(14).fontColor(PHONE_COLOR_PRIMARY_DEEP).backgroundColor(PHONE_COLOR_SOFT)
          .enabled(this.ready() && this.viewScale < 8).accessibilityText('放大来源原图').onClick(() => { this.zoom(1.5); })
      }.width('100%')
      Row({ space: 8 }) {
        Button('←').layoutWeight(1).height(44).fontSize(20).fontColor(PHONE_COLOR_PRIMARY_DEEP).backgroundColor(PHONE_COLOR_SOFT)
          .enabled(this.ready()).accessibilityText('向左查看原图').onClick(() => { this.move(0.6, 0); })
        Button('↑').layoutWeight(1).height(44).fontSize(20).fontColor(PHONE_COLOR_PRIMARY_DEEP).backgroundColor(PHONE_COLOR_SOFT)
          .enabled(this.ready()).accessibilityText('向上查看原图').onClick(() => { this.move(0, 0.6); })
        Button('↓').layoutWeight(1).height(44).fontSize(20).fontColor(PHONE_COLOR_PRIMARY_DEEP).backgroundColor(PHONE_COLOR_SOFT)
          .enabled(this.ready()).accessibilityText('向下查看原图').onClick(() => { this.move(0, -0.6); })
        Button('→').layoutWeight(1).height(44).fontSize(20).fontColor(PHONE_COLOR_PRIMARY_DEEP).backgroundColor(PHONE_COLOR_SOFT)
          .enabled(this.ready()).accessibilityText('向右查看原图').onClick(() => { this.move(-0.6, 0); })
      }.width('100%')
      Button('返回题目列表').width('100%').height(48).fontSize(15).fontColor(PHONE_COLOR_PRIMARY_DEEP).backgroundColor(PHONE_COLOR_SOFT)
        .onClick(() => { this.onClose(); })
    }.width('100%').height('100%').padding(14).backgroundColor(PHONE_COLOR_CARD)
  }
  private ready(): boolean { return this.loaded && this.loadError.length === 0 && this.viewportWidth > 0 && this.viewportHeight > 0; }
  private length(value: Length): number {
    if (typeof value === 'number') { return value; }
    if (typeof value === 'string' && /^\d+(\.\d+)?(vp)?$/.test(value.trim())) { return Number.parseFloat(value); }
    return 0;
  }
  private fitWidth(): void {
    if (this.viewportWidth <= 32 || this.viewportHeight <= 32) { return; }
    const fit: AnswerViewTransform = fitAnswerView({ x: 0, y: 0, width: this.sourceWidth, height: this.sourceHeight },
      this.viewportWidth, this.viewportHeight);
    this.minimumScale = Math.min(1, fit.scale);
    this.readingScale = Math.min(1, (this.viewportWidth - 32) / this.sourceWidth);
    this.apply(this.readingScale, 16, 16);
  }
  private apply(scale: number, x: number, y: number): void {
    const bounded: number = Math.max(this.minimumScale, Math.min(8, scale));
    const width: number = this.sourceWidth * bounded;
    const height: number = this.sourceHeight * bounded;
    this.viewScale = bounded;
    this.offsetX = width <= this.viewportWidth ? (this.viewportWidth - width) / 2 : Math.max(this.viewportWidth - width - 16, Math.min(16, x));
    this.offsetY = height <= this.viewportHeight ? (this.viewportHeight - height) / 2 : Math.max(this.viewportHeight - height - 16, Math.min(16, y));
  }
  private zoom(factor: number): void {
    if (!this.ready()) { return; }
    const view: AnswerViewTransform = zoomAnswerViewAt({ scale: this.viewScale, offsetX: this.offsetX, offsetY: this.offsetY },
      Math.max(this.minimumScale, Math.min(8, this.viewScale * factor)), this.viewportWidth / 2, this.viewportHeight / 2);
    this.apply(view.scale, view.offsetX, view.offsetY);
  }
  private move(x: number, y: number): void {
    if (this.ready()) { this.apply(this.viewScale, this.offsetX + this.viewportWidth * x, this.offsetY + this.viewportHeight * y); }
  }
}
