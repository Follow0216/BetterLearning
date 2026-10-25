import { image } from '@kit.ImageKit';
import {
  PHONE_COLOR_BACKGROUND,
  PHONE_COLOR_BORDER,
  PHONE_COLOR_DANGER,
  PHONE_COLOR_MUTED,
  PHONE_COLOR_PRIMARY,
  PHONE_COLOR_SOFT,
  PHONE_COLOR_TITLE,
  PHONE_COLOR_WHITE,
  PHONE_RADIUS_CONTROL
} from '../../theme/PhoneTheme';
import {
  QuestionCropDrag,
  QuestionCropFit,
  QuestionCropRect,
  questionCropDrag,
  questionCropFit,
  questionCropFullRect,
  questionCropValid
} from './QuestionCropGeometry';

export { QuestionCropRect } from './QuestionCropGeometry';

const HANDLE_SIZE: number = 44;

/**
 * 只选择实际PixelMap中的矩形，不克隆/释放预览、不写文件或发请求。
 * preview不能使用@Prop：NAPI PixelMap不支持该装饰器的深拷贝。
 * AP每换一张图须用唯一key重建本组件，宽高来自同一PixelMap.getImageInfo()。
 */
@Component
export struct QuestionCropView {
  preview: image.PixelMap | null = null;
  @Prop @Watch('resetImage') imageWidth: number = 0;
  @Prop @Watch('resetImage') imageHeight: number = 0;
  @Prop @Watch('busyChanged') busy: boolean = false;
  @Prop message: string = '';
  onConfirm: (rect: QuestionCropRect) => void = () => {};
  onCancel: () => void = () => {};

  @State private rect: QuestionCropRect = { x: 0, y: 0, width: 0, height: 0 };
  @State private fit: QuestionCropFit = { scale: 0, offsetX: 0, offsetY: 0, width: 0, height: 0 };
  @State private imageReady: boolean = false;
  @State private errorText: string = '';

  private viewportWidth: number = 0;
  private viewportHeight: number = 0;
  private alive: boolean = false;
  private dragActive: boolean = false;
  private dragKind: QuestionCropDrag = QuestionCropDrag.MOVE;
  private dragScale: number = 0;
  private dragBase: QuestionCropRect = { x: 0, y: 0, width: 0, height: 0 };

  aboutToAppear(): void {
    this.alive = true;
    this.resetImage();
  }

  aboutToDisappear(): void {
    this.alive = false;
    this.dragActive = false;
  }

  resetImage(): void {
    this.dragActive = false;
    this.imageReady = false;
    this.errorText = '';
    this.rect = questionCropFullRect(this.imageWidth, this.imageHeight);
    this.updateFit();
    if (this.preview === null || !questionCropValid(this.rect, this.imageWidth, this.imageHeight)) {
      this.errorText = '图片未准备好，请重新选择';
    }
  }

  busyChanged(): void {
    if (this.busy) {
      this.dragActive = false;
    }
  }

