import { common } from '@kit.AbilityKit';
import { image } from '@kit.ImageKit';
import { authSession, ToastStore } from 'common';
import { discardPendingPhoto, loadPreview, selectImage, takePhoto } from '../features/ocr/QuestionImageSource';
import { PhoneBackController, PhoneHomeMode, PhoneTutorSession, PhoneTutorSessionStore } from '../model/PhoneHomeState';
import {
  PHONE_COLOR_BACKGROUND, PHONE_COLOR_BORDER, PHONE_COLOR_CARD, PHONE_COLOR_MUTED,
  PHONE_COLOR_PRIMARY, PHONE_COLOR_PRIMARY_DEEP, PHONE_COLOR_SOFT, PHONE_COLOR_TITLE, PHONE_COLOR_WHITE,
  PHONE_RADIUS_CARD
} from '../theme/PhoneTheme';
import { PhoneReadingPage } from './PhoneReadingPage';
import { PhoneTutorPage } from './PhoneTutorPage';

/** AI 首页。当前题与独立图文服务尚无契约，未知状态不作为成功连接呈现。 */
@Component
export struct PhoneHomePage {
  toast?: ToastStore;
  onContextChanged: (context: string) => void = () => {};
  @Prop requestedQuestionId: string = '';
  @Prop @Watch('on_requested_question') requestSerial: number = 0;
  @Prop @Watch('on_active_changed') active: boolean = true;
  navigation: PhoneBackController = new PhoneBackController();
  onChooseQuestion: () => void = () => {};
  onComposingChanged: (value: boolean) => void = () => {};
  onFullscreenChanged: (value: boolean) => void = () => {};
  onOpenFrozenAnswer: (questionId: string, turnId: string) => void = () => {};
  onUnauthorized: (token: string) => void = () => {};

  @State @Watch('report_toast_context') mode: PhoneHomeMode = PhoneHomeMode.COLLABORATION;
  @State @Watch('report_toast_context') selectedQuestionId: string = '';
  @State modePickerVisible: boolean = false;
  @State @Watch('report_toast_context') historyVisible: boolean = false;
  @State historyItems: PhoneTutorSession[] = [];
  @State currentQuestionNotice: string = '';
  @State singleDraft: string = '';
  @State singleNotice: string = '';
  @State imageBusy: boolean = false;
  @State singlePreview: image.PixelMap | null = null;
  @State @Watch('report_toast_context') readingVisible: boolean = false;
  @State readingTitle: string = '';
  @State readingBody: string = '';
  @State readingPlainText: boolean = false;

  @State singleFocused: boolean = false;
  private singleEditor: TextAreaController = new TextAreaController();
  private tutorEditorNavigation: PhoneBackController = new PhoneBackController();
  private tutorComposing: boolean = false;
  private sessions: PhoneTutorSessionStore = new PhoneTutorSessionStore();
  private alive: boolean = true;
  private imageGeneration: number = 0;

  aboutToAppear(): void {
    this.alive = true;
    this.navigation.handleBack = (): boolean => this.handle_back();
    if (this.requestedQuestionId.length > 0) { this.on_requested_question(); }
  }

  aboutToDisappear(): void {
    this.stop_editing();
    this.alive = false;
    this.imageGeneration++;
    this.navigation.handleBack = (): boolean => false;
    this.drop_image();
    this.singleDraft = '';
    this.sessions.clear();
  }

  on_requested_question(): void {
    if (this.requestedQuestionId.length === 0) { return; }
    this.sessions.get(this.requestedQuestionId);
    this.selectedQuestionId = this.requestedQuestionId;
    this.switch_mode(PhoneHomeMode.COLLABORATION);
    this.modePickerVisible = false;
    this.historyVisible = false;
    this.close_reading();
  }

  on_active_changed(): void {
    if (this.active) { this.report_toast_context(); }
    if (!this.active) {
      this.stop_editing();
      this.imageGeneration++;
      this.imageBusy = false;
      this.modePickerVisible = false;
      this.historyVisible = false;
    }
  }

