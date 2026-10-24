import { common } from '@kit.AbilityKit';
import { image } from '@kit.ImageKit';
import {
  authSession,
  AuthResult,
  LearningApi,
  learning_subject_label,
  LearningSubject,
  ManualQuestionInput,
  MAX_QUESTION_MARKDOWN_LENGTH,
  OcrApi,
  OcrResult,
  QuestionDetail,
  QuestionMarkdownView
} from 'common';
import { describe_confirm_error, describe_learning_error } from '../model/QuestionFormat';
import { QuestionCropRect, QuestionCropView } from '../features/ocr/QuestionCropView';
import {
  CroppedImageCleanupError,
  cropToFile,
  discardCroppedImage,
  discardPendingPhoto,
  loadPreview,
  selectImage,
  takePhoto
} from '../features/ocr/QuestionImageSource';
import {
  PHONE_COLOR_BACKGROUND,
  PHONE_COLOR_BORDER,
  PHONE_COLOR_CARD,
  PHONE_COLOR_DANGER,
  PHONE_COLOR_DANGER_SOFT,
  PHONE_COLOR_MUTED,
  PHONE_COLOR_PRIMARY,
  PHONE_COLOR_PRIMARY_DEEP,
  PHONE_COLOR_SOFT,
  PHONE_COLOR_TITLE,
  PHONE_COLOR_WHITE,
  PHONE_RADIUS_CARD,
  PHONE_RADIUS_CONTROL
} from '../theme/PhoneTheme';

enum EntryImageStage {
  IDLE = 'IDLE',
  LOADING = 'LOADING',
  CROP = 'CROP',
  READY = 'READY'
}

/**
 * 手工 Markdown 录题：选择学科、输入正文并确认入库。
 *
 * <p>首次确认生成一次不可变的 {@link ManualQuestionInput}；双击、超时或断网
 * 重试复用同一对象，只有正文或学科改变才重建 requestId。组件在登录会话内
 * 常驻，提交中切换 tab 不会重复创建请求，未知结果可在返回后原样重试。
 * 成功后在父级打开同 questionId 的详情并刷新题库。</p>
 *
 * <p>图片识别只是正文输入辅助：选图或拍照后本机裁剪、上传裁剪 PNG 换取
 * Markdown，仍由用户检查并走原有确认入库；识别结果不会自动入库。</p>
 */
@Component
export struct ManualEntry {
  onSaved: (detail: QuestionDetail) => void = () => {
  };
  onUnauthorized: (token: string) => void = () => {
  };
  @Prop @Watch('on_active_changed') active: boolean = true;

  @State subject: LearningSubject = LearningSubject.HIGHER_MATHEMATICS;
  @State bodyMarkdown: string = '';
  @State errorText: string = '';
  @State submitting: boolean = false;
  @State pendingInput: ManualQuestionInput | null = null;
  @State imageStage: EntryImageStage = EntryImageStage.IDLE;
  @State imageLoading: boolean = false;
  @State cropBusy: boolean = false;
  @State recognizing: boolean = false;
  @State imageError: string = '';
  @State ocrNotice: string = '';
  @State pendingOcrText: string = '';
  @State previewWidth: number = 0;
  @State previewHeight: number = 0;
  @State cropKey: number = 0;

  private api: LearningApi = LearningApi.create_default();
  private ocrApi: OcrApi = OcrApi.create_default();
  private requestSeq: number = 0;
  private preview: image.PixelMap | null = null;
  private cropPath: string = '';
  private imageGeneration: number = 0;
  private alive: boolean = true;

  aboutToAppear(): void {
    this.alive = true;
  }

  aboutToDisappear(): void {
    this.alive = false;
    this.abandon_capture();
  }

  on_active_changed(): void {
    if (!this.active) {
      this.abandon_capture();
    }
  }

