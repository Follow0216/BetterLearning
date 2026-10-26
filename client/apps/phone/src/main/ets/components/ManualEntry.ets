import { common } from '@kit.AbilityKit';
import { BatchOcrReview } from './BatchOcrReview';
import { batchDraftSession } from '../features/ocr/BatchOcrDrafts';
import { BatchImageImport, selectBatchImages } from '../features/ocr/BatchQuestionImageSource';
import { KeyboardAvoidMode } from '@kit.ArkUI';
import {
  authSession, AuthResult, LearningApi, learning_subject_label, LearningSubject,
  ManualQuestionInput, MAX_QUESTION_MARKDOWN_LENGTH,
  QuestionDetail, QuestionMarkdownView, ToastStore
} from 'common';
import { describe_confirm_error } from '../model/QuestionFormat';
import { PhoneBackController } from '../model/PhoneHomeState';
import { discardPendingPhoto, takePhoto } from '../features/ocr/QuestionImageSource';
import {
  PHONE_COLOR_BACKGROUND, PHONE_COLOR_BORDER, PHONE_COLOR_CARD,
  PHONE_COLOR_DANGER, PHONE_COLOR_DANGER_SOFT, PHONE_COLOR_MUTED,
  PHONE_COLOR_PRIMARY, PHONE_COLOR_PRIMARY_DEEP, PHONE_COLOR_SOFT,
  PHONE_COLOR_TITLE, PHONE_COLOR_WHITE, PHONE_RADIUS_CARD, PHONE_RADIUS_CONTROL
} from '../theme/PhoneTheme';

enum EntryScreen {
  SOURCE = 'SOURCE',
  REVIEW = 'REVIEW',
  BATCH = 'BATCH'
}

enum EntryReviewMode {
  EDIT = 'EDIT',
  PREVIEW = 'PREVIEW'
}

/**
 * 本次登录会话中的录题覆盖层。父级常驻组件并用 active/visibility 切换。
 * 手动正文保留在本组件；相机和相册均以一次性整图意图交给批量页。
 * 图片识别只是输入辅助，不自动保存；图库原图永远只读。
 * 保存失败保留同一 ManualQuestionInput，正文或学科变化才重建 requestId。
 */
@Component
export struct ManualEntry {
  toast?: ToastStore;
  onContextChanged: (context: string) => void = () => {};
  onSaved: (detail: QuestionDetail, scope?: number) => void = () => {};
  onBatchSaved: () => void = () => {};
  onUnauthorized: (token: string) => void = () => {};
  onBack: () => void = () => {};
  onNavigationVisibilityChanged: (visible: boolean) => void = () => {};
  backController: PhoneBackController = new PhoneBackController();
  private batchBackController: PhoneBackController = new PhoneBackController();
  @Prop @Watch('on_active_changed') active: boolean = true;

  @State subject: LearningSubject = LearningSubject.HIGHER_MATHEMATICS;
  @State bodyMarkdown: string = '';
  @State errorText: string = '';
  @State @Watch('report_navigation_visibility') submitting: boolean = false;
  @State pendingInput: ManualQuestionInput | null = null;
  @State @Watch('report_navigation_visibility') screen: EntryScreen = EntryScreen.SOURCE;
  @State reviewMode: EntryReviewMode = EntryReviewMode.EDIT;
  @State @Watch('report_navigation_visibility') imageLoading: boolean = false;
  @State imageError: string = '';
  @State @Watch('report_navigation_visibility') editorFocused: boolean = false;
  @State @Watch('report_navigation_visibility') sourcePicker: boolean = false;
  @State @Watch('report_navigation_visibility') discardPrompt: boolean = false;

  private api: LearningApi = LearningApi.create_default();
  private editorController: TextAreaController = new TextAreaController();
  private requestSeq: number = 0;
  private imageGeneration: number = 0;
  private imageImport: BatchImageImport | null = null;
  private alive: boolean = true;
  private keyboardModeOwned: boolean = false;
  private previousKeyboardMode: KeyboardAvoidMode = KeyboardAvoidMode.OFFSET;

  aboutToAppear(): void {
    this.alive = true;
    batchDraftSession.bind(authSession.read_access_token() ?? '');
    this.backController.handleBack = (): boolean => { this.go_back(); return true; };
    if (this.active) { this.claim_keyboard_mode(); this.report_navigation_visibility(); }
  }

