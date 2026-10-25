import { TutorTurn } from 'common';

export enum PhoneHomeMode {
  COLLABORATION = 'collaboration',
  SINGLE_DEVICE = 'single-device'
}

/** 页面间返回分发；仅持有当前实例回调，退出会话时解除。 */
export class PhoneBackController {
  handleBack: () => boolean = (): boolean => false;
}

/** 每道题独立保存草稿和未知请求身份；不持有令牌、图片或计时器。 */
export class PhoneTutorSession {
  readonly questionId: string;
  readonly mode: PhoneHomeMode;
  label: string = '题库中选择的题目';
  prompt: string = '';
  pendingRequestId: string = '';
  pendingPrompt: string = '';
  expandedTurnId: string = '';
  turns: TutorTurn[] = [];
  scrollOffset: number = 0;
  hasScrollPosition: boolean = false;

  constructor(questionId: string, mode: PhoneHomeMode = PhoneHomeMode.COLLABORATION) {
    this.questionId = questionId;
    this.mode = mode;
  }
}

/** 单个登录会话的轻量缓存；按模式和题号隔离，组件切题时恢复。 */
export class PhoneTutorSessionStore {
  private sessions: Map<string, PhoneTutorSession> = new Map<string, PhoneTutorSession>();

  get(questionId: string, mode: PhoneHomeMode = PhoneHomeMode.COLLABORATION): PhoneTutorSession {
    const key: string = mode + ':' + questionId;
    let session: PhoneTutorSession | undefined = this.sessions.get(key);
    if (session === undefined) {
      session = new PhoneTutorSession(questionId, mode);
      this.sessions.set(key, session);
    }
    return session;
  }

  history(): PhoneTutorSession[] {
    const result: PhoneTutorSession[] = [];
    this.sessions.forEach((session: PhoneTutorSession) => {
      result.push(session);
    });
    result.reverse();
    return result;
  }

  clear(): void {
    this.sessions.clear();
  }
}
