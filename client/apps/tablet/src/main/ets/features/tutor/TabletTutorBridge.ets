import {
  AuthError,
  AuthResult,
  FrozenTutorAnswer,
  TutorApi,
  TutorPending,
  TutorTurn,
  UserProfile,
  authSession
} from 'common';
import { TabletCanvasController } from '../canvas/CanvasWorkspaceController';

/** 页面用 C2 TutorApi 适配这两个函数，不另建网络请求或公共协议。 */
export interface TabletTutorApiPort {
  pending(token: string, questionId: string): Promise<AuthResult<TutorTurn | null>>;
  get(token: string, questionId: string, turnId: string): Promise<AuthResult<TutorTurn>>;
}

/** 只转换 C2 已校验的公共响应，不复制其 HTTP/协议实现。 */
export class SharedTabletTutorApi implements TabletTutorApiPort {
  private readonly api: TutorApi;

  constructor(api: TutorApi = TutorApi.create_default()) {
    this.api = api;
  }

  async pending(token: string, questionId: string): Promise<AuthResult<TutorTurn | null>> {
    const result: AuthResult<TutorPending> = await this.api.getPending(token, questionId);
    if (!result.isSuccess || result.data === null) {
      return AuthResult.failure<TutorTurn | null>(
        result.error === null ? AuthError.protocol_failure('辅导等待响应无效') : result.error);
    }
    return AuthResult.success<TutorTurn | null>(result.data.turn);
  }

  async get(token: string, questionId: string, turnId: string): Promise<AuthResult<TutorTurn>> {
    return this.api.getTurn(token, questionId, turnId);
  }
}

export enum TabletTutorPhase {
  STOPPED = 'STOPPED',
  READY = 'READY',
  FREEZING = 'FREEZING',
  UPLOADING = 'UPLOADING',
  GENERATING = 'GENERATING',
  SUCCEEDED = 'SUCCEEDED',
  FAILED = 'FAILED',
  ERROR = 'ERROR'
}

export class TabletTutorState {
  readonly questionId: string;
  readonly turnId: string;
  readonly phase: TabletTutorPhase;
  readonly message: string;
  readonly error: AuthError | null;

  constructor(questionId: string, turnId: string, phase: TabletTutorPhase,
    message: string, error: AuthError | null = null) {
    this.questionId = questionId;
    this.turnId = turnId;
    this.phase = phase;
    this.message = message;
    this.error = error;
  }
}

/** 前台当前题单循环轮询；完整冻结和顺序发送全部委托 C2。 */
export class TabletTutorBridge {
  private readonly canvas: TabletCanvasController;
  private readonly api: TabletTutorApiPort;
  private listener: ((state: TabletTutorState) => void) | null;
  private readonly onSessionExpired: () => void;
  private timer: number = -1;
  private epoch: number = 0;
  private running: boolean = false;
  private disposed: boolean = false;
  private inFlightEpoch: number = -1;
  private questionId: string = '';
  private ownerAccountId: string = '';
  private token: string = '';
  private activeTurnId: string = '';
  private retained: FrozenTutorAnswer | null = null;

  constructor(canvas: TabletCanvasController, api: TabletTutorApiPort,
    onChange: (state: TabletTutorState) => void, onSessionExpired: () => void = () => {}) {
    this.canvas = canvas;
    this.api = api;
    this.listener = onChange;
    this.onSessionExpired = onSessionExpired;
  }

  /** 页面在前台且当前题画布已挂载后调用；同一上下文重复调用无副作用。 */
  start(questionId: string): void {
    if (this.disposed) {
      return;
    }
    const token: string | null = authSession.read_access_token();
    const user: UserProfile | null = authSession.read_user();
    if (token === null || user === null || !/^[1-9][0-9]{0,18}$/.test(questionId)) {
      this.stop();
      this.publish(TabletTutorPhase.ERROR, '请先登录并打开有效题目',
        AuthError.local_validation('当前题或会话不可用', {}));
      return;
    }
    if (this.running && this.questionId === questionId && this.token === token) {
      return;
    }
    this.stop();
    if (this.ownerAccountId !== user.accountId || this.questionId !== questionId) {
      // 不删除 C2 的账号草稿/冻结文件；只解除本桥接器对旧题的内存引用。
      this.retained = null;
      this.activeTurnId = '';
    }
    this.questionId = questionId;
    this.ownerAccountId = user.accountId;
    this.token = token;
    this.running = true;
    const epoch: number = this.epoch;
    this.publish(TabletTutorPhase.READY, '正在接收本题辅导请求');
    this.timer = setInterval(() => { this.poll(epoch); }, 2000);
    this.poll(epoch);
  }

  /** 离开前台、切题前或会话切换时立即调用，不取消已经发出的服务端模型请求。 */
  stop(): void {
    this.running = false;
    this.epoch += 1;
    if (this.timer !== -1) {
      clearInterval(this.timer);
      this.timer = -1;
    }
    this.publish(TabletTutorPhase.STOPPED, '辅导请求已暂停');
  }

  /** 页面销毁时调用；旧异步任务完成后不会回写状态或上传旧包。 */
  dispose(): void {
    this.disposed = true;
    this.listener = null;
    this.stop();
    this.retained = null;
    this.activeTurnId = '';
    this.token = '';
  }