  build() {
    Column() {
      this.header()
      Scroll() {
        Column() {
          this.subject_section()
          this.editor_section()
          this.image_section()
          this.preview_section()
          if (this.errorText.length > 0) {
            this.error_banner()
          }
          this.submit_button()
          Text('识别结果只回填正文，仍需手动确认保存；确认后题目进入“待整理”，入库后 M1 不提供修改。')
            .fontSize(11)
            .fontColor(PHONE_COLOR_MUTED)
            .lineHeight(17)
            .width('100%')
            .margin({ top: 10 })
        }
        .width('100%')
        .alignItems(HorizontalAlign.Start)
        .padding({ left: 18, right: 18, bottom: 28 })
      }
      .layoutWeight(1)
      .width('100%')
      .scrollBar(BarState.Off)
      .edgeEffect(EdgeEffect.Spring)
    }
    .width('100%')
    .height('100%')
    .backgroundColor(PHONE_COLOR_BACKGROUND)
  }

  @Builder
  header() {
    Column() {
      Text('录题')
        .fontSize(30)
        .fontWeight(FontWeight.Bold)
        .fontColor(PHONE_COLOR_TITLE)
      Text('粘贴或识别 Markdown，确认后保存到当前账号')
        .fontSize(13)
        .fontColor(PHONE_COLOR_MUTED)
        .margin({ top: 4 })
    }
    .width('100%')
    .alignItems(HorizontalAlign.Start)
    .padding({ left: 18, right: 18, top: 18, bottom: 12 })
  }

  @Builder
  subject_section() {
    Column() {
      Text('学科')
        .fontSize(13)
        .fontWeight(FontWeight.Medium)
        .fontColor(PHONE_COLOR_TITLE)
        .width('100%')
      Row() {
        this.subject_chip(LearningSubject.HIGHER_MATHEMATICS)
        this.subject_chip(LearningSubject.OTHER)
      }
      .width('100%')
      .margin({ top: 8 })
    }
    .width('100%')
    .padding(16)
    .backgroundColor(PHONE_COLOR_CARD)
    .borderRadius(PHONE_RADIUS_CARD)
    .margin({ top: 6 })
  }

  @Builder
  subject_chip(subject: LearningSubject) {
    Text(learning_subject_label(subject))
      .fontSize(13)
      .fontWeight(this.subject === subject ? FontWeight.Medium : FontWeight.Normal)
      .fontColor(this.subject === subject ? PHONE_COLOR_WHITE : PHONE_COLOR_PRIMARY_DEEP)
      .padding({ left: 16, right: 16, top: 8, bottom: 8 })
      .backgroundColor(this.subject === subject ? PHONE_COLOR_PRIMARY : PHONE_COLOR_SOFT)
      .borderRadius(14)
      .margin({ right: 8 })
      .enabled(!this.submitting)
      .accessibilityText('选择学科 ' + learning_subject_label(subject))
      .onClick(() => {
        this.change_subject(subject);
      })
  }

  @Builder
  editor_section() {
    Column() {
      Row() {
        Text('题目 Markdown')
          .fontSize(13)
          .fontWeight(FontWeight.Medium)
          .fontColor(PHONE_COLOR_TITLE)
          .layoutWeight(1)
        Text(this.bodyMarkdown.length.toString() + ' / ' + MAX_QUESTION_MARKDOWN_LENGTH.toString())
          .fontSize(11)
          .fontColor(this.bodyMarkdown.length > MAX_QUESTION_MARKDOWN_LENGTH ?
          PHONE_COLOR_DANGER : PHONE_COLOR_MUTED)
      }
      .width('100%')

      TextArea({ text: this.bodyMarkdown, placeholder: '# 题干\n在这里输入完整题目 Markdown，可包含公式。' })
        .width('100%')
        .height(260)
        .fontSize(14)
        .fontColor(PHONE_COLOR_TITLE)
        .placeholderColor(PHONE_COLOR_MUTED)
        .backgroundColor(PHONE_COLOR_BACKGROUND)
        .border({ width: 1, color: PHONE_COLOR_BORDER })
        .borderRadius(PHONE_RADIUS_CONTROL)
        .padding(12)
        .maxLength(MAX_QUESTION_MARKDOWN_LENGTH)
        .enabled(!this.submitting)
        .margin({ top: 10 })
        .onChange((value: string) => {
          this.bodyMarkdown = value;
          this.pendingInput = null;
          this.errorText = '';
        })
    }
    .width('100%')
    .padding(16)
    .backgroundColor(PHONE_COLOR_CARD)
    .borderRadius(PHONE_RADIUS_CARD)
    .margin({ top: 12 })
  }

