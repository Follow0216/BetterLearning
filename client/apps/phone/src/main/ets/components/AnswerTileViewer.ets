import {
  AnswerTileBounds,
  AnswerTileImage,
  AnswerTileGeometry,
  AnswerViewTransform,
  calculateAnswerGeometry,
  clampAnswerScale,
  fitAnswerView,
  panAnswerView,
  zoomAnswerViewAt
} from '../model/AnswerTileGeometry';
import {
  PHONE_COLOR_BORDER,
  PHONE_COLOR_CARD,
  PHONE_COLOR_DANGER,
  PHONE_COLOR_MUTED,
  PHONE_COLOR_PRIMARY,
  PHONE_COLOR_SOFT,
  PHONE_COLOR_TITLE
} from '../theme/PhoneTheme';

interface RenderTile extends AnswerTileImage {
  generation: number;
}

/** Read-only composite images. The caller supplies authenticated private file URIs. */
@Component
export struct AnswerTileViewer {
  @Prop @Watch('resetDocument') documentKey: string = '';
  @Prop @Watch('resetDocument') bounds: AnswerTileBounds = { x: 0, y: 0, width: 1, height: 1 };
  @Prop @Watch('resetDocument') tiles: AnswerTileImage[] = [];
  onTileError: (index: number) => void = () => {};
  onRetry: (() => void) | null = null;
  @Prop returnLabel: string = '返回详情';
  onReturn: () => void = () => {};

  @State private renderTiles: RenderTile[] = [];
  @State private loadedIndexes: number[] = [];
  @State private failedIndexes: number[] = [];
  @State private errorText: string = '';
  @State private viewScale: number = 1;
  @State private offsetX: number = 0;
  @State private offsetY: number = 0;
  @State private measured: boolean = false;

  private displayBounds: AnswerTileBounds = { x: 0, y: 0, width: 1, height: 1 };
  private viewportWidth: number = 0;
  private viewportHeight: number = 0;
  private fitScale: number = 1;
  private generation: number = 0;
  private panBase: AnswerViewTransform = { scale: 1, offsetX: 0, offsetY: 0 };
  private pinchBaseScale: number = 1;
  private pinchWorldX: number = 0;
  private pinchWorldY: number = 0;
  private pinching: boolean = false;
  private suppressPan: boolean = true;

  aboutToAppear(): void {
    this.resetDocument();
  }

  aboutToDisappear(): void {
    this.generation++;
    this.pinching = false;
    this.suppressPan = true;
  }

  resetDocument(): void {
    this.generation++;
    this.pinching = false;
    this.suppressPan = true;
    const result: AnswerTileGeometry = calculateAnswerGeometry(this.bounds, this.tiles);
    this.displayBounds = result.bounds;
    this.errorText = result.error;
    this.loadedIndexes = [];
    this.failedIndexes = [];
    const prepared: RenderTile[] = [];
    for (let index: number = 0; index < result.tiles.length; index++) {
      const tile: AnswerTileImage = result.tiles[index];
      prepared.push({ index: tile.index, x: tile.x, y: tile.y, width: tile.width,
        height: tile.height, source: tile.source, generation: this.generation });
    }
    this.renderTiles = prepared;
    this.resetView();
  }

