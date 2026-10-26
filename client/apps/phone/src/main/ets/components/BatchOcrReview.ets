import { common } from '@kit.AbilityKit';
import { BatchOcrSourceViewer } from './BatchOcrSourceViewer';
import { PhoneBackController } from '../model/PhoneHomeState';
import {
  authSession, AuthFailureKind, AuthResult, BatchOcrApi, BatchOcrImage, BatchOcrJob, BatchOcrStatus,
  BatchOcrTile, batch_ocr_is_active, LearningApi, LearningSubject, ManualQuestionInput,
  MAX_QUESTION_MARKDOWN_LENGTH, QuestionDetail, QuestionMarkdownView, ToastStore
} from 'common';
import { BatchQuestionDraft, BatchReviewDraft, batchDraftSession, batch_draft_can_add,
  batch_question_label, batch_question_summary, batch_ocr_stage_label, batch_start_can_retry_manually } from '../features/ocr/BatchOcrDrafts';
import { copyBatchImage, discardBatchImage, selectBatchImages } from '../features/ocr/BatchQuestionImageSource';
import {
  PHONE_COLOR_BORDER, PHONE_COLOR_CARD, PHONE_COLOR_DANGER, PHONE_COLOR_DANGER_SOFT,
  PHONE_COLOR_MUTED, PHONE_COLOR_PRIMARY, PHONE_COLOR_PRIMARY_DEEP, PHONE_COLOR_SOFT,
  PHONE_COLOR_TITLE, PHONE_COLOR_WHITE, PHONE_RADIUS_CARD, PHONE_RADIUS_CONTROL
} from '../theme/PhoneTheme';

/** 与单题录入完全独立。本登录内存恢复；隐藏后只以GET恢复原requestId。 */
@Component
export struct BatchOcrReview {
  toast?: ToastStore;
  onUnauthorized: (token: string) => void = () => {};
  onSaved: () => void = () => {};
  backController: PhoneBackController = new PhoneBackController();
  @Prop @Watch('active_changed') active: boolean = true;
  @State batches: BatchReviewDraft[] = [];
  @State revision: number = 0;
  @State choosing: boolean = false;
  @State prepareStage: string = '';
  @State notice: string = '';
  @State discardId: string = '';
  @State viewerImage: BatchOcrImage | null = null;
  @State viewerTitle: string = '';
  private api: BatchOcrApi = BatchOcrApi.create_default();
  private learningApi: LearningApi = LearningApi.create_default();
  private alive: boolean = false;
  private generation: number = 0;
  private timer: number = -1;