  build() {
    Stack({ alignContent: Alignment.TopEnd }) {
      Column() {
        this.header()
        Stack() {
          if (this.selectedQuestionId.length > 0) {
            PhoneTutorPage({
              toast: this.toast,
              questionId: this.selectedQuestionId,
              embedded: true,
              sessionStore: this.sessions,
              editorNavigation: this.tutorEditorNavigation,
              onComposingChanged: (value: boolean) => {
                this.tutorComposing = value;
                this.report_composing();
              },
              visible: this.active && this.mode === PhoneHomeMode.COLLABORATION &&
                !this.readingVisible && !this.historyVisible && !this.modePickerVisible,
              onChooseQuestion: () => { this.onChooseQuestion(); },
              onRead: (title: string, body: string, plainText: boolean) => {
                this.open_reading(title, body, plainText);
              },
              onOpenFrozenAnswer: (questionId: string, turnId: string) => {
                this.onOpenFrozenAnswer(questionId, turnId);
              },
              onUnauthorized: (token: string) => { this.onUnauthorized(token); }
            })
              .visibility(this.mode === PhoneHomeMode.COLLABORATION ? Visibility.Visible : Visibility.None)
          } else if (this.mode === PhoneHomeMode.COLLABORATION) {
            this.no_tablet_question()
          }
          Column() {
            this.single_device()
          }
          .width('100%').height('100%')
          .visibility(this.mode === PhoneHomeMode.SINGLE_DEVICE ? Visibility.Visible : Visibility.None)
        }
        .width('100%').layoutWeight(1)
      }
      .width('100%').height('100%')
      if (this.modePickerVisible) { this.mode_picker() }
      if (this.historyVisible) { this.history_page() }
      if (this.readingVisible) {
        PhoneReadingPage({
          title: this.readingTitle, bodyMarkdown: this.readingBody, plainText: this.readingPlainText,
          onBack: () => { this.close_reading(); }
        })
      }
    }
    .width('100%').height('100%').backgroundColor(PHONE_COLOR_BACKGROUND)
  }

  @Builder
  header() {
    Row() {
      Column() {
        Text('学习助手').fontSize(24).fontWeight(FontWeight.Bold).fontColor(PHONE_COLOR_TITLE)
        Text(this.mode === PhoneHomeMode.COLLABORATION ? '多设备协同' : '单设备问答')
          .fontSize(14).fontColor(PHONE_COLOR_MUTED).margin({ top: 4 })
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1)
      Button({ type: ButtonType.Normal }) {
        Image($r('app.media.bl_home_history')).width(24).height(24)
      }
      .width(44).height(44).backgroundColor(Color.Transparent).accessibilityText('对话记录')
      .onClick(() => { this.open_history(); })
      Column() {
        Button({ type: ButtonType.Circle }) {
          Image(this.mode === PhoneHomeMode.COLLABORATION ?
            $r('app.media.bl_home_devices') : $r('app.media.bl_home_phone')).width(25).height(25)
        }
        .width(46).height(46).backgroundColor(PHONE_COLOR_SOFT)
        .border({ width: 1, color: '#BED4FF' }).accessibilityText('切换对话模式')
        .onClick(() => { this.stop_editing(); this.modePickerVisible = !this.modePickerVisible; })
        Text('模式').fontSize(11).fontColor(PHONE_COLOR_MUTED).margin({ top: 3 })
      }
      .margin({ left: 8 })
    }
    .width('100%').padding({ left: 20, right: 18, top: 16, bottom: 14 })
    .alignItems(VerticalAlign.Center)
  }