  @Builder
  image_section() {
    Column() {
      Row() {
        Text('从图片识别题面')
          .fontSize(13)
          .fontWeight(FontWeight.Medium)
          .fontColor(PHONE_COLOR_TITLE)
          .layoutWeight(1)
        Text('可选')
          .fontSize(11)
          .fontColor(PHONE_COLOR_MUTED)
      }
      .width('100%')

      if (this.imageStage === EntryImageStage.IDLE || this.imageStage === EntryImageStage.READY) {
        Text(this.imageStage === EntryImageStage.READY ?
          '已裁剪好题图，可直接识别，或重新选择图片。' :
          '选择相册图片或拍照，裁剪题面后识别为 Markdown；不会自动入库。')
          .fontSize(12)
          .fontColor(PHONE_COLOR_MUTED)
          .lineHeight(18)
          .width('100%')
          .margin({ top: 6 })
        Row({ space: 10 }) {
          this.capture_button(this.imageStage === EntryImageStage.READY ? '重新选择' : '选择图片', false)
          this.capture_button(this.imageStage === EntryImageStage.READY ? '重新拍照' : '拍照', true)
        }
        .width('100%')
        .margin({ top: 10 })
        if (this.imageStage === EntryImageStage.READY) {
          Button(this.recognizing ? '正在识别…' : '开始识别')
            .width('100%')
            .height(44)
            .fontSize(14)
            .fontColor(PHONE_COLOR_WHITE)
            .backgroundColor(this.recognizing ? PHONE_COLOR_MUTED : PHONE_COLOR_PRIMARY)
            .borderRadius(PHONE_RADIUS_CONTROL)
            .enabled(!this.recognizing && !this.cropBusy && !this.imageLoading && !this.submitting)
            .opacity(this.recognizing ? 0.65 : 1)
            .margin({ top: 10 })
            .onClick(() => {
              this.start_recognition();
            })
        }
        if (this.recognizing) {
          Button('取消识别')
            .width('100%')
            .height(40)
            .fontSize(13)
            .fontColor(PHONE_COLOR_TITLE)
            .backgroundColor(PHONE_COLOR_SOFT)
            .borderRadius(PHONE_RADIUS_CONTROL)
            .margin({ top: 8 })
            .onClick(() => {
              this.cancel_recognition();
            })
        }
      }

      if (this.imageStage === EntryImageStage.LOADING) {
        Text('正在读取图片…')
          .fontSize(12)
          .fontColor(PHONE_COLOR_MUTED)
          .width('100%')
          .margin({ top: 10 })
      }

      if (this.imageStage === EntryImageStage.CROP) {
        Column() {
          ForEach([this.cropKey], (key: number) => {
            QuestionCropView({
              preview: this.preview,
              imageWidth: this.previewWidth,
              imageHeight: this.previewHeight,
              busy: this.cropBusy,
              onConfirm: (rect: QuestionCropRect) => {
                this.confirm_crop(rect);
              },
              onCancel: () => {
                this.cancel_crop();
              }
            })
              .id('entry-crop-' + key.toString())
          }, (key: number) => key.toString())
        }
        .width('100%')
        .height(430)
        .margin({ top: 10 })
      }

      if (this.pendingOcrText.length > 0) {
        Column() {
          Text('识别结果将替换当前正文（当前 ' + this.bodyMarkdown.length.toString() + ' 个字符）')
            .fontSize(12)
            .fontColor(PHONE_COLOR_TITLE)
            .lineHeight(18)
            .width('100%')
          Text('学科不会被修改；替换后仍可继续编辑再确认保存。')
            .fontSize(11)
            .fontColor(PHONE_COLOR_MUTED)
            .lineHeight(17)
            .width('100%')
            .margin({ top: 4 })
          Row({ space: 10 }) {
            Button('使用识别结果')
              .height(42)
              .layoutWeight(1)
              .fontSize(13)
              .fontColor(PHONE_COLOR_WHITE)
              .backgroundColor(PHONE_COLOR_PRIMARY)
              .borderRadius(PHONE_RADIUS_CONTROL)
              .enabled(!this.submitting)
              .onClick(() => {
                this.apply_pending_ocr();
              })
            Button('保留当前草稿')
              .height(42)
              .layoutWeight(1)
              .fontSize(13)
              .fontColor(PHONE_COLOR_TITLE)
              .backgroundColor(PHONE_COLOR_SOFT)
              .borderRadius(PHONE_RADIUS_CONTROL)
              .enabled(!this.submitting)
              .onClick(() => {
                this.discard_pending_ocr();
              })
          }
          .width('100%')
          .margin({ top: 10 })
        }
        .width('100%')
        .padding(12)
        .backgroundColor(PHONE_COLOR_SOFT)
        .borderRadius(PHONE_RADIUS_CONTROL)
        .margin({ top: 10 })
      }

      if (this.imageError.length > 0) {
        Text(this.imageError)
          .fontSize(12)
          .fontColor(PHONE_COLOR_DANGER)
          .lineHeight(18)
          .width('100%')
          .padding(12)
          .backgroundColor(PHONE_COLOR_DANGER_SOFT)
          .borderRadius(PHONE_RADIUS_CONTROL)
          .margin({ top: 10 })
      }
      if (this.ocrNotice.length > 0) {
        Text(this.ocrNotice)
          .fontSize(12)
          .fontColor(PHONE_COLOR_PRIMARY_DEEP)
          .lineHeight(18)
          .width('100%')
          .padding(12)
          .backgroundColor(PHONE_COLOR_SOFT)
          .borderRadius(PHONE_RADIUS_CONTROL)
          .margin({ top: 10 })
      }
    }
    .width('100%')
    .padding(16)
    .backgroundColor(PHONE_COLOR_CARD)
    .borderRadius(PHONE_RADIUS_CARD)
    .margin({ top: 12 })
  }