  aboutToAppear(): void {
    this.alive = true;
    this.backController.handleBack = (): boolean => {
      if (this.viewerImage === null) { return false; }
      this.viewerImage = null; return true;
    };
    const token: string | null = authSession.read_access_token();
    if (token === null) { this.viewerImage = null; this.onUnauthorized(''); return; }
    if (!batchDraftSession.owns(token)) { this.viewerImage = null; }
    batchDraftSession.bind(token);
    this.sync();
    this.schedule();
  }
  aboutToDisappear(): void { this.alive = false; this.backController.handleBack = (): boolean => false; this.pause(); }
  active_changed(): void {
    if (this.active) {
      const token: string | null = authSession.read_access_token();
      if (token === null) { this.viewerImage = null; this.onUnauthorized(''); return; }
      if (!batchDraftSession.owns(token)) { this.viewerImage = null; }
      batchDraftSession.bind(token);
      this.sync(); this.schedule();
    } else { this.pause(); }
  }
  private pause(): void {
    this.generation++;
    this.viewerImage = null;
    this.choosing = false;
    if (this.timer >= 0) { clearTimeout(this.timer); this.timer = -1; }
    for (const batch of batchDraftSession.batches) {
      if (batch.busy) { batch.notice = '已暂停等待，返回后查询原批次；不会自动再次识别。'; }
      batch.busy = false; batch.clientStage = '';
      for (const draft of batch.drafts) {
        if (draft.saving) { draft.error = '保存结果尚未确认，请重试原提交查清；正文暂时锁定。'; }
        draft.saving = false;
      }
    }
  }
  private current(token: string, generation: number): boolean {
    return this.alive && this.active && generation === this.generation && batchDraftSession.owns(token) &&
      authSession.matches_access_token(token);
  }
  private sync(): void { this.revision++; this.batches = batchDraftSession.batches.slice(); }
  private token(): string | null {
    const token: string | null = authSession.read_access_token();
    if (token === null) { this.viewerImage = null; this.onUnauthorized(''); return null; }
    if (!batchDraftSession.owns(token)) { this.viewerImage = null; batchDraftSession.bind(token); this.sync(); }
    return token;
  }
  private unauthorized(token: string): void {
    this.pause();
    batchDraftSession.bind('');
    this.sync();
    authSession.clear_session_if_token(token);
    this.onUnauthorized(token);
  }
  private schedule(): void {
    if (this.timer >= 0 || !this.alive || !this.active) { return; }
    const target: BatchReviewDraft | undefined = this.batches.find((batch: BatchReviewDraft) =>
      !batch.busy && batch.job !== null && batch_ocr_is_active(batch.job.status));
    if (target === undefined) { return; }
    this.timer = setTimeout(() => { this.timer = -1; this.query(target, false); }, 2000);
  }
  build() {
    Stack({ alignContent: Alignment.Center }) {
      Scroll() {
        Column({ space: 14 }) {
          Text('批量识别与人工校对').fontSize(20).fontWeight(FontWeight.Bold).fontColor(PHONE_COLOR_TITLE).width('100%')
          Text('大图、长图或多张题图会拆成多道题。先逐题检查文字、公式和来源，再勾选保存。')
            .fontSize(14).lineHeight(21).fontColor(PHONE_COLOR_MUTED).width('100%')
          Row({ space: 10 }) {
            Button('选单张大图 / 长图').layoutWeight(1).height(48).fontSize(13)
              .fontColor(PHONE_COLOR_PRIMARY_DEEP).backgroundColor(PHONE_COLOR_SOFT)
              .enabled(!this.choosing).onClick(() => { this.choose(1); })
            Button('多图批量选择').layoutWeight(1).height(48).fontSize(14)
              .fontColor(PHONE_COLOR_WHITE).backgroundColor(PHONE_COLOR_PRIMARY)
              .enabled(!this.choosing).onClick(() => { this.choose(4); })
          }.width('100%')
          Text('每批1–4张，单图≤16 MiB / 1600万像素，最长边16000像素，合计≤32 MiB。仅PNG/JPEG；原图不会缩小。')
            .fontSize(11).lineHeight(17).fontColor(PHONE_COLOR_MUTED).width('100%')
          if (this.choosing) { this.progress(this.prepareStage) }
          if (this.notice.length > 0) { this.banner(this.notice, true) }
          ForEach(this.batches, (batch: BatchReviewDraft, index: number) => {
            BatchOcrBatchCard({ batch: batch, index: index, revision: this.revision, hasSaving: this.any_saving(),
              onStart: () => { this.start(batch); }, onQuery: (cancel: boolean) => { this.query(batch, cancel); },
              onDiscard: () => { this.discardId = batch.requestId; }, onSaveSelected: () => { this.save_selected(batch); },
              onManual: (sourceIndex: number) => { batchDraftSession.addManual(batch, sourceIndex); this.sync(); },
              onView: (sourceIndex: number) => { this.open_source(batch, sourceIndex); },
              onMoveUp: (sourceIndex: number) => { this.move_up(batch, sourceIndex); },
              onSelectAll: (selected: boolean) => { batchDraftSession.selectAll(batch, selected); this.sync(); },
              onChanged: () => { this.sync(); }, onRetry: (draft: BatchQuestionDraft) => { this.save_one(batch, draft); } })
          }, (batch: BatchReviewDraft) => batch.requestId)
          Text('暂离录题页会保留本次登录的草稿与原图；退出登录会清除，关闭应用后不保证恢复。重新识别会产生额外识别调用，旧草稿仍保留。')
            .fontSize(12).lineHeight(19).fontColor(PHONE_COLOR_MUTED).width('100%')
        }.width('100%').padding({ left: 18, right: 18, top: 10, bottom: 28 })
      }.width('100%').height('100%').scrollBar(BarState.Off)
      if (this.discardId.length > 0) { this.discard_prompt() }
      if (this.viewerImage !== null) {
        BatchOcrSourceViewer({ sourcePath: this.viewerImage.path, sourceWidth: this.viewerImage.width, sourceHeight: this.viewerImage.height,
          title: this.viewerTitle, onClose: () => { this.viewerImage = null; } })
      }
    }.width('100%').layoutWeight(1)
  }
  @Builder
  banner(text: string, danger: boolean) {
    Text(text).fontSize(12).lineHeight(18).fontColor(danger ? PHONE_COLOR_DANGER : PHONE_COLOR_PRIMARY_DEEP)
      .width('100%').padding(12).backgroundColor(danger ? PHONE_COLOR_DANGER_SOFT : PHONE_COLOR_SOFT)
      .borderRadius(PHONE_RADIUS_CONTROL)
  }
  @Builder
  progress(text: string) {
    Row({ space: 10 }) {
      LoadingProgress().width(24).height(24).color(PHONE_COLOR_PRIMARY).accessibilityText(text)
      Text(text).layoutWeight(1).fontSize(13).lineHeight(20).fontColor(PHONE_COLOR_PRIMARY_DEEP)
    }.width('100%').padding(12).backgroundColor(PHONE_COLOR_SOFT).borderRadius(PHONE_RADIUS_CONTROL)
  }
  @Builder
  discard_prompt() {
    Column({ space: 14 }) {
      Text('丢弃本批未保存内容？').fontSize(19).fontWeight(FontWeight.Bold).fontColor(PHONE_COLOR_TITLE)
      Text('会清除本批草稿和自有原图，已保存题目不受影响。保存结果未知的题目也将失去本地恢复资料，请先重试查清。')
        .fontSize(13).lineHeight(20).fontColor(PHONE_COLOR_MUTED)
      Button('继续校对').width('100%').height(44).fontColor(PHONE_COLOR_WHITE).backgroundColor(PHONE_COLOR_PRIMARY)
        .onClick(() => { this.discardId = ''; })
      Button('确认丢弃本批').width('100%').height(44).fontColor(PHONE_COLOR_DANGER).backgroundColor(PHONE_COLOR_DANGER_SOFT)
        .onClick(() => { batchDraftSession.discard(this.discardId); this.discardId = ''; this.sync(); })
    }.width('90%').padding(20).backgroundColor(PHONE_COLOR_CARD)
    .border({ width: 2, color: PHONE_COLOR_BORDER }).borderRadius(PHONE_RADIUS_CARD)
  }
  private open_source(batch: BatchReviewDraft, index: number): void {
    const token: string | null = this.token();
    if (token === null || !batchDraftSession.batches.includes(batch) || index < 0 || index >= batch.images.length ||
      batch.releasedSourceIds.includes('image-' + index.toString())) { return; }
    this.viewerTitle = '来源图 ' + (index + 1).toString() + ' · 放大核对';
    this.viewerImage = batch.images[index];
  }
  private any_saving(): boolean {
    return this.batches.some((batch: BatchReviewDraft) => batch.drafts.some((draft: BatchQuestionDraft) => draft.saving));
  }
  private move_up(batch: BatchReviewDraft, index: number): void {
    if (batch.posted || index < 1) { return; }
    const images: BatchOcrImage[] = batch.images.slice();
    const keys: string[] = batch.imageKeys.slice();
    const previous: BatchOcrImage = images[index - 1];
    const key: string = keys[index - 1];
    images[index - 1] = images[index]; images[index] = previous;
    keys[index - 1] = keys[index]; keys[index] = key;
    batch.images = images; batch.imageKeys = keys; this.sync();
  }
  private async choose(maximum: number): Promise<void> {
    if (this.choosing || !this.active) { return; }
    const token: string | null = this.token(); if (token === null) { return; }
    const generation: number = this.generation;
    const context: common.UIAbilityContext = getContext(this) as common.UIAbilityContext;
    const loaded: BatchOcrImage[] = [];
    this.choosing = true; this.prepareStage = '正在选择原图…'; this.notice = '';
    try {
      const uris: string[] = await selectBatchImages(context, maximum);
      if (!this.current(token, generation)) { return; }
      let total: number = 0;
      for (let index: number = 0; index < uris.length; index++) {
        const uri: string = uris[index];
        this.prepareStage = '正在准备第 ' + (index + 1).toString() + ' / ' + uris.length.toString() + ' 张原图…';
        const input: BatchOcrImage = await copyBatchImage(context, uri, () => this.current(token, generation));
        loaded.push(input);
        if (!this.current(token, generation)) { return; }
        total += input.byteLength;
        if (total > 33554432) { throw new Error('本批原图合计超过32 MiB，请减少图片'); }
      }
      if (loaded.length > 0) {
        batchDraftSession.batches.push(new BatchReviewDraft(loaded.slice())); loaded.splice(0, loaded.length); this.sync();
      }
    } catch (error) {
      if (this.current(token, generation)) { this.notice = (error as Error).message || '图片校验失败，请选择符合限制的PNG/JPEG'; }
    } finally {
      for (const input of loaded) { try { discardBatchImage(input.path); } catch (error) {} }
      if (this.current(token, generation)) { this.choosing = false; }
    }
  }
  private unknown(batch: BatchReviewDraft): void {
    batch.job = { requestId: batch.requestId, status: BatchOcrStatus.UNKNOWN, stage: 'UNKNOWN',
      completedTiles: batch.job === null ? 0 : batch.job.completedTiles, totalTiles: batch.job === null ? 0 : batch.job.totalTiles };
  }
  private async start(batch: BatchReviewDraft): Promise<void> {
    if (batch.posted || batch.busy) { return; }
    const token: string | null = this.token(); if (token === null || !batchDraftSession.batches.includes(batch)) { return; }
    const generation: number = this.generation;
    batch.posted = true; batch.busy = true; batch.clientStage = 'PREPARING'; this.unknown(batch);
    batch.noticeIsError = false;
    batch.notice = '已发起本批识别；响应未知时仅查询，不会自动再次调用。'; this.sync();
    try {
      const result: AuthResult<BatchOcrJob> = await this.api.start(token, batch.requestId, batch.images.slice(), (stage: string) => {
        if (this.current(token, generation)) { batch.clientStage = stage; }
      });
      if (!this.current(token, generation)) { return; }
      if (!result.isSuccess && result.error !== null &&
        batch_start_can_retry_manually('POST', result.error.httpStatus, result.error.serverCode)) {
        batch.posted = false; batch.job = null; batch.noticeIsError = true;
        batch.notice = '本批尚未开始：另一批题图正在处理。原图已保留，待其它批次完成后，请手动点击“开始本批识别”。';
        return;
      }
      this.accept_result(batch, result, token);
    } catch (error) {
      if (this.current(token, generation)) { batch.noticeIsError = true; batch.notice = '提交结果未知，请查询原批次。原图已保留。'; }
    } finally {
      if (this.current(token, generation)) { batch.busy = false; batch.clientStage = ''; this.sync(); this.schedule(); }
    }
  }
  private async query(batch: BatchReviewDraft, cancel: boolean): Promise<void> {
    if (batch.busy || !batch.posted) { return; }
    const token: string | null = this.token(); if (token === null || !batchDraftSession.batches.includes(batch)) { return; }
    const generation: number = this.generation;
    batch.busy = true; batch.clientStage = cancel ? 'CANCELLING' : ''; this.sync();
    try {
      const result: AuthResult<BatchOcrJob> = cancel ? await this.api.cancel(token, batch.requestId) : await this.api.recover(token, batch.requestId);
      if (!this.current(token, generation)) { return; }
      this.accept_result(batch, result, token);
    } catch (error) {
      if (this.current(token, generation)) { this.unknown(batch); batch.noticeIsError = true; batch.notice = '当前结果未知，保留原图和草稿，请再次查询。'; }
    } finally {
      if (this.current(token, generation)) { batch.busy = false; batch.clientStage = ''; this.sync(); this.schedule(); }
    }
  }
  private accept_result(batch: BatchReviewDraft, result: AuthResult<BatchOcrJob>, token: string): void {
    if (!authSession.has_valid_session()) { this.unauthorized(token); return; }
    if (!result.isSuccess || result.data === null) {
      if (result.error !== null && result.error.is_unauthorized()) { this.unauthorized(token); return; }
      this.unknown(batch);
      batch.noticeIsError = true;
      batch.notice = (result.error === null ? '批次结果未知' : result.error.message) + '。请查询原批次；如需新识别，顶部选图会创建额外识别调用。';
      return;
    }
    batchDraftSession.accept(batch, result.data);
    batch.noticeIsError = result.data.status === BatchOcrStatus.FAILED;
    batch.notice = batch_ocr_is_active(result.data.status) ? '只在本页自动查询进度，离开后暂停；不会重复提交。' :
      result.data.status === BatchOcrStatus.UNKNOWN ? '执行状态未知，已有资料已保留；原请求不会自动重跑。新识别需重新选图并产生额外调用。' :
        '结果已保留，默认未勾选；逐题校对后勾选保存。';
  }
  private async save_selected(batch: BatchReviewDraft): Promise<void> {
    const token: string | null = this.token(); if (token === null || !batchDraftSession.batches.includes(batch) || this.any_saving()) { return; }
    const generation: number = this.generation;
    const selected: BatchQuestionDraft[] = batch.drafts.filter((draft: BatchQuestionDraft) =>
      draft.selected && batch_draft_can_add(draft));
    for (const draft of selected) {
      await this.save_one(batch, draft);
      if (!this.current(token, generation)) { return; }
    }
  }
  private async save_one(batch: BatchReviewDraft, draft: BatchQuestionDraft): Promise<void> {
    if (draft.savedQuestionId.length > 0 || this.any_saving() || (!draft.selected && draft.pendingInput === null)) { return; }
    const token: string | null = this.token(); if (token === null || !batchDraftSession.batches.includes(batch)) { return; }
    const generation: number = this.generation;
    if (draft.bodyMarkdown.trim().length === 0 || draft.bodyMarkdown.length > MAX_QUESTION_MARKDOWN_LENGTH) {
      draft.error = '请补全题面，最多20000字符'; this.sync(); return;
    }
    if (draft.pendingInput === null) { draft.pendingInput = new ManualQuestionInput(draft.requestId, draft.subject, draft.bodyMarkdown); }
    const input: ManualQuestionInput = draft.pendingInput;
    draft.saving = true; draft.error = ''; this.sync();
    try {
      const result: AuthResult<QuestionDetail> = await this.learningApi.confirmManualQuestion(token, input);
      if (!this.current(token, generation)) { return; }
      if (!authSession.has_valid_session() || (result.error !== null && result.error.is_unauthorized())) { this.unauthorized(token); return; }
      if (!result.isSuccess || result.data === null) {
        const definitive: boolean = result.error !== null && (result.error.kind === AuthFailureKind.VALIDATION ||
          (result.error.kind === AuthFailureKind.HTTP && (result.error.httpStatus === 400 || result.error.httpStatus === 422)));
        if (definitive) { draft.pendingInput = null; draft.requestId = ManualQuestionInput.create(draft.subject, draft.bodyMarkdown).requestId; }
        draft.error = definitive ? '本题未保存，请修改后再确认：' + (result.error === null ? '' : result.error.message) :
          '保存结果尚未确认，正文已锁定；请重试原提交查清，其他草稿可继续校对。';
      } else {
        draft.savedQuestionId = result.data.questionId; draft.selected = false; draft.pendingInput = null;
        batchDraftSession.releaseConfirmedSources(batch);
        this.onSaved();
        this.toast?.show({ key: 'phone-batch-save-' + draft.itemId, type: 'success', message: '题目已保存到待整理', description: '本批其它草稿仍保留' });
      }
    } catch (error) {
      if (this.current(token, generation)) { draft.error = '保存结果未知，请重试同一原提交；其它草稿可继续校对。'; }
    } finally {
      if (this.current(token, generation)) { draft.saving = false; this.sync(); }
    }
  }
}

