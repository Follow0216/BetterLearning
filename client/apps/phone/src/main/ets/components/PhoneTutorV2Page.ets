import { util } from '@kit.ArkTS';
import {
  authSession, AuthError, AuthResult, citationFocusController, citation_marker_ids,
  LearningApi, LearningV2Api, QuestionDetail, QuestionMarkdownView, ToastStore, UserProfile,
  V2Citation, V2TutorMode, V2TutorRequest, V2TutorRequestDetail, V2TutorRequestPage, V2TutorRequestStatus,
  V2SnapshotInput
} from 'common';
import {
  phone_v2_active, phone_v2_apply_created, phone_v2_apply_detail, phone_v2_draft_for,
  phone_v2_intent_for, phone_v2_merge_requests, phone_v2_retryable, PhoneV2CreateIntent,
  PhoneV2Draft, PhoneV2ViewState
} from '../model/PhoneTutorV2Logic';
import { PhoneBackController } from '../model/PhoneHomeState';
import { format_question_time } from '../model/QuestionFormat';
import {
  PHONE_COLOR_BACKGROUND, PHONE_COLOR_BORDER, PHONE_COLOR_CARD, PHONE_COLOR_DANGER,
  PHONE_COLOR_DANGER_SOFT, PHONE_COLOR_MUTED, PHONE_COLOR_PRIMARY, PHONE_COLOR_PRIMARY_DEEP,
  PHONE_COLOR_SOFT, PHONE_COLOR_TITLE, PHONE_COLOR_WHITE, PHONE_RADIUS_CARD,
  PHONE_RADIUS_CONTROL
} from '../theme/PhoneTheme';

const V2_POLL_MS: number = 3000;
const V2_PROMPT_MAX: number = 4000;

@Component
export struct PhoneTutorV2Page {
  toast?: ToastStore;
  @Prop embedded: boolean = false;
  editorNavigation: PhoneBackController = new PhoneBackController();
  onComposingChanged: (value: boolean) => void = () => {};
  onChooseQuestion: () => void = () => {};
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

  @State requests: V2TutorRequest[] = [];
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