  @Builder
  capture_button(label: string, from_camera: boolean) {
    Button(label)
      .height(42)
      .layoutWeight(1)
      .fontSize(14)
      .fontColor(PHONE_COLOR_PRIMARY_DEEP)
      .backgroundColor(PHONE_COLOR_SOFT)
      .borderRadius(PHONE_RADIUS_CONTROL)
      .enabled(!this.imageLoading && !this.cropBusy && !this.recognizing && !this.submitting)
      .opacity(!this.imageLoading && !this.cropBusy && !this.recognizing && !this.submitting ? 1 : 0.6)
      .onClick(() => {
        this.start_capture(from_camera);
      })
  }

  @Builder
  preview_section() {
    Column() {
      Row() {
        Text('公式预览')
          .fontSize(13)
          .fontWeight(FontWeight.Medium)
          .fontColor(PHONE_COLOR_TITLE)
          .layoutWeight(1)
        Text('本地渲染')
          .fontSize(11)
          .fontColor(PHONE_COLOR_MUTED)
      }
      .width('100%')
      if (this.bodyMarkdown.trim().length === 0) {
        Text('输入或识别正文后，在此预览公式排版。')
          .fontSize(12)
          .fontColor(PHONE_COLOR_MUTED)
          .lineHeight(18)
          .width('100%')
          .margin({ top: 8 })
      } else {
        Column() {
          QuestionMarkdownView({ bodyMarkdown: this.bodyMarkdown, fontSize: 14 })
        }
        .width('100%')
        .height(240)
        .backgroundColor(PHONE_COLOR_BACKGROUND)
        .border({ width: 1, color: PHONE_COLOR_BORDER })
        .borderRadius(PHONE_RADIUS_CONTROL)
        .clip(true)
        .margin({ top: 10 })
      }
    }
    .width('100%')
    .padding(16)
    .backgroundColor(PHONE_COLOR_CARD)
    .borderRadius(PHONE_RADIUS_CARD)
    .margin({ top: 12 })
  }

  @Builder
  error_banner() {
    Text(this.errorText)
      .fontSize(12)
      .fontColor(PHONE_COLOR_DANGER)
      .lineHeight(18)
      .width('100%')
      .padding(12)
      .backgroundColor(PHONE_COLOR_DANGER_SOFT)
      .borderRadius(PHONE_RADIUS_CONTROL)
      .margin({ top: 12 })
  }

  @Builder
  submit_button() {
    Button(this.submit_label())
      .width('100%')
      .height(48)
      .fontSize(15)
      .fontWeight(FontWeight.Medium)
      .fontColor(PHONE_COLOR_WHITE)
      .backgroundColor(this.save_blocked() ? PHONE_COLOR_MUTED : PHONE_COLOR_PRIMARY)
      .borderRadius(PHONE_RADIUS_CONTROL)
      .enabled(!this.save_blocked())
      .opacity(this.save_blocked() ? 0.65 : 1)
      .margin({ top: 16 })
      .onClick(() => {
        this.confirm();
      })
  }

