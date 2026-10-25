import { common } from '@kit.AbilityKit';
import {
  AnswerManifest, AnswerState, AuthError, AuthResult, CanvasEditableDocument, CanvasImageBlock, CanvasLocalFile, CanvasView,
  FrozenAnswerUpload, FrozenTutorAnswer, TutorApi, TutorTurn, QuestionDetail, QuestionRasterHost, QuestionRasterResult, SolutionApi, SolutionDetail,
  UserProfile, authSession, canvas_exists, canvas_read, canvas_text, canvas_sha256, canvas_utf8, canvas_write, clone_canvas_document,
  valid_canvas_view, validate_answer_manifest, validate_canvas_document
} from 'common';
import { CanvasWorkspaceActions, CanvasWorkspaceState, TabletCanvasController } from './CanvasWorkspaceController';
import { CanvasDraftStore, LocalCanvasDraft } from './CanvasDraftStore';
import { CanvasFreezeHandle, CanvasSurface } from './CanvasSurface';
import { export_canvas } from './CanvasExporter';
import { TABLET_COLOR_BACKGROUND, TABLET_COLOR_BORDER, TABLET_COLOR_CARD, TABLET_COLOR_DANGER,
  TABLET_COLOR_PRIMARY, TABLET_COLOR_TEXT, TABLET_RADIUS } from '../../theme/TabletTheme';

export { CanvasWorkspaceState, TabletCanvasController } from './CanvasWorkspaceController';
interface RasterRequest { key: string; generation: number; accountId: string; questionId: string; body: string; directory: string; }

interface CanvasUploadSlot { done: Promise<void>; finish: () => void; }
// 仅一个进程内发送槽；没有命令队列、后台任务或持久调度。
let activeCanvasUpload: CanvasUploadSlot | null = null;
class TutorFreezeContext {
  accountId: string;
  questionId: string;
  turnId: string;
  token: string;
  generation: number;
  mountKey: string;
  directory: string;
  files: CanvasLocalFile[];
  document: CanvasEditableDocument | null = null;
  frozen: FrozenTutorAnswer | null = null;
  freezePromise: Promise<AuthResult<FrozenTutorAnswer>> | null = null;
  sendPromise: Promise<AuthResult<TutorTurn>> | null = null;
  phase: string = 'capturing';
  cancelled: boolean = false;
  constructor(accountId: string, questionId: string, turnId: string, token: string, generation: number,
    mountKey: string, directory: string, files: CanvasLocalFile[]) {
    this.accountId = accountId; this.questionId = questionId; this.turnId = turnId; this.token = token;
    this.generation = generation; this.mountKey = mountKey; this.directory = directory;
    this.files = files.map((file: CanvasLocalFile): CanvasLocalFile => ({ partName: file.partName, filePath: file.filePath }));
  }
}


/** 固定题面与可编辑笔迹共用一个原生世界画布；页面按钮只经 controller 调用真实结果。 */
@Component
export struct TabletCanvasWorkspace {
  @Prop @Watch('question_changed') question: QuestionDetail | null = null;
  controller: TabletCanvasController = new TabletCanvasController();
  onStateChange: (state: CanvasWorkspaceState) => void = () => {};
  onSessionExpired: () => void = () => {};
  @State private document: CanvasEditableDocument | null = null;
  @State private files: CanvasLocalFile[] = [];
  @State private loading: boolean = true;
  @State private rasterizing: boolean = false;
  @State private busy: boolean = false;
  @State private completed: boolean = false;
  @State private message: string = '';
  @State private needsRefresh: boolean = false;
  @State private mountKey: string = '';
  @State private rasterRequest: RasterRequest = { key: '', generation: 0, accountId: '', questionId: '', body: '', directory: '' };
  private api: SolutionApi = SolutionApi.create_default();
  private tutorApi: TutorApi = TutorApi.create_default();
  private freezeHandle: CanvasFreezeHandle | null = null;
  private tutorContext: TutorFreezeContext | null = null;
  private store: CanvasDraftStore | null = null;
  private accountId: string = '';
  private questionId: string = '';
  private token: string = '';
  private bodyHash: string = '';
  private generation: number = 0;
  private alive: boolean = false;
  private dirty: boolean = false;
  private localSaved: boolean = false;
  private canUndo: boolean = false;
  private view: CanvasView = { x: -24, y: -24, scale: 1 };
  private initialView: CanvasView | null = null;
  private baseFileRef: string | null = null;
  private state: AnswerState = AnswerState.NOT_STARTED;
  private pending: FrozenAnswerUpload | null = null;
  private record: SolutionDetail | null = null;
  private actions: CanvasWorkspaceActions = {
    freezeAtPenUp: (questionId: string, turnId: string) => this.freeze_at_pen_up(questionId,turnId),
    sendFrozenTutorAnswer: (frozen: FrozenTutorAnswer) => this.send_frozen_tutor_answer(frozen),
    cancelTutorFreeze: (questionId: string, turnId: string) => this.cancel_tutor_freeze(questionId,turnId),
    save: () => this.save(), markCompleted: () => this.mark_completed(),
    continueWriting: () => this.continue_writing(), flushLocal: () => this.flush_local()
  };

