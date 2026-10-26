import { util } from '@kit.ArkTS';
import { BatchOcrImage, BatchOcrJob, BatchOcrQuestion, BatchOcrResult, BatchOcrStatus, BatchOcrTile, LearningSubject, ManualQuestionInput, MAX_QUESTION_MARKDOWN_LENGTH } from 'common';
import { discardBatchImage } from './BatchQuestionImageSource';

@Observed
export class BatchQuestionDraft {
  itemId: string;
  requestId: string = util.generateRandomUUID(true).toLowerCase();
  bodyMarkdown: string;
  subject: LearningSubject = LearningSubject.HIGHER_MATHEMATICS;
  sourceIds: string[];
  tileIds: string[];
  warnings: string[];
  selected: boolean = false;
  preview: boolean = false;
  expanded: boolean = false;
  pendingInput: ManualQuestionInput | null = null;
  saving: boolean = false;
  savedQuestionId: string = '';
  error: string = '';
  changeBody(value: string): boolean {
    if (this.pendingInput !== null || this.savedQuestionId.length > 0 || value === this.bodyMarkdown) { return false; }
    this.bodyMarkdown = value; this.selected = false; this.error = ''; return true;
  }
  changeSubject(subject: LearningSubject): boolean {
    if (this.pendingInput !== null || this.savedQuestionId.length > 0 || subject === this.subject) { return false; }
    this.subject = subject; this.selected = false; this.error = ''; return true;
  }
  constructor(question: BatchOcrQuestion) {
    this.itemId = question.itemId;
    this.bodyMarkdown = question.bodyMarkdown;
    this.sourceIds = question.sourceIds.slice();
    this.tileIds = question.tileIds.slice();
    this.warnings = question.warnings.slice();
    if (question.needsReview) { this.warnings.unshift('识别服务提示需重点人工校对'); }
  }
}
@Observed
export class BatchReviewDraft {
  requestId: string = util.generateRandomUUID(true).toLowerCase();
  images: BatchOcrImage[];
  imageKeys: string[];
  releasedSourceIds: string[] = [];
  posted: boolean = false;
  busy: boolean = false;
  drafts: BatchQuestionDraft[] = [];
  expanded: boolean = false;
  resultImported: boolean = false;
  reviewResult: BatchOcrResult | null = null;
  job: BatchOcrJob | null = null;
  notice: string = '';
  noticeIsError: boolean = false;
  clientStage: string = '';
  constructor(images: BatchOcrImage[]) { this.images = images; this.imageKeys = images.map((input: BatchOcrImage) => input.path); }
}
/** 只保存当前登录的进程内资料。离开录题页不丢弃，换token即清除旧会话自有题图。 */
export class BatchDraftSession {
  private ownerToken: string = '';
  batches: BatchReviewDraft[] = [];
  bind(token: string): void {
    if (this.ownerToken === token) { return; }
    for (const batch of this.batches) { this.cleanup(batch); }
    this.batches = [];
    this.ownerToken = token;
  }
  owns(token: string): boolean { return token.length > 0 && this.ownerToken === token; }
  cleanup(batch: BatchReviewDraft): void {
    for (const input of batch.images) {
      if (input.path.length === 0) { continue; }
      try { discardBatchImage(input.path); } catch (error) { /* 缓存由系统回收，不扩大删除范围。 */ }
    }
  }
  discard(requestId: string): void {
    const batch: BatchReviewDraft | undefined = this.batches.find((item: BatchReviewDraft) => item.requestId === requestId);
    if (batch !== undefined) { this.cleanup(batch); }
    this.batches = this.batches.filter((item: BatchReviewDraft) => item.requestId !== requestId);
  }
  /** 已有itemId不覆盖，避免轮询回写破坏人工编辑、勾选和未知提交快照。 */
  accept(batch: BatchReviewDraft, job: BatchOcrJob): void {
    batch.job = job;
    if (batch.resultImported || job.result === undefined || job.status === BatchOcrStatus.QUEUED || job.status === BatchOcrStatus.RUNNING) { return; }
    batch.resultImported = true;
    batch.reviewResult = job.result;
    for (const question of job.result.questions) {
      if (!batch.drafts.some((draft: BatchQuestionDraft) => draft.itemId === question.itemId)) {
        batch.drafts = batch.drafts.concat([new BatchQuestionDraft(question)]);
      }
    }
  }
  addManual(batch: BatchReviewDraft, sourceIndex: number): BatchQuestionDraft | null {
    if (!this.batches.includes(batch) || sourceIndex < 0 || sourceIndex >= batch.images.length || batch.busy ||
      !batch.posted || batch.job === null || batch.job.status === BatchOcrStatus.QUEUED || batch.job.status === BatchOcrStatus.RUNNING) { return null; }
    const sourceId: string = 'image-' + sourceIndex.toString();
    if (batch.releasedSourceIds.includes(sourceId)) { return null; }
    const tileIds: string[] = batch.reviewResult === null ? [] : batch.reviewResult.tiles
      .filter((tile: BatchOcrTile) => tile.sourceId === sourceId).map((tile: BatchOcrTile) => tile.tileId);
    const draft: BatchQuestionDraft = new BatchQuestionDraft({ itemId: 'manual-' + util.generateRandomUUID(true).toLowerCase(),
      bodyMarkdown: '', sourceIds: [sourceId], tileIds: tileIds, needsReview: true, warnings: ['根据此图及已有分片手动补全题目，未发起新的识别。'] });
    batch.drafts = batch.drafts.concat([draft]);
    return draft;
  }
  selectAll(batch: BatchReviewDraft, selected: boolean): void {
    if (!this.batches.includes(batch)) { return; }
    for (const draft of batch.drafts) { draft.selected = selected && batch_draft_can_add(draft); }
  }
  releaseConfirmedSources(batch: BatchReviewDraft): void {
    if (batch.job === null || batch.reviewResult === null || batch.reviewResult.incomplete ||
      batch.job.status !== BatchOcrStatus.COMPLETED) { return; }
    for (let index: number = 0; index < batch.images.length; index++) {
      const id: string = 'image-' + index.toString();
      const related: BatchQuestionDraft[] = batch.drafts.filter((draft: BatchQuestionDraft) => draft.sourceIds.includes(id));
      if (related.length > 0 && related.every((draft: BatchQuestionDraft) => draft.savedQuestionId.length > 0)) {
        try { discardBatchImage(batch.images[index].path); } catch (error) { /* 自有cache交系统回收。 */ }
        batch.releasedSourceIds = batch.releasedSourceIds.includes(id) ? batch.releasedSourceIds : batch.releasedSourceIds.concat([id]);
      }
    }
  }
}
export const batchDraftSession: BatchDraftSession = new BatchDraftSession();

