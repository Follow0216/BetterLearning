import { AuthError, AuthResult, SolutionDetail } from 'common';

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