  private save_blocked(): boolean {
    return this.submitting || this.recognizing || this.cropBusy || this.imageLoading;
  }

  private submit_label(): string {
    if (this.submitting) {
      return '正在保存…';
    }
    if (this.recognizing) {
      return '识别中，暂不可保存';
    }
    if (this.cropBusy) {
      return '裁剪处理中…';
    }
    if (this.imageLoading) {
      return '图片读取中…';
    }
    return this.pendingInput === null ? '确认保存' : '重试保存';
  }

  private change_subject(subject: LearningSubject): void {
    if (this.submitting || this.subject === subject) {
      return;
    }
    this.subject = subject;
    this.pendingInput = null;
    this.errorText = '';
  }

  private image_stale(generation: number, token: string): boolean {
    return !this.alive || !this.active || generation !== this.imageGeneration ||
      !authSession.matches_access_token(token);
  }

  private detach_preview(): void {
    this.preview = null;
    if (this.alive) {
      this.previewWidth = 0;
      this.previewHeight = 0;
      this.cropKey = this.cropKey + 1;
    }
  }

  private async release_map(map: image.PixelMap | null): Promise<void> {
    if (map === null) {
      return;
    }
    try {
      await map.release();
    } catch (error) {
      // PixelMap 已释放时忽略重复释放错误，不阻断后续流程。
    }
  }

  private release_preview(): void {
    const preview: image.PixelMap | null = this.preview;
    this.detach_preview();
    this.release_map(preview);
  }

  private drop_crop_file(): void {
    if (this.cropPath.length === 0) {
      return;
    }
    discardCroppedImage(this.cropPath);
    this.cropPath = '';
  }

  /**
   * 切页、退出会话或销毁时取消采集/裁剪等待；正文与学科保持不变。
   * 裁剪在途时只解除页面对该 PixelMap 的引用，释放由 confirm_crop 的局部引用负责。
   */
  private abandon_capture(): void {
    const cropping: boolean = this.cropBusy;
    this.imageGeneration = this.imageGeneration + 1;
    this.imageStage = EntryImageStage.IDLE;
    this.imageLoading = false;
    this.cropBusy = false;
    this.recognizing = false;
    this.imageError = '';
    this.ocrNotice = '';
    this.pendingOcrText = '';
    this.drop_crop_file();
    if (cropping) {
      this.detach_preview();
    } else {
      this.release_preview();
    }
  }

  private async start_capture(from_camera: boolean): Promise<void> {
    if (this.save_blocked()) {
      return;
    }
    const token: string | null = authSession.read_access_token();
    if (token === null) {
      this.onUnauthorized('');
      return;
    }
    const generation: number = this.imageGeneration + 1;
    this.imageGeneration = generation;
    this.imageLoading = true;
    this.imageStage = EntryImageStage.LOADING;
    this.imageError = '';
    this.ocrNotice = '';
    this.pendingOcrText = '';
    this.drop_crop_file();
    this.release_preview();
    const context: common.UIAbilityContext = getContext(this) as common.UIAbilityContext;
    let loaded: image.PixelMap | null = null;
    try {
      const uri: string | undefined = from_camera ? await takePhoto(context) : await selectImage(context);
      if (uri === undefined) {
        if (!this.image_stale(generation, token)) {
          this.imageStage = EntryImageStage.IDLE;
        }
        return;
      }
      if (this.image_stale(generation, token)) {
        if (from_camera) {
          discardPendingPhoto(uri);
        }
        return;
      }
      loaded = await loadPreview(uri);
      if (this.image_stale(generation, token)) {
        return;
      }
      const info: image.ImageInfo = await loaded.getImageInfo();
      if (this.image_stale(generation, token)) {
        return;
      }
      if (!Number.isInteger(info.size.width) || !Number.isInteger(info.size.height) ||
        info.size.width <= 0 || info.size.height <= 0) {
        this.imageError = '图片尺寸无效，请重新选择';
        return;
      }
      this.preview = loaded;
      loaded = null;
      this.previewWidth = info.size.width;
      this.previewHeight = info.size.height;
      this.cropKey = this.cropKey + 1;
      this.imageStage = EntryImageStage.CROP;
    } catch (error) {
      if (!this.image_stale(generation, token)) {
        this.imageError = from_camera ? '无法打开相机或读取照片，请重试' : '无法读取所选图片，请重试';
      }
    } finally {
      await this.release_map(loaded);
      if (generation === this.imageGeneration) {
        this.imageLoading = false;
        if (this.imageStage === EntryImageStage.LOADING) {
          this.imageStage = EntryImageStage.IDLE;
        }
      }
    }
  }

