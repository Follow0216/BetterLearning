import { util } from '@kit.ArkTS';
import {
  authSession,
  AuthError,
  AuthFailureKind,
  AuthResult,
  QuestionMarkdownView,
  MAX_QUESTION_MARKDOWN_LENGTH,
  LearningApi,
  QuestionDetail,
  learning_subject_label,
  ToastStore,
  TutorApi,
  TutorTurn,
  TutorTurnStatus,
  UserProfile
} from 'common';
import { PhoneBackController, PhoneTutorSession, PhoneTutorSessionStore } from '../model/PhoneHomeState';
import {
  describe_tutor_error,
  describe_tutor_failure,
  describe_tutor_status,
  format_question_time
} from '../model/QuestionFormat';
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
  PHONE_COLOR_SOFT_STRONG,
  PHONE_COLOR_TITLE,
  PHONE_COLOR_WHITE,
  PHONE_RADIUS_CARD,
  PHONE_RADIUS_CONTROL
} from '../theme/PhoneTheme';

const MAX_PROMPT_LENGTH: number = 4000;
const POLL_INTERVAL_MS: number = 3000;

@Component
export struct PhoneTutorPage {
  toast?: ToastStore;
  private requestedToastScopes: Map<string, number> = new Map();
  @Prop @Watch('on_question_changed') questionId: string = '';
  @Prop embedded: boolean = false;
  editorNavigation: PhoneBackController = new PhoneBackController();
  onComposingChanged: (value: boolean) => void = () => {};
  sessionStore: PhoneTutorSessionStore = new PhoneTutorSessionStore();
  onChooseQuestion: () => void = () => {};
  onRead: (title: string, body: string, plainText: boolean) => void = () => {};
  @Prop @Watch('on_poll_gate_changed') visible: boolean = true;
  @StorageProp('phoneForeground') @Watch('on_poll_gate_changed') foreground: boolean = true;
  onBack: () => void = () => {
  };
  onOpenFrozenAnswer: (questionId: string, turnId: string) => void = () => {
  };
  onUnauthorized: (token: string) => void = () => {
  };

  @State loading: boolean = true;
  @State listError: string = '';
  @State turns: TutorTurn[] = [];
  @State displayTurns: TutorTurn[] = [];
  @State prompt: string = '';
  @State submitting: boolean = false;
  @State composeError: string = '';
  @State composeHint: string = '';
  @State expandedTurnId: string = '';
  @State busyTurnId: string = '';
  @State actionError: string = '';
  @State pollNotice: string = '';
  @State questionDetail: QuestionDetail | null = null;
  @State questionError: string = '';
  @State editorFocused: boolean = false;
  private editorController: TextAreaController = new TextAreaController();
  private conversationScroller: Scroller = new Scroller();
  private scrollTimer: number = -1;
  private scrollRestorePending: boolean = true;
  private latestTurnTop: number = 0;
  private latestTurnMeasured: boolean = false;

  private loadedQuestionId: string = '';
  private questionSeq: number = 0;
  private learningApi: LearningApi = LearningApi.create_default();
  private api: TutorApi = TutorApi.create_default();
  private requestSeq: number = 0;
  private pollTimer: number = -1;
  private pollBusyGeneration: number = -1;
  private pollGeneration: number = 0;
  private alive: boolean = true;
  private activeTurnId: string = '';
  private pendingRequestToastScope: number | undefined = undefined;
  private pendingRequestId: string = '';
  private pendingPrompt: string = '';

  aboutToAppear(): void {
    this.alive = true;
    this.editorNavigation.handleBack = (): boolean => {
      if (!this.editorFocused) { return false; }
      this.stop_editing();
      return true;
    };
    this.restore_session();
    this.load_turns();
    this.load_question();
  }

  aboutToDisappear(): void {
    this.stop_editing();
    this.editorNavigation.handleBack = (): boolean => false;
    this.save_scroll_position();
    this.save_session();
    this.clear_scroll_timer();
    this.alive = false;
    this.requestedToastScopes.clear();
    this.questionSeq++;
    this.requestSeq = this.requestSeq + 1;
    this.stop_poll();
  }

  on_question_changed(): void {
    this.requestedToastScopes.clear();
    this.pendingRequestToastScope = undefined;
    this.stop_editing();
    this.save_scroll_position();
    this.save_session();
    this.clear_scroll_timer();
    this.requestSeq++;
    this.questionSeq++;
    this.stop_poll();
    this.latestTurnMeasured = false;
    this.questionDetail = null;
    this.questionError = '';
    this.listError = '';
    this.activeTurnId = '';
    this.actionError = '';
    this.pollNotice = '';
    this.composeError = '';
    this.composeHint = '';
    this.submitting = false;
    this.busyTurnId = '';
    this.restore_session();
    this.load_turns();
    this.load_question();
  }

  on_poll_gate_changed(): void {
    if (!this.poll_allowed()) {
      this.stop_editing();
      this.save_scroll_position();
      this.save_session();
      this.stop_poll();
      return;
    }
    if (this.scrollRestorePending) { this.restore_scroll_position(); }
    this.resume_poll();
  }