  @Builder
  no_tablet_question() {
    Column() {
      Scroll() {
        Column({ space: 16 }) {
          Row({ space: 12 }) {
            Image($r('app.media.bl_home_devices')).width(42).height(42)
              .padding(10).backgroundColor(PHONE_COLOR_SOFT).borderRadius(30)
            Column({ space: 6 }) {
              Text('尚未获取平板当前题目').fontSize(17).fontWeight(FontWeight.Medium).fontColor(PHONE_COLOR_TITLE)
              Text('平板当前题目关联暂未开通，可先从题库选择一道题。')
                .fontSize(13).lineHeight(20).fontColor(PHONE_COLOR_MUTED)
            }
            .alignItems(HorizontalAlign.Start).layoutWeight(1)
          }
          .width('100%').padding(18).border({ width: 1, color: PHONE_COLOR_BORDER }).borderRadius(PHONE_RADIUS_CARD)
          Text('你可以先从题库选题，继续使用作答辅导。也可以切换到单设备问答，准备文字或图片。')
            .fontSize(15).lineHeight(24).fontColor(PHONE_COLOR_TITLE).padding(16)
            .width('100%').backgroundColor(PHONE_COLOR_SOFT).borderRadius(18)
          Row({ space: 10 }) {
            Button('重新获取').layoutWeight(1).height(46).fontSize(14).fontColor(PHONE_COLOR_PRIMARY)
              .backgroundColor(PHONE_COLOR_CARD).border({ width: 1, color: PHONE_COLOR_PRIMARY }).borderRadius(16)
              .onClick(() => {
                this.currentQuestionNotice = '暂时还不能获取平板当前题目。请先从题库选择，或切换单设备问答。';
                this.toast?.show({ key: 'phone-current-question', type: 'info', message: '暂时无法获取平板当前题目',
                  description: '请先从题库选择题目' });
              })
            Button('切换单设备问答').layoutWeight(1).height(46).fontSize(14).fontColor(PHONE_COLOR_WHITE)
              .backgroundColor(PHONE_COLOR_PRIMARY).borderRadius(16)
              .onClick(() => { this.switch_mode(PhoneHomeMode.SINGLE_DEVICE); })
          }
          .width('100%')
          if (this.currentQuestionNotice.length > 0) { this.info(this.currentQuestionNotice) }
          Button('从题库选择题目').height(44).fontSize(14).fontColor(PHONE_COLOR_PRIMARY)
            .backgroundColor(Color.Transparent).onClick(() => { this.onChooseQuestion(); })
        }
        .width('100%').padding({ left: 16, right: 16, top: 4, bottom: 20 })
      }
      .width('100%').layoutWeight(1).align(Alignment.TopStart).scrollBar(BarState.Off)
      Column({ space: 8 }) {
        Text('选择题目后，可针对平板作答提问').fontSize(12).fontColor(PHONE_COLOR_MUTED).width('100%')
        Row() {
          Text('等待关联平板题目…').fontSize(15).fontColor(PHONE_COLOR_MUTED).layoutWeight(1)
          this.disabled_send()
        }
        .width('100%').padding(10).backgroundColor(PHONE_COLOR_CARD).borderRadius(28)
      }
      .width('100%').padding({ left: 16, right: 16, bottom: 12 })
    }
    .width('100%').height('100%')
  }