  build() {
    Column() {
      Stack({ alignContent: Alignment.TopStart }) {
        ForEach(this.renderTiles, (tile: RenderTile) => {
          Image(tile.source)
            .width(tile.width * this.viewScale)
            .height(tile.height * this.viewScale)
            .position({ x: tile.x * this.viewScale + this.offsetX,
              y: tile.y * this.viewScale + this.offsetY })
            .objectFit(ImageFit.Fill)
            .draggable(false)
            .opacity(this.imagesReady() ? 1 : 0)
            .accessibilityText('只读作答内容')
            .onComplete((event) => {
              if (event !== undefined && event.loadingStatus === 1) {
                this.imageLoaded(tile);
              }
            })
            .onError(() => this.imageFailed(tile))
        }, (tile: RenderTile) => tile.generation.toString() + ':' + tile.index.toString())

        if (!this.imagesReady()) {
          this.document_status()
        } else {
          Column() {
            Row() {
              Button('定位开头')
                .height(44)
                .fontSize(13)
                .fontColor(PHONE_COLOR_PRIMARY)
                .backgroundColor(PHONE_COLOR_CARD)
                .border({ width: 1, color: PHONE_COLOR_BORDER })
                .borderRadius(22)
                .accessibilityText('定位到作答画布开头')
                .onClick(() => this.locateStart())
              Blank().layoutWeight(1)
              this.zoom_controls()
            }
            .width('100%').padding(12)
          }
          .width('100%').height('100%').justifyContent(FlexAlign.End)
          .hitTestBehavior(HitTestMode.Transparent)
        }
      }
      .width('100%')
      .layoutWeight(1)
      .backgroundColor(PHONE_COLOR_CARD)
      .border({ width: 1, color: PHONE_COLOR_BORDER })
      .borderRadius(20)
      .clip(true)
      .onAreaChange((_oldArea: Area, area: Area) => {
        const width: number = this.viewportLength(area.width);
        const height: number = this.viewportLength(area.height);
        if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
          return;
        }
        if (width !== this.viewportWidth || height !== this.viewportHeight) {
          this.viewportWidth = width;
          this.viewportHeight = height;
          this.measured = true;
          this.pinching = false;
          this.suppressPan = true;
          this.resetView();
        }
      })
      .gesture(
        GestureGroup(GestureMode.Parallel,
          PanGesture({ fingers: 1 })
            .onActionStart(() => {
              if (!this.pinching && this.usable()) {
                this.panBase = this.currentView();
                this.suppressPan = false;
              }
            })
            .onActionUpdate((event: GestureEvent) => {
              if (!this.pinching && !this.suppressPan && this.usable()) {
                this.applyView(panAnswerView(this.panBase, event.offsetX, event.offsetY));
              }
            })
            .onActionEnd(() => { this.suppressPan = true; })
            .onActionCancel(() => { this.suppressPan = true; }),
          PinchGesture({ fingers: 2 })
            .onActionStart((event: GestureEvent) => {
              if (this.usable()) {
                this.pinching = true;
                this.suppressPan = true;
                this.pinchBaseScale = this.viewScale;
                this.pinchWorldX = (event.pinchCenterX - this.offsetX) / this.viewScale;
                this.pinchWorldY = (event.pinchCenterY - this.offsetY) / this.viewScale;
              }
            })
            .onActionUpdate((event: GestureEvent) => {
              if (this.pinching && this.usable() && Number.isFinite(event.scale) && event.scale > 0) {
                const scale: number = clampAnswerScale(this.pinchBaseScale * event.scale, this.fitScale);
                this.applyView({ scale: scale,
                  offsetX: event.pinchCenterX - this.pinchWorldX * scale,
                  offsetY: event.pinchCenterY - this.pinchWorldY * scale });
              }
            })
            .onActionEnd(() => { this.pinching = false; })
            .onActionCancel(() => { this.pinching = false; })
        )
      )
      Text(this.loadingText())
        .width('100%')
        .padding({ left: 12, right: 12, top: 9, bottom: 10 })
        .fontSize(12)
        .fontColor(PHONE_COLOR_MUTED)
        .textAlign(TextAlign.Center)
      Row({ space: 10 }) {
        Button('查看全貌')
          .height(50)
          .layoutWeight(1)
          .fontSize(15)
          .fontWeight(FontWeight.Medium)
          .fontColor(PHONE_COLOR_TITLE)
          .backgroundColor(PHONE_COLOR_SOFT)
          .borderRadius(25)
          .enabled(this.usable())
          .accessibilityText('缩放并居中显示完整作答画布')
          .onClick(() => this.resetView())
        Button(this.returnLabel)
          .height(50)
          .layoutWeight(1)
          .fontSize(15)
          .fontWeight(FontWeight.Medium)
          .fontColor(PHONE_COLOR_TITLE)
          .backgroundColor(PHONE_COLOR_SOFT)
          .borderRadius(25)
          .onClick(() => this.onReturn())
      }
      .width('100%')
      .padding(6)
      .backgroundColor(PHONE_COLOR_CARD)
      .borderRadius(31)
    }
    .width('100%')
    .height('100%')
  }

  @Builder
  document_status() {
    Column({ space: 12 }) {
      if (this.failedIndexes.length > 0 || this.errorText.length > 0) {
        Image($r('app.media.bl_warning')).width(32).height(32)
        Text('作答暂时无法完整显示')
          .fontSize(16)
          .fontWeight(FontWeight.Medium)
          .fontColor(PHONE_COLOR_TITLE)
        Text(this.errorText.length > 0 ? this.errorText :
          '有作答图像无法显示。请重新加载完整作答。')
          .fontSize(14)
          .lineHeight(22)
          .fontColor(PHONE_COLOR_DANGER)
          .textAlign(TextAlign.Center)
        if (this.onRetry !== null) {
          Button('重新加载')
            .height(44)
            .fontSize(14)
            .backgroundColor(PHONE_COLOR_SOFT)
            .fontColor(PHONE_COLOR_PRIMARY)
            .onClick(() => {
              const retry: (() => void) | null = this.onRetry;
              if (retry !== null) {
                retry();
              }
            })
        }
      } else if (this.renderTiles.length > 0) {
        LoadingProgress().width(28).height(28).color(PHONE_COLOR_PRIMARY)
        Text('正在准备完整作答…')
          .fontSize(14)
          .fontColor(PHONE_COLOR_MUTED)
      } else {
        Text('暂无已保存的作答')
          .fontSize(15)
          .fontColor(PHONE_COLOR_MUTED)
      }
    }
    .width('100%')
    .height('100%')
    .padding(24)
    .justifyContent(FlexAlign.Center)
    .backgroundColor(PHONE_COLOR_CARD)
  }

  @Builder
  zoom_controls() {
    Row() {
      Button('−')
        .type(ButtonType.Normal)
        .padding(0)
        .width(44)
        .height(44)
        .fontSize(22)
        .fontColor(PHONE_COLOR_TITLE)
        .backgroundColor(Color.Transparent)
        .enabled(this.usable())
        .accessibilityText('缩小作答')
        .onClick(() => this.zoom(0.8))
      Text(this.scaleText())
        .width(52)
        .fontSize(13)
        .fontColor(PHONE_COLOR_TITLE)
        .textAlign(TextAlign.Center)
      Button('+')
        .type(ButtonType.Normal)
        .padding(0)
        .width(44)
        .height(44)
        .fontSize(22)
        .fontColor(PHONE_COLOR_TITLE)
        .backgroundColor(Color.Transparent)
        .enabled(this.usable())
        .accessibilityText('放大作答')
        .onClick(() => this.zoom(1.25))
    }
    .padding({ left: 4, right: 4 })
    .backgroundColor(PHONE_COLOR_CARD)
    .border({ width: 1, color: PHONE_COLOR_BORDER })
    .borderRadius(24)
  }

  private viewportLength(value: Length): number {
    if (typeof value === 'number') {
      return value;
    }
    if (typeof value === 'string' && /^\d+(\.\d+)?(vp)?$/.test(value.trim())) {
      return Number.parseFloat(value);
    }
    return 0;
  }

  private currentView(): AnswerViewTransform {
    return { scale: this.viewScale, offsetX: this.offsetX, offsetY: this.offsetY };
  }

  private applyView(view: AnswerViewTransform): void {
    if (Number.isFinite(view.scale) && view.scale > 0 &&
      Number.isFinite(view.offsetX) && Number.isFinite(view.offsetY)) {
      this.viewScale = view.scale;
      this.offsetX = view.offsetX;
      this.offsetY = view.offsetY;
    }
  }

  private resetView(): void {
    const view: AnswerViewTransform = fitAnswerView(this.displayBounds, this.viewportWidth, this.viewportHeight);
    this.fitScale = view.scale;
    this.applyView(view);
  }

  private usable(): boolean {
    return this.imagesReady();
  }

  private zoom(factor: number): void {
    if (!this.usable()) {
      return;
    }
    this.applyView(zoomAnswerViewAt(this.currentView(),
      clampAnswerScale(this.viewScale * factor, this.fitScale),
      this.viewportWidth / 2, this.viewportHeight / 2));
  }

  private imageLoaded(tile: RenderTile): void {
    if (tile.generation !== this.generation) {
      return;
    }
    if (!this.loadedIndexes.includes(tile.index)) {
      this.loadedIndexes = this.loadedIndexes.concat([tile.index]);
    }
    // 解码失败保持到显式重载，不允许后续单块回调把半份文档恢复为成功。
  }

  private imageFailed(tile: RenderTile): void {
    if (tile.generation !== this.generation || this.failedIndexes.includes(tile.index)) {
      return;
    }
    this.failedIndexes = this.failedIndexes.concat([tile.index]);
    this.pinching = false;
    this.suppressPan = true;
    this.onTileError(tile.index);
  }

  private scaleText(): string {
    return (this.viewScale * 100).toFixed(this.viewScale < 0.1 ? 2 : 0) + '%';
  }

  private imagesReady(): boolean {
    return this.measured && this.errorText.length === 0 && this.failedIndexes.length === 0 &&
      this.renderTiles.length > 0 && this.loadedIndexes.length === this.renderTiles.length;
  }

  private locateStart(): void {
    if (!this.usable()) {
      return;
    }
    const scale: number = this.viewScale;
    const contentWidth: number = this.displayBounds.width * scale;
    const insetX: number = contentWidth <= this.viewportWidth - 32 ?
      (this.viewportWidth - contentWidth) / 2 : 16;
    this.applyView({ scale: scale,
      offsetX: insetX - this.displayBounds.x * scale,
      offsetY: 16 - this.displayBounds.y * scale });
  }

  private loadingText(): string {
    if (this.failedIndexes.length > 0 || this.errorText.length > 0) {
      return '完整加载后才会显示作答。';
    }
    if (!this.imagesReady() && this.renderTiles.length > 0) {
      return '正在准备作答图像 ' + this.loadedIndexes.length.toString() + '/' +
        this.renderTiles.length.toString();
    }
    return '双指缩放，拖动查看';
  }
}