  build() {
    Column() {
      if (!this.embedded) { this.top_bar() }
      this.question_context()
      if (this.listError.length > 0 && this.turns.length === 0) {
        this.list_error_view()
      } else if (this.loading && this.turns.length === 0) {
        this.loading_view()
      } else {
        Scroll(this.conversationScroller) {
          Column({ space: 14 }) {
            if (this.listError.length > 0) { this.notice_banner(this.listError, PHONE_COLOR_DANGER, PHONE_COLOR_DANGER_SOFT) }
            if (this.actionError.length > 0) { this.notice_banner(this.actionError, PHONE_COLOR_DANGER, PHONE_COLOR_DANGER_SOFT) }
            if (this.pollNotice.length > 0) { this.notice_banner(this.pollNotice, PHONE_COLOR_PRIMARY_DEEP, PHONE_COLOR_SOFT) }
            if (this.turns.length === 0) { this.empty_view() }
            ForEach(this.displayTurns, (turn: TutorTurn) => { this.turn_card(turn) },
              (turn: TutorTurn) => this.turn_key(turn))
          }
          .width('100%').alignItems(HorizontalAlign.Start)
          .padding({ left: 16, right: 16, top: 8, bottom: 20 })
        }
        .layoutWeight(1).width('100%').scrollBar(BarState.Off).align(Alignment.TopStart)
        .onScrollStop(() => { this.save_scroll_position(); })
      }
      this.compose_section()
    }
    .width('100%').height('100%').backgroundColor(PHONE_COLOR_BACKGROUND)
  }

  @Builder
  top_bar() {
    Row() {
      Button({ type: ButtonType.Normal }) { Image($r('app.media.bl_back')).width(22).height(22) }
        .width(44).height(44).backgroundColor(Color.Transparent).accessibilityText('返回')
        .onClick(() => { this.onBack(); })
      Text('学习助手').fontSize(22).fontWeight(FontWeight.Bold).fontColor(PHONE_COLOR_TITLE)
    }
    .width('100%').padding({ top: 8, left: 8, right: 16, bottom: 8 })
  }

  @Builder
  question_context() {
    Column({ space: 5 }) {
      Row() {
        Text(this.questionDetail === null ? '从题库选择的题目' :
          '题库选题 · ' + learning_subject_label((this.questionDetail as QuestionDetail).subject))
          .fontSize(12).fontColor(PHONE_COLOR_PRIMARY).layoutWeight(1)
        Button('换题').height(44).fontSize(12).fontColor(PHONE_COLOR_PRIMARY)
          .backgroundColor(Color.Transparent).onClick(() => { this.onChooseQuestion(); })
        Button({ type: ButtonType.Normal }) { Image($r('app.media.bl_refresh')).width(18).height(18) }
          .width(44).height(44).backgroundColor(Color.Transparent).accessibilityText('刷新题目和对话')
          .enabled(!this.loading && !this.submitting && this.busyTurnId.length === 0)
          .onClick(() => { this.refresh_manual(); })
      }
      .width('100%')
      if (this.questionDetail !== null) {
        Row() {
          QuestionMarkdownView({ bodyMarkdown: (this.questionDetail as QuestionDetail).bodyMarkdown, fontSize: 16 })
            .layoutWeight(1).height(78).hitTestBehavior(HitTestMode.None)
          Image($r('app.media.bl_chevron_right')).width(18).height(18)
        }
        .width('100%').constraintSize({ minHeight: 78 }).accessibilityText('阅读完整题目')
        .onClick(() => { this.read_question(); })
      } else {
        Text(this.questionError.length > 0 ? this.questionError : '正在读取题目内容…')
          .fontSize(14).fontColor(PHONE_COLOR_MUTED).width('100%').padding({ top: 8, bottom: 8 })
      }
      Text('尚未确认平板当前题目，请在平板上打开本题。')
        .fontSize(11).lineHeight(17).fontColor(PHONE_COLOR_MUTED).width('100%')
    }
    .width('100%').padding({ left: 16, right: 10, top: 0, bottom: 12 })
    .backgroundColor(PHONE_COLOR_CARD).borderRadius(PHONE_RADIUS_CARD)
    .margin({ left: 16, right: 16, bottom: 8 }).alignItems(HorizontalAlign.Start)
  }

  @Builder
  compose_section() {
    Column({ space: 7 }) {
      if (this.composeError.length > 0) {
        this.notice_banner(this.composeError, PHONE_COLOR_DANGER, PHONE_COLOR_DANGER_SOFT)
      }
      if (this.composeHint.length > 0) {
        this.notice_banner(this.composeHint, PHONE_COLOR_PRIMARY_DEEP, PHONE_COLOR_SOFT)
      }
      Text(this.has_active_turn() ? '本次提问尚未结束，请稍候或取消等待' : '发送时获取平板上本题的作答')
        .fontSize(12).fontColor(PHONE_COLOR_MUTED).width('100%')
      Row({ space: 8 }) {
        TextArea({ text: this.prompt, placeholder: '继续问这道题…', controller: this.editorController })
          .layoutWeight(1).height(72).fontSize(15).fontColor(PHONE_COLOR_TITLE)
          .placeholderColor(PHONE_COLOR_MUTED).backgroundColor(PHONE_COLOR_BACKGROUND)
          .borderRadius(22).padding(12).maxLength(MAX_PROMPT_LENGTH)
          .onFocus(() => { this.set_composing(true); })
          .onBlur(() => { this.set_composing(false); })
          .onWillChange(() => !this.submitting)
          .onChange((value: string) => {
            this.prompt = value;
            this.composeError = '';
            if (this.pendingRequestId.length > 0 && this.pendingPrompt !== value) {
              this.pendingRequestId = '';
              this.pendingPrompt = '';
              this.composeHint = '';
            }
            this.save_session();
          })
        Button({ type: ButtonType.Circle }) {
          Image($r('app.media.bl_home_send')).width(24).height(24)
        }
        .width(46).height(46).backgroundColor(this.can_submit() ? PHONE_COLOR_PRIMARY : '#C7D0DF')
        .enabled(this.can_submit()).accessibilityText(this.submit_label())
        .onClick(() => { this.submit_prompt(); })
      }
      .width('100%').padding(8).backgroundColor(PHONE_COLOR_CARD).borderRadius(28)
      if (this.pendingRequestId.length > 0) {
        Text(this.submitting ? '正在确认原提问…' : '再次发送会继续确认原提问').fontSize(12).fontColor(PHONE_COLOR_PRIMARY)
      }
    }
    .width('100%').padding({ left: 16, right: 16, bottom: 12, top: 8 })
  }