  @Builder
  single_device() {
    Column() {
      Scroll() {
        Column({ space: 14 }) {
          Text('有什么想弄懂的？\n可以写下问题，也可以拍下题目。')
            .fontSize(16).lineHeight(26).fontColor(PHONE_COLOR_TITLE).width('100%').padding(18)
            .backgroundColor(PHONE_COLOR_SOFT).borderRadius(18)
          this.info('单设备问答暂未开通，文字和图片会保留在本次草稿中，尚未发送。')
          if (this.singlePreview !== null) {
            Column() {
              Image(this.singlePreview as image.PixelMap).width('100%').height(180).objectFit(ImageFit.Contain)
              Button('移除图片').height(44).fontSize(13).fontColor(PHONE_COLOR_PRIMARY)
                .backgroundColor(Color.Transparent).onClick(() => { this.drop_image(); })
            }
            .width('100%').padding(12).backgroundColor(PHONE_COLOR_CARD).borderRadius(18)
          }
          if (this.singleNotice.length > 0) { this.info(this.singleNotice) }
        }
        .width('100%').padding({ left: 16, right: 16, bottom: 20, top: 4 })
      }
      .layoutWeight(1).width('100%').align(Alignment.TopStart).scrollBar(BarState.Off)
      Column({ space: 8 }) {
        TextArea({ text: this.singleDraft, placeholder: '输入问题，或添加题目图片…', controller: this.singleEditor })
          .height(76).width('100%').fontSize(15).fontColor(PHONE_COLOR_TITLE)
          .placeholderColor(PHONE_COLOR_MUTED).backgroundColor(PHONE_COLOR_BACKGROUND).borderRadius(20)
          .maxLength(4000).padding(14)
          .onFocus(() => { this.singleFocused = true; this.report_composing(); })
          .onBlur(() => { this.singleFocused = false; this.report_composing(); })
          .onChange((value: string) => { this.singleDraft = value; this.singleNotice = ''; })
        Row() {
          this.image_action($r('app.media.bl_home_camera'), '拍照', true)
          this.image_action($r('app.media.bl_home_album'), '相册', false)
          Blank()
          Button({ type: ButtonType.Circle }) {
            Image($r('app.media.bl_home_send')).width(24).height(24)
          }
          .width(46).height(46)
          .backgroundColor(this.singleDraft.trim().length > 0 || this.singlePreview !== null ?
            PHONE_COLOR_PRIMARY : '#C7D0DF')
          .accessibilityText('发送单设备提问')
          .enabled(!this.imageBusy && (this.singleDraft.trim().length > 0 || this.singlePreview !== null))
          .onClick(() => {
            this.singleNotice = '单设备问答暂未开通，暂时无法发送。你的文字和图片仍保留。';
            this.toast?.show({ key: 'phone-single-unavailable', type: 'info', message: '单设备问答暂未开通',
              description: '文字和图片仍保留在本次草稿中' });
          })
        }
        .width('100%')
      }
      .width('100%').padding(12).backgroundColor(PHONE_COLOR_CARD).borderRadius(22)
      .margin({ left: 12, right: 12, bottom: 12 })
    }
    .width('100%').height('100%')
  }

  @Builder
  image_action(icon: Resource, label: string, fromCamera: boolean) {
    Button({ type: ButtonType.Normal }) {
      Row({ space: 7 }) {
        Image(icon).width(22).height(22)
        Text(this.imageBusy ? '读取中…' : label).fontSize(14).fontColor(PHONE_COLOR_TITLE)
      }
    }
    .height(44).padding({ left: 10, right: 10 }).backgroundColor(PHONE_COLOR_BACKGROUND).borderRadius(14)
    .enabled(!this.imageBusy).accessibilityText(label + '添加单设备问答图片')
    .onClick(() => { this.choose_image(fromCamera); })
  }

  @Builder
  disabled_send() {
    Row() {
      Image($r('app.media.bl_home_send')).width(24).height(24)
    }
    .width(44).height(44).justifyContent(FlexAlign.Center)
    .backgroundColor('#C7D0DF').borderRadius(24).accessibilityText('尚未关联题目，暂不可发送')
  }

  @Builder
  info(text: string) {
    Text(text).fontSize(13).lineHeight(20).fontColor(PHONE_COLOR_MUTED).width('100%')
      .padding(12).backgroundColor('#EEF3FB').borderRadius(14)
  }

  @Builder
  mode_picker() {
    Stack({ alignContent: Alignment.TopEnd }) {
      Column().width('100%').height('100%').backgroundColor('#55152744')
        .onClick(() => { this.modePickerVisible = false; })
      Column({ space: 8 }) {
        Text('对话模式').fontSize(18).fontWeight(FontWeight.Bold).fontColor(PHONE_COLOR_TITLE)
          .width('100%').margin({ bottom: 4 })
        this.mode_option(PhoneHomeMode.COLLABORATION, $r('app.media.bl_home_devices'), '多设备协同', '关联平板当前题目')
        this.mode_option(PhoneHomeMode.SINGLE_DEVICE, $r('app.media.bl_home_phone'), '单设备问答', '文字、拍照或相册直接提问')
        Text('切换模式后，原有对话和草稿仍保留').fontSize(12).fontColor(PHONE_COLOR_MUTED)
          .width('100%').padding({ top: 12 }).border({ width: { top: 1 }, color: PHONE_COLOR_BORDER })
      }
      .width('92%').padding(16).backgroundColor(PHONE_COLOR_CARD).borderRadius(20)
      .margin({ top: 76, right: 12 })
    }
    .width('100%').height('100%')
  }

