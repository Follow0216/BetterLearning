import {
  AnswerManifest, AuthError, AuthResult, FrozenAnswerUpload, LearningV2Api, UserProfile,
  V2PendingRequest, V2TutorRequest, V2TutorRequestDetail, authSession
} from 'common';
import { TabletCanvasController } from '../canvas/CanvasWorkspaceController';
import { TabletTutorPhase, TabletTutorState } from './TabletTutorBridge';

export interface TabletV2TutorApiPort {
  pending(token: string, questionId: string): Promise<AuthResult<V2PendingRequest>>;
  get(token: string, questionId: string, requestId: string): Promise<AuthResult<V2TutorRequestDetail>>;
  upload(token: string, questionId: string, requestId: string, upload: FrozenAnswerUpload,
    active: () => boolean): Promise<AuthResult<V2TutorRequestDetail>>;
}

export class SharedTabletV2TutorApi implements TabletV2TutorApiPort {
  private api: LearningV2Api = LearningV2Api.create_default();
  pending(token: string, questionId: string): Promise<AuthResult<V2PendingRequest>> {
    return this.api.getPending(token,questionId);
  }
  get(token: string, questionId: string, requestId: string): Promise<AuthResult<V2TutorRequestDetail>> {
    return this.api.getRequest(token,questionId,requestId);
  }
  upload(token: string, questionId: string, requestId: string, upload: FrozenAnswerUpload,
    active: () => boolean): Promise<AuthResult<V2TutorRequestDetail>> {
    return this.api.uploadSnapshot(token,questionId,requestId,upload,active);
  }
}

/** 只在当前题前台读取 V2 pending；本机包以 requestId 固定，未知上传结果先查详情。 */
export class TabletV2TutorBridge {
  private timer: number = -1;
  private epoch: number = 0;
  private inFlightEpoch: number = -1;
  private running: boolean = false;
  private disposed: boolean = false;
  private questionId: string = '';
  private accountId: string = '';
  private token: string = '';
  private requestId: string = '';
  private frozen: FrozenAnswerUpload | null = null;
  private readonly canvas: TabletCanvasController;
  private readonly api: TabletV2TutorApiPort;
  private listener: ((state: TabletTutorState) => void) | null;
  private readonly onSessionExpired: () => void;

  constructor(canvas: TabletCanvasController, api: TabletV2TutorApiPort,
    onChange: (state: TabletTutorState) => void, onSessionExpired: () => void = () => {}) {
    this.canvas = canvas; this.api = api; this.listener = onChange; this.onSessionExpired = onSessionExpired;
  }

  start(questionId: string): void {
    if (this.disposed) { return; }
    const token: string | null = authSession.read_access_token();
    const user: UserProfile | null = authSession.read_user();
    if (token === null || user === null || !/^[1-9][0-9]{0,18}$/.test(questionId)) {
      this.stop(); this.publish(TabletTutorPhase.ERROR,'请先登录并打开有效题目'); return;
    }
    if (this.running && this.questionId === questionId && this.token === token && this.accountId === user.accountId) { return; }
    this.stop();
    this.questionId = questionId; this.accountId = user.accountId; this.token = token;
    this.requestId = ''; this.frozen = null; this.running = true;
    const epoch: number = this.epoch;
    this.publish(TabletTutorPhase.READY,'正在接收本题辅导请求');
    this.timer = setInterval(() => { this.poll(epoch); },2000);
    this.poll(epoch);
  }

  stop(): void {
    this.running = false; this.epoch += 1;
    if (this.timer !== -1) { clearInterval(this.timer); this.timer = -1; }
    if (this.questionId.length > 0 && this.requestId.length > 0) {
      this.canvas.cancelV2Snapshot(this.questionId,this.requestId);
    }
    this.requestId = ''; this.frozen = null;
    this.publish(TabletTutorPhase.STOPPED,'辅导请求已暂停');
  }

  dispose(): void {
    this.stop(); this.disposed = true; this.listener = null; this.token = ''; this.accountId = '';
  }

  private current(epoch: number): boolean {
    if (this.disposed || !this.running || this.epoch !== epoch) { return false; }
    const user: UserProfile | null = authSession.read_user();
    if (authSession.read_access_token() !== this.token || user === null || user.accountId !== this.accountId) {
      const previous: string = this.token;
      this.stop();
      if (authSession.matches_access_token(previous) && authSession.clear_session_if_token(previous)) { this.onSessionExpired(); }
      return false;
    }
    return true;
  }