  private api: LearningV2Api = LearningV2Api.create_default();
  private createIntent: PhoneV2CreateIntent = new PhoneV2CreateIntent();
  private retryKey: string = '';
  private retryTargetRunId: string = '';
  private accountId: string = '';
  private seq: number = 0;
  private listSeq: number = 0;
  private pollTimer: number = -1;
  private pollGeneration: number = 0;
  private pollBusy: boolean = false;
  private alive: boolean = false;
  private editorController: TextAreaController = new TextAreaController();
  private loadedQuestionId: string = '';
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
  }

  aboutToDisappear(): void {
    this.save_draft();
    this.stop_editing();
    this.editorNavigation.handleBack = (): boolean => false;
    this.alive = false;
    this.seq++;
    this.listSeq++;
    this.stop_poll();
  }

  on_question_changed(): void {
    this.save_draft();
    this.stop_editing();
    this.loadedQuestionId = this.questionId;
    this.seq++;
    this.listSeq++;
    this.stop_poll();
    this.requests = [];
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
    this.errorMessage = '';
    this.nextCursor = null;
    this.loadedOnce = false;
    this.loading = false;
    this.busy = false;
    this.pollBusy = false;
    this.adopt_intent();
    this.adopt_draft();
    this.refresh_list();
    this.load_question();
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
      Scroll() {
        Column() {
          if (this.embedded) { this.embedded_toolbar() }
          this.compose_card()
          if (this.errorMessage.length > 0) {
            this.notice(this.errorMessage, PHONE_COLOR_DANGER, PHONE_COLOR_DANGER_SOFT)
          }
          if (this.message.length > 0) {
            this.notice(this.message, PHONE_COLOR_PRIMARY_DEEP, PHONE_COLOR_SOFT)
          }
          this.request_section()
        }
        .width('100%')
        .padding({ left: 14, right: 14, bottom: 28 })
      }
      .layoutWeight(1)
      .width('100%')
      .scrollBar(BarState.Off)
    }
    .width('100%')
    .height('100%')
    .backgroundColor(PHONE_COLOR_BACKGROUND)
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
    Column({ space: 8 }) {
      Row() {
        Text('题库选题 · 新版辅导').fontSize(12).fontColor(PHONE_COLOR_PRIMARY_DEEP)
          .layoutWeight(1)
        Button('换题').height(44).fontSize(12).fontColor(PHONE_COLOR_PRIMARY_DEEP)
          .backgroundColor(Color.Transparent).onClick(() => { this.onChooseQuestion(); })
      }.width('100%')
      if (this.questionDetail !== null) {
        Row() {
          QuestionMarkdownView({ bodyMarkdown: (this.questionDetail as QuestionDetail).bodyMarkdown,
            fontSize: 16 }).layoutWeight(1).height(78).hitTestBehavior(HitTestMode.None)
          Image($r('app.media.bl_chevron_right')).width(18).height(18)
        }
        .width('100%').onClick(() => {
          if (this.questionDetail !== null) {
            this.onRead('完整题目', (this.questionDetail as QuestionDetail).bodyMarkdown, false);
          }
        })
      }
      Row({ space: 10 }) {
        Button('学习笔记').fontSize(12).fontColor(PHONE_COLOR_PRIMARY_DEEP)
          .backgroundColor(PHONE_COLOR_SOFT).onClick(() => { this.onOpenNotes(); })
        Button('旧版记录').fontSize(12).fontColor(PHONE_COLOR_PRIMARY_DEEP)
          .backgroundColor(PHONE_COLOR_SOFT).onClick(() => { this.onOpenLegacy(); })
      }
      Text('尚未确认平板当前题目，请在平板上打开本题。')
        .fontSize(11).fontColor(PHONE_COLOR_MUTED)
    }
    .width('100%').padding(14).backgroundColor(PHONE_COLOR_CARD)
    .borderRadius(PHONE_RADIUS_CARD).margin({ bottom: 10 })
  }

  @Builder
  compose_card() {
    Column() {
      Text('向 AI 提问').fontSize(14).fontWeight(FontWeight.Medium).fontColor(PHONE_COLOR_TITLE)
      TextArea({ text: this.prompt, placeholder: '写下你对这道题的具体疑问', controller: this.editorController })
        .width('100%').height(92).fontSize(14).maxLength(V2_PROMPT_MAX)
        .backgroundColor(PHONE_COLOR_BACKGROUND)
        .border({ width: 1, color: PHONE_COLOR_BORDER }).borderRadius(PHONE_RADIUS_CONTROL)
        .padding(10).margin({ top: 8 })
        .enabled(!this.busy && this.createIntent.requestId.length === 0)
        .onFocus(() => { this.editorFocused = true; this.onComposingChanged(true); })
        .onBlur(() => { this.editorFocused = false; this.onComposingChanged(false); })
        .onChange((value: string) => { this.prompt = value; this.errorMessage = ''; this.save_draft(); })
      Text(this.prompt.length.toString() + ' / ' + V2_PROMPT_MAX.toString())
        .fontSize(11).fontColor(PHONE_COLOR_MUTED).margin({ top: 4 })
      Row() {
        Button('提示与下一步')
          .fontSize(12).fontColor(this.fullSolution ? PHONE_COLOR_PRIMARY_DEEP : PHONE_COLOR_WHITE)
          .backgroundColor(this.fullSolution ? PHONE_COLOR_SOFT : PHONE_COLOR_PRIMARY)
          .enabled(this.createIntent.requestId.length === 0 && !this.busy)
          .onClick(() => { this.fullSolution = false; this.save_draft(); })
        Button('完整解答')
          .fontSize(12).fontColor(this.fullSolution ? PHONE_COLOR_WHITE : PHONE_COLOR_PRIMARY_DEEP)
          .backgroundColor(this.fullSolution ? PHONE_COLOR_PRIMARY : PHONE_COLOR_SOFT)
          .enabled(this.createIntent.requestId.length === 0 && !this.busy)
          .margin({ left: 8 })
          .onClick(() => { this.fullSolution = true; this.save_draft(); })
      }.margin({ top: 8 })
      Text(this.reuseRequestId.length > 0 ? '沿用已冻结作答追问' : '检查新作答：等待平板冻结当前画布')
        .fontSize(12).fontColor(PHONE_COLOR_MUTED).margin({ top: 10 })
      if (this.reuseRequestId.length > 0) {
        Button('改为检查新作答').fontSize(12).fontColor(PHONE_COLOR_PRIMARY_DEEP)
          .backgroundColor(PHONE_COLOR_SOFT).margin({ top: 6 })
          .enabled(this.createIntent.requestId.length === 0 && !this.busy)
          .onClick(() => { this.reuseRequestId = ''; this.save_draft(); })
      }
      Button(this.createIntent.requestId.length > 0 ? '确认原提问' :
        (this.fullSolution ? '明确请求完整解答' : '发送提示请求'))
        .width('100%').height(44).margin({ top: 12 })
        .fontColor(PHONE_COLOR_WHITE).backgroundColor(PHONE_COLOR_PRIMARY)
        .borderRadius(PHONE_RADIUS_CONTROL)
        .enabled(!this.busy && this.prompt.trim().length > 0 && this.prompt.length <= V2_PROMPT_MAX &&
          !this.has_active_request() && this.loadedOnce)
        .onClick(() => { this.submit(); })
      if (this.createIntent.requestId.length > 0) {
        Text('原提问结果未确认。再次发送只会使用相同 requestId 与参数。')
          .fontSize(11).fontColor(PHONE_COLOR_PRIMARY_DEEP).margin({ top: 8 })
      }
    }
    .width('100%').padding(14).backgroundColor(PHONE_COLOR_CARD)
    .borderRadius(PHONE_RADIUS_CARD).margin({ top: 4, bottom: 10 })
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  notice(text: string, foreground: string, background: string) {
    Text(text).fontSize(12).fontColor(foreground).lineHeight(18)
      .width('100%').padding(10).backgroundColor(background)
      .borderRadius(PHONE_RADIUS_CONTROL).margin({ bottom: 8 })
  }

  @Builder
  request_section() {
    Row() {
      Text('新版辅导记录').fontSize(16).fontWeight(FontWeight.Medium)
        .fontColor(PHONE_COLOR_TITLE).layoutWeight(1)
      Text(this.loading ? '刷新中…' : '刷新').fontSize(12).fontColor(PHONE_COLOR_PRIMARY_DEEP)
        .padding(8).onClick(() => { this.refresh_list(); })
    }.width('100%')
    if (this.requests.length === 0) {
      Text(this.loading ? '正在加载…' : '还没有新版辅导记录')
        .fontSize(13).fontColor(PHONE_COLOR_MUTED)
        .width('100%').padding(20).backgroundColor(PHONE_COLOR_CARD)
        .borderRadius(PHONE_RADIUS_CARD)
    }
    ForEach(this.requests, (request: V2TutorRequest) => {
      this.request_card(request)
    }, (request: V2TutorRequest) => request.requestId + '|' + request.revision.toString())
    if (this.nextCursor !== null) {
      Button('加载更多记录')
        .width('100%').fontSize(12).fontColor(PHONE_COLOR_PRIMARY_DEEP)
        .backgroundColor(PHONE_COLOR_SOFT).margin({ top: 10 })
        .enabled(!this.loading)
        .onClick(() => { this.load_more(); })
    }
  }

  @Builder
  request_card(request: V2TutorRequest) {
    Column() {
      Row() {
        Text(this.status_text(request.status)).fontSize(11)
          .fontColor(PHONE_COLOR_PRIMARY_DEEP).padding(5)
          .backgroundColor(PHONE_COLOR_SOFT).borderRadius(8)
        Text(request.mode === V2TutorMode.FULL_SOLUTION ? '完整解答' : '提示')
          .fontSize(11).fontColor(PHONE_COLOR_MUTED).margin({ left: 8 })
        Blank()
        Text(format_question_time(request.createdAt)).fontSize(11).fontColor(PHONE_COLOR_MUTED)
      }.width('100%')
      Text(request.prompt).fontSize(14).fontColor(PHONE_COLOR_TITLE)
        .maxLines(this.selectedRequestId === request.requestId ? 20 : 2)
        .textOverflow({ overflow: TextOverflow.Ellipsis })
        .width('100%').margin({ top: 9 })
      Button(this.selectedRequestId === request.requestId ? '收起详情' : '查看详情')
        .fontSize(12).fontColor(PHONE_COLOR_PRIMARY_DEEP)
        .backgroundColor(PHONE_COLOR_SOFT).margin({ top: 8 })
        .onClick(() => { this.select_request(request.requestId); })
      if (this.selectedRequestId === request.requestId) {
        this.selected_detail(request)
      }
    }
    .width('100%').padding(14).backgroundColor(PHONE_COLOR_CARD)
    .borderRadius(PHONE_RADIUS_CARD).margin({ top: 8 })
    .alignItems(HorizontalAlign.Start)
  }

  @Builder
  selected_detail(request: V2TutorRequest) {
    if (this.detail === null || this.detail.request.requestId !== request.requestId) {
      Text('正在加载本次请求…').fontSize(12).fontColor(PHONE_COLOR_MUTED).margin({ top: 10 })
    } else {
      if (request.status === V2TutorRequestStatus.WAITING_INPUT) {
        this.notice('等待平板前台打开本题并上传不可变作答快照。',
          PHONE_COLOR_PRIMARY_DEEP, PHONE_COLOR_SOFT)
      } else if (phone_v2_active(request.status)) {
        this.notice('任务进行中。断线或切换前后台后仍按原 requestId 查询。',
          PHONE_COLOR_PRIMARY_DEEP, PHONE_COLOR_SOFT)
      } else if (this.detail.result !== null && request.status === V2TutorRequestStatus.SUCCEEDED) {
        QuestionMarkdownView({ bodyMarkdown: this.detail.result.bodyMarkdown, fontSize: 15,
          maxLength: 65536, citations: this.detail.result.citations,
          onCitation: (citationId: string) => { this.onOpenCitation(citationId); } })
          .width('100%').height(132).margin({ top: 10 }).hitTestBehavior(HitTestMode.None)
        Button('完整阅读 ›').height(44).fontSize(14).fontColor(PHONE_COLOR_PRIMARY_DEEP)
          .backgroundColor(PHONE_COLOR_SOFT).margin({ top: 8 })
          .onClick(() => {
            const result = this.detail?.result;
            if (result !== null && result !== undefined) {
              this.onRead('新版辅导解答', result.bodyMarkdown, false, result.citations);
            }
          })
        ForEach(this.visible_citations(), (citation: V2Citation) => {
          Button('[' + citation.label + '] 查看来源')
            .id('citation-' + citation.citationId)
            .fontSize(12).fontColor(PHONE_COLOR_PRIMARY_DEEP)
            .backgroundColor(PHONE_COLOR_SOFT).margin({ top: 6 })
            .onClick(() => {
              citationFocusController.clear();
              this.onOpenCitation(citation.citationId);
            })
        }, (citation: V2Citation) => citation.citationId)
      } else if (request.status === V2TutorRequestStatus.FAILED ||
        request.status === V2TutorRequestStatus.TIMED_OUT) {
        this.notice('本次运行未发布结果。可明确重试同一快照。', PHONE_COLOR_DANGER,
          PHONE_COLOR_DANGER_SOFT)
      }
      if (request.snapshotFileRef !== null && !phone_v2_active(request.status)) {
        Button('沿此快照追问')
          .fontSize(12).fontColor(PHONE_COLOR_PRIMARY_DEEP).backgroundColor(PHONE_COLOR_SOFT)
          .enabled(this.createIntent.requestId.length === 0 && !this.busy)
          .margin({ top: 8 }).onClick(() => {
            this.reuseRequestId = request.requestId;
            this.save_draft();
          })
      }
      if (phone_v2_retryable(request)) {
        Button(this.busy ? '处理中…' : '重试本次运行')
          .fontSize(12).fontColor(PHONE_COLOR_WHITE).backgroundColor(PHONE_COLOR_PRIMARY)
          .enabled(!this.busy).margin({ top: 8 })
          .onClick(() => { this.retry(request); })
      }
      if (phone_v2_active(request.status) && request.status !== V2TutorRequestStatus.CANCELLING) {
        Button('取消本次请求')
          .fontSize(12).fontColor(PHONE_COLOR_DANGER).backgroundColor(PHONE_COLOR_DANGER_SOFT)
          .enabled(!this.busy).margin({ top: 8 })
          .onClick(() => { this.cancel(request); })
      }
      Text('请求 ' + request.requestId + ' · revision ' + request.revision.toString())
        .fontSize(10).fontColor(PHONE_COLOR_MUTED).margin({ top: 10 })
    }
  }

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

  private visible_citations(): V2Citation[] {
    if (this.detail === null || this.detail.result === null) { return []; }
    const markerIds: string[] = citation_marker_ids(this.detail.result.bodyMarkdown,
      this.detail.result.citations);
    return this.detail.result.citations.filter((citation: V2Citation) =>
      markerIds.includes(citation.citationId));
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
    if (this.accountId.length === 0 || this.loadedQuestionId.length === 0) { return; }
    const draft: PhoneV2Draft = phone_v2_draft_for(this.accountId, this.loadedQuestionId);
    draft.prompt = this.prompt;
    draft.fullSolution = this.fullSolution;
    draft.reuseRequestId = this.reuseRequestId;
    draft.selectedRequestId = this.selectedRequestId;
    this.onSessionSummary(this.loadedQuestionId, '题目 #' + this.loadedQuestionId, this.prompt);
  }

  private adopt_draft(): void {
    if (this.accountId.length === 0 || this.questionId.length === 0) { return; }
    const draft: PhoneV2Draft = phone_v2_draft_for(this.accountId, this.questionId);
    if (this.createIntent.requestId.length === 0) {
      this.prompt = draft.prompt;
      this.fullSolution = draft.fullSolution;
      this.reuseRequestId = draft.reuseRequestId;
    }
    this.selectedRequestId = draft.selectedRequestId;
    this.onSessionSummary(this.questionId, '题目 #' + this.questionId, this.prompt);
  }

  private async load_question(): Promise<void> {
    const token: string | null = this.session();
    if (token === null || this.questionId.length === 0) { return; }
    const accountId: string = this.accountId;
    const seq: number = this.seq;
    const questionId: string = this.questionId;
    try {
      const result: AuthResult<QuestionDetail> = await this.learningApi.getQuestion(token, questionId);
      if (this.valid_context(seq, token, accountId) && this.questionId === questionId &&
        result.isSuccess && result.data !== null) {
        this.questionDetail = result.data;
        this.onSessionSummary(questionId, '题目 #' + questionId, this.prompt);
      }
    } catch (error) {}
  }

  private adopt_intent(): void {
    if (this.accountId.length === 0 || this.questionId.length === 0) { return; }
    const stored: PhoneV2CreateIntent = phone_v2_intent_for(this.accountId, this.questionId);
    if (this.createIntent !== stored) {
      this.createIntent = stored;
      if (stored.requestId.length > 0) {
        this.prompt = stored.prompt;
        this.fullSolution = stored.mode === V2TutorMode.FULL_SOLUTION;
        this.reuseRequestId = stored.sourceRequestId;
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

  private async refresh_list(): Promise<void> {
    const token: string | null = this.session();
    if (token === null || this.questionId.length === 0) { return; }
    const accountId: string = this.accountId;
    const seq: number = this.seq;
    const listSeq: number = ++this.listSeq;
    this.loading = true;
    try {
      const result: AuthResult<V2TutorRequestPage> = await this.api.listRequests(token, this.questionId);
      if (!this.valid_context(seq, token, accountId) || listSeq !== this.listSeq) { return; }
      if (!result.isSuccess || result.data === null) {
        this.errorMessage = this.failed(result.error, token);
        return;
      }
      this.requests = phone_v2_merge_requests(this.requests, result.data.items);
      this.loadedOnce = true;
      this.nextCursor = result.data.nextCursor;
      if (this.createIntent.requestId.length > 0) {
        for (let index: number = 0; index < this.requests.length; index++) {
          if (this.requests[index].requestId === this.createIntent.requestId) {
            this.on_created(this.requests[index]);
            break;
          }
        }
      }
      if (this.selectedRequestId.length === 0 && this.requests.length > 0) {
        this.selectedRequestId = this.requests[0].requestId;
      }
      if (this.selectedRequestId.length > 0) { this.refresh_detail(this.selectedRequestId); }
      if (this.createIntent.requestId.length > 0) { this.confirm_create(this.createIntent.requestId); }
      this.start_poll();
    } catch (error) {
      if (this.valid_context(seq, token, accountId) && listSeq === this.listSeq) {
        this.errorMessage = '暂时无法读取新版辅导记录';
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
    const listSeq: number = ++this.listSeq;
    this.loading = true;
    try {
      const result: AuthResult<V2TutorRequestPage> = await this.api.listRequests(token, this.questionId, cursor);
      if (!this.valid_context(seq, token, accountId) || listSeq !== this.listSeq) { return; }
      if (!result.isSuccess || result.data === null) {
        this.errorMessage = this.failed(result.error, token);
        return;
      }
      this.requests = phone_v2_merge_requests(this.requests, result.data.items);
      this.nextCursor = result.data.nextCursor;
    } catch (error) {
      if (this.valid_context(seq, token, accountId) && listSeq === this.listSeq) {
        this.errorMessage = '读取更多记录失败，请稍后重试';
      }
    } finally {
      if (listSeq === this.listSeq) { this.loading = false; }
    }
  }

  private select_request(requestId: string): void {
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
    try {
      const result: AuthResult<V2TutorRequestDetail> = await this.api.getRequest(token, this.questionId, requestId);
      if (!this.valid_context(seq, token, accountId)) { return; }
      if (!result.isSuccess || result.data === null) {
        this.errorMessage = this.failed(result.error, token);
        return;
      }
      this.receive_detail(result.data);
    } catch (error) {
      if (this.valid_context(seq, token, accountId)) {
        this.message = '网络暂不可用，恢复后按原 requestId 查询';
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

  /** 写回包与轮询均先合并摘要；用户切换到历史时只保留其选中详情。 */
  private receive_detail(incoming: V2TutorRequestDetail): void {
    this.requests = phone_v2_merge_requests(this.requests, [incoming.request]);
    if (this.selectedRequestId === incoming.request.requestId) {
      this.accept_detail(incoming);
    } else if (!this.has_active_request()) {
      this.stop_poll();
    }
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
    const token: string | null = this.session();
    if (token === null) { return; }
    const accountId: string = this.accountId;
    const text: string = this.prompt.trim();
    if (text.length === 0 || text.length > V2_PROMPT_MAX) { return; }
    const mode: V2TutorMode = this.fullSolution ? V2TutorMode.FULL_SOLUTION : V2TutorMode.HINT;
    const snapshotKind: string = this.reuseRequestId.length > 0 ? 'REUSE_REQUEST' : 'NEW_CAPTURE';
    if (!this.createIntent.matches(text, mode, snapshotKind, this.reuseRequestId)) {
      this.createIntent.requestId = util.generateRandomUUID(true).toLowerCase();
      this.createIntent.prompt = text;
      this.createIntent.mode = mode;
      this.createIntent.snapshotKind = snapshotKind;
      this.createIntent.sourceRequestId = this.reuseRequestId;
    }
    const requestId: string = this.createIntent.requestId;
    const snapshotInput: V2SnapshotInput = this.reuseRequestId.length > 0 ?
      { kind: 'REUSE_REQUEST', sourceRequestId: this.reuseRequestId } : { kind: 'NEW_CAPTURE' };
    const seq: number = ++this.seq;
    const toastScope: number | undefined = this.toast?.captureScope();
    this.busy = true;
    this.errorMessage = '';
    try {
      const result: AuthResult<V2TutorRequest> = await this.api.createRequest(token, this.questionId,
        requestId, text, mode, snapshotInput);
      if (!this.valid_context(seq, token, accountId)) { return; }
      if (!result.isSuccess || result.data === null) {
        if (result.error !== null && result.error.httpStatus === 409) {
          this.createIntent.clear();
          this.errorMessage = this.failed(result.error, token);
        } else {
          this.message = '创建结果未知，正在查询原 requestId；再次操作仍使用原请求标识';
          this.confirm_create(requestId);
        }
        return;
      }
      this.on_created(result.data);
      this.toast?.show({ key: 'phone-v2-create', type: 'success', message: '新版辅导请求已发送' }, toastScope);
    } catch (error) {
      if (this.valid_context(seq, token, accountId)) {
        this.message = '创建结果未知，正在查询原 requestId';
        this.confirm_create(requestId);
      }
    } finally {
      if (seq === this.seq) { this.busy = false; }
    }
  }

  private on_created(request: V2TutorRequest, fetchDetail: boolean = true): void {
    this.createIntent.clear();
    this.prompt = '';
    this.fullSolution = false;
    this.reuseRequestId = '';
    this.stop_editing();
    const next: PhoneV2ViewState = phone_v2_apply_created({
      requests: this.requests, detail: this.detail, selectedRequestId: this.selectedRequestId
    }, request);
    this.requests = next.requests;
    this.selectedRequestId = next.selectedRequestId;
    this.detail = next.detail;
    this.save_draft();
    this.message = request.status === V2TutorRequestStatus.WAITING_INPUT ?
      '请求已创建，请让平板前台打开本题冻结作答' : '请求已创建，正在执行';
    if (fetchDetail && this.detail === null) { this.refresh_detail(request.requestId); }
    this.start_poll();
  }

  private async confirm_create(requestId: string): Promise<void> {
    const token: string | null = this.session();
    if (token === null) { return; }
    const seq: number = this.seq;
    const accountId: string = this.accountId;
    try {
      const result: AuthResult<V2TutorRequestDetail> = await this.api.getRequest(token, this.questionId, requestId);
      if (!this.valid_context(seq, token, accountId)) { return; }
      if (result.isSuccess && result.data !== null) {
        this.on_created(result.data.request, false);
        this.receive_detail(result.data);
      } else if (result.error !== null && result.error.is_unauthorized()) {
        this.failed(result.error, token);
      } else {
        this.message = '尚未确认原请求，请点击“确认原提问”按同一 requestId 重发';
      }
    } catch (error) {
      if (this.valid_context(seq, token, accountId)) {
        this.message = '尚未确认原请求，请点击“确认原提问”按同一 requestId 重发';
      }
    }
  }

  private async cancel(request: V2TutorRequest): Promise<void> {
    if (this.busy) { return; }
    const token: string | null = this.session();
    if (token === null) { return; }
    const seq: number = ++this.seq;
    const accountId: string = this.accountId;
    const toastScope: number | undefined = this.toast?.captureScope();
    this.busy = true;
    try {
      const result: AuthResult<V2TutorRequestDetail> = await this.api.cancelRequest(token, this.questionId,
        request.requestId, request.currentRunId);
      if (!this.valid_context(seq, token, accountId)) { return; }
      if (result.isSuccess && result.data !== null) {
        this.receive_detail(result.data);
        this.toast?.show({ key: 'phone-v2-cancel', type: 'info', message: '本次辅导状态已更新' }, toastScope);
      }
      else { this.errorMessage = this.failed(result.error, token); this.refresh_detail(request.requestId); }
    } catch (error) {
      if (this.valid_context(seq, token, accountId)) {
        this.message = '取消结果未知，按原 requestId 查询状态';
        this.refresh_detail(request.requestId);
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
    const targetRunId: string = request.currentRunId;
    if (this.retryKey.length === 0 || this.retryTargetRunId !== targetRunId) {
      this.retryKey = util.generateRandomUUID(true).toLowerCase();
      this.retryTargetRunId = targetRunId;
    }
    const seq: number = ++this.seq;
    const toastScope: number | undefined = this.toast?.captureScope();
    this.busy = true;
    try {
      const result: AuthResult<V2TutorRequestDetail> = await this.api.retryRequest(token, this.questionId,
        request.requestId, targetRunId, this.retryKey);
      if (!this.valid_context(seq, token, accountId)) { return; }
      if (result.isSuccess && result.data !== null) {
        this.retryKey = '';
        this.retryTargetRunId = '';
        this.receive_detail(result.data);
        this.start_poll();
        this.toast?.show({ key: 'phone-v2-retry', type: 'info', message: '已重试本次辅导' }, toastScope);
      } else {
        this.errorMessage = this.failed(result.error, token);
        this.refresh_detail(request.requestId);
      }
    } catch (error) {
      if (this.valid_context(seq, token, accountId)) {
        this.message = '重试结果未知，按原 requestId 查询；不会自动再次发起重试';
        this.refresh_detail(request.requestId);
      }
    } finally {
      if (seq === this.seq) { this.busy = false; }
    }
  }
}
