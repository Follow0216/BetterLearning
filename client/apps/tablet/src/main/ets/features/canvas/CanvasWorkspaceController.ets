import { AuthError, AuthResult, FrozenTutorAnswer, SolutionDetail, TutorTurn } from 'common';

/** A-T 仅消费该状态，不复制画布或服务端模型。 */
export class CanvasWorkspaceState {
  isReady: boolean = false;
  isBusy: boolean = false;
  hasUnsavedChanges: boolean = false;
  hasSavedSolution: boolean = false;
  canComplete: boolean = false;
  isCompleted: boolean = false;
  canUndo: boolean = false;
  localDraftSaved: boolean = false;
  errorMessage: string = '';
}

export interface CanvasWorkspaceActions {
  cancelV2Snapshot?: (questionId: string, requestId: string) => void;
  freezeV2AtPenUp?: (questionId: string, requestId: string) => Promise<AuthResult<FrozenAnswerUpload>>;
  cancelTutorFreeze?: (questionId: string, turnId: string) => void;
  freezeAtPenUp?: (questionId: string, turnId: string) => Promise<AuthResult<FrozenTutorAnswer>>;
  sendFrozenTutorAnswer?: (frozen: FrozenTutorAnswer) => Promise<AuthResult<TutorTurn>>;
  save: () => Promise<AuthResult<SolutionDetail>>;
  markCompleted: () => Promise<AuthResult<SolutionDetail>>;
  continueWriting: () => Promise<AuthResult<SolutionDetail>>;
  flushLocal: () => Promise<AuthResult<boolean>>;
}

/** 外层按钮委托当前已挂载工作区；未就绪明确失败，不伪报保存。 */
export class TabletCanvasController {
  private actions: CanvasWorkspaceActions | null = null;
  attach(actions: CanvasWorkspaceActions): void { this.actions = actions; }
  detach(actions: CanvasWorkspaceActions): void {
    if (this.actions === actions) { this.actions = null; }
  }
  cancelV2Snapshot(questionId: string, requestId: string): void { this.actions?.cancelV2Snapshot?.(questionId, requestId); }
  async freezeV2AtPenUp(questionId: string, requestId: string): Promise<AuthResult<FrozenAnswerUpload>> {
    const action = this.actions?.freezeV2AtPenUp;
    return action === undefined ? AuthResult.failure<FrozenAnswerUpload>(
      AuthError.local_validation('画布冻结尚未就绪', {})) : action(questionId, requestId);
  }
  cancelTutorFreeze(questionId: string, turnId: string): void { this.actions?.cancelTutorFreeze?.(questionId,turnId); }
  async freezeAtPenUp(questionId: string, turnId: string): Promise<AuthResult<FrozenTutorAnswer>> {
    const action: ((questionId: string, turnId: string) => Promise<AuthResult<FrozenTutorAnswer>>) | undefined = this.actions?.freezeAtPenUp;
    return action === undefined ? AuthResult.failure<FrozenTutorAnswer>(
      AuthError.local_validation('画布冻结尚未就绪', {})) : action(questionId,turnId);
  }
  async sendFrozenTutorAnswer(frozen: FrozenTutorAnswer): Promise<AuthResult<TutorTurn>> {
    const action: ((frozen: FrozenTutorAnswer) => Promise<AuthResult<TutorTurn>>) | undefined = this.actions?.sendFrozenTutorAnswer;
    return action === undefined ? AuthResult.failure<TutorTurn>(
      AuthError.local_validation('画布求助发送尚未就绪', {})) : action(frozen);
  }
  async save(): Promise<AuthResult<SolutionDetail>> {
    return this.actions === null ? this.not_ready() : this.actions.save();
  }
  async markCompleted(): Promise<AuthResult<SolutionDetail>> {
    return this.actions === null ? this.not_ready() : this.actions.markCompleted();
  }
  async continueWriting(): Promise<AuthResult<SolutionDetail>> {
    return this.actions === null ? this.not_ready() : this.actions.continueWriting();
  }
  async flushLocal(): Promise<AuthResult<boolean>> {
    return this.actions === null ? AuthResult.failure<boolean>(
      AuthError.local_validation('画布尚未准备完成', {})) : this.actions.flushLocal();
  }
  private not_ready(): AuthResult<SolutionDetail> {
    return AuthResult.failure<SolutionDetail>(AuthError.local_validation('画布尚未准备完成', {}));
  }
}
