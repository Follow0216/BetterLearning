import { util } from '@kit.ArkTS';
import {
  authSession,
  AuthError,
  AuthFailureKind,
  AuthResult,
  QuestionMarkdownView,
  TutorApi,
  TutorTurn,
  TutorTurnStatus,
  UserProfile
} from 'common';
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
  @Prop readOnly: boolean = false;
  @Prop @Watch('on_question_changed') questionId: string = '';
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

  private api: TutorApi = TutorApi.create_default();
  private requestSeq: number = 0;
  private pollTimer: number = -1;
  private pollBusyGeneration: number = -1;
  private pollGeneration: number = 0;
  private alive: boolean = true;
  private activeTurnId: string = '';
  private pendingRequestId: string = '';
  private pendingPrompt: string = '';

  aboutToAppear(): void {
    this.alive = true;
    this.load_turns();
  }

  aboutToDisappear(): void {
    this.alive = false;
    this.requestSeq = this.requestSeq + 1;
    this.stop_poll();
  }

  on_question_changed(): void {
    this.requestSeq = this.requestSeq + 1;
    this.stop_poll();
    this.turns = [];
    this.displayTurns = [];
    this.loading = true;
    this.listError = '';
    this.expandedTurnId = '';
    this.activeTurnId = '';
    this.actionError = '';
    this.pollNotice = '';
    this.composeError = '';
    this.composeHint = '';
    this.pendingRequestId = '';
    this.pendingPrompt = '';
    this.load_turns();
  }

  on_poll_gate_changed(): void {
    if (!this.poll_allowed()) {
      this.stop_poll();
      return;
    }
    this.resume_poll();
  }

  build() {
    Column() {
      this.top_bar()
      if (!this.readOnly) {
        this.compose_section()
      }
      if (this.listError.length > 0 && this.turns.length === 0) {
        this.list_error_view()
      } else if (this.loading && this.turns.length === 0) {
        this.loading_view()
      } else {
        Scroll() {
          Column() {
            if (this.listError.length > 0) {
              this.notice_banner(this.listError, PHONE_COLOR_DANGER, PHONE_COLOR_DANGER_SOFT)
            }
            if (this.actionError.length > 0) {
              this.notice_banner(this.actionError, PHONE_COLOR_DANGER, PHONE_COLOR_DANGER_SOFT)
            }
            if (this.pollNotice.length > 0) {
              this.notice_banner(this.pollNotice, PHONE_COLOR_PRIMARY_DEEP, PHONE_COLOR_SOFT)
            }
            if (this.turns.length === 0) {
              this.empty_view()
            } else {
              ForEach(this.displayTurns, (turn: TutorTurn) => {
                this.turn_card(turn)
              }, (turn: TutorTurn) => this.turn_key(turn))
            }
          }
          .width('100%')
          .alignItems(HorizontalAlign.Start)
          .padding({ left: 14, right: 14, bottom: 24 })
        }
        .layoutWeight(1)
        .width('100%')
        .scrollBar(BarState.Off)
        .edgeEffect(EdgeEffect.Spring)
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(PHONE_COLOR_BACKGROUND)
  }

  @Builder
  top_bar() {
    Row() {
      Image($r('app.media.bl_back'))
        .width(22)
        .height(22)
        .padding(7)
        .accessibilityText('返回题目详情')
        .onClick(() => {
          this.onBack();
        })
      Column() {
        Text(this.readOnly ? '旧版辅导记录' : 'AI 辅导')
          .fontSize(20)
          .fontWeight(FontWeight.Bold)
          .fontColor(PHONE_COLOR_TITLE)
        Text('同题最近 50 轮 · 最新在上')
          .fontSize(11)
          .fontColor(PHONE_COLOR_MUTED)
          .margin({ top: 2 })
      }
      .alignItems(HorizontalAlign.Start)
      .layoutWeight(1)
      .margin({ left: 4 })
      Text(this.loading ? '刷新中…' : '刷新')
        .fontSize(12)
        .fontColor(PHONE_COLOR_PRIMARY_DEEP)
        .padding({ left: 10, right: 10, top: 6, bottom: 6 })
        .backgroundColor(PHONE_COLOR_CARD)
        .border({ width: 1, color: PHONE_COLOR_BORDER })
        .borderRadius(14)
        .opacity(this.loading ? 0.45 : 1)
        .accessibilityText('刷新辅导轮次')
        .onClick(() => {
          this.refresh_manual();
        })
    }
    .width('100%')
    .padding({ left: 10, right: 14, top: 12, bottom: 8 })
    .alignItems(VerticalAlign.Center)
  }

  @Builder
  compose_section() {
    Column() {
      Row() {
        Text('向 AI 提问')
          .fontSize(13)
          .fontWeight(FontWeight.Medium)
          .fontColor(PHONE_COLOR_TITLE)
          .layoutWeight(1)
        Text(this.prompt.length.toString() + ' / ' + MAX_PROMPT_LENGTH.toString())
          .fontSize(11)
          .fontColor(this.prompt.length > MAX_PROMPT_LENGTH ? PHONE_COLOR_DANGER : PHONE_COLOR_MUTED)
      }
      .width('100%')

      TextArea({
        text: this.prompt,
        placeholder: '写下你对这道题的具体疑问，发送后由平板冻结本题作答。'
      })
        .width('100%')
        .height(92)
        .fontSize(14)
        .fontColor(PHONE_COLOR_TITLE)
        .placeholderColor(PHONE_COLOR_MUTED)
        .backgroundColor(PHONE_COLOR_BACKGROUND)
        .border({ width: 1, color: PHONE_COLOR_BORDER })
        .borderRadius(PHONE_RADIUS_CONTROL)
        .padding(10)
        .maxLength(MAX_PROMPT_LENGTH)
        .enabled(!this.submitting)
        .margin({ top: 8 })
        .onChange((value: string) => {
          this.prompt = value;
          this.composeError = '';
          if (this.pendingRequestId.length > 0 && this.pendingPrompt !== value) {
            this.pendingRequestId = '';
            this.pendingPrompt = '';
            this.composeHint = '';
          }
        })

      if (this.has_active_turn()) {
        Text('当前账号已有未结束的辅导轮次。等待平板作答时，请让平板前台打开本题；新提问需等本轮结束后再发送。')
          .fontSize(11)
          .fontColor(PHONE_COLOR_PRIMARY_DEEP)
          .lineHeight(17)
          .width('100%')
          .padding(10)
          .backgroundColor(PHONE_COLOR_SOFT)
          .borderRadius(PHONE_RADIUS_CONTROL)
          .margin({ top: 8 })
      }

      if (this.composeError.length > 0) {
        Text(this.composeError)
          .fontSize(12)
          .fontColor(PHONE_COLOR_DANGER)
          .lineHeight(18)
          .width('100%')
          .padding(10)
          .backgroundColor(PHONE_COLOR_DANGER_SOFT)
          .borderRadius(PHONE_RADIUS_CONTROL)
          .margin({ top: 8 })
      }

      if (this.composeHint.length > 0) {
        Text(this.composeHint)
          .fontSize(12)
          .fontColor(PHONE_COLOR_PRIMARY_DEEP)
          .lineHeight(18)
          .width('100%')
          .padding(10)
          .backgroundColor(PHONE_COLOR_SOFT)
          .borderRadius(PHONE_RADIUS_CONTROL)
          .margin({ top: 8 })
      }

      Button(this.submit_label())
        .width('100%')
        .height(44)
        .fontSize(14)
        .fontWeight(FontWeight.Medium)
        .fontColor(PHONE_COLOR_WHITE)
        .backgroundColor(this.can_submit() ? PHONE_COLOR_PRIMARY : PHONE_COLOR_MUTED)
        .borderRadius(PHONE_RADIUS_CONTROL)
        .enabled(this.can_submit())
        .opacity(this.can_submit() ? 1 : 0.6)
        .margin({ top: 10 })
        .onClick(() => {
          this.submit_prompt();
        })
    }
    .width('100%')
    .padding(14)
    .backgroundColor(PHONE_COLOR_CARD)
    .borderRadius(PHONE_RADIUS_CARD)
    .margin({ left: 14, right: 14, top: 4, bottom: 10 })
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  notice_banner(text: string, foreground: string, background: string) {
    Text(text)
      .fontSize(12)
      .fontColor(foreground)
      .lineHeight(18)
      .width('100%')
      .padding(11)
      .backgroundColor(background)
      .borderRadius(PHONE_RADIUS_CONTROL)
      .margin({ top: 6 })
  }

  @Builder
  loading_view() {
    Column() {
      LoadingProgress()
        .width(28)
        .height(28)
        .color(PHONE_COLOR_PRIMARY)
      Text('正在读取辅导轮次…')
        .fontSize(13)
        .fontColor(PHONE_COLOR_MUTED)
        .margin({ top: 10 })
    }
    .width('100%')
    .layoutWeight(1)
    .justifyContent(FlexAlign.Center)
  }

  @Builder
  list_error_view() {
    Column() {
      Image($r('app.media.bl_warning'))
        .width(34)
        .height(34)
      Text(this.listError)
        .fontSize(14)
        .fontColor(PHONE_COLOR_DANGER)
        .textAlign(TextAlign.Center)
        .margin({ top: 12 })
      Button('重新加载')
        .height(38)
        .fontSize(13)
        .fontColor(PHONE_COLOR_WHITE)
        .backgroundColor(PHONE_COLOR_PRIMARY)
        .borderRadius(PHONE_RADIUS_CONTROL)
        .enabled(!this.loading)
        .opacity(this.loading ? 0.5 : 1)
        .margin({ top: 16 })
        .onClick(() => {
          this.load_turns();
        })
    }
    .width('100%')
    .layoutWeight(1)
    .justifyContent(FlexAlign.Center)
    .padding(28)
  }

  @Builder
  empty_view() {
    Column() {
      Image($r('app.media.bl_inbox'))
        .width(42)
        .height(42)
      Text('还没有辅导轮次')
        .fontSize(16)
        .fontWeight(FontWeight.Medium)
        .fontColor(PHONE_COLOR_TITLE)
        .margin({ top: 12 })
      Text(this.readOnly ? '这道题没有旧版辅导记录。' :
        '在上方写下问题后发送。平板需在前台打开本题，冻结完整作答后才会生成解答。')
        .fontSize(12)
        .fontColor(PHONE_COLOR_MUTED)
        .textAlign(TextAlign.Center)
        .lineHeight(18)
        .margin({ top: 6 })
    }
    .width('100%')
    .padding({ top: 44, bottom: 40, left: 22, right: 22 })
    .backgroundColor(PHONE_COLOR_CARD)
    .borderRadius(PHONE_RADIUS_CARD)
    .margin({ top: 8 })
  }

  @Builder
  turn_card(turn: TutorTurn) {
    Column() {
      Row() {
        Text(describe_tutor_status(turn.status))
          .fontSize(11)
          .fontWeight(FontWeight.Medium)
          .fontColor(this.status_foreground(turn.status))
          .padding({ left: 8, right: 8, top: 3, bottom: 3 })
          .backgroundColor(this.status_background(turn.status))
          .borderRadius(8)
        Text(format_question_time(turn.createdAt))
          .fontSize(11)
          .fontColor(PHONE_COLOR_MUTED)
          .margin({ left: 8 })
        Blank()
        Text(this.expandedTurnId === turn.turnId ? '收起' : '展开')
          .fontSize(11)
          .fontColor(PHONE_COLOR_PRIMARY_DEEP)
      }
      .width('100%')
      .alignItems(VerticalAlign.Center)
      .accessibilityText('切换本轮详情')
      .onClick(() => {
        this.toggle_expanded(turn.turnId);
      })

      Text(turn.prompt)
        .fontSize(14)
        .fontColor(PHONE_COLOR_TITLE)
        .lineHeight(21)
        .maxLines(this.expandedTurnId === turn.turnId ? 50 : 2)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .width('100%')
        .margin({ top: 10 })

      if (this.expandedTurnId === turn.turnId) {
        if (turn.status === TutorTurnStatus.WAITING_ANSWER) {
          Row() {
            LoadingProgress()
              .width(16)
              .height(16)
              .color(PHONE_COLOR_PRIMARY)
            Text('等待平板打开本题并冻结作答。平板需在前台停留，冻结后仍可继续书写。')
              .fontSize(12)
              .fontColor(PHONE_COLOR_MUTED)
              .lineHeight(18)
              .layoutWeight(1)
              .margin({ left: 8 })
          }
          .width('100%')
          .alignItems(VerticalAlign.Top)
          .margin({ top: 10 })

          if (turn.fileRef === null && !this.readOnly) {
            Button(this.busyTurnId === turn.turnId ? '正在取消…' : '取消等待')
              .height(36)
              .fontSize(12)
              .fontColor(PHONE_COLOR_DANGER)
              .backgroundColor(PHONE_COLOR_DANGER_SOFT)
              .borderRadius(PHONE_RADIUS_CONTROL)
              .enabled(this.busyTurnId.length === 0 && !this.submitting)
              .margin({ top: 10 })
              .onClick(() => {
                this.cancel_turn(turn);
              })
          } else if (turn.fileRef !== null) {
            Text('本轮完整作答已冻结，正在等待领取生成；冻结文件已固定，不会重新导出。')
              .fontSize(11)
              .fontColor(PHONE_COLOR_MUTED)
              .lineHeight(17)
              .width('100%')
              .margin({ top: 8 })
          }
        } else if (turn.status === TutorTurnStatus.GENERATING) {
          Row() {
            LoadingProgress()
              .width(16)
              .height(16)
              .color(PHONE_COLOR_PRIMARY)
            Text('模型正在生成解答，可能需要一段时间；网络中断时会按本轮状态确认，不重复另开轮。')
              .fontSize(12)
              .fontColor(PHONE_COLOR_MUTED)
              .lineHeight(18)
              .layoutWeight(1)
              .margin({ left: 8 })
          }
          .width('100%')
          .alignItems(VerticalAlign.Top)
          .margin({ top: 10 })
        } else if (turn.status === TutorTurnStatus.SUCCEEDED) {
          Column() {
            QuestionMarkdownView({
              bodyMarkdown: turn.answerMarkdown === null ? '' : turn.answerMarkdown,
              fontSize: 15
            })
              .width('100%')
              .height(320)
          }
          .width('100%')
          .backgroundColor(PHONE_COLOR_BACKGROUND)
          .border({ width: 1, color: PHONE_COLOR_BORDER })
          .borderRadius(PHONE_RADIUS_CONTROL)
          .clip(true)
          .margin({ top: 10 })
        } else {
          Text(describe_tutor_failure(turn))
            .fontSize(12)
            .fontColor(PHONE_COLOR_DANGER)
            .lineHeight(18)
            .width('100%')
            .padding(10)
            .backgroundColor(PHONE_COLOR_DANGER_SOFT)
            .borderRadius(PHONE_RADIUS_CONTROL)
            .margin({ top: 10 })

          if (turn.fileRef !== null) {
            Text('本轮冻结文件仍保留，重试只复用该轮，不会重新冻结或新建轮次。')
              .fontSize(11)
              .fontColor(PHONE_COLOR_MUTED)
              .lineHeight(17)
              .width('100%')
              .margin({ top: 8 })
            if (!this.readOnly) {
              Button(this.busyTurnId === turn.turnId ? '正在重试…' : '重试本轮')
                .height(36)
                .fontSize(12)
                .fontColor(PHONE_COLOR_WHITE)
                .backgroundColor(PHONE_COLOR_PRIMARY)
                .borderRadius(PHONE_RADIUS_CONTROL)
                .enabled(this.busyTurnId.length === 0 && !this.submitting)
                .margin({ top: 10 })
                .onClick(() => {
                  this.retry_turn(turn);
                })
            }
          } else {
            Text('本轮没有可复用的冻结文件，请重新提问。')
              .fontSize(11)
              .fontColor(PHONE_COLOR_MUTED)
              .margin({ top: 8 })
          }
        }

        if (turn.fileRef !== null) {
          Button('查看本轮冻结作答')
            .height(36)
            .fontSize(12)
            .fontColor(PHONE_COLOR_PRIMARY_DEEP)
            .backgroundColor(PHONE_COLOR_SOFT)
            .borderRadius(PHONE_RADIUS_CONTROL)
            .margin({ top: 10 })
            .onClick(() => {
              this.onOpenFrozenAnswer(this.questionId, turn.turnId);
            })
        }

        Text('更新于 ' + format_question_time(turn.updatedAt))
          .fontSize(11)
          .fontColor(PHONE_COLOR_MUTED)
          .margin({ top: 10 })
      }
    }
    .width('100%')
    .padding(14)
    .backgroundColor(PHONE_COLOR_CARD)
    .borderRadius(PHONE_RADIUS_CARD)
    .margin({ top: 8 })
    .alignItems(HorizontalAlign.Start)
  }

  private set_turns(next: TutorTurn[]): void {
    this.turns = next;
    const copy: TutorTurn[] = next.slice();
    copy.reverse();
    this.displayTurns = copy;
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
    return this.pendingRequestId.length > 0 ? '重试原提问' : '发送问题';
  }

  private can_submit(): boolean {
    return !this.submitting && this.busyTurnId.length === 0 && this.prompt.trim().length > 0 &&
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

  private async load_turns(): Promise<void> {
    const token: string | null = authSession.read_access_token();
    const user: UserProfile | null = authSession.read_user();
    if (token === null || user === null) {
      this.onUnauthorized('');
      return;
    }
    if (this.questionId.length === 0) {
      this.loading = false;
      this.listError = '题目 ID 缺失';
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
      this.adopt_default_expansion();
      this.sync_active_turn();
    } catch (error) {
      if (this.is_stale(seq, token, accountId)) {
        return;
      }
      if (!authSession.has_valid_session()) {
        this.handle_unauthorized(token);
        return;
      }
      this.listError = '辅导轮次加载异常，请稍后重试';
    } finally {
      if (seq === this.requestSeq) {
        this.loading = false;
      }
    }
  }

  private refresh_manual(): void {
    if (this.loading || this.submitting || this.busyTurnId.length > 0) {
      return;
    }
    this.actionError = '';
    this.load_turns();
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
        this.pollNotice = '暂时无法刷新本轮状态，将继续重试';
        return;
      }
      this.pollNotice = '';
      this.merge_turn(result.data);
      this.sync_active_turn();
    } catch (error) {
      if (generation === this.pollGeneration && this.poll_allowed() &&
        !this.is_stale(seq, token, accountId)) {
        this.pollNotice = '暂时无法刷新本轮状态，将继续重试';
      }
    } finally {
      if (this.pollBusyGeneration === generation) {
        this.pollBusyGeneration = -1;
      }
    }
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
      this.composeError = '当前轮次尚未结束，请等待其结束或先取消等待中的轮次';
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
    const seq: number = ++this.requestSeq;
    this.submitting = true;
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
        this.prompt = '';
        this.merge_turn(result.data);
        this.expandedTurnId = result.data.turnId;
        this.composeHint = result.data.status === TutorTurnStatus.WAITING_ANSWER ?
          '提问已创建。请让平板前台打开本题并冻结作答，冻结后才会开始生成。' : '';
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
        await this.confirm_unknown_create(requestId, text, token, accountId, seq);
        return;
      }
      this.pendingRequestId = '';
      this.pendingPrompt = '';
      this.composeError = describe_tutor_error(error);
    } catch (error) {
      if (!this.is_stale(seq, token, accountId)) {
        await this.confirm_unknown_create(requestId, text, token, accountId, seq);
      }
    } finally {
      if (seq === this.requestSeq) {
        this.submitting = false;
      }
    }
  }

  private async confirm_unknown_create(requestId: string, text: string, token: string,
    accountId: string, seq: number): Promise<void> {
    this.composeHint = '网络结果未知，正在确认原提问是否已创建…';
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
        this.composeHint = '暂时无法确认原提问状态，请稍后点击“重试原提问”';
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
        this.prompt = '';
        this.composeHint = '原提问已创建，未重复发问';
        this.sync_active_turn();
        return;
      }
      this.pendingPrompt = text;
      this.composeHint = '未确认到原提问。请点击“重试原提问”，将复用同一请求标识，不会自动另开轮次';
    } catch (error) {
      if (!this.is_stale(seq, token, accountId)) {
        this.composeHint = '暂时无法确认原提问状态，请稍后点击“重试原提问”';
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
    const seq: number = ++this.requestSeq;
    this.busyTurnId = turn.turnId;
    this.actionError = '';
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
        await this.refresh_after_unknown(turn.turnId, token, accountId, seq, '取消结果未知');
        return;
      }
      this.actionError = describe_tutor_error(error);
    } catch (error) {
      await this.refresh_after_unknown(turn.turnId, token, accountId, seq, '取消结果未知');
    } finally {
      if (seq === this.requestSeq) {
        this.busyTurnId = '';
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
    const seq: number = ++this.requestSeq;
    this.busyTurnId = turn.turnId;
    this.actionError = '';
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
        await this.refresh_after_unknown(turn.turnId, token, accountId, seq, '重试结果未知');
        return;
      }
      this.actionError = describe_tutor_error(error);
    } catch (error) {
      await this.refresh_after_unknown(turn.turnId, token, accountId, seq, '重试结果未知');
    } finally {
      if (seq === this.requestSeq) {
        this.busyTurnId = '';
      }
    }
  }

  private async refresh_after_unknown(turnId: string, token: string, accountId: string,
    seq: number, prefix: string): Promise<void> {
    try {
      const current: AuthResult<TutorTurn> = await this.api.getTurn(token, this.questionId, turnId);
      if (this.is_stale(seq, token, accountId)) {
        return;
      }
      if (!authSession.has_valid_session()) {
        this.handle_unauthorized(token);
        return;
      }
      if (!current.isSuccess || current.data === null) {
        this.actionError = prefix + '，且暂时无法读取本轮状态，请手动刷新';
        return;
      }
      this.merge_turn(current.data);
      this.sync_active_turn();
      if (current.data.status === TutorTurnStatus.FAILED) {
        this.actionError = prefix + '；已确认本轮仍为失败' +
          (current.data.fileRef === null ? '' : '，可再次重试本轮');
      } else if (current.data.status === TutorTurnStatus.WAITING_ANSWER) {
        this.actionError = prefix + '；已确认本轮仍在等待平板作答';
      } else if (current.data.status === TutorTurnStatus.GENERATING) {
        this.actionError = prefix + '；已确认本轮正在生成';
      } else {
        this.actionError = prefix + '；已确认本轮已完成';
      }
    } catch (error) {
      if (!this.is_stale(seq, token, accountId)) {
        this.actionError = prefix + '，且暂时无法读取本轮状态，请手动刷新';
      }
    }
  }

  private merge_turn(turn: TutorTurn): void {
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
  }

  private is_stale(seq: number, token: string, accountId: string): boolean {
    if (seq !== this.requestSeq) {
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