  @Builder
  mode_option(value: PhoneHomeMode, icon: Resource, title: string, subtitle: string) {
    Row({ space: 12 }) {
      Image(icon).width(30).height(30)
      Column({ space: 5 }) {
        Text(title).fontSize(16).fontWeight(FontWeight.Medium).fontColor(PHONE_COLOR_TITLE)
        Text(subtitle).fontSize(12).fontColor(PHONE_COLOR_MUTED)
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1)
      if (this.mode === value) {
        Image($r('app.media.bl_home_check')).width(22).height(22)
          .padding(4).backgroundColor(PHONE_COLOR_PRIMARY).borderRadius(16)
      } else {
        Column().width(24).height(24).border({ width: 1, color: PHONE_COLOR_MUTED }).borderRadius(14)
      }
    }
    .width('100%').padding(12).constraintSize({ minHeight: 76 }).borderRadius(16)
    .backgroundColor(this.mode === value ? PHONE_COLOR_SOFT : PHONE_COLOR_CARD)
    .accessibilityText(title + (this.mode === value ? '，当前模式' : ''))
    .onClick(() => { this.switch_mode(value); this.modePickerVisible = false; })
  }

  @Builder
  history_page() {
    Column() {
      Row() {
        Button({ type: ButtonType.Normal }) { Image($r('app.media.bl_back')).width(22).height(22) }
          .width(44).height(44).backgroundColor(Color.Transparent).accessibilityText('返回首页')
          .onClick(() => { this.historyVisible = false; this.onFullscreenChanged(false); })
        Text('对话记录').fontSize(20).fontWeight(FontWeight.Bold).fontColor(PHONE_COLOR_TITLE)
      }
      .width('100%').padding({ left: 8, top: 8, bottom: 8 })
      Scroll() {
        Column({ space: 12 }) {
          Text('本次登录中打开过的题目与草稿。题目历史会从服务重新读取最近 50 轮。')
            .fontSize(13).fontColor(PHONE_COLOR_MUTED).lineHeight(20).width('100%')
          if (this.singleDraft.length > 0 || this.singlePreview !== null) {
            Button('单设备问答 · 未发送草稿').width('100%').height(52).fontSize(15)
              .fontColor(PHONE_COLOR_PRIMARY).backgroundColor(PHONE_COLOR_SOFT).borderRadius(16)
              .onClick(() => { this.switch_mode(PhoneHomeMode.SINGLE_DEVICE); this.close_history(); })
          }
          ForEach(this.historyItems, (session: PhoneTutorSession) => {
            Row() {
              Column({ space: 6 }) {
                Text(session.label).fontSize(15).fontColor(PHONE_COLOR_TITLE).maxLines(2)
                Text('多设备协同 · 题库选题' + (session.prompt.length > 0 ? ' · 有草稿' : ''))
                  .fontSize(12).fontColor(PHONE_COLOR_MUTED)
              }
              .alignItems(HorizontalAlign.Start).layoutWeight(1)
              Image($r('app.media.bl_chevron_right')).width(20).height(20)
            }
            .width('100%').padding(16).constraintSize({ minHeight: 72 })
            .backgroundColor(PHONE_COLOR_CARD).borderRadius(18)
            .onClick(() => {
              this.selectedQuestionId = session.questionId;
              this.switch_mode(session.mode);
              this.close_history();
            })
          }, (session: PhoneTutorSession) => session.mode + ':' + session.questionId)
          if (this.historyItems.length === 0 && this.singleDraft.length === 0 && this.singlePreview === null) {
            Text('还没有对话记录\n从题库选择一道题后，会在这里保留入口。')
              .fontSize(15).lineHeight(26).fontColor(PHONE_COLOR_MUTED).width('100%').padding(24)
          }
        }
        .width('100%').padding(16)
      }
      .width('100%').layoutWeight(1).align(Alignment.TopStart)
    }
    .width('100%').height('100%').backgroundColor(PHONE_COLOR_BACKGROUND)
  }