  @Builder
  notice_banner(text: string, foreground: string, background: string) {
    Text(text).fontSize(12).fontColor(foreground).lineHeight(18).width('100%')
      .padding(11).backgroundColor(background).borderRadius(PHONE_RADIUS_CONTROL)
  }

  @Builder
  loading_view() {
    Column({ space: 10 }) {
      LoadingProgress().width(28).height(28).color(PHONE_COLOR_PRIMARY)
      Text('正在读取对话…').fontSize(13).fontColor(PHONE_COLOR_MUTED)
    }
    .width('100%').layoutWeight(1).justifyContent(FlexAlign.Center)
  }

  @Builder
  list_error_view() {
    Column({ space: 12 }) {
      Text(this.listError).fontSize(14).fontColor(PHONE_COLOR_DANGER).textAlign(TextAlign.Center)
      Button('重新加载').height(44).fontSize(14).fontColor(PHONE_COLOR_WHITE)
        .backgroundColor(PHONE_COLOR_PRIMARY).enabled(!this.loading).onClick(() => { this.load_turns(true); })
    }
    .width('100%').layoutWeight(1).justifyContent(FlexAlign.Center).padding(28)
  }

  @Builder
  empty_view() {
    Text('现在一起看这道题。\n你可以问解题思路，也可以让我看看你的作答。')
      .fontSize(15).fontColor(PHONE_COLOR_TITLE).lineHeight(24).width('100%')
      .padding(18).backgroundColor(PHONE_COLOR_SOFT).borderRadius(18)
  }

  @Builder
  turn_card(turn: TutorTurn) {
    Column({ space: 10 }) {
      Row() {
        Blank().width(24)
        Text(turn.prompt).fontSize(15).fontColor(PHONE_COLOR_TITLE).lineHeight(24)
          .layoutWeight(1).padding(15).backgroundColor(PHONE_COLOR_SOFT_STRONG).borderRadius(18)
          .copyOption(CopyOptions.InApp)
      }
      .width('100%')
      Column({ space: 10 }) {
        Row() {
          Text(describe_tutor_status(turn.status)).fontSize(12).fontColor(this.status_foreground(turn.status))
            .padding({ left: 8, right: 8, top: 4, bottom: 4 })
            .backgroundColor(this.status_background(turn.status)).borderRadius(10)
          Blank()
          Text(format_question_time(turn.createdAt)).fontSize(11).fontColor(PHONE_COLOR_MUTED)
        }
        .width('100%')
        if (turn.status === TutorTurnStatus.SUCCEEDED) {
          if (this.expandedTurnId === turn.turnId) {
            if (turn.answerMarkdown !== null && turn.answerMarkdown.length <= MAX_QUESTION_MARKDOWN_LENGTH) {
              QuestionMarkdownView({ bodyMarkdown: turn.answerMarkdown, fontSize: 15 })
                .width('100%').height(132).hitTestBehavior(HitTestMode.None)
              Text('预览部分解答，完整内容可展开阅读').fontSize(11).fontColor(PHONE_COLOR_MUTED).width('100%')
            } else {
              Text('解答较长，点击完整阅读查看全部文字；公式排版暂不可用。')
                .fontSize(14).lineHeight(22).fontColor(PHONE_COLOR_MUTED).width('100%')
            }
          } else {
            Button('展开解答预览').height(44).fontSize(14).fontColor(PHONE_COLOR_PRIMARY)
              .backgroundColor(PHONE_COLOR_SOFT).onClick(() => { this.toggle_expanded(turn.turnId); })
          }
          Row() {
            Button('完整阅读 ›').height(44).fontSize(14).fontColor(PHONE_COLOR_PRIMARY)
              .backgroundColor(Color.Transparent).layoutWeight(1).onClick(() => {
                this.onRead('完整解答', turn.answerMarkdown === null ? '' : turn.answerMarkdown, false);
              })
            if (turn.fileRef !== null) {
              Button('本次作答 ›').height(44).fontSize(13).fontColor(PHONE_COLOR_PRIMARY)
                .backgroundColor(Color.Transparent).layoutWeight(1)
                .onClick(() => { this.onOpenFrozenAnswer(this.questionId, turn.turnId); })
            }
          }
          .width('100%')
        } else if (turn.status === TutorTurnStatus.WAITING_ANSWER) {
          Text('等待平板打开这道题并获取本次作答。发送时的作答会单独保留，之后仍可继续书写。')
            .fontSize(14).fontColor(PHONE_COLOR_MUTED).lineHeight(22).width('100%')
          if (turn.fileRef === null) {
            Button(this.busyTurnId === turn.turnId ? '正在取消…' : '取消等待')
              .height(44).fontSize(13).fontColor(PHONE_COLOR_DANGER).backgroundColor(PHONE_COLOR_DANGER_SOFT)
              .enabled(this.busyTurnId.length === 0 && !this.submitting)
              .onClick(() => { this.cancel_turn(turn); })
          } else {
            Text('本次作答已收到，正在准备解答。').fontSize(13).fontColor(PHONE_COLOR_MUTED)
          }
        } else if (turn.status === TutorTurnStatus.GENERATING) {
          Row({ space: 8 }) {
            LoadingProgress().width(18).height(18).color(PHONE_COLOR_PRIMARY)
            Text('正在根据本次作答准备解答…').fontSize(14).fontColor(PHONE_COLOR_MUTED).layoutWeight(1)
          }
          .width('100%')
        } else {
          Text(describe_tutor_failure(turn)).fontSize(14).fontColor(PHONE_COLOR_DANGER).lineHeight(22).width('100%')
          if (turn.fileRef !== null) {
            Text('重试解答会继续使用本次作答。').fontSize(12).fontColor(PHONE_COLOR_MUTED).width('100%')
            Button(this.busyTurnId === turn.turnId ? '正在重试…' : '重试解答')
              .height(44).fontSize(14).fontColor(PHONE_COLOR_WHITE).backgroundColor(PHONE_COLOR_PRIMARY)
              .enabled(this.busyTurnId.length === 0 && !this.submitting)
              .onClick(() => { this.retry_turn(turn); })
          } else {
            Text('没有收到本次作答，可以重新提问。').fontSize(12).fontColor(PHONE_COLOR_MUTED).width('100%')
          }
        }
        if (turn.fileRef !== null && turn.status !== TutorTurnStatus.SUCCEEDED) {
          Row() {
            Button('本次作答 ›').height(44).fontSize(13).fontColor(PHONE_COLOR_PRIMARY)
              .backgroundColor(Color.Transparent).layoutWeight(1)
              .onClick(() => { this.onOpenFrozenAnswer(this.questionId, turn.turnId); })
            if (turn.status === TutorTurnStatus.FAILED) {
              Button('重新获取作答').height(44).fontSize(13).fontColor(PHONE_COLOR_PRIMARY)
                .backgroundColor(PHONE_COLOR_SOFT).layoutWeight(1)
                .enabled(!this.has_active_turn() && !this.submitting && this.busyTurnId.length === 0)
                .onClick(() => { this.request_latest_answer(turn); })
            }
          }
          .width('100%')
        }
      }
      .width('100%').padding(14).backgroundColor(PHONE_COLOR_CARD).borderRadius(18)
      .border({ width: 1, color: PHONE_COLOR_BORDER }).alignItems(HorizontalAlign.Start)
    }
    .width('100%')
    .onAreaChange((_oldArea: Area, area: Area) => { this.track_latest_turn(turn, area); })
  }