  aboutToDisappear(): void {
    this.alive = false;
    this.backController.handleBack = (): boolean => false;
    this.requestSeq++;
    this.editorController.stopEditing();
    this.restore_keyboard_mode();
    this.abandon_capture();
    if (authSession.read_access_token() === null) { batchDraftSession.bind(''); }
  }

  on_active_changed(): void {
    if (this.active) {
      this.claim_keyboard_mode();
      this.report_navigation_visibility();
      return;
    }
    this.editorController.stopEditing();
    this.editorFocused = false;
    this.restore_keyboard_mode();
    this.sourcePicker = false;
    this.discardPrompt = false;
    this.discard_image_import();
    // 暂离使等待中的选择响应失效；已形成的批次由登录草稿会话保留。
    this.imageGeneration++;
    this.imageLoading = false;
  }

  /** 只有当前可见实例报告入口导航；隐藏期间的异步状态不控制父层底栏。 */
  report_navigation_visibility(): void {
    if (!this.alive || !this.active) { return; }
    this.onContextChanged(this.screen);
    this.onNavigationVisibilityChanged(this.screen === EntryScreen.SOURCE && !this.editorFocused &&
      !this.sourcePicker && !this.discardPrompt && !this.imageLoading && !this.submitting);
  }

  build() {
    Stack({ alignContent: Alignment.Bottom }) {
      Column() {
        this.header()
        if (this.screen === EntryScreen.SOURCE) {
          this.source_screen()
        } else if (this.screen === EntryScreen.BATCH) {
          BatchOcrReview({ toast: this.toast, active: this.active, imageImport: this.imageImport, backController: this.batchBackController, onSaved: () => { this.onBatchSaved(); }, onUnauthorized: (token: string) => { this.handle_unauthorized(token); } })
        } else {
          this.review_screen()
        }
      }
      .width('100%')
      .height('100%')
      if (this.sourcePicker) { this.source_picker() }
      if (this.discardPrompt) { this.discard_prompt() }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(PHONE_COLOR_BACKGROUND)
  }

  @Builder
  header() {
    Row({ space: 10 }) {
      Button({ type: ButtonType.Normal }) {
        Image($r('app.media.bl_back')).width(22).height(22)
      }
      .width(44).height(44).padding(10)
      .backgroundColor(Color.Transparent)
      .accessibilityText(this.screen === EntryScreen.SOURCE ? '返回AI首页，保留本次录题' : '返回录题入口，保留本次内容')
      .enabled(!this.submitting)
      .onClick(() => { this.go_back(); })
      Text(this.screen === EntryScreen.SOURCE ? '录题' :
        this.screen === EntryScreen.BATCH ? '批量录题' : '校对题目')
        .fontSize(22).fontWeight(FontWeight.Bold).fontColor(PHONE_COLOR_TITLE).layoutWeight(1)
      if (this.screen !== EntryScreen.SOURCE && this.screen !== EntryScreen.BATCH) {
        Button(this.editorFocused ? '完成' : '添加图片')
          .height(40).fontSize(14).fontColor(PHONE_COLOR_PRIMARY)
          .backgroundColor(PHONE_COLOR_SOFT).borderRadius(20)
          .enabled(!this.save_blocked())
          .onClick(() => {
            if (this.editorFocused) { this.show_preview(); } else { this.sourcePicker = true; }
          })
      }
    }
    .width('100%').padding({ left: 8, right: 18, top: 10, bottom: 8 })
  }

  @Builder
  source_screen() {
    Scroll() {
      Column({ space: 16 }) {
        Text('录入题目，开始学习。')
          .fontSize(16).fontColor(PHONE_COLOR_MUTED).width('100%')
        Column({ space: 12 }) {
          Image($r('app.media.entry_camera')).width(62).height(62)
          Text('拍下整页题目').fontSize(23).fontWeight(FontWeight.Bold).fontColor(PHONE_COLOR_TITLE)
          Text('无需裁剪，自动识别整图题目，勾选后添加').fontSize(14).fontColor(PHONE_COLOR_MUTED)
          Button(this.imageLoading ? '正在打开…' : '打开相机')
            .width('100%').height(48).fontSize(17).fontColor(PHONE_COLOR_WHITE)
            .backgroundColor(PHONE_COLOR_PRIMARY).borderRadius(24).enabled(!this.save_blocked())
            .margin({ top: 6 }).onClick(() => { this.start_capture(true); })
        }
        .width('100%').padding(22).backgroundColor(PHONE_COLOR_SOFT).borderRadius(PHONE_RADIUS_CARD)
        Row({ space: 12 }) {
          this.source_card('从相册选择', $r('app.media.entry_gallery'), false)
          this.source_card('手动输入', $r('app.media.entry_edit'), true)
        }
        .width('100%')
        if (this.has_batch_drafts()) {
          Button('继续校对 · ' + batchDraftSession.batches.length.toString() + ' 批草稿')
            .width('100%').height(44).fontSize(14).fontColor(PHONE_COLOR_PRIMARY)
            .backgroundColor(Color.Transparent).enabled(!this.save_blocked())
            .onClick(() => { this.screen = EntryScreen.BATCH; })
        }
        if (this.has_draft()) {
          Column({ space: 10 }) {
            Row({ space: 10 }) {
              Image($r('app.media.bl_list')).width(28).height(28)
              Column({ space: 4 }) {
                Text('还有一道题正在校对').fontSize(16).fontWeight(FontWeight.Medium).fontColor(PHONE_COLOR_TITLE)
                Text('本次录题尚未保存').fontSize(12).fontColor(PHONE_COLOR_MUTED)
              }.alignItems(HorizontalAlign.Start).layoutWeight(1)
              Text('继续  ›').fontSize(15).fontColor(PHONE_COLOR_PRIMARY)
            }
            .width('100%').constraintSize({ minHeight: 54 })
            .onClick(() => { this.continue_draft(); })
            Button('丢弃本次录题').height(40).fontSize(13).fontColor(PHONE_COLOR_MUTED)
              .backgroundColor(Color.Transparent).enabled(!this.save_blocked())
              .onClick(() => { this.discardPrompt = true; })
          }
          .width('100%').padding(16).backgroundColor(PHONE_COLOR_CARD)
          .border({ width: 1, color: PHONE_COLOR_BORDER }).borderRadius(PHONE_RADIUS_CARD)
        }
        this.notices()
        Text('识别后由你检查，再保存到题库。\n未保存内容仅保留在本次登录会话，关闭应用后不保证保留。')
          .fontSize(12).fontColor(PHONE_COLOR_MUTED).lineHeight(19).textAlign(TextAlign.Center)
          .width('100%').margin({ top: 4 })
      }
      .width('100%').padding({ left: 18, right: 18, top: 10, bottom: 24 })
    }
    .layoutWeight(1).width('100%').align(Alignment.TopStart).scrollBar(BarState.Off)
  }

  @Builder
  source_card(label: string, icon: Resource, manual: boolean) {
    Column({ space: 14 }) {
      Image(icon).width(36).height(36)
      Text(label).fontSize(16).fontWeight(FontWeight.Medium).fontColor(PHONE_COLOR_TITLE)
    }
    .layoutWeight(1).height(132).justifyContent(FlexAlign.Center)
    .backgroundColor(PHONE_COLOR_CARD).border({ width: 1, color: PHONE_COLOR_BORDER })
    .borderRadius(PHONE_RADIUS_CARD).enabled(!this.save_blocked())
    .accessibilityText(label).accessibilityLevel('yes')
    .onClick(() => {
      if (manual) {
        this.reviewMode = EntryReviewMode.EDIT;
        this.screen = EntryScreen.REVIEW;
      } else { this.start_capture(false); }
    })
  }

  @Builder
  review_screen() {
    Column() {
      Scroll() {
        Column({ space: 12 }) {
          this.subject_section()
          this.review_tabs()
          if (this.reviewMode === EntryReviewMode.EDIT) {
            this.editor_section()
          } else {
            this.preview_section()
          }
          this.notices()
          Text(this.reviewMode === EntryReviewMode.EDIT ?
            '可直接修改文字和公式，切换到预览检查排版。' : '请核对公式上下限、符号和题目条件。')
            .fontSize(12).fontColor(PHONE_COLOR_MUTED).lineHeight(18).width('100%')
        }
        .width('100%').padding({ left: 18, right: 18, top: 4, bottom: 16 })
      }
      .width('100%').layoutWeight(1).align(Alignment.TopStart).scrollBar(BarState.Off)
      this.review_actions()
    }
    .width('100%').layoutWeight(1)
  }

  @Builder
  subject_section() {
    Row({ space: 10 }) {
      Text('学科').fontSize(14).fontColor(PHONE_COLOR_MUTED)
      this.subject_chip(LearningSubject.HIGHER_MATHEMATICS)
      this.subject_chip(LearningSubject.OTHER)
    }.width('100%')
  }

  @Builder
  subject_chip(subject: LearningSubject) {
    Button(learning_subject_label(subject))
      .height(40).fontSize(13)
      .fontColor(this.subject === subject ? PHONE_COLOR_PRIMARY_DEEP : PHONE_COLOR_MUTED)
      .fontWeight(this.subject === subject ? FontWeight.Medium : FontWeight.Normal)
      .backgroundColor(this.subject === subject ? PHONE_COLOR_SOFT : PHONE_COLOR_CARD)
      .borderRadius(20).enabled(!this.submitting)
      .accessibilityText('选择学科 ' + learning_subject_label(subject))
      .onClick(() => { this.change_subject(subject); })
  }

  @Builder
  review_tabs() {
    Row({ space: 4 }) {
      this.review_tab('编辑文字', EntryReviewMode.EDIT)
      this.review_tab('排版预览', EntryReviewMode.PREVIEW)
    }
    .width('100%').padding(4).border({ width: 1, color: PHONE_COLOR_BORDER }).borderRadius(24)
  }

  @Builder
  review_tab(label: string, mode: EntryReviewMode) {
    Button(label).layoutWeight(1).height(40).fontSize(15)
      .fontColor(this.reviewMode === mode ? PHONE_COLOR_PRIMARY : PHONE_COLOR_MUTED)
      .backgroundColor(this.reviewMode === mode ? PHONE_COLOR_SOFT : Color.Transparent)
      .borderRadius(20)
      .onClick(() => {
        if (mode === EntryReviewMode.PREVIEW) { this.show_preview(); } else { this.reviewMode = mode; }
      })
  }

  @Builder
  editor_section() {
    Column({ space: 8 }) {
      TextArea({ text: this.bodyMarkdown, placeholder: '输入完整题目，保留全部条件和公式。', controller: this.editorController })
        .width('100%').height(this.editorFocused ? 156 : 250).fontSize(17).lineHeight(26)
        .fontColor(PHONE_COLOR_TITLE).placeholderColor(PHONE_COLOR_MUTED)
        .backgroundColor(PHONE_COLOR_CARD).border({ width: 1, color: PHONE_COLOR_BORDER })
        .borderRadius(PHONE_RADIUS_CARD).padding(16).maxLength(MAX_QUESTION_MARKDOWN_LENGTH)
        .enabled(!this.submitting)
        .onChange((value: string) => { this.change_body(value); })
        .onEditChange((editing: boolean) => { this.editorFocused = editing; })
      Text(this.bodyMarkdown.length.toString() + ' / ' + MAX_QUESTION_MARKDOWN_LENGTH.toString())
        .fontSize(11).fontColor(this.bodyMarkdown.length > MAX_QUESTION_MARKDOWN_LENGTH ?
          PHONE_COLOR_DANGER : PHONE_COLOR_MUTED).width('100%').textAlign(TextAlign.End)
    }.width('100%')
  }

  @Builder
  preview_section() {
    Column() {
      if (this.bodyMarkdown.trim().length === 0) {
        Text('输入题目后，在这里检查排版。')
          .fontSize(15).fontColor(PHONE_COLOR_MUTED).padding(22).width('100%')
      } else {
        QuestionMarkdownView({ bodyMarkdown: this.bodyMarkdown, fontSize: 17 })
      }
    }
    .width('100%').height(300).backgroundColor(PHONE_COLOR_CARD)
    .border({ width: 1, color: PHONE_COLOR_BORDER }).borderRadius(PHONE_RADIUS_CARD).clip(true)
  }

  @Builder
  notices() {
    if (this.imageLoading) { this.notice_banner('正在读取图片，取消选择会保留原来的内容。', false) }
    if (this.imageError.length > 0) { this.notice_banner(this.imageError, true) }
    if (this.errorText.length > 0) { this.notice_banner(this.errorText, true) }
  }

  @Builder
  notice_banner(text: string, danger: boolean) {
    Text(text).fontSize(12).fontColor(danger ? PHONE_COLOR_DANGER : PHONE_COLOR_PRIMARY_DEEP)
      .lineHeight(18).width('100%').padding(12)
      .backgroundColor(danger ? PHONE_COLOR_DANGER_SOFT : PHONE_COLOR_SOFT).borderRadius(PHONE_RADIUS_CONTROL)
  }

  @Builder
  review_actions() {
    Column({ space: 7 }) {
      Row({ space: 10 }) {
        Button(this.reviewMode === EntryReviewMode.EDIT ? '预览题目' : '编辑文字')
          .layoutWeight(1).height(48).fontSize(16).fontColor(PHONE_COLOR_PRIMARY_DEEP)
          .backgroundColor(PHONE_COLOR_SOFT).borderRadius(24)
          .onClick(() => {
            if (this.reviewMode === EntryReviewMode.EDIT) { this.show_preview(); }
            else { this.reviewMode = EntryReviewMode.EDIT; }
          })
        Button(this.submitting ? '正在保存…' : this.pendingInput === null ? '确认保存' : '重试保存')
          .layoutWeight(1).height(48).fontSize(16).fontColor(PHONE_COLOR_WHITE)
          .backgroundColor(PHONE_COLOR_PRIMARY).borderRadius(24)
          .enabled(!this.save_blocked())
          .opacity(this.save_blocked() ? 0.55 : 1)
          .onClick(() => { this.editorController.stopEditing(); this.confirm(); })
      }.width('100%')
      if (!this.editorFocused) {
        Text('保存后加入「待整理」').fontSize(11).fontColor(PHONE_COLOR_MUTED)
      }
    }
    .width('100%').padding({ left: 18, right: 18, top: 10, bottom: 12 })
    .backgroundColor(PHONE_COLOR_CARD).border({ width: { top: 1 }, color: PHONE_COLOR_BORDER })
  }

  @Builder
  source_picker() {
    Stack({ alignContent: Alignment.Bottom }) {
      Column() {}.width('100%').height('100%').backgroundColor('#66152744')
        .onClick(() => { this.sourcePicker = false; })
      Column({ space: 12 }) {
        Text('选择题图').fontSize(19).fontWeight(FontWeight.Bold).fontColor(PHONE_COLOR_TITLE)
        Text('拍照或选1–4张原图后自动批量识别；手动正文继续保留。').fontSize(13).fontColor(PHONE_COLOR_MUTED)
        Button('拍照').width('100%').height(48).fontColor(PHONE_COLOR_WHITE).backgroundColor(PHONE_COLOR_PRIMARY)
          .onClick(() => { this.sourcePicker = false; this.start_capture(true); })
        Button('从相册选择').width('100%').height(48).fontColor(PHONE_COLOR_PRIMARY).backgroundColor(PHONE_COLOR_SOFT)
          .onClick(() => { this.sourcePicker = false; this.start_capture(false); })
        Button('取消').width('100%').height(44).fontColor(PHONE_COLOR_MUTED).backgroundColor(Color.Transparent)
          .onClick(() => { this.sourcePicker = false; })
      }.width('100%').padding(22).backgroundColor(PHONE_COLOR_CARD).borderRadius({ topLeft: 24, topRight: 24 })
    }.width('100%').height('100%')
  }

  @Builder
  discard_prompt() {
    Stack({ alignContent: Alignment.Center }) {
      Column() {}.width('100%').height('100%').backgroundColor('#66152744')
      Column({ space: 14 }) {
        Text('丢弃本次录题？').fontSize(20).fontWeight(FontWeight.Bold).fontColor(PHONE_COLOR_TITLE)
        Text('本次手动输入的文字将被清除；批量草稿独立保留。').fontSize(14).fontColor(PHONE_COLOR_MUTED)
        Button('继续校对').width('100%').height(46).fontColor(PHONE_COLOR_WHITE).backgroundColor(PHONE_COLOR_PRIMARY)
          .onClick(() => { this.discardPrompt = false; this.continue_draft(); })
        Button('确认丢弃').width('100%').height(44).fontColor(PHONE_COLOR_DANGER).backgroundColor(PHONE_COLOR_DANGER_SOFT)
          .onClick(() => { this.discard_draft(); })
      }.width('90%').padding(22).backgroundColor(PHONE_COLOR_CARD).borderRadius(PHONE_RADIUS_CARD)
    }.width('100%').height('100%')
  }

  private claim_keyboard_mode(): void {
    if (!this.keyboardModeOwned) {
      this.previousKeyboardMode = this.getUIContext().getKeyboardAvoidMode();
      // Index 已统一接管 RESIZE 时不重复领取，避免子组件卸载覆盖页面恢复。
      if (this.previousKeyboardMode === KeyboardAvoidMode.RESIZE) { return; }
      this.getUIContext().setKeyboardAvoidMode(KeyboardAvoidMode.RESIZE);
      this.keyboardModeOwned = true;
    }
  }

  private restore_keyboard_mode(): void {
    if (this.keyboardModeOwned) {
      this.getUIContext().setKeyboardAvoidMode(this.previousKeyboardMode);
      this.keyboardModeOwned = false;
    }
  }

  private has_draft(): boolean {
    return this.bodyMarkdown.length > 0 || this.pendingInput !== null;
  }

  private has_batch_drafts(): boolean {
    const token: string | null = authSession.read_access_token();
    return token !== null && batchDraftSession.owns(token) && batchDraftSession.batches.length > 0;
  }

  private continue_draft(): void { this.screen = EntryScreen.REVIEW; }

  private go_back(): void {
    if (this.screen === EntryScreen.BATCH && this.batchBackController.handleBack()) { return; }
    // 所有返回入口共用顺序；忙时消费返回，避免未知保存结果跳转。
    if (this.sourcePicker) { this.sourcePicker = false; return; }
    if (this.discardPrompt) { this.discardPrompt = false; return; }
    if (this.editorFocused) {
      this.editorController.stopEditing();
      this.editorFocused = false;
      return;
    }
    if (this.submitting || this.imageLoading) { return; }
    if (this.screen === EntryScreen.SOURCE) { this.onBack(); }
    else { this.discard_image_import(); this.screen = EntryScreen.SOURCE; }
  }

  private show_preview(): void {
    this.editorController.stopEditing();
    this.editorFocused = false;
    this.reviewMode = EntryReviewMode.PREVIEW;
  }

  private save_blocked(): boolean {
    return !this.alive || !this.active || this.submitting || this.imageLoading;
  }

  private change_body(value: string): void {
    if (this.submitting || this.bodyMarkdown === value) { return; }
    this.bodyMarkdown = value;
    this.pendingInput = null;
    this.errorText = '';
  }

  private change_subject(subject: LearningSubject): void {
    if (this.submitting || this.subject === subject) { return; }
    this.subject = subject;
    this.pendingInput = null;
    this.errorText = '';
  }

  private image_stale(generation: number, token: string): boolean {
    return !this.alive || !this.active || generation !== this.imageGeneration || !authSession.matches_access_token(token);
  }

  private discard_image_import(): void {
    const capture: BatchImageImport | null = this.imageImport;
    this.imageImport = null;
    if (capture !== null) {
      try { capture.discard(); } catch (error) { /* 仅清相机自有缓存，图库原图只读，交系统回收失败项。 */ }
    }
  }

  /** 仅确认保存、显式丢弃、会话销毁调用；暂离页面不调用。 */
  private abandon_capture(): void {
    this.discard_image_import();
    this.imageGeneration++;
    this.imageLoading = false;
    this.imageError = '';
  }

  private discard_draft(): void {
    if (this.submitting) { return; }
    this.requestSeq++;
    this.abandon_capture();
    this.bodyMarkdown = '';
    this.pendingInput = null;
    this.errorText = '';
    this.discardPrompt = false;
    this.screen = EntryScreen.SOURCE;
  }

  private async start_capture(from_camera: boolean): Promise<void> {
    if (this.save_blocked()) { return; }
    const token: string | null = authSession.read_access_token();
    if (token === null) { this.onUnauthorized(''); return; }
    const toast_scope: number | undefined = this.toast?.captureScope();
    const generation: number = ++this.imageGeneration;
    this.imageLoading = true;
    this.imageError = '';
    const context: common.UIAbilityContext = getContext(this) as common.UIAbilityContext;
    let cameraUri: string = '';
    try {
      let uris: string[] = [];
      if (from_camera) {
        const uri: string | undefined = await takePhoto(context);
        if (uri === undefined) { return; }
        cameraUri = uri; uris = [uri];
      } else {
        uris = await selectBatchImages(context, 4);
      }
      if (uris.length === 0 || this.image_stale(generation, token)) { return; }
      this.discard_image_import();
      this.imageImport = from_camera ? BatchImageImport.fromCamera(cameraUri, token) : BatchImageImport.fromGallery(uris, token);
      cameraUri = '';
      this.screen = EntryScreen.BATCH;
    } catch (error) {
      if (!this.image_stale(generation, token)) {
        this.imageError = from_camera ? '无法打开相机或读取照片，原来的内容已保留' : '无法读取所选图片，原来的内容已保留';
        this.toast?.show({ key: 'phone-entry-image', type: 'error', message: '暂时无法读取图片', description: '原来的内容已保留，请重试' }, toast_scope);
      }
    } finally {
      if (cameraUri.length > 0) {
        try { discardPendingPhoto(cameraUri); } catch (error) { /* 仅清相机自有缓存。 */ }
      }
      if (generation === this.imageGeneration) { this.imageLoading = false; }
    }
  }

  private async confirm(): Promise<void> {
    if (this.save_blocked()) { return; }
    const validation: string = this.validate();
    if (validation.length > 0) { this.errorText = validation; return; }
    let input: ManualQuestionInput | null = this.pendingInput;
    if (input === null || input.subject !== this.subject || input.bodyMarkdown !== this.bodyMarkdown) {
      input = ManualQuestionInput.create(this.subject, this.bodyMarkdown);
      this.pendingInput = input;
    }
    if (input === null) { return; }
    const token: string | null = authSession.read_access_token();
    if (token === null) { this.onUnauthorized(''); return; }
    const toast_scope: number | undefined = this.toast?.captureScope();
    const seq: number = ++this.requestSeq;
    this.submitting = true;
    this.errorText = '';
    try {
      const result: AuthResult<QuestionDetail> = await this.api.confirmManualQuestion(token, input);
      if (!this.alive || seq !== this.requestSeq || !authSession.matches_access_token(token)) { return; }
      if (!authSession.has_valid_session()) { this.handle_unauthorized(token); return; }
      if (!result.isSuccess || result.data === null) {
        if (result.error !== null && result.error.is_unauthorized()) { this.handle_unauthorized(token); return; }
        this.errorText = describe_confirm_error(result.error);
        this.toast?.show({ key: 'phone-entry-save', type: 'error', message: '题目尚未确认保存', description: '内容已保留，请查看页面提示后重试' }, toast_scope);
        return;
      }
      const detail: QuestionDetail = result.data;
      this.pendingInput = null;
      this.bodyMarkdown = '';
      this.abandon_capture();
      this.onSaved(detail, toast_scope);
      this.screen = EntryScreen.SOURCE;
    } catch (error) {
      if (!this.alive || seq !== this.requestSeq || !authSession.matches_access_token(token)) { return; }
      if (!authSession.has_valid_session()) { this.handle_unauthorized(token); return; }
      this.errorText = '保存请求异常，内容已保留，请重试';
      this.toast?.show({ key: 'phone-entry-save', type: 'error', message: '暂时无法确认保存结果', description: '内容已保留，请重试确认' }, toast_scope);
    } finally {
      if (seq === this.requestSeq) { this.submitting = false; }
    }
  }

  private validate(): string {
    if (this.bodyMarkdown.trim().length === 0) { return '请输入完整题目'; }
    if (this.bodyMarkdown.length > MAX_QUESTION_MARKDOWN_LENGTH) { return '题目不能超过 20000 个字符，请精简后保存'; }
    return '';
  }

  private handle_unauthorized(token: string): void {
    this.abandon_capture();
    authSession.clear_session_if_token(token);
    this.onUnauthorized(token);
  }
}
