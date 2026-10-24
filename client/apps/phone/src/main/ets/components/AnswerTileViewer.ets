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
      this.answerToolbar()
      Stack({ alignContent: Alignment.TopStart }) {
        if (this.errorText.length > 0) {
          Text(this.errorText)
            .width('100%')
            .height('100%')
            .padding(24)
            .fontSize(14)
            .fontColor(PHONE_COLOR_DANGER)
            .textAlign(TextAlign.Center)
        } else if (this.renderTiles.length === 0) {
          Text('暂无已保存的解答')
            .width('100%')
            .height('100%')
            .fontSize(14)
            .fontColor(PHONE_COLOR_MUTED)
            .textAlign(TextAlign.Center)
        } else {
          ForEach(this.renderTiles, (tile: RenderTile) => {
            Image(tile.source)
              .width(tile.width * this.viewScale)
              .height(tile.height * this.viewScale)
              .position({ x: tile.x * this.viewScale + this.offsetX,
                y: tile.y * this.viewScale + this.offsetY })
              .objectFit(ImageFit.Fill)
              .draggable(false)
              .visibility(this.measured && !this.failedIndexes.includes(tile.index) ?
                Visibility.Visible : Visibility.Hidden)
              .accessibilityText('解答图块 ' + (tile.index + 1).toString())
              .onComplete((event) => {
                if (event !== undefined && event.loadingStatus === 1) {
                  this.imageLoaded(tile);
                }
              })
              .onError(() => this.imageFailed(tile))
          }, (tile: RenderTile) => tile.generation.toString() + ':' + tile.index.toString())
        }
      }
      .width('100%')
      .layoutWeight(1)
      .backgroundColor(PHONE_COLOR_CARD)
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
              if (!this.pinching) {
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
              if (this.pinching && Number.isFinite(event.scale) && event.scale > 0) {
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
      if (this.failedIndexes.length > 0 || this.errorText.length > 0) {
        Row() {
          Text(this.errorText.length > 0 ? this.errorText : '部分解答图像加载失败')
            .fontSize(12)
            .fontColor(PHONE_COLOR_DANGER)
            .layoutWeight(1)
          if (this.onRetry !== null) {
            Button('重新加载')
              .height(32)
              .fontSize(12)
              .backgroundColor(PHONE_COLOR_CARD)
              .fontColor(PHONE_COLOR_PRIMARY)
              .onClick(() => {
                const retry: (() => void) | null = this.onRetry;
                if (retry !== null) {
                  retry();
                }
              })
          }
        }
        .width('100%')
        .padding(10)
        .backgroundColor('#FFF5F5')
      }
      Text(this.loadingText())
        .width('100%')
        .padding({ left: 12, right: 12, top: 8, bottom: 8 })
        .fontSize(11)
        .fontColor(PHONE_COLOR_MUTED)
        .border({ width: { top: 1 }, color: PHONE_COLOR_BORDER })
    }
    .width('100%')
    .height('100%')
    .backgroundColor(PHONE_COLOR_CARD)
    .border({ width: 1, color: PHONE_COLOR_BORDER })
    .borderRadius(12)
    .clip(true)
  }

  @Builder
  answerToolbar() {
    Row({ space: 6 }) {
      Text('完整解答')
        .fontSize(13)
        .fontWeight(FontWeight.Medium)
        .fontColor(PHONE_COLOR_TITLE)
      Blank()
      Button('−')
        .type(ButtonType.Normal)
        .padding(0)
        .width(32)
        .height(34)
        .fontSize(18)
        .fontColor(PHONE_COLOR_PRIMARY)
        .backgroundColor(PHONE_COLOR_CARD)
        .borderRadius(8)
        .enabled(this.usable())
        .accessibilityText('缩小解答')
        .onClick(() => this.zoom(0.8))
      Text(this.scaleText())
        .width(48)
        .fontSize(11)
        .fontColor(PHONE_COLOR_MUTED)
        .textAlign(TextAlign.Center)
      Button('+')
        .type(ButtonType.Normal)
        .padding(0)
        .width(32)
        .height(34)
        .fontSize(18)
        .fontColor(PHONE_COLOR_PRIMARY)
        .backgroundColor(PHONE_COLOR_CARD)
        .borderRadius(8)
        .enabled(this.usable())
        .accessibilityText('放大解答')
        .onClick(() => this.zoom(1.25))
      Button('适应全部')
        .height(34)
        .fontSize(12)
        .fontColor(PHONE_COLOR_PRIMARY)
        .backgroundColor(PHONE_COLOR_CARD)
        .borderRadius(8)
        .enabled(this.usable())
        .onClick(() => this.resetView())
    }
    .width('100%')
    .padding({ left: 10, right: 10, top: 8, bottom: 8 })
    .backgroundColor(PHONE_COLOR_SOFT)
    .border({ width: { bottom: 1 }, color: PHONE_COLOR_BORDER })
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
    return this.measured && this.errorText.length === 0 && this.renderTiles.length > 0;
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
    this.failedIndexes = this.failedIndexes.filter((index: number) => index !== tile.index);
  }

  private imageFailed(tile: RenderTile): void {
    if (tile.generation !== this.generation || this.failedIndexes.includes(tile.index)) {
      return;
    }
    this.failedIndexes = this.failedIndexes.concat([tile.index]);
    this.onTileError(tile.index);
  }

  private scaleText(): string {
    return (this.viewScale * 100).toFixed(this.viewScale < 0.1 ? 2 : 0) + '%';
  }

  private loadingText(): string {
    if (this.errorText.length === 0 &&
      this.loadedIndexes.length + this.failedIndexes.length < this.renderTiles.length) {
      return '正在加载解答图像… ' + this.loadedIndexes.length.toString() + '/' + this.renderTiles.length.toString();
    }
    return '拖动平移，双指缩放；点“适应全部”查看完整范围。';
  }
}
