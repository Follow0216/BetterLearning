import { AuthError, AuthResult, CitationModal, LearningV2Api, QuestionMarkdownView, V2Citation, V2TutorRequest, V2TutorRequestDetail,
  V2TutorRequestPage, accept_v2_detail, authSession, citationFocusController, citation_marker_ids,
  v2_detail_is_active } from 'common';
import { TabletBackController } from '../../components/TabletBackController';

function tablet_v2_input_text(request: V2TutorRequest): string {
  if (request.snapshotInput.kind === 'NO_ANSWER') {
    const reason: string = request.inputSelectionReason === 'OTHER_QUESTION' ? '平板当时正在其他题目' :
      request.inputSelectionReason === 'OFFLINE' ? '平板当时离线或可用状态已过期' : '当时没有同账号可用平板';
    return reason + '；本轮使用题面、问题和已确认的问答上下文。';
  }
  if (request.snapshotInput.kind === 'REUSE_REQUEST') { return '本轮沿用所选历史作答。'; }
  return request.snapshotFileRef === null ? '本轮等待平板同步作答。' :
    '本轮使用新冻结的平板作答，未书写的画布也会照常同步。';
}

/** 当前题 V2 历史只读浏览；每次详情和引用都由服务端按当前账号重新授权。 */
@Component
export struct TabletV2HistoryView {
  @Prop questionId: string = '';
  @Prop @Watch('activeChanged') active: boolean = false;
  backController: TabletBackController = new TabletBackController();
  onBack: () => void = () => {};
  onUnauthorized: () => void = () => {};
  @State private requests: V2TutorRequest[] = [];
  @State private detail: V2TutorRequestDetail | null = null;
  @State private loading: boolean = false;
  @State private message: string = '';
  @State private nextCursor: string | null = null;
  @State private citationId: string = '';
  private api: LearningV2Api = LearningV2Api.create_default();
  private epoch: number = 0;
  private detailEpoch: number = 0;
  private selectedRequestId: string = '';
  private alive: boolean = false;
  private token: string = '';
  private accountId: string = '';
  private citationDialog: CustomDialogController | null = null;
  private sessionTimer: number = -1;
  private detailTimer: number = -1;
  private detailPollCount: number = 0;
  private detailInFlightEpoch: number = -1;
  private backHandler: () => boolean = () => {
    if (!this.active) { return false; }
    if (this.citationDialog !== null) {
      this.citationDialog.close(); this.citationDialog = null;
      this.restoreCitationFocus(this.citationId);
      return true;
    }
    this.goBack(); return true;
  };

  private goBack(): void {
    if (this.selectedRequestId.length > 0 || this.detail !== null) {
      this.stopDetailPolling();
      this.detailEpoch += 1; this.selectedRequestId = ''; this.detail = null;
      this.loading = false; this.message = ''; return;
    }
    this.onBack();
  }

