import { util } from '@kit.ArkTS';
import { LengthMetrics } from '@kit.ArkUI';
import {
  authSession, AuthError, AuthResult, citationFocusController, citation_marker_ids,
  LearningApi, LearningV2Api, QuestionDetail, QuestionMarkdownView, ToastStore, UserProfile,
  V2Citation, V2TutorMode, V2TutorRequest, V2TutorRequestDetail, V2TutorRequestPage, V2TutorRequestStatus,
  V2SnapshotInput
} from 'common';
import {
  phone_v2_active, phone_v2_apply_created, phone_v2_apply_detail, phone_v2_draft_for,
  phone_v2_intent_for, phone_v2_merge_requests, phone_v2_retryable, PhoneV2CreateIntent,
  PhoneV2Draft, PhoneV2ViewState, PhoneV2RecentQuestion, phone_v2_chronological,
  phone_v2_merge_details, phone_v2_verified, phone_v2_stage_text, phone_v2_recent_questions,
  phone_v2_context_parent, phone_v2_context_unconfirmed
} from '../model/PhoneTutorV2Logic';
import { PhoneBackController } from '../model/PhoneHomeState';
import { format_question_time, question_summary_title } from '../model/QuestionFormat';
import {
  PHONE_COLOR_BACKGROUND, PHONE_COLOR_BORDER, PHONE_COLOR_CARD, PHONE_COLOR_DANGER,
  PHONE_COLOR_DANGER_SOFT, PHONE_COLOR_MUTED, PHONE_COLOR_PRIMARY, PHONE_COLOR_PRIMARY_DEEP,
  PHONE_COLOR_SOFT, PHONE_COLOR_TITLE, PHONE_COLOR_WHITE, PHONE_RADIUS_CARD,
  PHONE_RADIUS_CONTROL
} from '../theme/PhoneTheme';

const V2_POLL_MS: number = 3000;
const V2_PROMPT_MAX: number = 4000;

/** LazyForEach 按视口创建消息，身份始终是 requestId；摘要和详情分别显式通知。 */
class PhoneV2MessageDataSource implements IDataSource {
  private items: V2TutorRequest[] = [];
  private listeners: DataChangeListener[] = [];

  totalCount(): number { return this.items.length; }
  getData(index: number): V2TutorRequest { return this.items[index]; }
  registerDataChangeListener(listener: DataChangeListener): void {
    if (!this.listeners.includes(listener)) { this.listeners.push(listener); }
  }
  unregisterDataChangeListener(listener: DataChangeListener): void {
    this.listeners = this.listeners.filter((item: DataChangeListener) => item !== listener);
  }
  replace(incoming: V2TutorRequest[]): void {
    const previous: V2TutorRequest[] = this.items;
    this.items = incoming.slice();
    const shapeChanged: boolean = previous.length !== incoming.length || previous.some((item: V2TutorRequest,
      index: number) => incoming[index]?.requestId !== item.requestId);
    if (shapeChanged) { this.listeners.forEach((listener: DataChangeListener) => listener.onDataReloaded()); }
    for (let index: number = 0; index < incoming.length; index++) {
      const old: V2TutorRequest | undefined = previous.find((item: V2TutorRequest) => item.requestId === incoming[index].requestId);
      if (old !== undefined && (old.revision !== incoming[index].revision || old.status !== incoming[index].status ||
        old.currentRunId !== incoming[index].currentRunId || old.prompt !== incoming[index].prompt || old.mode !== incoming[index].mode)) {
        this.changed(incoming[index].requestId);
      }
    }
  }
  changed(requestId: string): void {
    const index: number = this.items.findIndex((item: V2TutorRequest) => item.requestId === requestId);
    if (index < 0) { return; }
    this.listeners.forEach((listener: DataChangeListener) => listener.onDataChange(index));
  }
}

@Component
export struct PhoneTutorV2Page {
  toast?: ToastStore;
  @Prop embedded: boolean = false;
  editorNavigation: PhoneBackController = new PhoneBackController();
  onComposingChanged: (value: boolean) => void = () => {};
  onChooseQuestion: () => void = () => {};
  onSwitchQuestion: (questionId: string) => void = () => {};
  onRead: (title: string, body: string, plainText: boolean, citations?: V2Citation[]) => void = () => {};
  onSessionSummary: (questionId: string, label: string, prompt: string) => void = () => {};
  @Prop @Watch('on_question_changed') questionId: string = '';
  @Prop @Watch('on_visibility_changed') visible: boolean = true;
  @StorageProp('phoneForeground') @Watch('on_visibility_changed') foreground: boolean = true;
  onBack: () => void = () => {};
  onOpenLegacy: () => void = () => {};
  onOpenNotes: () => void = () => {};
  onOpenCitation: (citationId: string) => void = () => {};
  onUnauthorized: (token: string) => void = () => {};

  @State @Watch('on_requests_changed') requests: V2TutorRequest[] = [];
  private messageDataSource: PhoneV2MessageDataSource = new PhoneV2MessageDataSource();
  @State detail: V2TutorRequestDetail | null = null;
  @State selectedRequestId: string = '';
  @State prompt: string = '';
  @State fullSolution: boolean = false;
  @State reuseRequestId: string = '';
  @State loading: boolean = false;
  @State busy: boolean = false;
  @State message: string = '';
  @State errorMessage: string = '';
  @State nextCursor: string | null = null;
  @State loadedOnce: boolean = false;
  @State editorFocused: boolean = false;
  @State questionDetail: QuestionDetail | null = null;
  @State details: V2TutorRequestDetail[] = [];
  @State collapsedRequestIds: string[] = [];
  @State questionExpanded: boolean = false;
  @State questionBody: string = '';
  @State questionLabel: string = '';
  @State questionPickerVisible: boolean = false;
  @State recentQuestions: PhoneV2RecentQuestion[] = [];
  @State contextParentRequestId: string = '';
  @State independentQuestion: boolean = false;
  @State failedPrompt: string = '';
  @State newAnswerRequestId: string = '';
  @State detailNetworkIds: string[] = [];
  private questionReturnAnchorId: string = '';
  private questionReturnAnchorOffset: number = 0;
  private conversationScroller: ListScroller = new ListScroller();
  private listTopPadding: number = 8;
  private firstVisibleIndex: number = 0;
  private userMessageHeights: Map<string, number> = new Map<string, number>();
  private pendingRevealRequestId: string = '';
  private anchorRequestId: string = '';
  private anchorOffset: number = 0;
  private followLatest: boolean = true;
  private restoreTimer: number = -1;
  private restoreGeneration: number = 0;
  private hydrating: boolean = false;
  private hydrateGeneration: number = 0;

  private api: LearningV2Api = LearningV2Api.create_default();
  private createIntent: PhoneV2CreateIntent = new PhoneV2CreateIntent();
  private retryKey: string = '';
  private retryTargetRunId: string = '';
  private accountId: string = '';
  private seq: number = 0;
  private listSeq: number = 0;
  private selectionEpoch: number = 0;
  private detailSeqs: Map<string, number> = new Map<string, number>();
  private confirmSeq: number = 0;
  private errorSource: string = '';
  private errorRequestId: string = '';
  private errorActionKind: string = '';
  private errorTargetRunId: string | null = null;
  private errorSelectionEpoch: number = 0;
  private errorVersion: number = 0;
  private errorRecoverable: boolean = false;
  private pollTimer: number = -1;
  private pollGeneration: number = 0;
  private pollBusy: boolean = false;
  private alive: boolean = false;
  private editorController: TextAreaController = new TextAreaController();
  private loadedQuestionId: string = '';
  private questionLoadSeq: number = 0;
  private learningApi: LearningApi = LearningApi.create_default();

  aboutToAppear(): void {
    this.alive = true;
    this.accountId = authSession.read_user()?.accountId ?? '';
    this.loadedQuestionId = this.questionId;
    this.editorNavigation.handleBack = (): boolean => {
      if (!this.editorFocused) { return false; }
      this.stop_editing();
      return true;
    };
    this.adopt_intent();
    this.adopt_draft();
    this.refresh_list();
    this.load_question();
    this.schedule_restore();
  }

  aboutToDisappear(): void {
    this.capture_anchor();
    this.save_draft();
    this.stop_editing();
    this.cancel_restore();
    this.editorNavigation.handleBack = (): boolean => false;
    this.alive = false;
    this.questionLoadSeq++;
    this.seq++;
    this.listSeq++;
    this.selectionEpoch++;
    this.detailSeqs.clear();
    this.stop_poll();
  }

