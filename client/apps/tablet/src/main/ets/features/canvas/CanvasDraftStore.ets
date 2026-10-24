import { util } from '@kit.ArkTS';
import {
  AnswerState, CanvasEditableDocument, CanvasLocalFile, CanvasView, FrozenAnswerUpload,
  canvas_exists, canvas_read, canvas_text, canvas_utf8, canvas_write, validate_canvas_document
} from 'common';

export interface LocalCanvasDraft {
  schemaVersion: number;
  accountId: string;
  questionId: string;
  document: CanvasEditableDocument;
  files: CanvasLocalFile[];
  view: CanvasView;
  baseFileRef: string | null;
  answerState: AnswerState;
  dirty: boolean;
  pendingUpload: FrozenAnswerUpload | null;
}
export class CanvasDraftStore {
  readonly directory: string;
  private accountId: string;
  private questionId: string;
  constructor(filesDir: string, accountId: string, questionId: string) {
    if (!/^[1-9][0-9]*$/.test(accountId) || !/^[1-9][0-9]*$/.test(questionId)) { throw new Error('本机草稿归属无效'); }
    this.accountId = accountId; this.questionId = questionId;
    this.directory = filesDir + '/m2-canvas/' + accountId + '/' + questionId;
  }
  load(): LocalCanvasDraft | null {
    const path: string = this.directory + '/draft.json';
    if (!canvas_exists(path)) { return null; }
    const draft: LocalCanvasDraft = JSON.parse(canvas_text(canvas_read(path,67108864))) as LocalCanvasDraft;
    if (draft.schemaVersion !== 1 || draft.accountId !== this.accountId || draft.questionId !== this.questionId ||
      typeof draft.dirty !== 'boolean' || (draft.answerState !== AnswerState.NOT_STARTED &&
      draft.answerState !== AnswerState.IN_PROGRESS && draft.answerState !== AnswerState.COMPLETED) ||
      (draft.baseFileRef !== null && !/^[0-9a-f-]{36}$/.test(draft.baseFileRef)) || !Array.isArray(draft.files) || !draft.view || !Number.isFinite(draft.view.x) || !Number.isFinite(draft.view.y) ||
      !Number.isFinite(draft.view.scale) || draft.view.scale <= 0 || draft.view.scale > 8) { throw new Error('本机草稿校验失败，原文件已保留'); }
    validate_canvas_document(draft.document);
    this.check_files(draft.files);
    if (draft.pendingUpload !== null) {
      if (!draft.pendingUpload || typeof draft.pendingUpload.manifestJson !== 'string') { throw new Error('冻结保存包无效'); }
      this.check_files(draft.pendingUpload.files);
    }
    return draft;
  }
  save(draft: LocalCanvasDraft): void {
    this.check_files(draft.files);
    canvas_write(this.directory + '/draft.json', canvas_utf8(JSON.stringify(draft)));
  }
  preserve(draft: LocalCanvasDraft): void {
    canvas_write(this.directory + '/preserved/' + util.generateRandomUUID(true).toLowerCase() + '.json', canvas_utf8(JSON.stringify(draft)));
  }
  private check_files(files: CanvasLocalFile[]): void {
    if (!Array.isArray(files) || files.length > 512) { throw new Error('本机图块列表无效'); }
    for (let i: number = 0; i < files.length; i++) {
      if (!files[i].filePath.startsWith(this.directory + '/') || files[i].filePath.includes('/../') ||
        !/^(question|chunk)_[0-9]{4}$/.test(files[i].partName)) { throw new Error('本机图块路径无效'); }
    }
  }
}