  aboutToAppear(): void {
    this.backController.attach(this.backHandler);
    this.alive = true; this.token = authSession.read_access_token() ?? '';
    this.accountId = authSession.read_user()?.accountId ?? ''; this.reload();
    this.sessionTimer = setInterval(() => {
      if (authSession.read_access_token() !== this.token || authSession.read_user()?.accountId !== this.accountId) {
        this.stopDetailPolling();
        this.epoch += 1; this.detailEpoch += 1; this.selectedRequestId = '';
        this.requests = []; this.detail = null; this.nextCursor = null; this.loading = false;
        this.citationDialog?.close(); this.citationDialog = null; citationFocusController.clear();
      }
    },1000);
  }
  aboutToDisappear(): void {
    this.backController.detach(this.backHandler);
    this.stopDetailPolling();
    this.alive = false; this.epoch += 1; this.detailEpoch += 1; this.selectedRequestId = '';
    if (this.sessionTimer !== -1) { clearInterval(this.sessionTimer); this.sessionTimer = -1; }
    this.citationDialog?.close(); this.citationDialog = null;
    citationFocusController.clear();
  }
  private current(epoch: number): boolean {
    return this.alive && this.active && epoch === this.epoch && this.token.length > 0 && this.accountId.length > 0 &&
      authSession.read_access_token() === this.token && authSession.read_user()?.accountId === this.accountId;
  }
  private activeChanged(): void {
    if (!this.active) {
      this.epoch += 1; this.detailInFlightEpoch = -1; this.loading = false;
      this.citationDialog?.close(); this.citationDialog = null; citationFocusController.clear();
      this.stopDetailPolling(); return;
    }
    if (this.selectedRequestId.length > 0) {
      if (this.detail !== null && v2_detail_is_active(this.detail)) { this.startDetailPolling(); }
      this.refreshSelectedDetail(false);
    } else if (this.requests.length === 0) {
      this.reload();
    }
  }
  private stopDetailPolling(): void {
    if (this.detailTimer !== -1) { clearInterval(this.detailTimer); this.detailTimer = -1; }
  }
  private startDetailPolling(): void {
    this.stopDetailPolling();
    if (!this.active || this.detail === null || !v2_detail_is_active(this.detail)) { return; }
    this.detailPollCount = 0;
    this.detailTimer = setInterval(() => { this.pollSelectedDetail(); },2000);
  }
  private pollSelectedDetail(): void {
    if (!this.active || this.detail === null || !v2_detail_is_active(this.detail)) {
      this.stopDetailPolling(); return;
    }
    if (this.detailPollCount >= 600) {
      this.stopDetailPolling(); this.message = '自动更新已暂停，请手动刷新辅导详情'; return;
    }
    this.detailPollCount += 1;
    this.refreshSelectedDetail(false);
  }
  private fail(error: AuthError | null): void {
    const actual: AuthError = error ?? AuthError.protocol_failure('辅导历史响应无效');
    this.message = actual.message;
    if (actual.is_unauthorized()) {
      this.stopDetailPolling();
      this.epoch += 1; this.detailEpoch += 1; this.selectedRequestId = '';
      this.requests = []; this.detail = null; this.nextCursor = null; this.loading = false;
      if (authSession.clear_session_if_token(this.token)) { this.onUnauthorized(); }
    }
  }
  private async reload(): Promise<void> {
    if (this.selectedRequestId.length > 0) { await this.refreshSelectedDetail(true); return; }
    this.stopDetailPolling();
    this.epoch += 1; this.detailEpoch += 1; this.selectedRequestId = ''; const epoch: number = this.epoch;
    this.requests = []; this.detail = null; this.nextCursor = null; this.message = ''; this.loading = false;
    await this.loadPage(epoch,null);
  }
  private async loadPage(epoch: number, cursor: string | null): Promise<void> {
    if (!this.current(epoch) || this.loading) { return; }
    this.loading = true;
    try {
      const result: AuthResult<V2TutorRequestPage> = await this.api.listRequests(this.token,this.questionId,cursor,20);
      if (!this.current(epoch)) { return; }
      if (!result.isSuccess || result.data === null) { this.fail(result.error); return; }
      this.requests = this.requests.concat(result.data.items.filter((request: V2TutorRequest) => request.questionId === this.questionId));
      this.nextCursor = result.data.nextCursor;
    } catch (error) {
      if (this.current(epoch)) { this.message = '辅导历史暂不可用，请重试'; }
    } finally { if (this.current(epoch)) { this.loading = false; } }
  }
  private async openRequest(requestId: string): Promise<void> {
    const epoch: number = this.epoch;
    if (!this.current(epoch)) { return; }
    this.stopDetailPolling();
    const detailEpoch: number = ++this.detailEpoch;
    this.selectedRequestId = requestId; this.detail = null; this.loading = true; this.message = '';
    try {
      const result: AuthResult<V2TutorRequestDetail> = await this.api.getRequest(this.token,this.questionId,requestId);
      if (!this.current(epoch) || detailEpoch !== this.detailEpoch || this.selectedRequestId !== requestId) { return; }
      if (!result.isSuccess || result.data === null) { this.fail(result.error); return; }
      if (result.data.request.requestId !== requestId || result.data.request.questionId !== this.questionId) {
        this.message = '辅导详情与当前题不一致'; return;
      }
      if (!accept_v2_detail(this.detail,result.data)) { this.message = '辅导状态暂未同步，请重试'; return; }
      this.detail = result.data; this.startDetailPolling();
    } catch (error) {
      if (this.current(epoch) && detailEpoch === this.detailEpoch) { this.message = '辅导详情暂不可用，请重试'; }
    } finally { if (this.current(epoch) && detailEpoch === this.detailEpoch) { this.loading = false; } }
  }
  private async refreshSelectedDetail(showLoading: boolean): Promise<void> {
    const epoch: number = this.epoch;
    const detailEpoch: number = this.detailEpoch;
    const requestId: string = this.selectedRequestId;
    if (!this.current(epoch) || requestId.length === 0 || this.detailInFlightEpoch === detailEpoch) { return; }
    this.detailInFlightEpoch = detailEpoch;
    if (showLoading) { this.loading = true; this.message = ''; }
    try {
      const result: AuthResult<V2TutorRequestDetail> = await this.api.getRequest(this.token,this.questionId,requestId);
      if (!this.current(epoch) || detailEpoch !== this.detailEpoch || this.selectedRequestId !== requestId) { return; }
      if (!result.isSuccess || result.data === null) { this.fail(result.error); return; }
      if (!accept_v2_detail(this.detail,result.data)) { return; }
      this.detail = result.data;
      if (!v2_detail_is_active(result.data)) { this.stopDetailPolling(); }
      else if (this.detailTimer === -1) { this.startDetailPolling(); }
    } catch (error) {
      if (this.current(epoch) && detailEpoch === this.detailEpoch) { this.message = '辅导详情暂不可用，请重试'; }
    } finally {
      if (this.detailInFlightEpoch === detailEpoch) { this.detailInFlightEpoch = -1; }
      if (showLoading && this.current(epoch) && detailEpoch === this.detailEpoch) { this.loading = false; }
    }
  }
  private openCitation(citationId: string): void {
    if (!this.allowedCitationIds().includes(citationId)) { return; }
    this.citationId = citationId;
    this.citationDialog?.close();
    this.citationDialog = new CustomDialogController({
      builder: CitationModal({ citationId: this.citationId,
        onUnauthorized: () => this.onUnauthorized(),
        onClose: () => { this.citationDialog?.close(); this.citationDialog = null;
          this.restoreCitationFocus(citationId); } }),
      cancel: () => { this.citationDialog = null; this.restoreCitationFocus(citationId); },
      autoCancel: true, customStyle: true
    });
    this.citationDialog.open();
  }
  private allowedCitationIds(): string[] {
    if (this.detail === null || this.detail.result === null) { return []; }
    return citation_marker_ids(this.detail.result.bodyMarkdown,this.detail.result.citations);
  }
  private visibleCitations(): V2Citation[] {
    if (this.detail === null || this.detail.result === null) { return []; }
    const allowed: string[] = this.allowedCitationIds();
    return this.detail.result.citations.filter((citation: V2Citation) => allowed.includes(citation.citationId));
  }
  private restoreCitationFocus(citationId: string): void {
    if (!citationFocusController.restore(citationId)) {
      focusControl.requestFocus('tablet-citation-' + citationId);
    }
  }