  private current(epoch: number): boolean {
    if (this.disposed || !this.running || epoch !== this.epoch) {
      return false;
    }
    if (authSession.read_access_token() !== this.token) {
      const expired: boolean = authSession.matches_access_token(this.token);
      const previousToken: string = this.token;
      this.stop();
      if (expired && authSession.clear_session_if_token(previousToken)) {
        this.onSessionExpired();
      }
      return false;
    }
    return true;
  }

  private async poll(epoch: number): Promise<void> {
    if (!this.current(epoch) || this.inFlightEpoch === epoch) {
      return;
    }
    this.inFlightEpoch = epoch;
    try {
      if (this.activeTurnId.length > 0) {
        const result: AuthResult<TutorTurn> = await this.api.get(this.token, this.questionId, this.activeTurnId);
        if (!this.current(epoch)) { return; }
        if (!result.isSuccess || result.data === null) {
          this.failure(result.error);
          return;
        }
        await this.consume(result.data, epoch);
      } else {
        const result: AuthResult<TutorTurn | null> = await this.api.pending(this.token, this.questionId);
        if (!this.current(epoch)) { return; }
        if (!result.isSuccess) {
          this.failure(result.error);
          return;
        }
        if (result.data !== null) {
          await this.consume(result.data, epoch);
        }
      }
    } catch (error) {
      if (this.current(epoch)) {
        this.failure(AuthError.network_failure('辅导通信暂时失败，将保留原冻结包重试', ''));
      }
    } finally {
      if (this.inFlightEpoch === epoch) {
        this.inFlightEpoch = -1;
      }
    }
  }

  private async consume(turn: TutorTurn, epoch: number): Promise<void> {
    if (!this.current(epoch)) { return; }
    if (turn.questionId !== this.questionId || turn.turnId.length === 0) {
      this.failure(AuthError.protocol_failure('辅导轮次与当前题不一致'));
      return;
    }
    this.activeTurnId = turn.turnId;
    if (turn.status !== 'WAITING_ANSWER') {
      this.display(turn);
      return;
    }
    if (turn.fileRef !== null) {
      // 服务端已接受文件，不得重新冻结来替换它。
      this.publish(TabletTutorPhase.GENERATING, '本轮解答已接收，等待模型处理');
      return;
    }
    if (this.retained === null || this.retained.turnId !== turn.turnId) {
      this.publish(TabletTutorPhase.FREEZING, '等待当前笔画结束并冻结完整解答');
      const frozen: AuthResult<FrozenTutorAnswer> = await this.canvas.freezeAtPenUp(this.questionId, turn.turnId);
      if (!this.current(epoch)) { return; }
      if (!frozen.isSuccess || frozen.data === null) {
        this.failure(frozen.error);
        return;
      }
      if (frozen.data.questionId !== this.questionId || frozen.data.turnId !== turn.turnId) {
        this.failure(AuthError.protocol_failure('冻结包与本轮题目不一致'));
        return;
      }
      this.retained = frozen.data;
    }
    if (!this.current(epoch) || this.retained === null) { return; }
    this.publish(TabletTutorPhase.UPLOADING, '正在发送本轮完整解答，可继续书写');
    const sent: AuthResult<TutorTurn> = await this.canvas.sendFrozenTutorAnswer(this.retained);
    if (!this.current(epoch)) { return; }
    if (!sent.isSuccess || sent.data === null) {
      // 未知接收结果保留同一个冻结包；下轮先查状态，再决定是否按原包发送。
      this.failure(sent.error);
      return;
    }
    if (sent.data.questionId !== this.questionId || sent.data.turnId !== turn.turnId) {
      this.failure(AuthError.protocol_failure('上传响应与本轮题目不一致'));
      return;
    }
    this.display(sent.data);
  }

  private display(turn: TutorTurn): void {
    if (turn.status === 'GENERATING' || (turn.status === 'WAITING_ANSWER' && turn.fileRef !== null)) {
      this.publish(TabletTutorPhase.GENERATING, '本轮解答已接收，正在生成辅导');
    } else if (turn.status === 'SUCCEEDED') {
      this.publish(TabletTutorPhase.SUCCEEDED, '辅导已生成，可在手机查看');
      this.activeTurnId = '';
      this.retained = null;
    } else if (turn.status === 'FAILED') {
      this.publish(TabletTutorPhase.FAILED,
        turn.failureMessage === null ? '本轮辅导失败，可在手机重试或重新提问' : turn.failureMessage);
      this.activeTurnId = '';
      this.retained = null;
    } else {
      this.failure(AuthError.protocol_failure('服务端尚未确认完整冻结包'));
    }
  }

  private failure(error: AuthError | null): void {
    const actual: AuthError = error === null ? AuthError.protocol_failure('辅导响应无效') : error;
    this.publish(TabletTutorPhase.ERROR, actual.message, actual);
    if (actual.is_unauthorized()) {
      const token: string = this.token;
      this.stop();
      if (authSession.clear_session_if_token(token)) {
        this.onSessionExpired();
      }
    }
  }

  private publish(phase: TabletTutorPhase, message: string, error: AuthError | null = null): void {
    if (!this.disposed && this.listener !== null) {
      this.listener(new TabletTutorState(this.questionId, this.activeTurnId, phase, message, error));
    }
  }
}