  private report_composing(): void {
    this.onComposingChanged(this.active && (this.singleFocused || this.tutorComposing));
  }

  report_toast_context(): void {
    if (!this.alive || !this.active) { return; }
    this.onContextChanged(this.mode + ':' + this.selectedQuestionId + ':' +
      (this.readingVisible ? 'reading' : (this.historyVisible ? 'history' : 'conversation')));
  }

  private stop_editing(): void {
    this.singleEditor.stopEditing();
    this.singleFocused = false;
    this.tutorEditorNavigation.handleBack();
    this.tutorComposing = false;
    this.report_composing();
  }

  private switch_mode(value: PhoneHomeMode): void {
    this.stop_editing();
    if (this.mode !== value) { this.imageGeneration++; this.imageBusy = false; }
    this.mode = value;
  }

  private open_history(): void {
    this.stop_editing();
    this.historyItems = this.sessions.history();
    this.historyVisible = true;
    this.onFullscreenChanged(true);
  }

  private close_history(): void {
    this.historyVisible = false;
    this.onFullscreenChanged(false);
  }

  private open_reading(title: string, body: string, plainText: boolean): void {
    this.stop_editing();
    this.readingTitle = title;
    this.readingBody = body;
    this.readingPlainText = plainText;
    this.readingVisible = true;
    this.onFullscreenChanged(true);
  }

  private close_reading(): void {
    this.readingVisible = false;
    this.readingBody = '';
    this.onFullscreenChanged(false);
  }

  private handle_back(): boolean {
    if (this.readingVisible) { this.close_reading(); return true; }
    if (this.modePickerVisible) { this.modePickerVisible = false; return true; }
    if (this.historyVisible) { this.close_history(); return true; }
    if (this.singleFocused) { this.stop_editing(); return true; }
    return this.tutorEditorNavigation.handleBack();
  }

  private async choose_image(fromCamera: boolean): Promise<void> {
    if (!this.active || this.mode !== PhoneHomeMode.SINGLE_DEVICE || this.imageBusy) { return; }
    this.stop_editing();
    const token: string | null = authSession.read_access_token();
    if (token === null) { this.onUnauthorized(''); return; }
    const toast_scope: number | undefined = this.toast?.captureScope();
    const generation: number = ++this.imageGeneration;
    this.imageBusy = true;
    this.singleNotice = '';
    let loaded: image.PixelMap | null = null;
    const context: common.UIAbilityContext = getContext(this) as common.UIAbilityContext;
    try {
      const uri: string | undefined = fromCamera ? await takePhoto(context) : await selectImage(context);
      if (uri === undefined) { return; }
      if (this.image_stale(generation, token)) {
        if (fromCamera) { discardPendingPhoto(uri); }
        return;
      }
      loaded = await loadPreview(uri);
      if (this.image_stale(generation, token)) { return; }
      this.drop_image();
      this.singlePreview = loaded;
      loaded = null;
      this.singleNotice = '图片已加入本次草稿，单设备问答开通后才可发送。';
      this.toast?.show({ key: 'phone-single-image', type: 'info', message: '图片已加入本次草稿',
        description: '单设备问答暂未开通' }, toast_scope);
    } catch (error) {
      if (!this.image_stale(generation, token)) {
        this.singleNotice = fromCamera ? '暂时无法读取拍摄的图片，请重试。' : '暂时无法读取所选图片，请重试。';
        this.toast?.show({ key: 'phone-single-image', type: 'error', message: '暂时无法读取图片',
          description: '原有草稿已保留，请重试' }, toast_scope);
      }
    } finally {
      if (loaded !== null) { await loaded.release(); }
      if (generation === this.imageGeneration) { this.imageBusy = false; }
    }
  }

  private image_stale(generation: number, token: string): boolean {
    return !this.alive || !this.active || this.mode !== PhoneHomeMode.SINGLE_DEVICE ||
      generation !== this.imageGeneration || !authSession.matches_access_token(token);
  }

  private drop_image(): void {
    const previous: image.PixelMap | null = this.singlePreview;
    this.singlePreview = null;
    if (previous !== null) { previous.release().catch(() => {}); }
  }
}