  private async confirm_crop(rect: QuestionCropRect): Promise<void> {
    const preview: image.PixelMap | null = this.preview;
    if (preview === null || this.save_blocked()) {
      return;
    }
    const token: string | null = authSession.read_access_token();
    if (token === null) {
      this.onUnauthorized('');
      return;
    }
    const generation: number = this.imageGeneration;
    const context: common.UIAbilityContext = getContext(this) as common.UIAbilityContext;
    this.cropBusy = true;
    this.imageError = '';
    this.ocrNotice = '';
    let createdPath: string = '';
    let keepPreview: boolean = false;
    try {
      createdPath = await cropToFile(context, preview, rect);
      if (this.image_stale(generation, token)) {
        discardCroppedImage(createdPath);
        createdPath = '';
        return;
      }
      this.drop_crop_file();
      this.cropPath = createdPath;
      createdPath = '';
      this.imageStage = EntryImageStage.READY;
      this.ocrNotice = '已生成裁剪图，可开始识别。';
    } catch (error) {
      if (!this.image_stale(generation, token)) {
        if (error instanceof CroppedImageCleanupError) {
          try {
            discardCroppedImage(error.path);
          } catch (cleanupError) {
            // 临时目录仍无法清理时保留在应用缓存中，由系统缓存回收。
          }
          this.imageError = '裁剪文件写入失败，请重试';
        } else {
          this.imageError = '裁剪失败，请调整选区后重试';
        }
        keepPreview = this.imageStage === EntryImageStage.CROP && this.preview === preview;
      }
    } finally {
      if (generation === this.imageGeneration) {
        this.cropBusy = false;
      }
      if (!keepPreview) {
        if (this.preview === preview) {
          this.detach_preview();
        }
        await this.release_map(preview);
      }
    }
  }

  private cancel_crop(): void {
    if (this.cropBusy || this.recognizing) {
      return;
    }
    this.imageGeneration = this.imageGeneration + 1;
    this.imageStage = EntryImageStage.IDLE;
    this.imageError = '';
    this.ocrNotice = '';
    this.release_preview();
  }

  private async start_recognition(): Promise<void> {
    if (this.save_blocked()) {
      return;
    }
    if (this.cropPath.length === 0) {
      this.imageError = '请先选择并裁剪题图';
      return;
    }
    const token: string | null = authSession.read_access_token();
    if (token === null) {
      this.onUnauthorized('');
      return;
    }
    const generation: number = this.imageGeneration;
    const path: string = this.cropPath;
    this.recognizing = true;
    this.imageError = '';
    this.ocrNotice = '';
    this.pendingOcrText = '';
    try {
      const result: AuthResult<OcrResult> = await this.ocrApi.recognize(token, path);
      if (this.image_stale(generation, token) || path !== this.cropPath) {
        return;
      }
      if (!authSession.has_valid_session()) {
        this.handle_unauthorized(token);
        return;
      }
      if (!result.isSuccess || result.data === null) {
        if (result.error !== null && result.error.is_unauthorized()) {
          this.handle_unauthorized(token);
          return;
        }
        this.imageError = '识别失败：' + describe_learning_error(result.error) + '；图片已保留，可重试';
        return;
      }
      this.accept_ocr_text(result.data.bodyMarkdown);
    } catch (error) {
      if (!this.image_stale(generation, token) && path === this.cropPath) {
        this.imageError = '识别请求异常，请重试；图片已保留';
      }
    } finally {
      if (generation === this.imageGeneration) {
        this.recognizing = false;
      }
    }
  }