  private track_latest_turn(turn: TutorTurn, area: Area): void {
    if (this.turns.length === 0 || turn.turnId !== this.turns[this.turns.length - 1].turnId) { return; }
    const positionY: Length | undefined = area.position.y;
    if (positionY === undefined) { return; }
    const value: number = typeof positionY === 'number' ? positionY : parseFloat(positionY.toString());
    if (Number.isFinite(value) && value >= 0) {
      this.latestTurnTop = value;
      this.latestTurnMeasured = true;
    }
  }

  private set_composing(value: boolean): void {
    this.editorFocused = value;
    this.onComposingChanged(value);
  }

  private stop_editing(): void {
    this.editorController.stopEditing();
    this.set_composing(false);
  }

  private read_question(): void {
    if (this.questionDetail !== null) {
      this.onRead('完整题目', this.questionDetail.bodyMarkdown, false);
    }
  }

  private request_latest_answer(turn: TutorTurn): void {
    if (!this.poll_allowed() || this.has_active_turn() || this.submitting || this.busyTurnId.length > 0) { return; }
    if (this.prompt.length > 0 || this.pendingRequestId.length > 0) {
      this.composeError = '输入框中还有未发送的提问，请先处理草稿，再重新获取作答。';
      return;
    }
    this.prompt = turn.prompt;
    this.pendingRequestId = '';
    this.pendingPrompt = '';
    this.save_session();
    this.submit_prompt();
  }

  private save_session(): void {
    if (this.loadedQuestionId.length === 0) { return; }
    const session: PhoneTutorSession = this.sessionStore.get(this.loadedQuestionId);
    session.prompt = this.prompt;
    session.pendingRequestId = this.pendingRequestId;
    session.pendingPrompt = this.pendingPrompt;
    session.expandedTurnId = this.expandedTurnId;
    session.turns = this.turns.slice();
  }

  private restore_session(): void {
    this.loadedQuestionId = this.questionId;
    this.scrollRestorePending = true;
    const session: PhoneTutorSession = this.sessionStore.get(this.questionId);
    this.prompt = session.prompt;
    this.pendingRequestId = session.pendingRequestId;
    this.pendingPrompt = session.pendingPrompt;
    this.expandedTurnId = session.expandedTurnId;
    this.turns = session.turns.slice();
    this.displayTurns = this.turns.slice();
    this.loading = this.turns.length === 0;
  }

  private async load_question(): Promise<void> {
    const token: string | null = authSession.read_access_token();
    const user: UserProfile | null = authSession.read_user();
    if (token === null || user === null || this.questionId.length === 0) { return; }
    this.questionError = '';
    const questionId: string = this.questionId;
    const accountId: string = user.accountId;
    const seq: number = ++this.questionSeq;
    try {
      const result: AuthResult<QuestionDetail> = await this.learningApi.getQuestion(token, questionId);
      const current: UserProfile | null = authSession.read_user();
      if (!this.alive || seq !== this.questionSeq || this.questionId !== questionId ||
        !authSession.matches_access_token(token) || current === null || current.accountId !== accountId) { return; }
      if (!result.isSuccess || result.data === null) {
        if (result.error !== null && result.error.is_unauthorized()) { this.handle_unauthorized(token); return; }
        this.questionError = '题目暂时无法读取，请点击刷新重试。';
        return;
      }
      this.questionDetail = result.data;
      this.sessionStore.get(questionId).label = learning_subject_label(result.data.subject) + ' · ' + result.data.summary;
    } catch (error) {
      if (this.alive && seq === this.questionSeq && this.questionId === questionId &&
        authSession.matches_access_token(token)) {
        this.questionError = '题目暂时无法读取，请点击刷新重试。';
      }
    }
  }

  private set_turns(next: TutorTurn[]): void {
    this.turns = next;
    const copy: TutorTurn[] = next.slice();
    this.displayTurns = copy;
    this.save_session();
  }

  private turn_key(turn: TutorTurn): string {
    return turn.turnId + '|' + turn.status + '|' + (turn.fileRef === null ? '' : turn.fileRef) + '|' +
      (turn.failureCode === null ? '' : turn.failureCode) + '|' +
      (turn.answerMarkdown === null ? '0' : turn.answerMarkdown.length.toString()) + '|' + turn.updatedAt;
  }