  build() {
    Column({ space: 12 }) {
      Row({ space: 12 }) {
        Button(this.selectedRequestId.length > 0 ? '返回列表' : '返回作答').onClick(() => this.goBack())
        Text('本题辅导历史').fontSize(20).fontWeight(FontWeight.Bold)
        Blank()
        Button('刷新').onClick(() => this.reload()).enabled(!this.loading)
      }.width('100%')
      if (this.message.length > 0) { Text(this.message).fontColor(Color.Red) }
      if (this.detail !== null) {
        Scroll() {
          Column({ space: 12 }) {
            Button('返回列表').onClick(() => this.goBack())
            Text(this.detail.request.prompt).fontSize(16).fontWeight(FontWeight.Medium)
            Text(this.detail.request.mode === 'FULL_SOLUTION' ? '完整解答' : '提示与下一步')
            Text(this.detail.request.status).fontSize(13)
            Text(tablet_v2_input_text(this.detail.request)).fontSize(12)
            if (this.detail.result !== null) {
              QuestionMarkdownView({ bodyMarkdown: this.detail.result.bodyMarkdown, maxLength: 65536,
                citations: this.detail.result.citations,
                onCitation: (citationId: string) => this.openCitation(citationId) })
                .width('100%').height(400)
              ForEach(this.visibleCitations(), (citation: V2Citation) => {
                Button('[' + citation.label + '] ' + citation.sourceType)
                  .id('tablet-citation-' + citation.citationId)
                  .accessibilityText('打开引用 ' + citation.label)
                  .onClick(() => { citationFocusController.clear(); this.openCitation(citation.citationId); })
              }, (citation: V2Citation) => citation.citationId)
            }
          }.alignItems(HorizontalAlign.Start).width('100%').padding(20)
        }.layoutWeight(1)
      } else {
        List({ space: 8 }) {
          ForEach(this.requests, (request: V2TutorRequest) => {
            ListItem() {
              Column({ space: 5 }) {
                Text(request.prompt).maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis })
                Text(request.status + ' · ' + request.createdAt).fontSize(12)
                Text(tablet_v2_input_text(request)).fontSize(12)
              }.alignItems(HorizontalAlign.Start).padding(16).width('100%')
            }.onClick(() => this.openRequest(request.requestId))
          }, (request: V2TutorRequest) => request.requestId)
          if (this.nextCursor !== null) {
            ListItem() { Button('加载更多').onClick(() => this.loadPage(this.epoch,this.nextCursor)).enabled(!this.loading) }
          }
        }.layoutWeight(1)
      }
    }.width('100%').height('100%').padding(20).backgroundColor(Color.White)
  }
}