  build() {
    Column({ space: 10 }) {
      Row({ space: 10 }) {
        Text('框住完整的一道题')
          .fontSize(20)
          .fontWeight(FontWeight.Bold)
          .fontColor(PHONE_COLOR_TITLE)
          .layoutWeight(1)
        Button('重置选区')
          .height(44)
          .fontSize(13)
          .fontColor(PHONE_COLOR_PRIMARY)
          .backgroundColor(PHONE_COLOR_SOFT)
          .borderRadius(PHONE_RADIUS_CONTROL)
          .enabled(this.usable() && !this.busy)
          .onClick(() => {
            this.dragActive = false;
            this.rect = questionCropFullRect(this.imageWidth, this.imageHeight);
          })
      }
      .width('100%')
      Text('保留全部条件、公式和图形。拖动框内移动，拖动四角调整。')
        .width('100%')
        .fontSize(12)
        .lineHeight(18)
        .fontColor(PHONE_COLOR_MUTED)
      Stack({ alignContent: Alignment.TopStart }) {
        if (this.preview !== null && this.fit.scale > 0 && this.errorText.length === 0) {
          Image(this.preview as image.PixelMap)
            .width(this.fit.width)
            .height(this.fit.height)
            .position({ x: this.fit.offsetX, y: this.fit.offsetY })
            .objectFit(ImageFit.Fill)
            .orientation(ImageRotateOrientation.UP)
            .matchTextDirection(false)
            .draggable(false)
            .accessibilityText('待裁剪的完整题目图片')
            .onComplete((event) => {
              if (this.alive && event !== undefined && event.loadingStatus === 1) {
                this.imageReady = true;
              }
            })
            .onError(() => {
              if (!this.alive) { return; }
              this.dragActive = false;
              this.imageReady = false;
              this.errorText = '图片显示失败，请重新选择';
            })
          if (this.imageReady) {
            this.outsideMask()
            Column() {}
              .width(this.rect.width * this.fit.scale)
              .height(this.rect.height * this.fit.scale)
              .position({ x: this.left(), y: this.top() })
              .border({ width: 2, color: PHONE_COLOR_PRIMARY })
              .backgroundColor(Color.Transparent)
              .accessibilityText('裁剪区域，拖动可移动')
              .gesture(
                PanGesture({ fingers: 1, distance: 0 })
                  .onActionStart(() => this.beginDrag(QuestionCropDrag.MOVE))
                  .onActionUpdate((event: GestureEvent) => this.drag(event))
                  .onActionEnd(() => { this.dragActive = false; })
                  .onActionCancel(() => { this.dragActive = false; })
              )
            this.corner(QuestionCropDrag.TOP_LEFT)
            this.corner(QuestionCropDrag.TOP_RIGHT)
            this.corner(QuestionCropDrag.BOTTOM_LEFT)
            this.corner(QuestionCropDrag.BOTTOM_RIGHT)
          }
        }
        if (this.errorText.length > 0) {
          Text(this.errorText)
            .width('100%')
            .height('100%')
            .padding(20)
            .fontSize(14)
            .fontColor(PHONE_COLOR_DANGER)
            .textAlign(TextAlign.Center)
        }
      }
      .width('100%')
      .layoutWeight(1)
      .direction(Direction.Ltr)
      .backgroundColor(PHONE_COLOR_SOFT)
      .border({ width: 1, color: PHONE_COLOR_BORDER })
      .borderRadius(PHONE_RADIUS_CONTROL)
      .clip(true)
      .onAreaChange((_previous: Area, area: Area) => {
        const width: number = this.viewportLength(area.width);
        const height: number = this.viewportLength(area.height);
        if (width !== this.viewportWidth || height !== this.viewportHeight) {
          this.dragActive = false;
          this.viewportWidth = width;
          this.viewportHeight = height;
          this.updateFit();
        }
      })
      if (this.message.length > 0) {
        Text(this.message)
          .width('100%')
          .fontSize(12)
          .lineHeight(18)
          .fontColor(PHONE_COLOR_DANGER)
      }
      Text(this.selectionText())
        .width('100%')
        .fontSize(12)
        .fontColor(PHONE_COLOR_MUTED)
        .textAlign(TextAlign.Center)
      Row({ space: 12 }) {
        Button('返回')
          .height(44)
          .layoutWeight(1)
          .fontSize(14)
          .fontColor(PHONE_COLOR_TITLE)
          .backgroundColor(PHONE_COLOR_SOFT)
          .borderRadius(PHONE_RADIUS_CONTROL)
          .enabled(!this.busy)
          .onClick(() => { if (this.alive && !this.busy) { this.onCancel(); } })
        Button(this.busy ? '处理中…' : '识别这道题')
          .height(44)
          .layoutWeight(1)
          .fontSize(14)
          .fontColor(PHONE_COLOR_WHITE)
          .backgroundColor(PHONE_COLOR_PRIMARY)
          .borderRadius(PHONE_RADIUS_CONTROL)
          .enabled(this.usable() && !this.busy)
          .onClick(() => {
            if (this.usable() && !this.busy) {
              this.dragActive = false;
              this.onConfirm({ x: this.rect.x, y: this.rect.y,
                width: this.rect.width, height: this.rect.height });
            }
          })
      }
      .width('100%')
    }
    .width('100%')
    .height('100%')
    .padding({ left: 18, right: 18, top: 4, bottom: 12 })
    .backgroundColor(PHONE_COLOR_BACKGROUND)
  }

