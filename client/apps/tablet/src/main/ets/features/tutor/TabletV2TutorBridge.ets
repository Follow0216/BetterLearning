import {
  AnswerManifest, AuthError, AuthResult, FrozenAnswerUpload, LearningV2Api, UserProfile,
  V2PendingRequest, V2TutorRequest, V2TutorRequestDetail, accept_v2_detail, authSession
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

/** 当前题前台优先接收新 pending，并持续读取已观察请求；未知上传结果先查详情。 */
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
  private observedDetail: V2TutorRequestDetail | null = null;
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
      this.stop(); this.requestId = ''; this.observedDetail = null;
      this.publish(TabletTutorPhase.ERROR,'请先登录并打开有效题目'); return;
    }
    if (this.running && this.questionId === questionId && this.token === token && this.accountId === user.accountId) { return; }
    const sameContext: boolean = this.questionId === questionId && this.token === token && this.accountId === user.accountId;
    this.stop();
    if (!sameContext) { this.requestId = ''; this.observedDetail = null; }
    this.questionId = questionId; this.accountId = user.accountId; this.token = token;
    this.running = true;
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
    // 暂停只失效异步代次和冻结包；同账号/题/token 回前台仍可观察显式 retry。
    this.frozen = null;
    this.publish(TabletTutorPhase.STOPPED,'辅导请求已暂停');
  }

  dispose(): void {
    this.stop(); this.disposed = true; this.listener = null; this.token = ''; this.accountId = '';
    this.requestId = ''; this.observedDetail = null;
  }

  private current(epoch: number): boolean {
    if (this.disposed || !this.running || this.epoch !== epoch) { return false; }
    const user: UserProfile | null = authSession.read_user();
    if (authSession.read_access_token() !== this.token || user === null || user.accountId !== this.accountId) {
      const previous: string = this.token;
      this.stop(); this.requestId = ''; this.observedDetail = null;
      if (authSession.matches_access_token(previous) && authSession.clear_session_if_token(previous)) { this.onSessionExpired(); }
      return false;
    }
    return true;
  }

  private async poll(epoch: number): Promise<void> {
    if (!this.current(epoch) || this.inFlightEpoch === epoch) { return; }
    this.inFlightEpoch = epoch;
    try {
      const pending: AuthResult<V2PendingRequest> = await this.api.pending(this.token,this.questionId);
      if (!this.current(epoch)) { return; }
      if (!pending.isSuccess || pending.data === null) { this.failure(pending.error); return; }
      const next: V2TutorRequest | null = pending.data.request;
      if (next !== null && next.requestId !== this.requestId) {
        if (next.questionId !== this.questionId || next.requestId.length === 0) {
          this.failure(AuthError.protocol_failure('辅导请求与当前题不一致')); return;
        }
        if (this.requestId.length > 0) { this.canvas.cancelV2Snapshot(this.questionId,this.requestId); }
        this.requestId = next.requestId; this.observedDetail = null; this.frozen = null;
        await this.consume(next,epoch);
      } else if (this.requestId.length > 0) {
        // 终态也保留观察：手机显式 retry 会更换 currentRun，而不会重新进入 pending。
        // 同一 pending 也先查详情，避免迟到的 WAITING_INPUT 触发已绑定快照的重传。
        const requestId: string = this.requestId;
        const detail: AuthResult<V2TutorRequestDetail> = await this.api.get(this.token,this.questionId,requestId);
        if (!this.current(epoch) || this.requestId !== requestId) { return; }
        if (!detail.isSuccess || detail.data === null) { this.failure(detail.error); return; }
        if (this.accept_detail(detail.data)) { await this.consume(detail.data.request,epoch); }
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
    if (this.accept_detail(sent.data)) { this.display(sent.data.request); }
  }

  private accept_detail(detail: V2TutorRequestDetail): boolean {
    if (detail.request.requestId !== this.requestId || detail.request.questionId !== this.questionId) {
      this.failure(AuthError.protocol_failure('辅导详情与当前请求不一致')); return false;
    }
    if (!accept_v2_detail(this.observedDetail,detail)) { return false; }
    this.observedDetail = detail;
    return true;
  }

  private release_frozen(): void {
    if (this.frozen !== null) {
      this.canvas.cancelV2Snapshot(this.questionId,this.requestId);
      this.frozen = null;
    }
  }

  private display(request: V2TutorRequest): void {
    if (request.status === 'QUEUED' || request.status === 'RUNNING' || request.status === 'CANCELLING') {
      this.publish(TabletTutorPhase.GENERATING,'完整画布已接收，正在生成辅导');
    } else if (request.status === 'SUCCEEDED') {
      this.publish(TabletTutorPhase.SUCCEEDED,'辅导已生成，可查看本题历史');
      this.release_frozen();
    } else if (request.status === 'FAILED' || request.status === 'CANCELLED' || request.status === 'TIMED_OUT') {
      this.publish(TabletTutorPhase.FAILED,'本轮辅导已结束，可查看本题历史');
      this.release_frozen();
    } else if (request.status === 'WAITING_INPUT' && request.snapshotFileRef !== null) {
      this.publish(TabletTutorPhase.GENERATING,'快照绑定确认中');
    }
  }

  private failure(error: AuthError | null): void {
    const actual: AuthError = error ?? AuthError.protocol_failure('辅导响应无效');
    this.publish(TabletTutorPhase.ERROR,actual.message,actual);
    if (actual.is_unauthorized()) {
      const token: string = this.token; this.stop(); this.requestId = ''; this.observedDetail = null;
      if (authSession.clear_session_if_token(token)) { this.onSessionExpired(); }
    }
  }
  private publish(phase: TabletTutorPhase, message: string, error: AuthError | null = null): void {
    if (!this.disposed && this.listener !== null) {
      this.listener(new TabletTutorState(this.questionId,this.requestId,phase,message,error));
    }
  }
}