/** @ObjectLink batch observes progress, expansion, imported draft lists and source retention. */
@Component
struct BatchOcrBatchCard {
  @ObjectLink batch: BatchReviewDraft;
  @Prop index: number = 0;
  @Prop revision: number = 0;
  @Prop hasSaving: boolean = false;
  onStart: () => void = () => {};
  onQuery: (cancel: boolean) => void = () => {};
  onDiscard: () => void = () => {};
  onSaveSelected: () => void = () => {};
  onSelectAll: (selected: boolean) => void = () => {};
  onManual: (index: number) => void = () => {};
  onView: (index: number) => void = () => {};
  onMoveUp: (index: number) => void = () => {};
  onChanged: () => void = () => {};
  onRetry: (draft: BatchQuestionDraft) => void = () => {};
  build() {
    Column({ space: 12 }) {
      Text('批次 ' + (this.index + 1).toString() + ' · ' + this.status_label(this.batch))
        .fontSize(17).fontWeight(FontWeight.Bold).fontColor(PHONE_COLOR_TITLE).width('100%')
      if (this.batch.busy || (this.batch.job !== null && batch_ocr_is_active(this.batch.job.status))) {
        this.progress(this.progress_label())
      }
      if (this.batch.job !== null && this.batch.job.totalTiles > 0) {
        Text('已处理分片 ' + this.batch.job.completedTiles.toString() + ' / ' + this.batch.job.totalTiles.toString() + '；补识别时分片数可能增加')
          .fontSize(11).lineHeight(17).fontColor(PHONE_COLOR_MUTED).width('100%')
      }
      if (this.batch.notice.length > 0) { this.banner(this.batch.notice, this.batch.noticeIsError) }
      if (this.batch.job !== null && this.batch.job.message !== undefined) { this.banner(this.batch.job.message, true) }
      if (this.batch.reviewResult !== null) {
        ForEach(this.batch.reviewResult.warnings, (warning: string) => { this.banner(warning, false) })
      }
      this.sources()
      if (!this.batch.posted) {
        Button('开始本批识别').width('100%').height(48).fontSize(14)
          .fontColor(PHONE_COLOR_WHITE).backgroundColor(PHONE_COLOR_PRIMARY)
          .enabled(!this.batch.busy).onClick(() => { this.onStart(); })
      } else {
        Row({ space: 8 }) {
          Button(this.batch.busy ? '正在查询…' : '查询原批次').layoutWeight(1).height(44).fontSize(13)
            .backgroundColor(PHONE_COLOR_SOFT).fontColor(PHONE_COLOR_PRIMARY_DEEP).enabled(!this.batch.busy)
            .onClick(() => { this.onQuery(false); })
          if (this.batch.job === null || batch_ocr_is_active(this.batch.job.status) || this.batch.job.status === BatchOcrStatus.UNKNOWN) {
            Button('取消识别').layoutWeight(1).height(44).fontSize(13)
              .backgroundColor(PHONE_COLOR_DANGER_SOFT).fontColor(PHONE_COLOR_DANGER).enabled(!this.batch.busy)
              .onClick(() => { this.onQuery(true); })
          }
        }.width('100%')
      }
      if (this.batch.drafts.length > 0) {
        Row({ space: 10 }) {
          Text('待校对 ' + this.pending_count(this.batch).toString() + ' 题 · 已添加 ' + (this.batch.drafts.length - this.pending_count(this.batch)).toString() + ' 题')
            .fontSize(14).fontColor(PHONE_COLOR_TITLE).layoutWeight(1)
          Button(this.all_selected() ? '取消全选' : '全选').height(44).constraintSize({ minWidth: 64 }).fontSize(13)
            .fontColor(PHONE_COLOR_PRIMARY_DEEP).backgroundColor(PHONE_COLOR_SOFT).enabled(this.eligible_count() > 0 && !this.hasSaving)
            .accessibilityText(this.all_selected() ? '取消选择本批所有待添加题目' : '全选本批所有可添加题目，表示你已检查题目内容')
            .onClick(() => { this.onSelectAll(!this.all_selected()); })
        }.width('100%')
        Text('逐题勾选或全选，表示你已检查题干、公式与完整性。')
          .fontSize(11).lineHeight(17).fontColor(PHONE_COLOR_MUTED).width('100%')
        ForEach(this.batch.drafts, (draft: BatchQuestionDraft, itemIndex: number) => {
          BatchOcrQuestionCard({ draft: draft, batchId: this.batch.requestId, index: itemIndex, hasSaving: this.hasSaving,
            onChanged: () => { this.onChanged(); }, onRetry: () => { this.onRetry(draft); } })
        }, (draft: BatchQuestionDraft) => draft.itemId)
        Button('添加已选 ' + this.selected_count(this.batch).toString() + ' 题').width('100%').height(48)
          .fontSize(15).fontColor(PHONE_COLOR_WHITE).backgroundColor(PHONE_COLOR_PRIMARY)
          .enabled(this.selected_count(this.batch) > 0 && !this.hasSaving)
          .accessibilityText('添加本批已选 ' + this.selected_count(this.batch).toString() + ' 道题到待整理')
          .onClick(() => { this.onSaveSelected(); })
        if (this.saving_count() > 0) { this.progress('正在添加题目，正在确认保存结果…') }
        Text('未勾选题目不会入库；有未保存题目关联的原图会继续保留。')
          .fontSize(11).lineHeight(17).fontColor(PHONE_COLOR_MUTED).width('100%')
      }
      Button('丢弃本批草稿与原图').width('100%').height(44).fontSize(12)
        .fontColor(PHONE_COLOR_DANGER).backgroundColor(Color.Transparent)
        .enabled(!this.batch.busy && !this.hasSaving && (this.batch.job === null || !batch_ocr_is_active(this.batch.job.status)))
        .onClick(() => { this.onDiscard(); })
    }.width('100%').padding(14).backgroundColor(PHONE_COLOR_CARD)
    .border({ width: 1, color: PHONE_COLOR_BORDER }).borderRadius(PHONE_RADIUS_CARD)
  }
  @Builder
  banner(text: string, danger: boolean) {
    Text(text).fontSize(12).lineHeight(18).fontColor(danger ? PHONE_COLOR_DANGER : PHONE_COLOR_PRIMARY_DEEP)
      .width('100%').padding(12).backgroundColor(danger ? PHONE_COLOR_DANGER_SOFT : PHONE_COLOR_SOFT)
      .borderRadius(PHONE_RADIUS_CONTROL)
  }
  @Builder
  sources() {
    Column({ space: 8 }) {
      ForEach(this.batch.images, (input: BatchOcrImage, index: number) => {
        Row({ space: 8 }) {
          if (!this.batch.releasedSourceIds.includes('image-' + index.toString())) {
            Button({ type: ButtonType.Normal }) {
              Image(input.path).width(50).height(62).objectFit(ImageFit.Contain).borderRadius(6)
            }.width(58).height(70).padding(4).backgroundColor(PHONE_COLOR_SOFT).borderRadius(8)
            .accessibilityText('放大查看来源图 ' + (index + 1).toString()).onClick(() => { this.onView(index); })
          }
          Column({ space: 3 }) {
            Text('来源图 ' + (index + 1).toString()).fontSize(12).fontColor(PHONE_COLOR_TITLE)
              .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            Text(input.width.toString() + '×' + input.height.toString() + ' · ' + (input.byteLength / 1048576).toFixed(1) + ' MiB')
              .fontSize(10).fontColor(PHONE_COLOR_MUTED).maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis })
            if (this.batch.releasedSourceIds.includes('image-' + index.toString())) { Text('相关题目已保存，原图已清理').fontSize(10).fontColor(PHONE_COLOR_MUTED) }
          }.layoutWeight(1).alignItems(HorizontalAlign.Start)
          if (this.batch.posted && !this.batch.busy && this.batch.job !== null && !batch_ocr_is_active(this.batch.job.status) && !this.batch.releasedSourceIds.includes('image-' + index.toString())) {
            Button('从此图手动补题').height(44).fontSize(10).fontColor(PHONE_COLOR_PRIMARY_DEEP).backgroundColor(PHONE_COLOR_SOFT)
              .onClick(() => { this.onManual(index); })
          }
          if (!this.batch.posted && index > 0) {
            Button('上移').height(44).fontSize(11).fontColor(PHONE_COLOR_PRIMARY_DEEP).backgroundColor(PHONE_COLOR_SOFT)
              .onClick(() => { this.onMoveUp(index); })
          }
        }.width('100%')
      }, (input: BatchOcrImage, index: number) => this.batch.imageKeys[index] + '-' + index.toString())
      Button(this.batch.expanded ? '收起来源原图与分片' : '展开来源原图与分片坐标').width('100%').height(44).fontSize(12)
        .fontColor(PHONE_COLOR_PRIMARY_DEEP).backgroundColor(PHONE_COLOR_SOFT)
        .onClick(() => { this.batch.expanded = !this.batch.expanded; this.onChanged(); })
      if (this.batch.expanded) {
        Text('诊断编号：' + this.batch.requestId).fontSize(10).fontColor(PHONE_COLOR_MUTED).width('100%').copyOption(CopyOptions.InApp)
        ForEach(this.batch.images, (input: BatchOcrImage, index: number) => {
          if (!this.batch.releasedSourceIds.includes('image-' + index.toString())) {
            Text('原图 image-' + index.toString() + '（点击下方按钮放大核对细小公式）').fontSize(11).fontColor(PHONE_COLOR_MUTED)
            Button('放大查看来源图 ' + (index + 1).toString()).width('100%').height(44).fontSize(13)
              .fontColor(PHONE_COLOR_PRIMARY_DEEP).backgroundColor(PHONE_COLOR_SOFT).onClick(() => { this.onView(index); })
            Image(input.path).width('100%').aspectRatio(input.width / input.height).objectFit(ImageFit.Contain)
              .accessibilityText('来源图 ' + (index + 1).toString() + ' 的完整原图')
          }
        }, (input: BatchOcrImage, index: number) => this.batch.imageKeys[index] + '-full')
        if (this.batch.reviewResult !== null) {
          ForEach(this.batch.reviewResult.tiles, (tile: BatchOcrTile) => {
            Column({ space: 6 }) {
              Text(tile.tileId + ' · ' + tile.sourceId + ' · (' + tile.x.toString() + ', ' + tile.y.toString() + ') ' +
                tile.width.toString() + '×' + tile.height.toString() + (tile.repair ? ' · 补识别' : ''))
                .fontSize(11).fontColor(PHONE_COLOR_PRIMARY_DEEP).width('100%')
              Text(tile.markdown.length > 0 ? tile.markdown : '此分片未取得文字').fontSize(12).fontColor(PHONE_COLOR_MUTED)
                .width('100%').copyOption(CopyOptions.InApp)
            }.padding(8).width('100%').backgroundColor(PHONE_COLOR_SOFT).borderRadius(8)
          }, (tile: BatchOcrTile) => tile.tileId)
        }
      }
    }.width('100%')
  }
  @Builder
  progress(text: string) {
    Row({ space: 10 }) {
      LoadingProgress().width(24).height(24).color(PHONE_COLOR_PRIMARY).accessibilityText(text)
      Text(text).layoutWeight(1).fontSize(13).lineHeight(20).fontColor(PHONE_COLOR_PRIMARY_DEEP)
    }.width('100%').padding(12).backgroundColor(PHONE_COLOR_SOFT).borderRadius(PHONE_RADIUS_CONTROL)
  }
  private progress_label(): string {
    if (this.batch.clientStage.length > 0) { return batch_ocr_stage_label(this.batch.clientStage); }
    if (this.batch.job !== null && batch_ocr_is_active(this.batch.job.status)) { return batch_ocr_stage_label(this.batch.job.stage); }
    return '正在查询原批次…';
  }
  private eligible_count(): number {
    return this.revision >= 0 ? this.batch.drafts.filter((draft: BatchQuestionDraft) => batch_draft_can_add(draft)).length : 0;
  }
  private all_selected(): boolean {
    const eligible: BatchQuestionDraft[] = this.revision >= 0 ? this.batch.drafts.filter((draft: BatchQuestionDraft) => batch_draft_can_add(draft)) : [];
    return eligible.length > 0 && eligible.every((draft: BatchQuestionDraft) => draft.selected);
  }
  private saving_count(): number {
    return this.revision >= 0 ? this.batch.drafts.filter((draft: BatchQuestionDraft) => draft.saving).length : 0;
  }
  private status_label(batch: BatchReviewDraft): string {
    if (batch.busy && batch.clientStage.length > 0) { return batch_ocr_stage_label(batch.clientStage); }
    if (!batch.posted) { return '待开始'; }
    if (batch.job === null) { return '提交结果未知'; }
    if (batch.job.status === BatchOcrStatus.QUEUED) { return '排队中'; }
    if (batch.job.status === BatchOcrStatus.RUNNING) { return '识别中'; }
    if (batch.job.status === BatchOcrStatus.COMPLETED) { return '识别完成，请逐题校对'; }
    if (batch.job.status === BatchOcrStatus.INCOMPLETE) { return '识别不完整，请对照原图补全'; }
    if (batch.job.status === BatchOcrStatus.EMPTY) { return '未识别到题目，原图已保留'; }
    if (batch.job.status === BatchOcrStatus.FAILED) { return '识别失败，已取得内容仍可校对'; }
    if (batch.job.status === BatchOcrStatus.CANCELLED) { return '已取消，已有草稿仍保留'; }
    return '结果未知，仅查询原批次恢复';
  }
  private pending_count(batch: BatchReviewDraft): number {
    return this.revision >= 0 ? batch.drafts.filter((draft: BatchQuestionDraft) => draft.savedQuestionId.length === 0).length : 0;
  }
  private selected_count(batch: BatchReviewDraft): number {
    return this.revision >= 0 ? batch.drafts.filter((draft: BatchQuestionDraft) => draft.selected && batch_draft_can_add(draft)).length : 0;
  }
}