  /**
   * 取消等待识别：只忽略迟到响应并结束界面，不承诺服务端中止。
   * 裁剪 PNG 保留，允许用户再次显式识别。
   */
  private cancel_recognition(): void {
    if (!this.recognizing) {
      return;
    }
    this.imageGeneration = this.imageGeneration + 1;
    this.recognizing = false;
    this.imageError = '';
    this.ocrNotice = '已取消等待识别结果，裁剪图仍保留可重试。';
    this.imageStage = EntryImageStage.READY;
  }

  private accept_ocr_text(markdown: string): void {
    const text: string = markdown.trim();
    this.drop_crop_file();
    this.imageStage = EntryImageStage.IDLE;
    if (text.length === 0) {
      this.imageError = '识别结果为空，请重新选择图片或手工输入';
      return;
    }
    if (this.bodyMarkdown.trim().length === 0) {
      this.bodyMarkdown = text;
      this.pendingInput = null;
      this.errorText = '';
      this.ocrNotice = '已回填识别结果，请检查公式后确认保存';
      return;
    }
    this.pendingOcrText = text;
    this.errorText = '';
    this.ocrNotice = '';
  }

  private apply_pending_ocr(): void {
    if (this.pendingOcrText.length === 0) {
      return;
    }
    this.bodyMarkdown = this.pendingOcrText;
    this.pendingOcrText = '';
    this.pendingInput = null;
    this.errorText = '';
    this.ocrNotice = '已使用识别结果，请检查公式后确认保存';
  }

  private discard_pending_ocr(): void {
    this.pendingOcrText = '';
    this.ocrNotice = '已保留当前正文';
  }

  /**
   * 校验正文并确认入库；失败保留同一确认对象供重试。
   */
  private async confirm(): Promise<void> {
    if (this.save_blocked()) {
      return;
    }
    const validation: string = this.validate();
    if (validation.length > 0) {
      this.errorText = validation;
      return;
    }
    let input: ManualQuestionInput | null = this.pendingInput;
    if (input === null || input.subject !== this.subject ||
      input.bodyMarkdown !== this.bodyMarkdown) {
      input = ManualQuestionInput.create(this.subject, this.bodyMarkdown);
      this.pendingInput = input;
    }
    if (input === null) {
      return;
    }
    const token: string | null = authSession.read_access_token();
    if (token === null) {
      this.onUnauthorized('');
      return;
    }
    const seq: number = ++this.requestSeq;
    this.submitting = true;
    this.errorText = '';
    try {
      const result: AuthResult<QuestionDetail> = await this.api.confirmManualQuestion(token, input);
      if (seq !== this.requestSeq || !authSession.matches_access_token(token)) {
        return;
      }
      if (!authSession.has_valid_session()) {
        this.handle_unauthorized(token);
        return;
      }
      if (!result.isSuccess || result.data === null) {
        if (result.error !== null && result.error.is_unauthorized()) {
          this.handle_unauthorized(token);
          return;
        }
        this.errorText = describe_confirm_error(result.error);
        return;
      }
      const detail: QuestionDetail = result.data;
      this.pendingInput = null;
      this.bodyMarkdown = '';
      this.imageGeneration = this.imageGeneration + 1;
      this.imageStage = EntryImageStage.IDLE;
      this.imageError = '';
      this.ocrNotice = '';
      this.pendingOcrText = '';
      this.drop_crop_file();
      this.release_preview();
      this.onSaved(detail);
    } catch (error) {
      if (seq !== this.requestSeq || !authSession.matches_access_token(token)) {
        return;
      }
      if (!authSession.has_valid_session()) {
        this.handle_unauthorized(token);
        return;
      }
      this.errorText = '确认请求异常，请重试';
    } finally {
      if (seq === this.requestSeq) {
        this.submitting = false;
      }
    }
  }

  private validate(): string {
    if (this.bodyMarkdown.trim().length === 0) {
      return '请输入题目正文';
    }
    if (this.bodyMarkdown.length > MAX_QUESTION_MARKDOWN_LENGTH) {
      return '题目正文不能超过 20000 个字符';
    }
    return '';
  }

  /**
   * 清理当前令牌会话并通知外层回到登录；旧令牌不影响新会话。
   *
   * @param token 触发失效的访问令牌
   */
  private handle_unauthorized(token: string): void {
    this.abandon_capture();
    authSession.clear_session_if_token(token);
    this.onUnauthorized(token);
  }
}