  on_question_changed(): void {
    this.capture_anchor();
    this.save_draft();
    this.cancel_restore();
    this.stop_editing();
    this.loadedQuestionId = this.questionId;
    this.questionLoadSeq++;
    this.seq++;
    this.listSeq++;
    this.selectionEpoch++;
    this.detailSeqs.clear();
    this.stop_poll();
    this.requests = [];
    this.details = [];
    this.collapsedRequestIds = [];
    this.questionExpanded = false;
    this.questionBody = '';
    this.questionLabel = '';
    this.questionPickerVisible = false;
    this.contextParentRequestId = '';
    this.independentQuestion = false;
    this.failedPrompt = '';
    this.newAnswerRequestId = '';
    this.detailNetworkIds = [];
    this.questionReturnAnchorId = '';
    this.questionReturnAnchorOffset = 0;
    this.anchorRequestId = '';
    this.anchorOffset = 0;
    this.followLatest = true;
    this.firstVisibleIndex = 0;
    this.userMessageHeights.clear();
    this.pendingRevealRequestId = '';
    this.hydrating = false;
    this.hydrateGeneration++;
    this.questionDetail = null;
    this.detail = null;
    this.selectedRequestId = '';
    this.prompt = '';
    this.fullSolution = false;
    this.reuseRequestId = '';
    this.createIntent = new PhoneV2CreateIntent();
    this.retryKey = '';
    this.retryTargetRunId = '';
    this.message = '';
    this.clear_error();
    this.nextCursor = null;
    this.loadedOnce = false;
    this.loading = false;
    this.busy = false;
    this.pollBusy = false;
    this.adopt_intent();
    this.adopt_draft();
    this.refresh_list();
    this.load_question();
    this.schedule_restore();
  }

  on_visibility_changed(): void {
    if (!this.poll_allowed()) {
      this.stop_editing();
      this.save_draft();
      this.stop_poll();
      return;
    }
    this.refresh_list();
  }

  build() {
    Column() {
      if (!this.embedded) { this.top_bar() }
      Stack({ alignContent: Alignment.Bottom }) {
        Column() {
          this.embedded_toolbar()
          Stack({ alignContent: Alignment.BottomEnd }) {
            List({ space: 14, scroller: this.conversationScroller }) {
              ListItem() {
                Column({ space: 8 }) {
                  if (this.questionExpanded) { this.question_content() }
                  if (this.errorMessage.length > 0 && (this.errorRequestId.length === 0 ||
                    this.errorActionKind === 'create' && !this.pending_local())) {
                    this.notice(this.errorMessage, PHONE_COLOR_DANGER, PHONE_COLOR_DANGER_SOFT)
                  }
                  if (this.message.length > 0 && this.createIntent.requestId.length > 0) {
                    this.notice(this.message, PHONE_COLOR_PRIMARY_DEEP, PHONE_COLOR_SOFT)
                  }
                  if (this.nextCursor !== null) {
                    Button(this.loading ? '正在加载更早消息…' : '加载更早消息')
                      .height(40).fontSize(12).fontColor(PHONE_COLOR_PRIMARY_DEEP)
                      .backgroundColor(PHONE_COLOR_SOFT).enabled(!this.loading)
                      .onClick(() => { this.load_more(); })
                  }
                  if (this.requests.length === 0 && this.createIntent.requestId.length === 0) {
                    Text(this.loading ? '正在读取本题对话…' : '从一个具体的疑问开始')
                      .fontSize(15).fontColor(PHONE_COLOR_TITLE).margin({ top: 18 })
                    Text(this.loading ? '已有草稿会保留' : '选择提示或完整解答，再发送你的问题。')
                      .fontSize(12).fontColor(PHONE_COLOR_MUTED).margin({ bottom: 16 })
                  }
                  if (this.requests.length > 0) {
                    Text(this.loading ? '正在同步本题对话…' : '本题对话')
                      .fontSize(11).fontColor(PHONE_COLOR_MUTED).width('100%')
                  }
                }.width('100%')
              }
              LazyForEach(this.messageDataSource, (request: V2TutorRequest) => {
                ListItem() { this.request_card(request.requestId) }
              }, (request: V2TutorRequest) => request.requestId)
              if (this.pending_local()) {
                ListItem() { this.pending_message() }
              }
            }
            .width('100%').height('100%').scrollBar(BarState.Off)
            .padding({ left: 14, right: 14, top: this.listTopPadding, bottom: 16 })
            .maintainVisibleContentPosition(true)
            .cachedCount(1)
            .onScrollIndex((start: number) => { this.firstVisibleIndex = start; this.capture_anchor(); })
            .onScroll(() => {
              this.followLatest = this.conversationScroller.isAtEnd();
              this.capture_anchor();
            })
            .onReachEnd(() => { this.followLatest = true; })
            if (this.newAnswerRequestId.length > 0) {
              Button('新回答 ↓').height(40).fontSize(12).fontColor(PHONE_COLOR_WHITE)
                .backgroundColor(PHONE_COLOR_PRIMARY).margin({ right: 14, bottom: 10 })
                .onClick(() => { this.reveal_answer(this.newAnswerRequestId); })
            }
          }.width('100%').layoutWeight(1)
          this.compose_card()
        }.width('100%').height('100%')
        if (this.questionPickerVisible) { this.question_picker() }
      }.width('100%').layoutWeight(1)
    }
    .width('100%').height('100%').backgroundColor(PHONE_COLOR_BACKGROUND)
  }

  @Builder
  top_bar() {
    Row() {
      Image($r('app.media.bl_back'))
        .width(22).height(22).padding(7)
        .accessibilityText('返回题目详情')
        .onClick(() => { this.onBack(); })
      Column() {
        Text('AI 辅导').fontSize(20).fontWeight(FontWeight.Bold).fontColor(PHONE_COLOR_TITLE)
        Text('先提示，再按需查看完整解答').fontSize(11).fontColor(PHONE_COLOR_MUTED)
      }
      .alignItems(HorizontalAlign.Start).layoutWeight(1).margin({ left: 4 })
      Text('笔记').fontSize(12).fontColor(PHONE_COLOR_PRIMARY_DEEP).padding(7)
        .onClick(() => { this.onOpenNotes(); })
      Text('旧版').fontSize(12).fontColor(PHONE_COLOR_PRIMARY_DEEP).padding(7)
        .onClick(() => { this.onOpenLegacy(); })
    }
    .width('100%').padding({ left: 10, right: 12, top: 12, bottom: 8 })
  }

  @Builder
  embedded_toolbar() {
    Row({ space: 8 }) {
      Column({ space: 2 }) {
        Text(this.question_label(this.questionLabel, this.questionId))
          .fontSize(14).fontWeight(FontWeight.Medium).fontColor(PHONE_COLOR_TITLE)
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%')
        Text(this.questionExpanded ? '收起题目 ↑' : '展开题目 ↓')
          .fontSize(11).fontColor(PHONE_COLOR_PRIMARY_DEEP)
      }
      .layoutWeight(1).alignItems(HorizontalAlign.Start).padding({ top: 6, bottom: 6 })
      .accessibilityText('查看当前题目，' + (this.questionExpanded ? '已展开' : '已收起'))
      .onClick(() => { this.toggle_question(); })
      Button('切题').height(40).fontSize(12).fontColor(PHONE_COLOR_PRIMARY_DEEP)
        .backgroundColor(PHONE_COLOR_SOFT).onClick(() => { this.open_question_picker(); })
      Text(this.loading ? '同步中' : '刷新').fontSize(11).fontColor(PHONE_COLOR_PRIMARY_DEEP)
        .padding(8).onClick(() => { this.refresh_conversation(); })
    }
    .width('100%').padding({ left: 16, right: 12, top: 4, bottom: 4 })
    .backgroundColor(PHONE_COLOR_CARD).border({ width: { bottom: 1 }, color: PHONE_COLOR_BORDER })
  }