/** 稳定itemId下的ObjectLink编辑器，输入时只更新属性，不重建TextArea/焦点。 */
@Component
struct BatchOcrQuestionCard {
  @ObjectLink draft: BatchQuestionDraft;
  @Prop batchId: string = '';
  @Prop index: number = 0;
  @Prop hasSaving: boolean = false;
  onChanged: () => void = () => {};
  onRetry: () => void = () => {};
  build() {
    Column({ space: 10 }) {
      Row({ space: 6 }) {
        if (this.draft.savedQuestionId.length === 0) {
          Checkbox({ name: this.draft.itemId, group: this.batchId })
            .select(this.draft.selected && batch_draft_can_add(this.draft)).width(44).height(44).padding(9)
            .selectedColor(PHONE_COLOR_PRIMARY).enabled(batch_draft_can_add(this.draft))
            .accessibilityText(this.question_label() + '，勾选表示已校对并选择添加此题')
            .onChange((value: boolean) => {
              if (batch_draft_can_add(this.draft)) { this.draft.selected = value; this.onChanged(); }
            })
        }
        Button({ type: ButtonType.Normal }) {
          Column({ space: 6 }) {
            Row({ space: 8 }) {
              Text(this.question_label()).fontSize(17).fontWeight(FontWeight.Bold).fontColor(PHONE_COLOR_PRIMARY_DEEP)
                .maxLines(1).textOverflow({ overflow: TextOverflow.Ellipsis }).layoutWeight(1)
              Text(this.draft.savedQuestionId.length > 0 ? '已添加' : this.draft.expanded ? '收起  −' : '展开  +')
                .fontSize(12).fontColor(PHONE_COLOR_PRIMARY_DEEP)
            }.width('100%')
            Text(batch_question_summary(this.draft.bodyMarkdown)).fontSize(13).lineHeight(20).fontColor(PHONE_COLOR_MUTED)
              .maxLines(2).textOverflow({ overflow: TextOverflow.Ellipsis }).width('100%').textAlign(TextAlign.Start)
          }.width('100%').alignItems(HorizontalAlign.Start)
        }.layoutWeight(1).constraintSize({ minHeight: 72 }).padding({ left: 6, right: 6, top: 8, bottom: 8 })
        .backgroundColor(Color.Transparent).borderRadius(8)
        .accessibilityText(this.question_label() + '，' + (this.draft.expanded ? '已展开，点击折叠' : '已折叠，点击展开编辑和预览'))
        .onClick(() => { this.draft.expanded = !this.draft.expanded; this.onChanged(); })
      }.width('100%')
      if (this.draft.error.length > 0) { this.banner(this.draft.error, true) }
      if (this.draft.saving) {
        Row({ space: 8 }) {
          LoadingProgress().width(22).height(22).color(PHONE_COLOR_PRIMARY)
          Text('正在添加这道题…').fontSize(12).fontColor(PHONE_COLOR_PRIMARY_DEEP)
        }.width('100%').padding(8)
      }
      if (this.draft.pendingInput !== null && this.draft.savedQuestionId.length === 0 && !this.draft.saving) {
        this.banner('本题已有提交，正文和学科暂时锁定。请重试原提交查清结果。', false)
        Button('重试原提交，查清保存结果').width('100%').height(44).fontSize(12)
          .fontColor(PHONE_COLOR_PRIMARY_DEEP).backgroundColor(PHONE_COLOR_SOFT).enabled(!this.hasSaving)
          .accessibilityText(this.question_label() + '，重试原提交查清保存结果')
          .onClick(() => { this.onRetry(); })
      }
      if (this.draft.expanded) {
        Divider().color(PHONE_COLOR_BORDER)
        Text('来源：' + this.draft.sourceIds.map((id: string) => '图' + (Number(id.substring(6)) + 1).toString()).join('、') + '；展开来源可核对分片与坐标')
          .fontSize(11).lineHeight(17).fontColor(PHONE_COLOR_MUTED).width('100%')
        ForEach(this.draft.warnings, (warning: string) => { this.banner(warning, false) })
        Row({ space: 8 }) {
          Button(this.draft.subject === LearningSubject.HIGHER_MATHEMATICS ? '学科：高等数学' : '学科：其他')
            .layoutWeight(1).height(44).fontSize(12).fontColor(PHONE_COLOR_PRIMARY_DEEP).backgroundColor(PHONE_COLOR_SOFT)
            .enabled(this.draft.pendingInput === null && this.draft.savedQuestionId.length === 0)
            .accessibilityText(this.question_label() + ' 的学科，点击切换')
            .onClick(() => { this.draft.changeSubject(this.draft.subject === LearningSubject.OTHER ? LearningSubject.HIGHER_MATHEMATICS : LearningSubject.OTHER); this.onChanged(); })
          Button(this.draft.preview ? '编辑 Markdown' : '预览公式')
            .layoutWeight(1).height(44).fontSize(12).fontColor(PHONE_COLOR_PRIMARY_DEEP).backgroundColor(PHONE_COLOR_SOFT)
            .accessibilityText(this.question_label() + (this.draft.preview ? '，编辑题目 Markdown' : '，预览题目和公式'))
            .onClick(() => { this.draft.preview = !this.draft.preview; this.onChanged(); })
        }.width('100%')
        if (this.draft.preview || this.draft.savedQuestionId.length > 0) {
          QuestionMarkdownView({ bodyMarkdown: this.draft.bodyMarkdown, fontSize: 15, autoHeight: true })
        } else {
          TextArea({ text: this.draft.bodyMarkdown, placeholder: '例如：求 $x+1=2$ 中的 $x$…' })
            .width('100%').height(240).fontSize(15).backgroundColor(PHONE_COLOR_SOFT)
            .maxLength(MAX_QUESTION_MARKDOWN_LENGTH).enabled(this.draft.pendingInput === null)
            .accessibilityText(this.question_label() + ' 的题干、条件与 Markdown 公式')
            .onChange((value: string) => { this.change_body(value); })
        }
      }
    }.padding(12).width('100%').backgroundColor(PHONE_COLOR_CARD)
    .border({ width: { left: 3, top: 1, right: 1, bottom: 1 }, color: PHONE_COLOR_BORDER }).borderRadius(PHONE_RADIUS_CONTROL)
  }
  private question_label(): string { return batch_question_label(this.draft.bodyMarkdown, this.index); }
  @Builder
  banner(text: string, danger: boolean) {
    Text(text).fontSize(12).lineHeight(18).fontColor(danger ? PHONE_COLOR_DANGER : PHONE_COLOR_PRIMARY_DEEP)
      .width('100%').padding(12).backgroundColor(danger ? PHONE_COLOR_DANGER_SOFT : PHONE_COLOR_SOFT)
      .borderRadius(PHONE_RADIUS_CONTROL)
  }

  private change_body(value: string): void {
    if (this.draft.changeBody(value)) { this.onChanged(); }
  }
}