  private async poll(epoch: number): Promise<void> {
    if (!this.current(epoch) || this.inFlightEpoch === epoch) { return; }
    this.inFlightEpoch = epoch;
    try {
      if (this.requestId.length > 0) {
        const detail: AuthResult<V2TutorRequestDetail> = await this.api.get(this.token,this.questionId,this.requestId);
        if (!this.current(epoch)) { return; }
        if (!detail.isSuccess || detail.data === null) { this.failure(detail.error); return; }
        await this.consume(detail.data.request,epoch);
      } else {
        const pending: AuthResult<V2PendingRequest> = await this.api.pending(this.token,this.questionId);
        if (!this.current(epoch)) { return; }
        if (!pending.isSuccess || pending.data === null) { this.failure(pending.error); return; }
        if (pending.data.request !== null) { await this.consume(pending.data.request,epoch); }
      }
    } catch (error) {
      if (this.current(epoch)) { this.failure(AuthError.network_failure('辅导通信暂时失败，原冻结包仍保留','')); }
    } finally { if (this.inFlightEpoch === epoch) { this.inFlightEpoch = -1; } }
  }

  private async consume(request: V2TutorRequest, epoch: number): Promise<void> {
    if (!this.current(epoch)) { return; }
    if (request.questionId !== this.questionId || request.requestId.length === 0 ||
      (this.requestId.length > 0 && this.requestId !== request.requestId)) {
      this.failure(AuthError.protocol_failure('辅导请求与当前题不一致')); return;
    }
    this.requestId = request.requestId;
    if (request.status !== 'WAITING_INPUT' || request.snapshotFileRef !== null) {
      this.display(request); return;
    }
    if (request.snapshotInput.kind !== 'NEW_CAPTURE' || request.currentRunId !== null) {
      this.failure(AuthError.protocol_failure('待冻结请求状态无效')); return;
    }
    if (this.frozen === null) {
      this.publish(TabletTutorPhase.FREEZING,'等待当前笔画结束并冻结完整画布');
      const frozen: AuthResult<FrozenAnswerUpload> = await this.canvas.freezeV2AtPenUp(this.questionId,this.requestId);
      if (!this.current(epoch)) { return; }
      if (!frozen.isSuccess || frozen.data === null) { this.failure(frozen.error); return; }
      try {
        const manifest: AnswerManifest = JSON.parse(frozen.data.manifestJson) as AnswerManifest;
        if (manifest.saveRequestId !== this.requestId || manifest.baseFileRef !== null) {
          this.failure(AuthError.protocol_failure('冻结包未绑定当前 V2 请求')); return;
        }
      } catch (error) { this.failure(AuthError.protocol_failure('冻结包清单无效')); return; }
      this.frozen = frozen.data;
    }
    if (!this.current(epoch) || this.frozen === null) { return; }
    this.publish(TabletTutorPhase.UPLOADING,'正在上传完整冻结包，可继续书写');
    const sent: AuthResult<V2TutorRequestDetail> = await this.api.upload(this.token,this.questionId,this.requestId,
      this.frozen,() => this.current(epoch));
    if (!this.current(epoch)) { return; }
    if (!sent.isSuccess || sent.data === null) {
      // 不重新冻结；下一轮先 GET，服务端若仍 WAITING_INPUT 才重传原包。
      this.failure(sent.error); return;
    }
    if (sent.data.request.requestId !== this.requestId || sent.data.request.questionId !== this.questionId) {
      this.failure(AuthError.protocol_failure('上传回执与当前请求不一致')); return;
    }
    this.display(sent.data.request);
  }

  private display(request: V2TutorRequest): void {
    if (request.status === 'QUEUED' || request.status === 'RUNNING' || request.status === 'CANCELLING') {
      this.publish(TabletTutorPhase.GENERATING,'完整画布已接收，正在生成辅导');
    } else if (request.status === 'SUCCEEDED') {
      this.publish(TabletTutorPhase.SUCCEEDED,'辅导已生成，可查看本题历史');
      this.requestId = ''; this.frozen = null;
    } else if (request.status === 'FAILED' || request.status === 'CANCELLED' || request.status === 'TIMED_OUT') {
      this.publish(TabletTutorPhase.FAILED,'本轮辅导已结束，可查看本题历史');
      this.requestId = ''; this.frozen = null;
    } else if (request.status === 'WAITING_INPUT' && request.snapshotFileRef !== null) {
      this.publish(TabletTutorPhase.GENERATING,'快照绑定确认中');
    }
  }

  private failure(error: AuthError | null): void {
    const actual: AuthError = error ?? AuthError.protocol_failure('辅导响应无效');
    this.publish(TabletTutorPhase.ERROR,actual.message,actual);
    if (actual.is_unauthorized()) {
      const token: string = this.token; this.stop();
      if (authSession.clear_session_if_token(token)) { this.onSessionExpired(); }
    }
  }
  private publish(phase: TabletTutorPhase, message: string, error: AuthError | null = null): void {
    if (!this.disposed && this.listener !== null) {
      this.listener(new TabletTutorState(this.questionId,this.requestId,phase,message,error));
    }
  }
}