  private submit_label(): string {
    if (this.submitting) {
      return '正在发送…';
    }
    return this.pendingRequestId.length > 0 ? '确认原提问' : '发送问题';
  }

  private can_submit(): boolean {
    return this.poll_allowed() && !this.loading && !this.submitting && this.busyTurnId.length === 0 && this.prompt.trim().length > 0 &&
      this.prompt.length <= MAX_PROMPT_LENGTH && !this.has_active_turn();
  }

  private has_active_turn(): boolean {
    for (let index: number = 0; index < this.turns.length; index++) {
      const status: TutorTurnStatus = this.turns[index].status;
      if (status === TutorTurnStatus.WAITING_ANSWER || status === TutorTurnStatus.GENERATING) {
        return true;
      }
    }
    return false;
  }

  private toggle_expanded(turnId: string): void {
    this.expandedTurnId = this.expandedTurnId === turnId ? '' : turnId;
    this.save_session();
  }

  private status_foreground(status: TutorTurnStatus): string {
    if (status === TutorTurnStatus.SUCCEEDED) {
      return PHONE_COLOR_WHITE;
    }
    if (status === TutorTurnStatus.FAILED) {
      return PHONE_COLOR_DANGER;
    }
    return PHONE_COLOR_PRIMARY_DEEP;
  }

  private status_background(status: TutorTurnStatus): string {
    if (status === TutorTurnStatus.SUCCEEDED) {
      return PHONE_COLOR_PRIMARY;
    }
    if (status === TutorTurnStatus.FAILED) {
      return PHONE_COLOR_DANGER_SOFT;
    }
    if (status === TutorTurnStatus.GENERATING) {
      return PHONE_COLOR_SOFT_STRONG;
    }
    return PHONE_COLOR_SOFT;
  }

  private async load_turns(manual: boolean = false): Promise<void> {
    const toast_scope: number | undefined = this.toast?.captureScope();
    const token: string | null = authSession.read_access_token();
    const user: UserProfile | null = authSession.read_user();
    if (token === null || user === null) {
      this.onUnauthorized('');
      return;
    }
    if (this.questionId.length === 0) {
      this.loading = false;
      this.listError = '请先选择一道题目。';
      return;
    }
    const accountId: string = user.accountId;
    const seq: number = ++this.requestSeq;
    this.loading = true;
    this.listError = '';
    try {
      const result: AuthResult<TutorTurn[]> = await this.api.listTurns(token, this.questionId);
      if (this.is_stale(seq, token, accountId)) {
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
        this.listError = describe_tutor_error(result.error);
        return;
      }
      this.set_turns(result.data);
      result.data.forEach((turn: TutorTurn) => this.notify_pending_turn(turn));
      if (this.pendingRequestId.length > 0) {
        for (let index: number = 0; index < result.data.length; index++) {
          if (result.data[index].requestId === this.pendingRequestId) {
            this.track_requested_turn(result.data[index], manual ? toast_scope : this.pendingRequestToastScope);
            if (this.prompt === this.pendingPrompt) { this.prompt = ''; }
            this.pendingRequestId = '';
            this.pendingPrompt = '';
            this.save_session();
            break;
          }
        }
      }
      this.adopt_default_expansion();
      this.sync_active_turn();
      this.restore_scroll_position();
    } catch (error) {
      if (this.is_stale(seq, token, accountId)) {
        return;
      }
      if (!authSession.has_valid_session()) {
        this.handle_unauthorized(token);
        return;
      }
      this.listError = '对话暂时无法读取，请稍后重试。';
    } finally {
      if (seq === this.requestSeq) {
        this.loading = false;
        if (manual && !this.is_stale(seq, token, accountId) && this.listError.length > 0) {
          this.toast?.show({ key: 'phone-tutor-refresh', type: 'error', message: '对话刷新未完成',
            description: '请查看页面提示后重试' }, toast_scope);
        }
      }
    }
  }

  private refresh_manual(): void {
    if (this.loading || this.submitting || this.busyTurnId.length > 0) {
      return;
    }
    this.actionError = '';
    this.load_turns(true);
    if (this.questionDetail === null) { this.load_question(); }
  }

  private adopt_default_expansion(): void {
    if (this.turns.length === 0) {
      this.expandedTurnId = '';
      return;
    }
    let exists: boolean = false;
    for (let index: number = 0; index < this.turns.length; index++) {
      if (this.turns[index].turnId === this.expandedTurnId) {
        exists = true;
        break;
      }
    }
    if (!exists) {
      this.expandedTurnId = this.turns[this.turns.length - 1].turnId;
    }
  }

  private sync_active_turn(): void {
    let active: string = '';
    for (let index: number = 0; index < this.turns.length; index++) {
      const status: TutorTurnStatus = this.turns[index].status;
      if (status === TutorTurnStatus.WAITING_ANSWER || status === TutorTurnStatus.GENERATING) {
        active = this.turns[index].turnId;
      }
    }
    this.activeTurnId = active;
    if (active.length === 0) {
      this.stop_poll();
      this.pollNotice = '';
      return;
    }
    this.start_poll();
  }

  private poll_allowed(): boolean {
    return this.alive && this.visible && this.foreground;
  }

  private resume_poll(): void {
    if (!this.poll_allowed()) {
      return;
    }
    this.sync_active_turn();
    if (this.activeTurnId.length === 0) {
      return;
    }
    this.poll_active();
  }

  private start_poll(): void {
    if (!this.poll_allowed()) {
      this.stop_poll();
      return;
    }
    if (this.pollTimer !== -1) {
      return;
    }
    this.pollTimer = setInterval(() => {
      this.poll_active();
    }, POLL_INTERVAL_MS);
  }