/** 可选列表、全选和提交队列共同使用同一规则；未知提交只能由原提交重试入口处理。 */
export function batch_draft_can_add(draft: BatchQuestionDraft): boolean {
  return draft.savedQuestionId.length === 0 && draft.pendingInput === null && !draft.saving &&
    draft.bodyMarkdown.trim().length > 0 && draft.bodyMarkdown.length <= MAX_QUESTION_MARKDOWN_LENGTH;
}
/** 只读取正文开头的明确大题号；括号小问不冒充原题号，也不在前端拆题。 */
export function batch_question_label(body: string, index: number): string {
  const match: RegExpMatchArray | null = body.trimStart().replace(/^#{1,6}\s*/, '').match(
    /^(第\s*[0-9一二三四五六七八九十百零〇]+\s*题|[0-9]{1,3}[、．.](?![0-9])|[一二三四五六七八九十百零〇]+[、．])/);
  return match === null ? '题序 ' + (index + 1).toString() : match[0].replace(/\s+/g, '');
}
export function batch_question_summary(body: string): string {
  const plain: string = body.replace(/^\s*#{1,6}\s*/, '').replace(/[\r\n\t]+/g, ' ').replace(/\s{2,}/g, ' ').trim();
  return plain.length === 0 ? '展开后补全这道题的题干、条件和公式' : plain.length > 160 ? plain.substring(0, 160) + '…' : plain;
}
export function batch_ocr_stage_label(stage: string): string {
  if (stage === 'PREPARING') { return '正在准备与校验原图…'; }
  if (stage === 'UPLOADING') { return '正在上传原图，等待接收…'; }
  if (stage === 'QUEUED') { return '正在等待识别…'; }
  if (stage === 'VALIDATING') { return '正在校验原图…'; }
  if (stage === 'OCR') { return '正在识别题图…'; }
  if (stage === 'REPAIR') { return '正在补识别题目接缝…'; }
  if (stage === 'ASSEMBLE' || stage === 'RECONSTRUCTING') { return '正在整理题目…'; }
  if (stage === 'CANCELLING') { return '正在取消识别…'; }
  if (stage === 'DONE') { return '已完成'; }
  if (stage === 'CANCELLED') { return '已取消'; }
  if (stage === 'UNKNOWN') { return '执行状态未知'; }
  return '正在处理题图…';
}

/** 仅首次POST明确未受理的账号活动批次冲突或队满可手动开始；其它错误仍保留原受理身份查询。 */
export function batch_start_can_retry_manually(method: string, httpStatus: number, serverCode: string): boolean {
  return method === 'POST' && ((httpStatus === 409 && serverCode === 'A0455') ||
    (httpStatus === 429 && serverCode === 'A0502'));
}