  aboutToAppear(): void { this.alive = true; this.controller.attach(this.actions); this.initialize(false); }
  aboutToDisappear(): void {
    this.cancel_tutor_context();
    // 存入已经捕获的账号目录，不在退出时重新查询账号，避免跨账号写入。
    if (this.document !== null && this.store !== null && !this.persist()) { this.emit(); }
    this.alive = false; this.generation++; this.controller.detach(this.actions);
  }
  private question_changed(): void {
    if (!this.alive) { return; }
    if (this.document !== null && this.store !== null && !this.persist()) {
      this.message = '上一题草稿写入失败，已保留当前画布，请先重试本机保存'; this.emit(); return;
    }
    this.initialize(false);
  }
  private current(generation: number): boolean {
    return this.alive && generation === this.generation && authSession.matches_access_token(this.token);
  }
  private expire(error: AuthError): void {
    if (error.is_unauthorized() && authSession.clear_session_if_token(this.token)) { this.onSessionExpired(); }
  }
  private emit(): void {
    const result: CanvasWorkspaceState = new CanvasWorkspaceState();
    result.isReady = this.document !== null && !this.loading && !this.rasterizing;
    result.isBusy = this.busy || this.loading || this.rasterizing || this.tutor_working();
    result.hasUnsavedChanges = this.dirty;
    result.hasSavedSolution = this.baseFileRef !== null;
    result.canComplete = result.isReady && !result.isBusy && !this.needsRefresh && !this.completed && this.baseFileRef !== null && !this.dirty;
    result.isCompleted = this.completed; result.canUndo = this.canUndo;
    result.localDraftSaved = this.localSaved; result.errorMessage = this.message;
    if (this.alive) { this.onStateChange(result); }
  }
  private draft(): LocalCanvasDraft {
    if (this.document === null) { throw new Error('画布尚未准备完成'); }
    return { schemaVersion: 1, accountId: this.accountId, questionId: this.questionId,
      document: this.document, files: this.files, view: this.view, baseFileRef: this.baseFileRef,
      answerState: this.state, dirty: this.dirty, pendingUpload: this.pending };
  }
  private persist(): boolean {
    try {
      if (this.store === null || this.document === null) { return false; }
      this.store.save(this.draft()); this.localSaved = true; return true;
    } catch (error) { this.localSaved = false; this.message = '本机草稿写入失败，请保留当前页面后重试'; return false; }
  }
  private async verify_files(doc: CanvasEditableDocument, files: CanvasLocalFile[], generation: number): Promise<void> {
    validate_canvas_document(doc);
    for (let i: number = 0; i < doc.questionLayout.rasterBlocks.length; i++) {
      const block = doc.questionLayout.rasterBlocks[i];
      const file: CanvasLocalFile | undefined = files.find((item: CanvasLocalFile) => item.partName === block.partName);
      if (file === undefined || await canvas_sha256(canvas_read(file.filePath)) !== block.sha256) { throw new Error('本机固定题面底图缺失或损坏'); }
      if (!this.current(generation)) { throw new Error('画布已切换'); }
    }
  }
  private install_local(draft: LocalCanvasDraft): void {
    this.document = draft.document; this.files = draft.files; this.initialView = valid_canvas_view(draft.view) ? draft.view : null; this.view = draft.view;
    this.baseFileRef = draft.baseFileRef; this.state = draft.answerState; this.completed = this.state === AnswerState.COMPLETED;
    this.dirty = draft.dirty; this.pending = draft.pendingUpload; this.localSaved = true;
    this.mountKey = this.accountId + ':' + this.questionId + ':' + this.generation.toString();
  }
  private async initialize(refresh: boolean): Promise<void> {
    this.cancel_tutor_context(); this.freezeHandle = null;
    const generation: number = ++this.generation;
    this.document = null; this.files = []; this.loading = true; this.rasterizing = false; this.busy = false;
    this.message = ''; this.needsRefresh = false; this.dirty = false; this.localSaved = false; this.canUndo = false;
    this.pending = null; this.record = null; this.baseFileRef = null; this.completed = false; this.state = AnswerState.NOT_STARTED;
    this.initialView = null; this.view = { x: -24, y: -24, scale: 1 }; this.emit();
    const question: QuestionDetail | null = this.question;
    const user: UserProfile | null = authSession.read_user();
    const token: string | null = authSession.read_access_token();
    if (question === null || user === null || token === null) {
      this.loading = false; this.message = '请登录后选择一道题'; this.emit(); return;
    }
    this.accountId = user.accountId; this.questionId = question.questionId; this.token = token;
    const context: common.Context | undefined = this.getUIContext().getHostContext();
    if (context === undefined) { this.loading = false; this.message = '无法访问本机草稿目录'; this.emit(); return; }
    try {
      this.store = new CanvasDraftStore(context.filesDir,user.accountId,question.questionId);
      const store: CanvasDraftStore = this.store;
      const hash: string = await canvas_sha256(canvas_utf8(question.bodyMarkdown));
      if (!this.current(generation)) { return; }
      this.bodyHash = hash;
      const local: LocalCanvasDraft | null = refresh ? null : store.load();
      if (local !== null) {
        if (local.document.questionLayout.questionBodySha256 !== hash) { throw new Error('本机题面与当前题干不一致，原草稿已保留'); }
        await this.verify_files(local.document,local.files,generation);
      }
      const result: AuthResult<SolutionDetail> = await this.api.getAnswer(token,question.questionId);
      if (!this.current(generation)) { return; }
      if (!result.isSuccess || result.data === null) {
        const error: AuthError = result.error ?? AuthError.protocol_failure('当前作答读取失败'); this.expire(error);
        if (local !== null && !error.is_unauthorized()) {
          this.install_local(local); this.message = '当前云端作答暂不可用，已恢复本机草稿；保存时仍需服务端确认';
        } else { this.message = error.message; }
        this.loading = false; this.emit(); return;
      }
      const remote: SolutionDetail = result.data; this.record = remote;
      if (local !== null) {
        // 丢失保存回执后的恢复只允许用原冻结包重试，不静默改 base。
        const ownPending: boolean = local.pendingUpload !== null && remote.manifest !== null &&
          (JSON.parse(local.pendingUpload.manifestJson) as AnswerManifest).saveRequestId === remote.manifest.saveRequestId;
        this.install_local(local);
        if (remote.fileRef !== local.baseFileRef && !ownPending) {
          this.needsRefresh = true; this.message = '云端作答已变化，本机草稿已保留，请刷新当前云端作答';
        } else if (!local.dirty && local.pendingUpload === null) {
          this.state = remote.state; this.completed = remote.state === AnswerState.COMPLETED; this.persist();
        } else if (remote.state === AnswerState.COMPLETED && !ownPending) {
          this.needsRefresh = true; this.completed = true; this.message = '云端作答已完成，本机草稿已保留，请刷新当前云端作答';
        }
      } else if (remote.manifest !== null && remote.fileRef !== null) {
        await this.install_remote(remote,store,context.filesDir,generation);
      } else {
        this.rasterRequest = { key: user.accountId + ':' + question.questionId + ':' + generation.toString(),
          generation: generation, accountId: user.accountId, questionId: question.questionId, body: question.bodyMarkdown, directory: store.directory };
        this.rasterizing = true;
      }
      if (!this.current(generation)) { return; }
      this.loading = false; this.emit();
    } catch (error) {
      if (this.current(generation)) {
        this.loading = false; this.rasterizing = false;
        this.message = (error as Error).message || '画布恢复失败，原本机草稿已保留'; this.emit();
      }
    }
  }
  private async install_remote(remote: SolutionDetail, store: CanvasDraftStore, filesDir: string, generation: number): Promise<void> {
    if (remote.manifest === null || remote.fileRef === null) { throw new Error('完整作答缺少文档'); }
    const doc: CanvasEditableDocument = clone_canvas_document(remote.manifest.document);
    if (doc.questionLayout.questionBodySha256 !== this.bodyHash) { throw new Error('云端题面与当前题干不一致'); }
    const files: CanvasLocalFile[] = [];
    for (let i: number = 0; i < doc.questionLayout.rasterBlocks.length; i++) {
      const block = doc.questionLayout.rasterBlocks[i];
      const result: AuthResult<string> = await this.api.downloadBlock(this.token,this.accountId,this.questionId,remote.fileRef,block,filesDir,true);
      if (!this.current(generation)) { return; }
      if (!result.isSuccess || result.data === null) {
        const error: AuthError = result.error ?? AuthError.protocol_failure('云端固定题面底图缺失'); this.expire(error); throw new Error(error.message);
      }
      const bytes: Uint8Array = canvas_read(result.data.substring(7));
      const path: string = store.directory + '/rasters/' + block.sha256 + '.png';
      canvas_write(path,bytes); files.push({ partName: block.partName, filePath: path });
    }
    if (!this.current(generation)) { return; }
    this.document = doc; this.files = files; this.baseFileRef = remote.fileRef; this.state = remote.state;
    this.completed = remote.state === AnswerState.COMPLETED; this.dirty = false; this.pending = null;
    this.mountKey = this.accountId + ':' + this.questionId + ':' + generation.toString(); this.persist();
  }
  private raster_current(request: RasterRequest): boolean {
    return this.current(request.generation) && this.rasterizing && request.accountId === this.accountId &&
      request.questionId === this.questionId && this.store !== null && request.directory === this.store.directory;
  }
  private raster_ready(result: QuestionRasterResult, request: RasterRequest): void {
    if (!this.raster_current(request) || result.layout.questionBodySha256 !== this.bodyHash ||
      result.files.some((file: CanvasLocalFile) => !file.filePath.startsWith(request.directory + '/'))) { return; }
    const doc: CanvasEditableDocument = { schemaVersion: 1, questionLayout: result.layout, strokes: [] };
    try { validate_canvas_document(doc); } catch (error) { this.raster_failed('固定题面布局校验失败',request); return; }
    this.document = doc; this.files = result.files; this.rasterizing = false; this.dirty = true;
    this.mountKey = this.accountId + ':' + this.questionId + ':' + this.generation.toString();
    this.persist(); this.emit();
  }
  private raster_failed(message: string, request: RasterRequest): void {
    if (!this.raster_current(request)) { return; }
    this.rasterizing = false; this.message = message; this.emit();
  }
  private document_changed(doc: CanvasEditableDocument, key: string): void {
    if (!this.alive || key !== this.mountKey || this.busy || this.completed || this.needsRefresh || !authSession.has_valid_session() || !authSession.matches_access_token(this.token)) { return; }
    this.document = doc; this.dirty = true; this.pending = null; this.message = ''; this.persist(); this.emit();
  }
  private view_changed(view: CanvasView, key: string): void {
    if (!this.alive || key !== this.mountKey || !authSession.matches_access_token(this.token) || !valid_canvas_view(view)) { return; }
    this.view = { x: view.x, y: view.y, scale: view.scale }; this.persist(); this.emit();
  }
  private failure(message: string): AuthResult<SolutionDetail> {
    this.message = message; this.emit(); return AuthResult.failure<SolutionDetail>(AuthError.local_validation(message, {}));
  }
  private bind_freeze_handle(key: string, handle: CanvasFreezeHandle, attached: boolean): void {
    if (key !== this.mountKey) { return; }
    if (attached) { this.freezeHandle = handle; }
    else if (this.freezeHandle === handle) { this.freezeHandle = null; }
  }
  private tutor_current(context: TutorFreezeContext): boolean {
    const user: UserProfile | null = authSession.read_user();
    return this.alive && this.tutorContext === context && !context.cancelled && this.generation === context.generation &&
      this.questionId === context.questionId && this.mountKey === context.mountKey && this.accountId === context.accountId &&
      this.token === context.token && authSession.matches_access_token(context.token) && authSession.has_valid_session() &&
      user !== null && user.accountId === context.accountId;
  }
  private tutor_working(): boolean {
    const context: TutorFreezeContext | null = this.tutorContext;
    return context !== null && this.tutor_current(context) &&
      (context.phase === 'capturing' || context.phase === 'exporting' || context.phase === 'sending');
  }
  private cancel_tutor_context(): void {
    if (this.tutorContext !== null) { this.tutorContext.cancelled = true; }
    this.freezeHandle?.cancel();
  }
  private cancel_tutor_freeze(questionId: string, turnId: string): void {
    const context: TutorFreezeContext | null = this.tutorContext;
    if (context !== null && context.questionId === questionId && context.turnId === turnId) {
      this.cancel_tutor_context(); this.emit();
    }
  }
  private begin_upload(): CanvasUploadSlot | null {
    if (activeCanvasUpload !== null) { return null; }
    let finish: () => void = () => {};
    const done: Promise<void> = new Promise<void>((resolve: () => void) => { finish = resolve; });
    const slot: CanvasUploadSlot = { done: done, finish: finish };
    activeCanvasUpload = slot; return slot;
  }
  private finish_upload(slot: CanvasUploadSlot): void {
    if (activeCanvasUpload === slot) { activeCanvasUpload = null; }
    slot.finish();
  }
  private async wait_upload(context: TutorFreezeContext): Promise<CanvasUploadSlot | null> {
    while (activeCanvasUpload !== null) {
      const slot: CanvasUploadSlot = activeCanvasUpload;
      await slot.done;
      if (!this.tutor_current(context)) { return null; }
    }
    return this.tutor_current(context) ? this.begin_upload() : null;
  }
  private tutor_failure(message: string): AuthResult<FrozenTutorAnswer> {
    return AuthResult.failure<FrozenTutorAnswer>(AuthError.local_validation(message, {}));
  }
  private copy_frozen(frozen: FrozenTutorAnswer): FrozenTutorAnswer {
    return { questionId: frozen.questionId, turnId: frozen.turnId,
      upload: { manifestJson: frozen.upload.manifestJson, files: frozen.upload.files.map((file: CanvasLocalFile): CanvasLocalFile =>
        ({ partName: file.partName, filePath: file.filePath })) } };
  }
  private read_frozen(context: TutorFreezeContext): FrozenTutorAnswer | null {
    const directory: string = context.directory + '/tutor/' + context.turnId + '/uploads/' + context.turnId;
    const path: string = directory + '/manifest.json';
    if (!canvas_exists(path)) { return null; }
    const json: string = canvas_text(canvas_read(path,8388608));
    const manifest: AnswerManifest = JSON.parse(json) as AnswerManifest;
    validate_answer_manifest(manifest);
    if (manifest.saveRequestId !== context.turnId || manifest.baseFileRef !== null ||
      manifest.document.questionLayout.questionBodySha256 !== this.bodyHash) { throw new Error('本轮冻结文档校验失败'); }
    const files: CanvasLocalFile[] = [];
    const blocks: CanvasImageBlock[] = manifest.blocks.concat(manifest.document.questionLayout.rasterBlocks);
    for (let i: number = 0; i < blocks.length; i++) {
      const file: CanvasLocalFile = { partName: blocks[i].partName, filePath: directory + '/' + blocks[i].partName + '.png' };
      if (!canvas_exists(file.filePath)) { throw new Error('本轮冻结图块缺失'); }
      files.push(file);
    }
    return { questionId: context.questionId, turnId: context.turnId, upload: { manifestJson: json, files: files } };
  }
  private freeze_at_pen_up(questionId: string, turnId: string): Promise<AuthResult<FrozenTutorAnswer>> {
    const user: UserProfile | null = authSession.read_user();
    const handle: CanvasFreezeHandle | null = this.freezeHandle;
    if (!this.alive || questionId !== this.questionId || this.document === null || this.store === null ||
      this.loading || this.rasterizing || handle === null || user === null || user.accountId !== this.accountId ||
      !authSession.matches_access_token(this.token) || !/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(turnId)) {
      return Promise.resolve(this.tutor_failure('当前题目画布或登录状态尚未就绪'));
    }
    let context: TutorFreezeContext | null = this.tutorContext;
    if (context !== null && context.questionId === questionId && context.turnId === turnId &&
      context.accountId === this.accountId && context.token === this.token && context.generation === this.generation && context.mountKey === this.mountKey) {
      if (!context.cancelled && context.freezePromise !== null) { return context.freezePromise; }
      if (context.cancelled) {
        // 不复活旧异步调用的上下文；旧任务保持 cancelled，重入只复用已捕获内容/原包。
        const resumed: TutorFreezeContext = new TutorFreezeContext(this.accountId,questionId,turnId,this.token,
          this.generation,this.mountKey,this.store.directory,context.files);
        resumed.document = context.document; resumed.frozen = context.frozen;
        context = resumed; this.tutorContext = resumed;
      }
      if (context.frozen !== null) {
        context.phase = 'frozen';
        return Promise.resolve(AuthResult.success<FrozenTutorAnswer>(this.copy_frozen(context.frozen)));
      }
    } else {
      if (this.tutor_working()) { return Promise.resolve(this.tutor_failure('正在处理另一轮求助，请稍后重试')); }
      this.cancel_tutor_context();
      context = new TutorFreezeContext(this.accountId,questionId,turnId,this.token,this.generation,this.mountKey,this.store.directory,this.files);
      this.tutorContext = context;
    }
    if (context === null) { return Promise.resolve(this.tutor_failure('画布尚未准备完成')); }
    const captured: TutorFreezeContext = context;
    try {
      const existing: FrozenTutorAnswer | null = this.read_frozen(captured);
      if (existing !== null) {
        captured.frozen = existing; captured.phase = 'frozen';
        return Promise.resolve(AuthResult.success<FrozenTutorAnswer>(this.copy_frozen(existing)));
      }
    } catch (error) { captured.phase = 'failed'; return Promise.resolve(this.tutor_failure('本轮原冻结包不可读取，未重新冻结或覆盖')); }
    captured.phase = 'capturing';
    // captureAtPenUp 的同步 executor 立即捕获或登记真实抬笔等待，不先设 busy/readOnly。
    const capture: Promise<AuthResult<CanvasEditableDocument>> = captured.document === null ? handle.captureAtPenUp() :
      Promise.resolve(AuthResult.success<CanvasEditableDocument>(captured.document));
    captured.freezePromise = this.capture_and_export(captured,capture);
    this.emit(); return captured.freezePromise;
  }
  private async capture_and_export(context: TutorFreezeContext, capture: Promise<AuthResult<CanvasEditableDocument>>): Promise<AuthResult<FrozenTutorAnswer>> {
    let slot: CanvasUploadSlot | null = null;
    try {
      const result: AuthResult<CanvasEditableDocument> = await capture;
      if (!this.tutor_current(context)) { return this.tutor_failure('冻结请求已取消或页面已变化'); }
      if (!result.isSuccess || result.data === null) { return AuthResult.failure<FrozenTutorAnswer>(result.error ?? AuthError.local_validation('本笔已取消，请重试', {})); }
      const capturedDocument: CanvasEditableDocument = result.data;
      context.document = capturedDocument;
      context.phase = 'exporting';
      // 网络忙时仍已同步冻结上面的文档；只在这里等已有发送结束。
      slot = await this.wait_upload(context);
      if (slot === null || !this.tutor_current(context)) { return this.tutor_failure('冻结请求已取消或页面已变化'); }
      const upload: FrozenAnswerUpload = await export_canvas(capturedDocument,context.files,null,
        context.directory + '/tutor/' + context.turnId,() => this.tutor_current(context),context.turnId);
      if (!this.tutor_current(context)) { return this.tutor_failure('冻结请求已取消或页面已变化'); }
      context.frozen = { questionId: context.questionId, turnId: context.turnId, upload: upload };
      context.phase = 'frozen'; return AuthResult.success<FrozenTutorAnswer>(this.copy_frozen(context.frozen));
    } catch (error) { return this.tutor_failure('完整画布冻结导出失败，请重试本轮'); }
    finally {
      if (slot !== null) { this.finish_upload(slot); }
      context.freezePromise = null;
      if (context.phase !== 'frozen') { context.phase = 'failed'; }
      if (this.tutor_current(context)) { this.emit(); }
    }
  }
  private send_frozen_tutor_answer(frozen: FrozenTutorAnswer): Promise<AuthResult<TutorTurn>> {
    const context: TutorFreezeContext | null = this.tutorContext;
    if (context === null || !this.tutor_current(context) || context.frozen === null ||
      frozen.questionId !== context.questionId || frozen.turnId !== context.turnId ||
      frozen.upload.manifestJson !== context.frozen.upload.manifestJson || frozen.upload.files.length !== context.frozen.upload.files.length) {
      return Promise.resolve(AuthResult.failure<TutorTurn>(AuthError.local_validation('请先冻结当前轮次画布', {})));
    }
    for (let i: number = 0; i < frozen.upload.files.length; i++) {
      if (frozen.upload.files[i].partName !== context.frozen.upload.files[i].partName || frozen.upload.files[i].filePath !== context.frozen.upload.files[i].filePath) {
        return Promise.resolve(AuthResult.failure<TutorTurn>(AuthError.local_validation('冻结原包已变化，请复用原包', {})));
      }
    }
    if (context.sendPromise !== null) { return context.sendPromise; }
    context.phase = 'sending'; context.sendPromise = this.send_tutor(context); this.emit(); return context.sendPromise;
  }
  private async send_tutor(context: TutorFreezeContext): Promise<AuthResult<TutorTurn>> {
    let slot: CanvasUploadSlot | null = null;
    try {
      slot = await this.wait_upload(context);
      if (slot === null || !this.tutor_current(context) || context.frozen === null) {
        return AuthResult.failure<TutorTurn>(AuthError.local_validation('求助请求已取消或页面已变化', {}));
      }
      const result: AuthResult<TutorTurn> = await this.tutorApi.uploadFrozenAnswer(context.token,context.questionId,context.turnId,context.frozen.upload,() => this.tutor_current(context));
      if (!this.tutor_current(context)) { return AuthResult.failure<TutorTurn>(AuthError.local_validation('求助请求已取消或页面已变化', {})); }
      if (result.error !== null) { this.expire(result.error); }
      return result;
    } finally {
      if (slot !== null) { this.finish_upload(slot); }
      context.sendPromise = null; context.phase = 'frozen';
      if (this.tutor_current(context)) { this.emit(); }
    }
  }
  private async save(): Promise<AuthResult<SolutionDetail>> {
    if (this.document === null || this.store === null || this.loading || this.rasterizing || this.busy) { return this.failure('画布尚未准备完成或正在保存'); }
    if (this.tutor_working()) { return this.failure('正在处理求助，请稍后保存'); }
    if (this.needsRefresh) { return this.failure('云端作答已变化，本机草稿已保留，请刷新当前云端作答'); }
    if (!authSession.has_valid_session() || !authSession.matches_access_token(this.token)) { return this.failure('登录状态已变化，请重新登录'); }
    if (this.completed) { return this.failure('请先继续作答再保存'); }
    if (!this.dirty && this.pending === null && this.record !== null && this.baseFileRef !== null) { return AuthResult.success<SolutionDetail>(this.record); }
    const slot: CanvasUploadSlot | null = this.begin_upload();
    if (slot === null) { return this.failure('正在发送其他作答，请稍后保存'); }
    const generation: number = this.generation;
    this.busy = true; this.message = ''; this.emit();
    try {
      if (this.pending === null) {
        this.pending = await export_canvas(this.document,this.files,this.baseFileRef,this.store.directory,() => this.current(generation));
      }
      if (!this.current(generation)) { return AuthResult.failure<SolutionDetail>(AuthError.local_validation('画布或登录状态已变化', {})); }
      if (!this.persist()) { return this.failure('保存包未能持久化，本次未上传，请重试'); }
      const result: AuthResult<SolutionDetail> = await this.api.saveAnswer(this.token,this.questionId,this.pending);
      if (!this.current(generation)) { return AuthResult.failure<SolutionDetail>(AuthError.local_validation('画布或登录状态已变化', {})); }
      if (!result.isSuccess || result.data === null) {
        const error: AuthError = result.error ?? AuthError.protocol_failure('保存回执无效'); this.expire(error);
        if (error.httpStatus === 409) { this.needsRefresh = true; this.message = '云端作答已变化，本机草稿已保留，请刷新当前云端作答'; }
        else { this.message = error.message; }
        return AuthResult.failure<SolutionDetail>(error);
      }
      const saved: SolutionDetail = result.data;
      if (saved.manifest === null) { saved.manifest = JSON.parse(this.pending.manifestJson) as AnswerManifest; }
      this.record = saved; this.baseFileRef = saved.fileRef; this.state = saved.state; this.completed = saved.state === AnswerState.COMPLETED;
      this.dirty = false; this.pending = null;
      if (!this.persist()) { this.message = '云端已保存，但本机草稿写入失败，请重试本机保存'; }
      return AuthResult.success<SolutionDetail>(saved);
    } catch (error) {
      const message: string = (error as Error).message || '完整作答导出失败，本机草稿已保留';
      return this.current(generation) ? this.failure(message) : AuthResult.failure<SolutionDetail>(AuthError.local_validation(message, {}));
    } finally { this.finish_upload(slot); if (this.current(generation)) { this.busy = false; this.emit(); } }
  }
  private async change_state(next: AnswerState): Promise<AuthResult<SolutionDetail>> {
    if (this.busy || this.baseFileRef === null || this.needsRefresh || this.document === null) { return this.failure('请先完整保存当前作答'); }
    if (!authSession.has_valid_session() || !authSession.matches_access_token(this.token)) { return this.failure('请重新登录'); }
    if (this.tutor_working()) { return this.failure('正在处理求助，请稍后操作'); }
    const slot: CanvasUploadSlot | null = this.begin_upload();
    if (slot === null) { return this.failure('正在发送其他作答，请稍后操作'); }
    const generation: number = this.generation; this.busy = true; this.message = ''; this.emit();
    try {
      const result: AuthResult<SolutionDetail> = await this.api.setAnswerState(this.token,this.questionId,this.baseFileRef,next);
      if (!this.current(generation)) { return AuthResult.failure<SolutionDetail>(AuthError.local_validation('画布或登录状态已变化', {})); }
      if (!result.isSuccess || result.data === null) {
        const error: AuthError = result.error ?? AuthError.protocol_failure('状态回执无效'); this.expire(error);
        if (error.httpStatus === 409) { this.needsRefresh = true; this.message = '云端作答已变化，本机草稿已保留，请刷新当前云端作答'; }
        else { this.message = error.message; }
        return AuthResult.failure<SolutionDetail>(error);
      }
      if (result.data.manifest === null) { result.data.manifest = this.record?.manifest ?? null; }
      this.record = result.data; this.state = result.data.state; this.completed = this.state === AnswerState.COMPLETED;
      this.persist(); return result;
    } finally { this.finish_upload(slot); if (this.current(generation)) { this.busy = false; this.emit(); } }
  }
  private async mark_completed(): Promise<AuthResult<SolutionDetail>> {
    const generation: number = this.generation;
    if (this.completed && this.record !== null) { return AuthResult.success<SolutionDetail>(this.record); }
    if (this.dirty || this.pending !== null || this.baseFileRef === null) {
      const saved: AuthResult<SolutionDetail> = await this.save(); if (!saved.isSuccess) { return saved; }
    }
    if (!this.current(generation)) { return AuthResult.failure<SolutionDetail>(AuthError.local_validation('画布或登录状态已变化', {})); }
    return this.change_state(AnswerState.COMPLETED);
  }
  private async continue_writing(): Promise<AuthResult<SolutionDetail>> {
    return this.change_state(AnswerState.IN_PROGRESS);
  }
  private async flush_local(): Promise<AuthResult<boolean>> {
    if (this.document === null || this.store === null || this.loading || this.rasterizing) {
      return AuthResult.failure<boolean>(AuthError.local_validation('画布尚未准备完成', {}));
    }
    const saved: boolean = this.persist(); this.emit();
    if (saved && this.question !== null && this.question.questionId !== this.questionId && !this.busy) { this.initialize(false); }
    return saved ? AuthResult.success<boolean>(true) : AuthResult.failure<boolean>(AuthError.local_validation(this.message, {}));
  }
  private refresh(): void {
    if (this.busy) { return; }
    if (this.document !== null && this.store !== null) {
      try { this.store.preserve(this.draft()); } catch (error) { this.message = '本机草稿保留失败，本次未刷新'; this.emit(); return; }
    }
    this.initialize(true);
  }
  build() {
    Stack({ alignContent: Alignment.Center }) {
      if (this.document !== null && !this.loading && !this.rasterizing) {
        ForEach([this.mountKey], (_key: string) => {
          CanvasSurface({ document: this.document, documentKey: this.mountKey, rasterFiles: this.files,
            initialView: this.initialView, readOnly: this.completed || this.busy || this.needsRefresh,
            onDocumentChange: (doc: CanvasEditableDocument) => this.document_changed(doc,_key),
            onFreezeHandle: (handle: CanvasFreezeHandle, attached: boolean) => this.bind_freeze_handle(_key,handle,attached),
            onViewChange: (view: CanvasView) => this.view_changed(view,_key),
            onUndoChange: (canUndo: boolean) => { if (_key === this.mountKey) { this.canUndo = canUndo; this.emit(); } },
            onFailure: (message: string) => { if (_key === this.mountKey) { this.message = message; this.emit(); } } })
        }, (key: string) => key)
      }
      if (this.rasterizing) {
        ForEach([this.rasterRequest], (request: RasterRequest) => {
          QuestionRasterHost({ bodyMarkdown: request.body, directory: request.directory,
            requestActive: () => this.raster_current(request) && authSession.has_valid_session(),
            onReady: (result: QuestionRasterResult) => this.raster_ready(result,request),
            onFailure: (message: string) => this.raster_failed(message,request) })
            .hitTestBehavior(HitTestMode.None)
        }, (request: RasterRequest) => request.key)
      }
      if (this.loading || this.rasterizing) {
        Column({ space: 12 }) {
          LoadingProgress().width(28).height(28).color(TABLET_COLOR_PRIMARY)
          Text('正在准备画布…').fontSize(14).fontColor(TABLET_COLOR_TEXT)
        }.padding(20).backgroundColor(TABLET_COLOR_CARD).borderRadius(TABLET_RADIUS)
          .border({ width: 1, color: TABLET_COLOR_BORDER })
          .shadow({ radius: 16, color: '#14246BFF', offsetY: 4 })
      }
      if (this.message.length > 0) {
        Column({ space: 8 }) {
          Text(this.message).fontSize(14).fontColor(TABLET_COLOR_DANGER)
          if (this.needsRefresh) { Button('刷新当前云端作答').onClick(() => this.refresh()).enabled(!this.busy) }
          else if (this.document === null && !this.loading && !this.rasterizing) { Button('重试').onClick(() => this.initialize(false)) }
        }.padding(12).backgroundColor(TABLET_COLOR_CARD).borderRadius(TABLET_RADIUS)
          .border({ width: 1, color: TABLET_COLOR_BORDER }).width('90%').margin({ top: 16 }).align(Alignment.Top)
      }
    }.width('100%').height('100%').backgroundColor(TABLET_COLOR_BACKGROUND)
  }
}