  private stop_poll(): void {
    this.pollGeneration = this.pollGeneration + 1;
    if (this.pollTimer !== -1) {
      clearInterval(this.pollTimer);
      this.pollTimer = -1;
    }
  }

  private async poll_active(): Promise<void> {
    if (!this.poll_allowed() || this.activeTurnId.length === 0 || this.questionId.length === 0) {
      return;
    }
    if (this.pollBusyGeneration === this.pollGeneration) {
      return;
    }
    const token: string | null = authSession.read_access_token();
    const user: UserProfile | null = authSession.read_user();
    if (token === null || user === null) {
      return;
    }
    const accountId: string = user.accountId;
    const turnId: string = this.activeTurnId;
    const seq: number = this.requestSeq;
    const generation: number = this.pollGeneration;
    this.pollBusyGeneration = generation;
    try {
      const result: AuthResult<TutorTurn> = await this.api.getTurn(token, this.questionId, turnId);
      if (generation !== this.pollGeneration || !this.poll_allowed() ||
        this.is_stale(seq, token, accountId) || this.activeTurnId !== turnId) {
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
        this.pollNotice = '暂时无法刷新这次提问的状态，将继续重试';
        return;
      }
      this.pollNotice = '';
      this.merge_turn(result.data);
      this.notify_pending_turn(result.data);
      this.sync_active_turn();
    } catch (error) {
      if (generation === this.pollGeneration && this.poll_allowed() &&
        !this.is_stale(seq, token, accountId)) {
        this.pollNotice = '暂时无法刷新这次提问的状态，将继续重试';
      }
    } finally {
      if (this.pollBusyGeneration === generation) {
        this.pollBusyGeneration = -1;
      }
    }
  }

  private track_requested_turn(turn: TutorTurn, scope?: number): void {
    if (turn.questionId !== this.questionId) { return; }
    if (scope === undefined) { return; }
    this.requestedToastScopes.set(turn.turnId, scope);
    this.notify_pending_turn(turn);
  }

  private notify_pending_turn(turn: TutorTurn): void {
    if (turn.questionId !== this.questionId) { return; }
    const scope: number | undefined = this.requestedToastScopes.get(turn.turnId);
    if (scope === undefined || (turn.status !== TutorTurnStatus.SUCCEEDED && turn.status !== TutorTurnStatus.FAILED)) { return; }
    this.requestedToastScopes.delete(turn.turnId);
    this.toast?.show({ key: 'phone-tutor-result:' + turn.turnId,
      type: turn.status === TutorTurnStatus.SUCCEEDED ? 'success' : 'error',
      message: turn.status === TutorTurnStatus.SUCCEEDED ? '本次解答已完成' : '暂时未能获取回复',
      description: turn.status === TutorTurnStatus.SUCCEEDED ? '可在当前对话查看' : '提问记录已保留，请查看页面提示' }, scope);
  }

  private async submit_prompt(): Promise<void> {
    if (this.submitting) {
      return;
    }
    if (this.prompt.trim().length === 0) {
      this.composeError = '请输入问题';
      return;
    }
    if (this.prompt.length > MAX_PROMPT_LENGTH) {
      this.composeError = '问题不能超过 4000 个字符';
      return;
    }
    if (this.has_active_turn()) {
      this.composeError = '本次提问尚未结束，请稍候，或先取消等待。';
      return;
    }
    const token: string | null = authSession.read_access_token();
    const user: UserProfile | null = authSession.read_user();
    if (token === null || user === null) {
      this.onUnauthorized('');
      return;
    }
    const accountId: string = user.accountId;
    const text: string = this.prompt;
    let requestId: string = this.pendingRequestId;
    if (requestId.length === 0 || this.pendingPrompt !== text) {
      requestId = util.generateRandomUUID(true).toLowerCase();
      this.pendingRequestId = requestId;
      this.pendingPrompt = text;
    }
    const toast_scope: number | undefined = this.toast?.captureScope();
    this.pendingRequestToastScope = toast_scope;
    const seq: number = ++this.requestSeq;
    this.submitting = true;
    this.save_session();
    this.composeError = '';
    this.composeHint = '';
    try {
      const result: AuthResult<TutorTurn> = await this.api.createTurn(token, this.questionId, requestId, text);
      if (this.is_stale(seq, token, accountId)) {
        return;
      }
      if (!authSession.has_valid_session()) {
        this.handle_unauthorized(token);
        return;
      }
      if (result.isSuccess && result.data !== null) {
        this.pendingRequestId = '';
        this.pendingPrompt = '';
        this.submitting = false;
        this.stop_editing();
        this.prompt = '';
        this.merge_turn(result.data);
        this.track_requested_turn(result.data, toast_scope);
        this.expandedTurnId = result.data.turnId;
        this.schedule_scroll(true, 0);
        this.composeHint = result.data.status === TutorTurnStatus.WAITING_ANSWER ?
          '提问已发送。请在平板上打开这道题，收到本次作答后会准备解答。' : '';
        this.sync_active_turn();
        return;
      }
      const error: AuthError | null = result.error;
      if (error !== null && error.is_unauthorized()) {
        this.handle_unauthorized(token);
        return;
      }
      if (error !== null && error.httpStatus === 409) {
        this.pendingRequestId = '';
        this.pendingPrompt = '';
        this.composeError = describe_tutor_error(error);
        return;
      }
      if (error !== null && (error.is_network() || error.httpStatus >= 500 ||
        error.kind === AuthFailureKind.PROTOCOL)) {
        await this.confirm_unknown_create(requestId, text, token, accountId, seq, toast_scope);
        return;
      }
      this.pendingRequestId = '';
      this.pendingPrompt = '';
      this.composeError = describe_tutor_error(error);
    } catch (error) {
      if (!this.is_stale(seq, token, accountId)) {
        await this.confirm_unknown_create(requestId, text, token, accountId, seq, toast_scope);
      }
    } finally {
      if (seq === this.requestSeq) {
        this.submitting = false;
        this.save_session();
        if (!this.is_stale(seq, token, accountId) && this.composeError.length > 0) {
          this.toast?.show({ key: 'phone-tutor-submit', type: 'error', message: '提问暂时未能发送',
            description: '提问内容已保留，请查看页面提示' }, toast_scope);
        }
      }
    }
  }