  @Builder
  outsideMask() {
    Column() {}
      .width(this.fit.width)
      .height(this.rect.y * this.fit.scale)
      .position({ x: this.fit.offsetX, y: this.fit.offsetY })
      .backgroundColor('#660F1F3C')
      .hitTestBehavior(HitTestMode.None)
    Column() {}
      .width(this.fit.width)
      .height((this.imageHeight - this.rect.y - this.rect.height) * this.fit.scale)
      .position({ x: this.fit.offsetX, y: this.bottom() })
      .backgroundColor('#660F1F3C')
      .hitTestBehavior(HitTestMode.None)
    Column() {}
      .width(this.rect.x * this.fit.scale)
      .height(this.rect.height * this.fit.scale)
      .position({ x: this.fit.offsetX, y: this.top() })
      .backgroundColor('#660F1F3C')
      .hitTestBehavior(HitTestMode.None)
    Column() {}
      .width((this.imageWidth - this.rect.x - this.rect.width) * this.fit.scale)
      .height(this.rect.height * this.fit.scale)
      .position({ x: this.right(), y: this.top() })
      .backgroundColor('#660F1F3C')
      .hitTestBehavior(HitTestMode.None)
  }

  @Builder
  corner(kind: QuestionCropDrag) {
    Stack() {
      Rect()
        .width(13)
        .height(13)
        .radius(3)
        .fill(PHONE_COLOR_PRIMARY)
        .stroke(PHONE_COLOR_WHITE)
        .strokeWidth(2)
    }
    .width(HANDLE_SIZE)
    .height(HANDLE_SIZE)
    .position({
      x: (kind === QuestionCropDrag.TOP_LEFT || kind === QuestionCropDrag.BOTTOM_LEFT ?
        this.left() : this.right()) - HANDLE_SIZE / 2,
      y: (kind === QuestionCropDrag.TOP_LEFT || kind === QuestionCropDrag.TOP_RIGHT ?
        this.top() : this.bottom()) - HANDLE_SIZE / 2
    })
    .backgroundColor(Color.Transparent)
    .accessibilityText(this.cornerLabel(kind))
    .gesture(
      PanGesture({ fingers: 1, distance: 0 })
        .onActionStart(() => this.beginDrag(kind))
        .onActionUpdate((event: GestureEvent) => this.drag(event))
        .onActionEnd(() => { this.dragActive = false; })
        .onActionCancel(() => { this.dragActive = false; })
    )
  }

  private cornerLabel(kind: QuestionCropDrag): string {
    if (kind === QuestionCropDrag.TOP_LEFT) { return '拖动调整左上角'; }
    if (kind === QuestionCropDrag.TOP_RIGHT) { return '拖动调整右上角'; }
    if (kind === QuestionCropDrag.BOTTOM_LEFT) { return '拖动调整左下角'; }
    return '拖动调整右下角';
  }

  private beginDrag(kind: QuestionCropDrag): void {
    if (!this.usable() || this.busy) { return; }
    this.dragKind = kind;
    this.dragScale = this.fit.scale;
    this.dragBase = { x: this.rect.x, y: this.rect.y, width: this.rect.width, height: this.rect.height };
    this.dragActive = true;
  }

  private drag(event: GestureEvent): void {
    if (!this.dragActive || !this.usable() || this.busy) { return; }
    this.rect = questionCropDrag(this.dragBase, this.dragKind, event.offsetX, event.offsetY,
      this.dragScale, this.imageWidth, this.imageHeight);
  }

  private updateFit(): void {
    this.fit = questionCropFit(this.imageWidth, this.imageHeight, this.viewportWidth, this.viewportHeight);
  }

  private usable(): boolean {
    return this.alive && this.preview !== null && this.imageReady && this.errorText.length === 0 && this.fit.scale > 0
      && questionCropValid(this.rect, this.imageWidth, this.imageHeight);
  }

  private left(): number { return this.fit.offsetX + this.rect.x * this.fit.scale; }
  private top(): number { return this.fit.offsetY + this.rect.y * this.fit.scale; }
  private right(): number { return this.left() + this.rect.width * this.fit.scale; }
  private bottom(): number { return this.top() + this.rect.height * this.fit.scale; }

  private selectionText(): string {
    return questionCropValid(this.rect, this.imageWidth, this.imageHeight)
      ? '选中 ' + this.rect.width.toString() + ' × ' + this.rect.height.toString() + ' 像素'
      : '请选择一张有效图片';
  }

  private viewportLength(value: Length): number {
    if (typeof value === 'number') {
      return Number.isFinite(value) && value > 0 ? value : 0;
    }
    if (typeof value === 'string' && /^\d+(\.\d+)?(vp)?$/.test(value.trim())) {
      return Number.parseFloat(value);
    }
    return 0;
  }
}