  @Builder
  question_content() {
    Column({ space: 6 }) {
      Text('当前题目').fontSize(11).fontColor(PHONE_COLOR_MUTED)
      if (this.questionBody.length > 0) {
        QuestionMarkdownView({ bodyMarkdown: this.questionBody, fontSize: 15, autoHeight: true,
          maxLength: 65536, onContentHeight: () => { this.schedule_restore(); } }).width('100%')
      } else {
        Text('题面暂未取得，点击刷新重新读取。').fontSize(13).fontColor(PHONE_COLOR_MUTED)
      }
      Text('手机切题不会切换平板。需要新作答时，请在平板上打开本题。')
        .fontSize(11).lineHeight(17).fontColor(PHONE_COLOR_MUTED)
      Row({ space: 8 }) {
        Button('学习笔记').height(36).fontSize(11).fontColor(PHONE_COLOR_PRIMARY_DEEP)
          .backgroundColor(PHONE_COLOR_SOFT).onClick(() => { this.onOpenNotes(); })
        Button('旧版记录').height(36).fontSize(11).fontColor(PHONE_COLOR_PRIMARY_DEEP)
          .backgroundColor(PHONE_COLOR_SOFT).onClick(() => { this.onOpenLegacy(); })
      }
    }
    .width('100%').padding(12).backgroundColor(PHONE_COLOR_CARD).borderRadius(14)
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  compose_card() {
    Column({ space: 6 }) {
      Row() {
        Text(this.context_label()).fontSize(10).fontColor(PHONE_COLOR_MUTED).layoutWeight(1)
          .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
        if (this.contextParentRequestId.length > 0 || this.independentQuestion) {
          Text('恢复自动续接').fontSize(10).fontColor(PHONE_COLOR_PRIMARY_DEEP).padding(4)
            .onClick(() => { this.contextParentRequestId = ''; this.independentQuestion = false; this.save_draft(); })
        }
      }.width('100%')
      TextArea({ text: this.prompt, placeholder: '继续问一个具体的问题…', controller: this.editorController })
        .width('100%').height(62).fontSize(14).maxLength(V2_PROMPT_MAX)
        .fontColor(PHONE_COLOR_TITLE).backgroundColor(PHONE_COLOR_BACKGROUND)
        .border({ width: 1, color: PHONE_COLOR_BORDER }).borderRadius(PHONE_RADIUS_CONTROL).padding(10)
        .onFocus(() => { this.editorFocused = true; this.onComposingChanged(true); })
        .onBlur(() => { this.editorFocused = false; this.onComposingChanged(false); })
        .onChange((value: string) => { this.prompt = value; this.save_draft(); })
      Row({ space: 6 }) {
        Button('提示').height(36).fontSize(12)
          .fontColor(this.fullSolution ? PHONE_COLOR_PRIMARY_DEEP : PHONE_COLOR_WHITE)
          .backgroundColor(this.fullSolution ? PHONE_COLOR_SOFT : PHONE_COLOR_PRIMARY)
          .onClick(() => { this.fullSolution = false; this.save_draft(); })
        Button('完整解答').height(36).fontSize(12)
          .fontColor(this.fullSolution ? PHONE_COLOR_WHITE : PHONE_COLOR_PRIMARY_DEEP)
          .backgroundColor(this.fullSolution ? PHONE_COLOR_PRIMARY : PHONE_COLOR_SOFT)
          .onClick(() => { this.fullSolution = true; this.save_draft(); })
        Blank()
        Text(this.prompt.length.toString() + '/' + V2_PROMPT_MAX.toString())
          .fontSize(9).fontColor(PHONE_COLOR_MUTED)
        Button(this.createIntent.requestId.length > 0 ?
          (this.createIntent.confirmedMissing ? '重发原问题' : '确认状态') : '发送')
          .height(40).fontSize(13).fontColor(PHONE_COLOR_WHITE).backgroundColor(PHONE_COLOR_PRIMARY)
          .enabled(this.can_send())
          .onClick(() => {
            if (this.createIntent.confirmedMissing) { this.resend_create(); }
            else if (this.createIntent.requestId.length > 0) { this.confirm_create(this.createIntent.requestId); }
            else { this.submit(); }
          })
      }.width('100%')
      if (this.failedPrompt.length > 0) {
        Button('恢复未发送的问题').height(32).fontSize(11).fontColor(PHONE_COLOR_DANGER)
          .backgroundColor(PHONE_COLOR_DANGER_SOFT).onClick(() => {
            const current: string = this.prompt; this.prompt = this.failedPrompt; this.failedPrompt = current;
            this.save_draft();
          })
      }
    }
    .width('100%').padding({ left: 14, right: 14, top: 9, bottom: 8 })
    .backgroundColor(PHONE_COLOR_CARD).border({ width: { top: 1 }, color: PHONE_COLOR_BORDER })
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  notice(text: string, foreground: string, background: string) {
    Text(text).fontSize(12).fontColor(foreground).lineHeight(18)
      .width('100%').padding(10).backgroundColor(background).borderRadius(PHONE_RADIUS_CONTROL)
  }

  @Builder
  request_card(requestId: string) {
    if (this.request_for(requestId) !== null) {
      Column({ space: 10 }) {
        Row() {
          Blank().width('12%')
          Column({ space: 5 }) {
            Text(this.request_for(requestId)?.prompt ?? '').fontSize(14).lineHeight(22).fontColor(PHONE_COLOR_WHITE)
              .width('100%').copyOption(CopyOptions.LocalDevice)
            Text((this.request_for(requestId)?.mode === V2TutorMode.FULL_SOLUTION ? '完整解答' : '提示') + ' · ' +
              format_question_time(this.request_for(requestId)?.createdAt ?? '')).fontSize(10).fontColor('#E4EEFF').width('100%')
          }
          .layoutWeight(1).padding(12).backgroundColor(PHONE_COLOR_PRIMARY)
          .borderRadius({ topLeft: 16, topRight: 5, bottomLeft: 16, bottomRight: 16 })
          .alignItems(HorizontalAlign.Start)
        }.width('100%')
        .onAreaChange((_oldArea: Area, newArea: Area) => {
          this.remember_user_height(requestId, Number(newArea.height));
        })
        Row() {
          Column({ space: 8 }) {
            Row({ space: 7 }) {
              if (this.request_active(requestId) && this.poll_allowed()) {
                LoadingProgress().width(18).height(18).color(PHONE_COLOR_PRIMARY)
              }
              Text(this.request_stage(requestId))
                .fontSize(12).fontColor(PHONE_COLOR_PRIMARY_DEEP).layoutWeight(1)
              if (phone_v2_verified(this.detail_for(requestId))) {
                Button(this.is_collapsed(requestId) ? '展开回答' : '收起')
                  .height(32).fontSize(10).fontColor(PHONE_COLOR_MUTED).backgroundColor(Color.Transparent)
                  .onClick(() => { this.toggle_answer(requestId); })
              }
            }.width('100%').accessibilityText(this.request_stage(requestId))
            this.selected_detail(requestId)
          }
          .layoutWeight(1).padding(12).backgroundColor(PHONE_COLOR_CARD)
          .borderRadius({ topLeft: 5, topRight: 16, bottomLeft: 16, bottomRight: 16 })
          .border({ width: 1, color: PHONE_COLOR_BORDER }).alignItems(HorizontalAlign.Start)
          Blank().width('4%')
        }.width('100%')
      }.width('100%')
    }
  }

  @Builder
  selected_detail(requestId: string) {
    if (this.request_for(requestId)?.status === V2TutorRequestStatus.WAITING_INPUT) {
      Text('请在平板前台打开本题，同步本轮作答。').fontSize(12).lineHeight(19).fontColor(PHONE_COLOR_MUTED)
      Button('检查同步').height(36).fontSize(11).fontColor(PHONE_COLOR_PRIMARY_DEEP)
        .backgroundColor(PHONE_COLOR_SOFT).onClick(() => { this.refresh_detail(requestId); })
    } else if (phone_v2_verified(this.detail_for(requestId))) {
      if (!this.is_collapsed(requestId)) { this.answer_body(requestId) }
      Row({ space: 6 }) {
        Button('针对这条追问').height(36).fontSize(11).fontColor(PHONE_COLOR_PRIMARY_DEEP)
          .backgroundColor(PHONE_COLOR_SOFT).onClick(() => {
            this.contextParentRequestId = requestId; this.independentQuestion = false; this.save_draft();
          })
        if (this.request_for(requestId)?.snapshotFileRef !== null) {
          Button('沿用此作答').height(36).fontSize(11).fontColor(PHONE_COLOR_PRIMARY_DEEP)
            .backgroundColor(PHONE_COLOR_SOFT).onClick(() => { this.reuseRequestId = requestId; this.save_draft(); })
        }
      }.width('100%')
      if (this.request_for(requestId)?.snapshotInput.kind === 'NEW_CAPTURE' && this.request_for(requestId)?.contextParentRequestId !== null &&
        this.request_for(requestId)?.contextParentRequestId !== undefined) {
        Text('本轮基于新作答；此前对话只作为问答上下文。').fontSize(10).fontColor(PHONE_COLOR_MUTED)
      }
    } else if (this.request_for(requestId)?.status === V2TutorRequestStatus.SUCCEEDED) {
      Text('回答详情尚未确认。保留原请求，点击重新读取。').fontSize(12).fontColor(PHONE_COLOR_MUTED)
      Button('读取回答').height(36).fontSize(11).fontColor(PHONE_COLOR_PRIMARY_DEEP)
        .backgroundColor(PHONE_COLOR_SOFT).onClick(() => { this.refresh_detail(requestId); })
    } else if (this.request_for(requestId)?.status === V2TutorRequestStatus.FAILED || this.request_for(requestId)?.status === V2TutorRequestStatus.TIMED_OUT) {
      Text(this.outcome_unknown(requestId) ?
        '上次调用结果未确认。手动重试可能再次生成讲解，请先检查状态。' : '本次没有发布讲解，可明确重试同一份作答。')
        .fontSize(12).lineHeight(19).fontColor(PHONE_COLOR_MUTED)
    } else if (this.request_for(requestId)?.status === V2TutorRequestStatus.CANCELLED) {
      Text('本轮已取消，草稿仍保留。').fontSize(12).fontColor(PHONE_COLOR_MUTED)
    }
    if (this.detailNetworkIds.includes(requestId) && this.errorRequestId !== requestId) {
      this.notice('连接暂时中断，正在确认原请求。', PHONE_COLOR_DANGER, PHONE_COLOR_DANGER_SOFT)
    }
    if (this.errorMessage.length > 0 && this.errorRequestId === requestId) {
      this.notice(this.errorRecoverable ? '连接暂时中断，正在确认原请求。' : this.errorMessage,
        PHONE_COLOR_DANGER, PHONE_COLOR_DANGER_SOFT)
      if (this.errorRecoverable) {
        Button('重新检查').height(36).fontSize(11).fontColor(PHONE_COLOR_PRIMARY_DEEP)
          .backgroundColor(PHONE_COLOR_SOFT).onClick(() => { this.refresh_detail(requestId); })
      }
    }
    if (this.request_retryable(requestId)) {
      Button(this.busy ? '处理中…' : (this.outcome_unknown(requestId) ? '明确再次生成' : '重试本次运行')).height(38).fontSize(12).fontColor(PHONE_COLOR_PRIMARY_DEEP)
        .backgroundColor(PHONE_COLOR_SOFT).enabled(!this.busy && !this.has_active_request())
        .onClick(() => { this.retry_current(requestId); })
    }
    if (this.request_active(requestId) && this.request_for(requestId)?.status !== V2TutorRequestStatus.CANCELLING) {
      Button('取消本次请求').height(36).fontSize(11).fontColor(PHONE_COLOR_DANGER)
        .backgroundColor(PHONE_COLOR_DANGER_SOFT).enabled(!this.busy).onClick(() => { this.cancel_current(requestId); })
    }
  }

  @Builder
  answer_body(requestId: string) {
    if (this.detail_for(requestId)?.result !== null && this.detail_for(requestId)?.result !== undefined) {
      QuestionMarkdownView({ bodyMarkdown: this.answer_markdown(requestId), fontSize: 15, maxLength: 65536,
        autoHeight: true, citations: this.answer_citations(requestId),
        onContentHeight: () => { this.schedule_restore(); },
        onCitation: (citationId: string) => { this.onOpenCitation(citationId); } }).width('100%')
      ForEach(this.visible_citations(requestId), (citation: V2Citation) => {
        Button('[' + citation.label + '] 查看来源').id('citation-' + citation.citationId)
          .height(36).fontSize(11).fontColor(PHONE_COLOR_PRIMARY_DEEP).backgroundColor(PHONE_COLOR_SOFT)
          .onClick(() => { citationFocusController.clear(); this.onOpenCitation(citation.citationId); })
      }, (citation: V2Citation) => citation.citationId)
    }
  }

  @Builder
  pending_message() {
    Column({ space: 10 }) {
      Row() {
        Blank().width('12%')
        Text(this.createIntent.prompt).fontSize(14).lineHeight(22).fontColor(PHONE_COLOR_WHITE)
          .layoutWeight(1).padding(12).backgroundColor(PHONE_COLOR_PRIMARY).borderRadius(16)
      }.width('100%')
      Row({ space: 8 }) {
        if (this.busy && this.poll_allowed()) { LoadingProgress().width(18).height(18).color(PHONE_COLOR_PRIMARY) }
        Text(this.busy ? '正在发送问题' : (this.createIntent.confirmedMissing ?
          '服务端未找到原请求，可手动重发原问题' : '正在确认原请求，未重复发送')).fontSize(12).fontColor(PHONE_COLOR_MUTED)
      }.width('100%').padding(12).backgroundColor(PHONE_COLOR_CARD).borderRadius(14)
      if (this.errorMessage.length > 0 && this.errorRequestId === this.createIntent.requestId) {
        this.notice(this.errorMessage, PHONE_COLOR_DANGER, PHONE_COLOR_DANGER_SOFT)
      }
    }.width('100%')
  }

  @Builder
  question_picker() {
    Column() {
      Blank().layoutWeight(1).width('100%').backgroundColor('#300F2444')
        .onClick(() => { this.questionPickerVisible = false; })
      Column({ space: 8 }) {
        Row() {
          Text('最近打开的题目').fontSize(16).fontWeight(FontWeight.Medium).fontColor(PHONE_COLOR_TITLE).layoutWeight(1)
          Button('关闭').height(36).fontSize(12).fontColor(PHONE_COLOR_MUTED)
            .backgroundColor(Color.Transparent).onClick(() => { this.questionPickerVisible = false; })
        }.width('100%')
        Text('仅显示本次登录已打开的题目；手机切题不会切换平板。')
          .fontSize(11).fontColor(PHONE_COLOR_MUTED).width('100%')
        List({ space: 6 }) {
          ForEach(this.recentQuestions, (item: PhoneV2RecentQuestion) => {
            ListItem() {
              Column({ space: 4 }) {
                Text((item.questionId === this.questionId ? '当前 · ' : '') + this.question_label(item.label, item.questionId))
                  .fontSize(14).fontColor(PHONE_COLOR_TITLE).maxLines(2).width('100%')
                Text(this.recent_label(item)).fontSize(11).fontColor(PHONE_COLOR_MUTED).width('100%')
              }
              .width('100%').padding(12).backgroundColor(item.questionId === this.questionId ?
                PHONE_COLOR_SOFT : PHONE_COLOR_BACKGROUND).borderRadius(12)
              .onClick(() => { this.switch_question(item.questionId); })
            }
          }, (item: PhoneV2RecentQuestion) => item.questionId)
        }.width('100%').layoutWeight(1).scrollBar(BarState.Off)
        Button('查看全部题目').height(42).width('100%').fontSize(13).fontColor(PHONE_COLOR_PRIMARY_DEEP)
          .backgroundColor(PHONE_COLOR_SOFT).onClick(() => {
            this.questionPickerVisible = false; this.onChooseQuestion();
          })
      }
      .width('100%').height('74%').padding(16).backgroundColor(PHONE_COLOR_CARD)
      .borderRadius({ topLeft: 20, topRight: 20 })
    }.width('100%').height('100%')
  }

  on_requests_changed(): void { this.messageDataSource.replace(this.requests); }

  private status_text(status: V2TutorRequestStatus): string {
    if (status === V2TutorRequestStatus.WAITING_INPUT) { return '等待作答'; }
    if (status === V2TutorRequestStatus.QUEUED) { return '排队中'; }
    if (status === V2TutorRequestStatus.RUNNING) { return '生成中'; }
    if (status === V2TutorRequestStatus.CANCELLING) { return '取消中'; }
    if (status === V2TutorRequestStatus.SUCCEEDED) { return '已完成'; }
    if (status === V2TutorRequestStatus.FAILED) { return '失败'; }
    if (status === V2TutorRequestStatus.TIMED_OUT) { return '超时'; }
    return '已取消';
  }

  /** LazyForEach 只捕获稳定 ID；每次渲染/点击都读取当前 @State，不能复用创建时的 DTO。 */
  private request_for(requestId: string): V2TutorRequest | null {
    return this.requests.find((request: V2TutorRequest) => request.requestId === requestId) ?? null;
  }

  private request_active(requestId: string): boolean {
    const request: V2TutorRequest | null = this.request_for(requestId);
    return request !== null && phone_v2_active(request.status);
  }

  private request_stage(requestId: string): string {
    const request: V2TutorRequest | null = this.request_for(requestId);
    return request === null ? '' : phone_v2_stage_text(request, this.detail_for(requestId));
  }

  private request_retryable(requestId: string): boolean {
    const request: V2TutorRequest | null = this.request_for(requestId);
    return request !== null && phone_v2_retryable(request);
  }

  private cancel_current(requestId: string): void {
    const request: V2TutorRequest | null = this.request_for(requestId);
    if (request !== null && phone_v2_active(request.status) && request.status !== V2TutorRequestStatus.CANCELLING) {
      this.cancel(request);
    }
  }

  private retry_current(requestId: string): void {
    const request: V2TutorRequest | null = this.request_for(requestId);
    if (request !== null && phone_v2_retryable(request) && !this.has_active_request()) { this.retry(request); }
  }

  private detail_for(requestId: string): V2TutorRequestDetail | null {
    for (let index: number = 0; index < this.details.length; index++) {
      if (this.details[index].request.requestId === requestId) { return this.details[index]; }
    }
    return this.detail !== null && this.detail.request.requestId === requestId ? this.detail : null;
  }

  private outcome_unknown(requestId: string): boolean {
    const detail: V2TutorRequestDetail | null = this.detail_for(requestId);
    return detail !== null && detail.run !== null && detail.run.runId === detail.request.currentRunId &&
      detail.run.reason === 'OUTCOME_UNKNOWN';
  }

  private answer_markdown(requestId: string): string { return this.detail_for(requestId)?.result?.bodyMarkdown ?? ''; }
  private answer_citations(requestId: string): V2Citation[] { return this.detail_for(requestId)?.result?.citations ?? []; }

  private visible_citations(requestId: string = ''): V2Citation[] {
    const detail: V2TutorRequestDetail | null = requestId.length > 0 ? this.detail_for(requestId) : this.detail;
    if (detail === null || detail.result === null) { return []; }
    const markerIds: string[] = citation_marker_ids(detail.result.bodyMarkdown, detail.result.citations);
    return detail.result.citations.filter((citation: V2Citation) => markerIds.includes(citation.citationId));
  }

  private is_collapsed(requestId: string): boolean { return this.collapsedRequestIds.includes(requestId); }

  private toggle_answer(requestId: string): void {
    this.capture_anchor();
    this.collapsedRequestIds = this.is_collapsed(requestId) ?
      this.collapsedRequestIds.filter((id: string) => id !== requestId) : this.collapsedRequestIds.concat([requestId]);
    this.save_draft();
    this.schedule_restore();
  }

  private toggle_question(): void {
    this.capture_anchor();
    if (!this.questionExpanded) {
      this.questionReturnAnchorId = this.anchorRequestId;
      this.questionReturnAnchorOffset = this.anchorOffset;
      this.questionExpanded = true;
      this.conversationScroller.scrollToIndex(0, false, ScrollAlign.START);
    } else {
      this.questionExpanded = false;
      if (this.questionReturnAnchorId.length > 0) {
        this.anchorRequestId = this.questionReturnAnchorId;
        this.anchorOffset = this.questionReturnAnchorOffset;
      }
      this.schedule_restore();
    }
    this.save_draft();
  }

  private open_question_picker(): void {
    this.capture_anchor(); this.save_draft(); this.stop_editing();
    this.recentQuestions = phone_v2_recent_questions(this.accountId);
    this.questionPickerVisible = true;
  }

  private question_label(summary: string, questionId: string): string {
    const title: string = question_summary_title(summary.trim(), questionId).trim();
    return title.length > 0 ? title : (questionId.length > 0 ? '题目 #' + questionId : '题目');
  }

  private recent_label(item: PhoneV2RecentQuestion): string {
    const labels: string[] = [];
    if (item.hasDraft) { labels.push('有草稿'); }
    if (item.active) { labels.push('请求进行中'); }
    if (item.hasAnswer) { labels.push('已有回答'); }
    if (!item.loaded) { labels.push('对话尚未查询'); }
    return labels.length > 0 ? labels.join(' · ') : '已打开';
  }

  private switch_question(questionId: string): void {
    this.questionPickerVisible = false;
    if (questionId === this.questionId) { return; }
    this.capture_anchor(); this.save_draft(); this.onSwitchQuestion(questionId);
  }

  private can_choose_independent(): boolean {
    return !this.independentQuestion && (this.context_unconfirmed() || this.contextParentRequestId.length > 0 ||
      phone_v2_context_parent(this.requests, this.details) !== null);
  }

  private choose_independent(): void {
    this.independentQuestion = true;
    this.contextParentRequestId = '';
    this.clear_error();
    this.save_draft();
  }

  private refresh_conversation(): void {
    this.refresh_list();
    this.load_question();
  }

  private context_unconfirmed(): boolean {
    if (this.independentQuestion) { return false; }
    if (this.nextCursor !== null && !this.requests.some((request: V2TutorRequest) =>
      request.status === V2TutorRequestStatus.SUCCEEDED)) { return true; }
    return phone_v2_context_unconfirmed(this.requests, this.details, this.contextParentRequestId, false);
  }

  private context_label(): string {
    if (this.independentQuestion) { return '本轮独立提问，不携带历史问答'; }
    if (this.context_unconfirmed()) { return '续接上下文待确认'; }
    if (this.contextParentRequestId.length > 0) { return '针对所选回答追问'; }
    return phone_v2_context_parent(this.requests, this.details) !== null ?
      '接着本题最近已确认的回答' : '本题新问题';
  }

  private can_send(): boolean {
    if (this.busy) { return false; }
    if (this.createIntent.requestId.length > 0) { return true; }
    return this.loadedOnce && !this.has_active_request() && !this.context_unconfirmed() &&
      this.prompt.trim().length > 0 && this.prompt.length <= V2_PROMPT_MAX;
  }

  private pending_local(): boolean {
    return this.createIntent.requestId.length > 0 && !this.requests.some((request: V2TutorRequest) =>
      request.requestId === this.createIntent.requestId);
  }

  private capture_anchor(): void {
    if (this.restoreTimer !== -1 || this.requests.length === 0) { return; }
    const index: number = Math.max(0, Math.min(this.firstVisibleIndex - 1, this.requests.length - 1));
    try {
      const rect: RectResult = this.conversationScroller.getItemRect(index + 1);
      if (!Number.isFinite(rect.y) || rect.height <= 0) { return; }
      this.anchorRequestId = this.requests[index].requestId;
      // START 对齐内容内缘；getItemRect.y 含 List 顶部 padding，保存时统一坐标原点。
      this.anchorOffset = rect.y - this.listTopPadding;
    } catch (error) {}
  }

  private cancel_restore(): void {
    this.restoreGeneration++;
    if (this.restoreTimer !== -1) { clearTimeout(this.restoreTimer); this.restoreTimer = -1; }
  }

  private schedule_restore(): void {
    if (this.anchorRequestId.length === 0 || !this.alive || this.restoreTimer !== -1) { return; }
    const generation: number = this.restoreGeneration;
    const questionId: string = this.questionId;
    this.restoreTimer = setTimeout(() => {
      this.restoreTimer = -1;
      if (!this.alive || generation !== this.restoreGeneration || this.questionId !== questionId) { return; }
      for (let index: number = 0; index < this.requests.length; index++) {
        if (this.requests[index].requestId === this.anchorRequestId) {
          this.conversationScroller.scrollToIndex(index + 1, false, ScrollAlign.START,
            { extraOffset: LengthMetrics.vp(this.anchorOffset === 0 ? 0 : -this.anchorOffset) });
          return;
        }
      }
    }, 0);
  }

  private remember_user_height(requestId: string, height: number): void {
    if (!Number.isFinite(height) || height <= 0) { return; }
    this.userMessageHeights.set(requestId, height + 10);
    if (this.pendingRevealRequestId === requestId) {
      this.pendingRevealRequestId = '';
      this.anchorOffset = -(height + 10);
      this.schedule_restore();
    }
  }

  private reveal_answer(requestId: string): void {
    this.newAnswerRequestId = '';
    this.anchorRequestId = requestId;
    const height: number | undefined = this.userMessageHeights.get(requestId);
    this.pendingRevealRequestId = height === undefined ? requestId : '';
    this.anchorOffset = height === undefined ? 0 : -height;
    this.followLatest = false;
    this.schedule_restore();
  }

  private async hydrate_details(): Promise<void> {
    if (this.hydrating) { return; }
    this.hydrating = true;
    const generation: number = ++this.hydrateGeneration;
    const seq: number = this.seq;
    const ids: string[] = this.requests.map((request: V2TutorRequest) => request.requestId);
    try {
      for (let index: number = 0; index < ids.length; index++) {
        if (seq !== this.seq || generation !== this.hydrateGeneration || !this.alive) { return; }
        const existing: V2TutorRequestDetail | null = this.detail_for(ids[index]);
        const request: V2TutorRequest | undefined = this.requests.find((item: V2TutorRequest) => item.requestId === ids[index]);
        if (existing === null || request !== undefined && existing.request.revision < request.revision) {
          await this.refresh_detail(ids[index]);
        }
      }
    } finally { if (generation === this.hydrateGeneration) { this.hydrating = false; } }
  }

  private has_active_request(): boolean {
    for (let index: number = 0; index < this.requests.length; index++) {
      if (phone_v2_active(this.requests[index].status)) { return true; }
    }
    return false;
  }

  private poll_allowed(): boolean {
    return this.alive && this.visible && this.foreground;
  }

  private stop_poll(): void {
    this.pollGeneration++;
    this.pollBusy = false;
    if (this.pollTimer !== -1) { clearInterval(this.pollTimer); this.pollTimer = -1; }
  }

  private start_poll(): void {
    if (!this.poll_allowed() || this.pollTimer !== -1) { return; }
    this.pollTimer = setInterval(() => { this.poll_selected(); }, V2_POLL_MS);
  }

  private valid_context(seq: number, token: string, accountId: string): boolean {
    const user: UserProfile | null = authSession.read_user();
    return this.alive && seq === this.seq && authSession.matches_access_token(token) &&
      user !== null && user.accountId === accountId && this.accountId === accountId;
  }

  private session(): string | null {
    const token: string | null = authSession.read_access_token();
    const user: UserProfile | null = authSession.read_user();
    if (token === null || user === null) { this.onUnauthorized(''); return null; }
    if (this.accountId.length > 0 && this.accountId !== user.accountId) {
      this.loadedQuestionId = '';
      this.accountId = user.accountId;
      this.on_question_changed();
      return null;
    }
    this.accountId = user.accountId;
    this.adopt_intent();
    return token;
  }

  private stop_editing(): void {
    this.editorController.stopEditing();
    this.editorFocused = false;
    this.onComposingChanged(false);
  }

  private save_draft(): void {
    const user: UserProfile | null = authSession.read_user();
    if (!authSession.has_valid_session() || user === null || user.accountId !== this.accountId ||
      this.accountId.length === 0 || this.loadedQuestionId.length === 0) { return; }
    const draft: PhoneV2Draft = phone_v2_draft_for(this.accountId, this.loadedQuestionId);
    draft.prompt = this.prompt;
    draft.fullSolution = this.fullSolution;
    draft.reuseRequestId = this.reuseRequestId;
    draft.selectedRequestId = this.selectedRequestId;
    draft.contextParentRequestId = this.contextParentRequestId;
    draft.independentQuestion = this.independentQuestion;
    draft.questionBody = this.questionBody;
    draft.questionLabel = this.questionLabel;
    draft.questionExpanded = this.questionExpanded;
    draft.questionReturnAnchorId = this.questionReturnAnchorId;
    draft.questionReturnAnchorOffset = this.questionReturnAnchorOffset;
    draft.failedPrompt = this.failedPrompt;
    draft.collapsedRequestIds = this.collapsedRequestIds.slice();
    draft.requests = this.requests.slice();
    draft.details = this.details.slice();
    draft.nextCursor = this.nextCursor;
    draft.loadedOnce = this.loadedOnce;
    draft.anchorRequestId = this.anchorRequestId;
    draft.anchorOffset = this.anchorOffset;
    draft.followLatest = this.followLatest;
    this.onSessionSummary(this.loadedQuestionId, this.questionLabel.length > 0 ? this.questionLabel :
      '题目 #' + this.loadedQuestionId, this.prompt);
  }

  private adopt_draft(): void {
    if (this.accountId.length === 0 || this.questionId.length === 0) { return; }
    const draft: PhoneV2Draft = phone_v2_draft_for(this.accountId, this.questionId);
    this.prompt = draft.prompt;
    this.fullSolution = draft.fullSolution;
    this.reuseRequestId = draft.reuseRequestId;
    this.contextParentRequestId = draft.contextParentRequestId;
    this.independentQuestion = draft.independentQuestion;
    this.questionBody = draft.questionBody;
    this.questionLabel = draft.questionLabel;
    this.questionExpanded = draft.questionExpanded;
    this.questionReturnAnchorId = draft.questionReturnAnchorId;
    this.questionReturnAnchorOffset = draft.questionReturnAnchorOffset;
    this.failedPrompt = draft.failedPrompt;
    this.collapsedRequestIds = draft.collapsedRequestIds.slice();
    this.requests = draft.requests.slice();
    this.details = draft.details.slice();
    this.nextCursor = draft.nextCursor;
    this.loadedOnce = draft.loadedOnce;
    this.anchorRequestId = draft.anchorRequestId;
    this.anchorOffset = draft.anchorOffset;
    this.followLatest = draft.followLatest;
    this.selectedRequestId = draft.selectedRequestId;
    this.onSessionSummary(this.questionId, this.questionLabel.length > 0 ? this.questionLabel :
      '题目 #' + this.questionId, this.prompt);
  }

  private valid_question_context(generation: number, token: string, accountId: string, questionId: string): boolean {
    const user: UserProfile | null = authSession.read_user();
    return this.alive && generation === this.questionLoadSeq && this.questionId === questionId &&
      authSession.matches_access_token(token) && user !== null && user.accountId === accountId &&
      this.accountId === accountId;
  }

  private async load_question(): Promise<void> {
    const token: string | null = this.session();
    if (token === null || this.questionId.length === 0) { return; }
    const accountId: string = this.accountId;
    const generation: number = ++this.questionLoadSeq;
    const questionId: string = this.questionId;
    try {
      const result: AuthResult<QuestionDetail> = await this.learningApi.getQuestion(token, questionId);
      if (!this.valid_question_context(generation, token, accountId, questionId)) { return; }
      if (result.isSuccess && result.data !== null && result.data.questionId === questionId) {
        this.questionDetail = result.data;
        this.questionBody = result.data.bodyMarkdown;
        this.questionLabel = result.data.summary.trim().split('\n')[0].substring(0, 80);
        this.save_draft();
        this.onSessionSummary(questionId, this.questionLabel, this.prompt);
      } else if (result.error !== null && result.error.is_unauthorized()) { this.failed(result.error, token); }
    } catch (error) {}
  }

  private adopt_intent(): void {
    if (this.accountId.length === 0 || this.questionId.length === 0) { return; }
    const stored: PhoneV2CreateIntent = phone_v2_intent_for(this.accountId, this.questionId);
    if (this.createIntent !== stored) {
      this.createIntent = stored;
      if (stored.requestId.length > 0) {
        this.message = '上次发问结果待确认，将继续查询原 requestId';
      }
    }
  }

  private failed(error: AuthError | null, token: string): string {
    if (error !== null && error.is_unauthorized()) {
      authSession.clear_session_if_token(token);
      this.onUnauthorized(token);
      return '';
    }
    return error === null ? '请求失败，请稍后重试' : error.message;
  }

  private set_error(text: string, source: string = 'action', requestId: string = '',
    recoverable: boolean = false, actionKind: string = '', targetRunId: string | null = null): void {
    // 后续 GET 不能盖住创建/取消/重试失败；只有显式操作或上下文重置能解除。
    if (source !== 'action' && this.errorSource === 'action' && this.errorMessage.length > 0) { return; }
    this.errorMessage = text;
    this.errorSource = text.length > 0 ? source : '';
    this.errorRequestId = requestId;
    this.errorActionKind = actionKind;
    this.errorTargetRunId = targetRunId;
    this.errorSelectionEpoch = this.selectionEpoch;
    this.errorRecoverable = recoverable;
    this.errorVersion++;
  }

  private clear_error(): void { this.set_error(''); }

  private refresh_failed(error: AuthError | null, token: string, source: string, requestId: string): void {
    this.set_error(this.failed(error, token), source, requestId, error !== null && error.is_network());
  }

  private clear_refresh_error(source: string, requestId: string, version: number): void {
    if (this.errorRecoverable && this.errorSource === source && this.errorRequestId === requestId &&
      this.errorVersion === version &&
      (source !== 'detail' || this.errorSelectionEpoch === this.selectionEpoch)) {
      this.clear_error();
    }
  }

  private action_failed(error: AuthError | null, token: string, kind: string,
    requestId: string, targetRunId: string | null): void {
    this.set_error(this.failed(error, token), 'action', requestId, false, kind, targetRunId);
  }

  private clear_action_error(kind: string, requestId: string, targetRunId: string | null, version: number): void {
    if (this.errorSource === 'action' && this.errorActionKind === kind && this.errorRequestId === requestId &&
      this.errorTargetRunId === targetRunId && this.errorVersion === version) {
      this.clear_error();
    }
  }

  private async refresh_list(): Promise<void> {
    const token: string | null = this.session();
    if (token === null || this.questionId.length === 0) { return; }
    const accountId: string = this.accountId;
    const seq: number = this.seq;
    const questionId: string = this.questionId;
    const errorVersion: number = this.errorVersion;
    const listSeq: number = ++this.listSeq;
    this.loading = true;
    try {
      const result: AuthResult<V2TutorRequestPage> = await this.api.listRequests(token, questionId);
      if (!this.valid_context(seq, token, accountId) || this.questionId !== questionId || listSeq !== this.listSeq) { return; }
      if (!result.isSuccess || result.data === null) {
        this.refresh_failed(result.error, token, 'list', '');
        return;
      }
      this.clear_refresh_error('list', '', errorVersion);
      this.capture_anchor();
      this.requests = phone_v2_chronological(phone_v2_merge_requests(this.requests, result.data.items));
      this.loadedOnce = true;
      this.nextCursor = result.data.nextCursor;
      if (this.createIntent.requestId.length > 0) {
        for (let index: number = 0; index < this.requests.length; index++) {
          if (this.requests[index].requestId === this.createIntent.requestId &&
            (this.requests[index].contextParentRequestId ?? null) === this.createIntent.contextParentRequestId) {
            this.on_created(this.requests[index]);
            break;
          }
        }
      }
      if (this.selectedRequestId.length === 0 && this.requests.length > 0) {
        this.selectionEpoch++;
        this.selectedRequestId = this.requests[0].requestId;
      }
      this.hydrate_details();
      this.save_draft();
      this.schedule_restore();
      if (this.createIntent.requestId.length > 0) { this.confirm_create(this.createIntent.requestId); }
      this.start_poll();
    } catch (error) {
      if (this.valid_context(seq, token, accountId) && this.questionId === questionId && listSeq === this.listSeq) {
        this.set_error('暂时无法读取新版辅导记录', 'list', '', true);
      }
    } finally {
      if (listSeq === this.listSeq) { this.loading = false; }
    }
  }

  private async load_more(): Promise<void> {
    if (this.loading || this.nextCursor === null) { return; }
    const token: string | null = this.session();
    if (token === null) { return; }
    const cursor: string = this.nextCursor;
    const accountId: string = this.accountId;
    const seq: number = this.seq;
    const questionId: string = this.questionId;
    const errorVersion: number = this.errorVersion;
    const listSeq: number = ++this.listSeq;
    this.loading = true;
    try {
      const result: AuthResult<V2TutorRequestPage> = await this.api.listRequests(token, questionId, cursor);
      if (!this.valid_context(seq, token, accountId) || this.questionId !== questionId || listSeq !== this.listSeq) { return; }
      if (!result.isSuccess || result.data === null) {
        this.refresh_failed(result.error, token, 'list', cursor);
        return;
      }
      this.clear_refresh_error('list', cursor, errorVersion);
      this.capture_anchor();
      this.requests = phone_v2_chronological(phone_v2_merge_requests(this.requests, result.data.items));
      this.nextCursor = result.data.nextCursor;
      this.hydrate_details();
      this.save_draft();
      this.schedule_restore();
    } catch (error) {
      if (this.valid_context(seq, token, accountId) && this.questionId === questionId && listSeq === this.listSeq) {
        this.set_error('读取更多记录失败，请稍后重试', 'list', cursor, true);
      }
    } finally {
      if (listSeq === this.listSeq) { this.loading = false; }
    }
  }

  private select_request(requestId: string): void {
    if (this.errorSource === 'detail' && this.errorRecoverable) { this.clear_error(); }
    this.selectionEpoch++;
    if (this.selectedRequestId === requestId) {
      this.selectedRequestId = '';
      this.detail = null;
      this.save_draft();
      return;
    }
    this.selectedRequestId = requestId;
    this.detail = null;
    this.save_draft();
    this.refresh_detail(requestId);
  }

  private async refresh_detail(requestId: string): Promise<void> {
    const token: string | null = this.session();
    if (token === null) { return; }
    const seq: number = this.seq;
    const accountId: string = this.accountId;
    const questionId: string = this.questionId;
    const selectionEpoch: number = this.selectionEpoch;
    const detailSeq: number = (this.detailSeqs.get(requestId) ?? 0) + 1;
    this.detailSeqs.set(requestId, detailSeq);
    const errorVersion: number = this.errorVersion;
    try {
      const result: AuthResult<V2TutorRequestDetail> = await this.api.getRequest(token, questionId, requestId);
      if (!this.valid_context(seq, token, accountId) || this.questionId !== questionId ||
        this.detailSeqs.get(requestId) !== detailSeq) { return; }
      if (!result.isSuccess || result.data === null) {
        if (result.error !== null && result.error.is_network() && !this.detailNetworkIds.includes(requestId)) {
          this.detailNetworkIds = this.detailNetworkIds.concat([requestId]);
        }
        if (result.error !== null && result.error.is_unauthorized()) {
          this.failed(result.error, token);
        } else if (selectionEpoch === this.selectionEpoch && this.selectedRequestId === requestId) {
          this.refresh_failed(result.error, token, 'detail', requestId);
        }
        return;
      }
      if (result.data.request.questionId !== questionId || result.data.request.requestId !== requestId) { return; }
      this.detailNetworkIds = this.detailNetworkIds.filter((id: string) => id !== requestId);
      if (selectionEpoch === this.selectionEpoch && this.selectedRequestId === requestId) {
        this.clear_refresh_error('detail', requestId, errorVersion);
      }
      // 全部成功详情各自缓存；过期选择不能清除另一条反馈，但也不能丢掉已发布正文。
      this.receive_detail(result.data);
    } catch (error) {
      if (this.valid_context(seq, token, accountId) && this.questionId === questionId &&
        this.detailSeqs.get(requestId) === detailSeq && !this.detailNetworkIds.includes(requestId)) {
        this.detailNetworkIds = this.detailNetworkIds.concat([requestId]);
      }
      if (this.valid_context(seq, token, accountId) && this.questionId === questionId &&
        this.detailSeqs.get(requestId) === detailSeq && selectionEpoch === this.selectionEpoch &&
        this.selectedRequestId === requestId) {
        this.set_error('网络暂不可用，恢复后按原 requestId 查询', 'detail', requestId, true);
      }
    }
  }

  private accept_detail(incoming: V2TutorRequestDetail): void {
    const prior: PhoneV2ViewState = {
      requests: this.requests, detail: this.detail, selectedRequestId: this.selectedRequestId
    };
    const next: PhoneV2ViewState = phone_v2_apply_detail(prior, incoming);
    if (next === prior) { return; }
    this.detail = next.detail;
    this.requests = next.requests;
    if (!this.has_active_request()) { this.stop_poll(); }
  }

  /** 所有轮次独立缓存，始终保留 request/run 的原有单调合并保护。 */
  private receive_detail(incoming: V2TutorRequestDetail): void {
    let existing: V2TutorRequestDetail[] = this.details;
    if (this.detail !== null && !existing.some((item: V2TutorRequestDetail) =>
      item.request.requestId === this.detail?.request.requestId)) { existing = existing.concat([this.detail]); }
    const previous: V2TutorRequestDetail | null = this.detail_for(incoming.request.requestId);
    const previousSummary: V2TutorRequest | undefined = this.requests.find((request: V2TutorRequest) =>
      request.requestId === incoming.request.requestId);
    const wasActive: boolean = previous !== null && phone_v2_active(previous.request.status) ||
      previousSummary !== undefined && phone_v2_active(previousSummary.status);
    const next: V2TutorRequestDetail[] = phone_v2_merge_details(existing, incoming, this.requests);
    if (next === existing) { return; }
    this.capture_anchor();
    const follow: boolean = this.followLatest || this.anchorRequestId === incoming.request.requestId &&
      Math.abs(this.anchorOffset + (this.userMessageHeights.get(incoming.request.requestId) ?? 0)) < 2;
    this.details = next;
    this.requests = phone_v2_chronological(phone_v2_merge_requests(this.requests, [incoming.request]));
    if (this.selectedRequestId === incoming.request.requestId) { this.detail = incoming; }
    if (previous === null || previous.run?.revision !== incoming.run?.revision ||
      JSON.stringify(previous.run?.steps) !== JSON.stringify(incoming.run?.steps) ||
      previous.result?.resultId !== incoming.result?.resultId || previous.result?.bodyMarkdown !== incoming.result?.bodyMarkdown) {
      this.messageDataSource.changed(incoming.request.requestId);
    }
    if (!this.has_active_request()) { this.stop_poll(); }
    if (!phone_v2_active(incoming.request.status) && this.createIntent.requestId.length === 0) { this.message = ''; }
    if (phone_v2_verified(incoming) && wasActive) {
      if (follow) { this.reveal_answer(incoming.request.requestId); }
      else { this.newAnswerRequestId = incoming.request.requestId; this.schedule_restore(); }
    } else { this.schedule_restore(); }
    this.save_draft();
  }

  private poll_selected(): void {
    if (!this.poll_allowed() || this.pollBusy) { return; }
    let requestId: string = '';
    for (let index: number = 0; index < this.requests.length; index++) {
      if (phone_v2_active(this.requests[index].status)) {
        requestId = this.requests[index].requestId;
        break;
      }
    }
    if (requestId.length === 0) { this.stop_poll(); return; }
    this.pollBusy = true;
    const generation: number = this.pollGeneration;
    this.refresh_detail(requestId).finally(() => {
      if (generation === this.pollGeneration) { this.pollBusy = false; }
    });
  }

  private async submit(): Promise<void> {
    if (this.busy || !this.loadedOnce || this.has_active_request()) { return; }
    if (this.createIntent.requestId.length > 0) { this.confirm_create(this.createIntent.requestId); return; }
    if (this.context_unconfirmed()) {
      this.set_error('请先同步并确认要续接的成功回答，或明确选择独立提问。');
      this.hydrate_details(); return;
    }
    const token: string | null = this.session();
    if (token === null) { return; }
    const accountId: string = this.accountId;
    const questionId: string = this.questionId;
    const text: string = this.prompt.trim();
    if (text.length === 0 || text.length > V2_PROMPT_MAX) { return; }
    const mode: V2TutorMode = this.fullSolution ? V2TutorMode.FULL_SOLUTION : V2TutorMode.HINT;
    const parent: string | null = phone_v2_context_parent(this.requests, this.details,
      this.contextParentRequestId, this.independentQuestion);
    this.createIntent.requestId = util.generateRandomUUID(true).toLowerCase();
    this.createIntent.prompt = text;
    this.createIntent.mode = mode;
    this.createIntent.snapshotKind = this.reuseRequestId.length > 0 ? 'REUSE_REQUEST' : 'NEW_CAPTURE';
    this.createIntent.sourceRequestId = this.reuseRequestId;
    this.createIntent.contextParentRequestId = parent;
    this.prompt = '';
    this.save_draft();
    await this.dispatch_create(token, accountId, questionId);
  }

  private async resend_create(): Promise<void> {
    if (this.busy || !this.createIntent.confirmedMissing || this.createIntent.requestId.length === 0) { return; }
    const token: string | null = this.session();
    if (token === null) { return; }
    await this.dispatch_create(token, this.accountId, this.questionId);
  }

  private async dispatch_create(token: string, accountId: string, questionId: string): Promise<void> {
    const requestId: string = this.createIntent.requestId;
    const text: string = this.createIntent.prompt;
    const mode: V2TutorMode = this.createIntent.mode === V2TutorMode.FULL_SOLUTION ? V2TutorMode.FULL_SOLUTION : V2TutorMode.HINT;
    const parent: string | null = this.createIntent.contextParentRequestId;
    const snapshotInput: V2SnapshotInput = this.createIntent.snapshotKind === 'REUSE_REQUEST' ?
      { kind: 'REUSE_REQUEST', sourceRequestId: this.createIntent.sourceRequestId } : { kind: 'NEW_CAPTURE' };
    this.createIntent.confirmedMissing = false;
    const seq: number = ++this.seq;
    this.hydrating = false;
    this.hydrateGeneration++;
    const toastScope: number | undefined = this.toast?.captureScope();
    this.capture_anchor();
    this.busy = true;
    if (this.errorSource !== 'action') { this.clear_error(); }
    const errorVersion: number = this.errorVersion;
    this.save_draft();
    try {
      const result: AuthResult<V2TutorRequest> = await this.api.createRequest(token, questionId,
        requestId, text, mode, snapshotInput, parent);
      if (!this.valid_context(seq, token, accountId) || this.questionId !== questionId) { return; }
      if (!result.isSuccess || result.data === null) {
        if (result.error !== null && result.error.is_unauthorized()) { this.failed(result.error, token); return; }
        if (result.error !== null && result.error.httpStatus >= 400 && result.error.httpStatus < 500 &&
          result.error.httpStatus !== 408) {
          if (this.prompt.length === 0) { this.prompt = text; } else { this.failedPrompt = text; }
          this.createIntent.clear();
          this.message = '';
          this.action_failed(result.error, token, 'create', requestId, null);
          this.save_draft();
        } else {
          this.message = '发送结果尚未确认，正在检查原请求。草稿可继续编辑。';
          this.confirm_create(requestId);
        }
        return;
      }
      if (result.data.questionId !== questionId || result.data.requestId !== requestId ||
        (result.data.contextParentRequestId ?? null) !== parent) {
        this.message = '服务端尚未确认本轮上下文，保留原请求继续核对。';
        this.confirm_create(requestId); return;
      }
      this.clear_action_error('create', requestId, null, errorVersion);
      this.on_created(result.data);
      this.toast?.show({ key: 'phone-v2-create', type: 'success', message: '辅导请求已发送' }, toastScope);
    } catch (error) {
      if (this.valid_context(seq, token, accountId) && this.questionId === questionId) {
        this.message = '发送结果尚未确认，正在检查原请求。'; this.confirm_create(requestId);
      }
    } finally { if (seq === this.seq) { this.busy = false; } }
  }

  private on_created(request: V2TutorRequest, fetchDetail: boolean = true): void {
    this.createIntent.clear();
    const next: PhoneV2ViewState = phone_v2_apply_created({
      requests: this.requests, detail: this.detail, selectedRequestId: this.selectedRequestId
    }, request);
    this.requests = phone_v2_chronological(next.requests);
    if (this.selectedRequestId !== next.selectedRequestId) { this.selectionEpoch++; }
    this.selectedRequestId = next.selectedRequestId;
    this.detail = next.detail;
    this.save_draft();
    this.message = '';
    if (this.followLatest) { this.reveal_answer(request.requestId); }
    if (fetchDetail && this.detail === null) { this.refresh_detail(request.requestId); }
    this.start_poll();
  }

  private async confirm_create(requestId: string): Promise<void> {
    const token: string | null = this.session();
    if (token === null || this.createIntent.requestId !== requestId) { return; }
    const seq: number = this.seq;
    const questionId: string = this.questionId;
    const confirmSeq: number = ++this.confirmSeq;
    const accountId: string = this.accountId;
    try {
      const result: AuthResult<V2TutorRequestDetail> = await this.api.getRequest(token, questionId, requestId);
      if (!this.valid_context(seq, token, accountId) || this.questionId !== questionId ||
        confirmSeq !== this.confirmSeq || this.createIntent.requestId !== requestId) { return; }
      if (result.isSuccess && result.data !== null) {
        if (result.data.request.questionId !== questionId || result.data.request.requestId !== requestId) { return; }
        this.createIntent.confirmedMissing = false;
        if ((result.data.request.contextParentRequestId ?? null) !== this.createIntent.contextParentRequestId) {
          this.message = '服务端未确认所选上下文，请同步后核对原请求。'; return;
        }
        this.on_created(result.data.request, false);
        this.receive_detail(result.data);
      } else if (result.error !== null && result.error.is_unauthorized()) {
        this.failed(result.error, token);
      } else if (result.error !== null && result.error.httpStatus === 404) {
        this.createIntent.confirmedMissing = true;
        this.message = '服务端未找到原请求。可手动重发原问题，仍使用相同标识、作答来源和上下文。';
      } else {
        this.message = '尚未确认原请求，请点击“确认状态”继续查询；不会自动重发';
      }
    } catch (error) {
      if (this.valid_context(seq, token, accountId) && this.questionId === questionId &&
        confirmSeq === this.confirmSeq && this.createIntent.requestId === requestId) {
        this.message = '尚未确认原请求，请点击“确认状态”继续查询；不会自动重发';
      }
    }
  }

  private async cancel(request: V2TutorRequest): Promise<void> {
    if (this.busy) { return; }
    const token: string | null = this.session();
    if (token === null) { return; }
    const seq: number = ++this.seq;
    this.hydrating = false;
    this.hydrateGeneration++;
    const accountId: string = this.accountId;
    const questionId: string = this.questionId;
    const requestId: string = request.requestId;
    const targetRunId: string | null = request.currentRunId;
    const errorVersion: number = this.errorVersion;
    const toastScope: number | undefined = this.toast?.captureScope();
    this.busy = true;
    try {
      const result: AuthResult<V2TutorRequestDetail> = await this.api.cancelRequest(token, questionId,
        requestId, targetRunId);
      if (!this.valid_context(seq, token, accountId) || this.questionId !== questionId) { return; }
      if (result.isSuccess && result.data !== null) {
        if (result.data.request.questionId !== questionId || result.data.request.requestId !== requestId) { return; }
        this.clear_action_error('cancel', requestId, targetRunId, errorVersion);
        this.receive_detail(result.data);
        this.toast?.show({ key: 'phone-v2-cancel', type: 'info', message: '本次辅导状态已更新' }, toastScope);
      }
      else { this.action_failed(result.error, token, 'cancel', requestId, targetRunId); this.refresh_detail(requestId); }
    } catch (error) {
      if (this.valid_context(seq, token, accountId) && this.questionId === questionId) {
        this.message = '取消结果未知，按原 requestId 查询状态';
        this.refresh_detail(requestId);
      }
    } finally {
      if (seq === this.seq) { this.busy = false; }
    }
  }

  private async retry(request: V2TutorRequest): Promise<void> {
    if (this.busy || !phone_v2_retryable(request) || request.currentRunId === null) { return; }
    const token: string | null = this.session();
    if (token === null) { return; }
    const accountId: string = this.accountId;
    const questionId: string = this.questionId;
    const requestId: string = request.requestId;
    const errorVersion: number = this.errorVersion;
    const targetRunId: string = request.currentRunId;
    if (this.retryKey.length === 0 || this.retryTargetRunId !== targetRunId) {
      this.retryKey = util.generateRandomUUID(true).toLowerCase();
      this.retryTargetRunId = targetRunId;
    }
    const seq: number = ++this.seq;
    this.hydrating = false;
    this.hydrateGeneration++;
    const toastScope: number | undefined = this.toast?.captureScope();
    this.busy = true;
    try {
      const result: AuthResult<V2TutorRequestDetail> = await this.api.retryRequest(token, questionId,
        requestId, targetRunId, this.retryKey);
      if (!this.valid_context(seq, token, accountId) || this.questionId !== questionId) { return; }
      if (result.isSuccess && result.data !== null) {
        if (result.data.request.questionId !== questionId || result.data.request.requestId !== requestId) { return; }
        this.clear_action_error('retry', requestId, targetRunId, errorVersion);
        this.retryKey = '';
        this.retryTargetRunId = '';
        this.receive_detail(result.data);
        this.start_poll();
        this.toast?.show({ key: 'phone-v2-retry', type: 'info', message: '已重试本次辅导' }, toastScope);
      } else {
        this.action_failed(result.error, token, 'retry', requestId, targetRunId);
        this.refresh_detail(requestId);
      }
    } catch (error) {
      if (this.valid_context(seq, token, accountId) && this.questionId === questionId) {
        this.message = '重试结果未知，按原 requestId 查询；不会自动再次发起重试';
        this.refresh_detail(requestId);
      }
    } finally {
      if (seq === this.seq) { this.busy = false; }
    }
  }
}