  private async confirm_unknown_create(requestId: string, text: string, token: string,
    accountId: string, seq: number, toast_scope?: number): Promise<void> {
    if (this.is_stale(seq, token, accountId)) { return; }
    this.composeHint = '正在确认刚才的提问是否发送成功…';
    try {
      const result: AuthResult<TutorTurn[]> = await this.api.listTurns(token, this.questionId);
      if (this.is_stale(seq, token, accountId)) {
        return;
      }
      if (!authSession.has_valid_session()) {
        this.handle_unauthorized(token);
        return;
      }
      if (!result.isSuccess || result.data === null) {
        this.composeHint = '暂时无法确认是否发送成功，请稍后再次发送以确认原提问。';
        return;
      }
      let found: TutorTurn | null = null;
      for (let index: number = 0; index < result.data.length; index++) {
        if (result.data[index].requestId === requestId) {
          found = result.data[index];
          break;
        }
      }
      if (found !== null) {
        this.set_turns(result.data);
        this.expandedTurnId = found.turnId;
        this.pendingRequestId = '';
        this.pendingPrompt = '';
        this.submitting = false;
        this.stop_editing();
        this.prompt = '';
        this.composeHint = '已确认刚才的提问发送成功。';
        this.track_requested_turn(found, toast_scope);
        this.schedule_scroll(true, 0);
        this.sync_active_turn();
        return;
      }
      this.pendingPrompt = text;
      this.composeHint = '尚未确认发送结果，再次发送会继续确认刚才的提问。';
    } catch (error) {
      if (!this.is_stale(seq, token, accountId)) {
        this.composeHint = '暂时无法确认是否发送成功，请稍后再次发送以确认原提问。';
      }
    } finally {
      if (!this.is_stale(seq, token, accountId) && this.pendingRequestId.length > 0) {
        this.toast?.show({ key: 'phone-tutor-submit', type: 'info', message: '暂时无法确认提问结果',
          description: '内容已保留，请稍后确认' }, toast_scope);
      }
    }
  }

  private async cancel_turn(turn: TutorTurn): Promise<void> {
    if (this.busyTurnId.length > 0 || this.submitting) {
      return;
    }
    const token: string | null = authSession.read_access_token();
    const user: UserProfile | null = authSession.read_user();
    if (token === null || user === null) {
      this.onUnauthorized('');
      return;
    }
    const accountId: string = user.accountId;
    const toast_scope: number | undefined = this.toast?.captureScope();
    const seq: number = ++this.requestSeq;
    this.busyTurnId = turn.turnId;
    this.actionError = '';
    let outcome_confirmed: boolean = false;
    try {
      const result: AuthResult<TutorTurn> = await this.api.cancelTurn(token, this.questionId, turn.turnId);
      if (this.is_stale(seq, token, accountId)) {
        return;
      }
      if (!authSession.has_valid_session()) {
        this.handle_unauthorized(token);
        return;
      }
      if (result.isSuccess && result.data !== null) {
        this.merge_turn(result.data);
        this.requestedToastScopes.delete(turn.turnId);
        this.toast?.show({ key: 'phone-tutor-cancel', type: 'info', message: '本次提问状态已更新' }, toast_scope);
        this.sync_active_turn();
        return;
      }
      const error: AuthError | null = result.error;
      if (error !== null && error.is_unauthorized()) {
        this.handle_unauthorized(token);
        return;
      }
      if (error !== null && (error.is_network() || error.httpStatus >= 500 ||
        error.kind === AuthFailureKind.PROTOCOL)) {
        outcome_confirmed = await this.refresh_after_unknown(turn.turnId, token, accountId, seq, '取消结果未知', toast_scope, false);
        return;
      }
      this.actionError = describe_tutor_error(error);
    } catch (error) {
      outcome_confirmed = await this.refresh_after_unknown(turn.turnId, token, accountId, seq, '取消结果未知', toast_scope, false);
    } finally {
      if (seq === this.requestSeq) {
        this.busyTurnId = '';
        if (!this.is_stale(seq, token, accountId) && !outcome_confirmed && this.actionError.length > 0) {
          this.toast?.show({ key: 'phone-tutor-action', type: 'error', message: '操作暂未确认完成',
            description: '请查看页面中的最新状态' }, toast_scope);
        }
      }
    }
  }

  private async retry_turn(turn: TutorTurn): Promise<void> {
    if (this.busyTurnId.length > 0 || this.submitting ||
      turn.status !== TutorTurnStatus.FAILED || turn.fileRef === null) {
      return;
    }
    const token: string | null = authSession.read_access_token();
    const user: UserProfile | null = authSession.read_user();
    if (token === null || user === null) {
      this.onUnauthorized('');
      return;
    }
    const accountId: string = user.accountId;
    const toast_scope: number | undefined = this.toast?.captureScope();
    const seq: number = ++this.requestSeq;
    this.busyTurnId = turn.turnId;
    this.actionError = '';
    let outcome_confirmed: boolean = false;
    try {
      const result: AuthResult<TutorTurn> = await this.api.retryTurn(token, this.questionId, turn.turnId);
      if (this.is_stale(seq, token, accountId)) {
        return;
      }
      if (!authSession.has_valid_session()) {
        this.handle_unauthorized(token);
        return;
      }
      if (result.isSuccess && result.data !== null) {
        this.merge_turn(result.data);
        this.track_requested_turn(result.data, toast_scope);
        this.sync_active_turn();
        return;
      }
      const error: AuthError | null = result.error;
      if (error !== null && error.is_unauthorized()) {
        this.handle_unauthorized(token);
        return;
      }
      if (error !== null && (error.is_network() || error.httpStatus >= 500 ||
        error.kind === AuthFailureKind.PROTOCOL)) {
        outcome_confirmed = await this.refresh_after_unknown(turn.turnId, token, accountId, seq, '重试结果未知', toast_scope, true);
        return;
      }
      this.actionError = describe_tutor_error(error);
    } catch (error) {
      outcome_confirmed = await this.refresh_after_unknown(turn.turnId, token, accountId, seq, '重试结果未知', toast_scope, true);
    } finally {
      if (seq === this.requestSeq) {
        this.busyTurnId = '';
        if (!this.is_stale(seq, token, accountId) && !outcome_confirmed && this.actionError.length > 0) {
          this.toast?.show({ key: 'phone-tutor-action', type: 'error', message: '操作暂未确认完成',
            description: '请查看页面中的最新状态' }, toast_scope);
        }
      }
    }
  }

  private async refresh_after_unknown(turnId: string, token: string, accountId: string,
    seq: number, prefix: string, toast_scope?: number, track: boolean = false): Promise<boolean> {
    if (this.is_stale(seq, token, accountId)) { return false; }
    try {
      const current: AuthResult<TutorTurn> = await this.api.getTurn(token, this.questionId, turnId);
      if (this.is_stale(seq, token, accountId)) {
        return false;
      }
      if (!authSession.has_valid_session()) {
        this.handle_unauthorized(token);
        return false;
      }
      if (!current.isSuccess || current.data === null) {
        this.actionError = prefix + '，且暂时无法读取这次提问的状态，请手动刷新';
        return false;
      }
      this.merge_turn(current.data);
      if (track) { this.track_requested_turn(current.data, toast_scope); }
      else { this.toast?.show({ key: 'phone-tutor-action', type: 'info', message: '已读取本次提问的最新状态' }, toast_scope); }
      this.sync_active_turn();
      if (current.data.status === TutorTurnStatus.FAILED) {
        this.actionError = prefix + '；已确认解答仍未完成' +
          (current.data.fileRef === null ? '' : '，可以再次重试解答');
      } else if (current.data.status === TutorTurnStatus.WAITING_ANSWER) {
        this.actionError = prefix + '；已确认仍在等待平板作答';
      } else if (current.data.status === TutorTurnStatus.GENERATING) {
        this.actionError = prefix + '；已确认仍在准备解答';
      } else {
        this.actionError = prefix + '；已确认解答已完成';
      }
      return true;
    } catch (error) {
      if (!this.is_stale(seq, token, accountId)) {
        this.actionError = prefix + '，且暂时无法读取这次提问的状态，请手动刷新';
      }
    }
    return false;
  }

  private merge_turn(turn: TutorTurn): void {
    if (turn.questionId !== this.questionId) { return; }
    let follow: boolean = false;
    try { follow = this.conversationScroller.isAtEnd(); } catch (error) {}
    const next: TutorTurn[] = [];
    let replaced: boolean = false;
    for (let index: number = 0; index < this.turns.length; index++) {
      if (this.turns[index].turnId === turn.turnId) {
        next.push(turn);
        replaced = true;
      } else {
        next.push(this.turns[index]);
      }
    }
    if (!replaced) {
      next.push(turn);
    }
    this.set_turns(next);
    if (follow) { this.schedule_scroll(true, 0); }
  }

  private save_scroll_position(): void {
    if (this.loadedQuestionId.length === 0 || !this.visible || this.scrollRestorePending) { return; }
    try {
      const session: PhoneTutorSession = this.sessionStore.get(this.loadedQuestionId);
      session.scrollOffset = this.conversationScroller.currentOffset().yOffset;
      session.hasScrollPosition = true;
    } catch (error) {}
  }

  private restore_scroll_position(): void {
    const session: PhoneTutorSession = this.sessionStore.get(this.questionId);
    this.schedule_scroll(!session.hasScrollPosition, session.scrollOffset, !session.hasScrollPosition);
  }

  private schedule_scroll(toEnd: boolean, offset: number, preferLatestTurn: boolean = false): void {
    this.clear_scroll_timer();
    const questionId: string = this.questionId;
    this.scrollTimer = setTimeout(() => {
      this.scrollTimer = -1;
      if (!this.poll_allowed() || this.questionId !== questionId) { return; }
      try {
        if (toEnd && preferLatestTurn && this.latestTurnMeasured) {
          this.conversationScroller.scrollTo({ xOffset: 0, yOffset: this.latestTurnTop, animation: false });
        } else if (toEnd) { this.conversationScroller.scrollEdge(Edge.Bottom); }
        else { this.conversationScroller.scrollTo({ xOffset: 0, yOffset: offset, animation: false }); }
        this.scrollRestorePending = false;
        this.save_scroll_position();
      } catch (error) {}
    }, 100);
  }

  private clear_scroll_timer(): void {
    if (this.scrollTimer !== -1) { clearTimeout(this.scrollTimer); this.scrollTimer = -1; }
  }

  private is_stale(seq: number, token: string, accountId: string): boolean {
    if (!this.alive || this.loadedQuestionId !== this.questionId || seq !== this.requestSeq) {
      return true;
    }
    const user: UserProfile | null = authSession.read_user();
    return !authSession.matches_access_token(token) || user === null || user.accountId !== accountId;
  }

  private handle_unauthorized(token: string): void {
    this.requestSeq = this.requestSeq + 1;
    this.stop_poll();
    this.loading = false;
    this.submitting = false;
    this.busyTurnId = '';
    authSession.clear_session_if_token(token);
    this.onUnauthorized(token);
  }
}
