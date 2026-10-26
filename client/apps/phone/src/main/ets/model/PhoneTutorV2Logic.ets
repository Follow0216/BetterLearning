import { accept_v2_detail, V2TutorRequest, V2TutorRequestDetail, V2TutorRequestStatus } from 'common';

/** UI、网络回调共同使用的纯状态规则。 */
export function phone_v2_active(status: V2TutorRequestStatus): boolean {
  return status === V2TutorRequestStatus.WAITING_INPUT || status === V2TutorRequestStatus.QUEUED ||
    status === V2TutorRequestStatus.RUNNING || status === V2TutorRequestStatus.CANCELLING;
}

export function phone_v2_retryable(request: V2TutorRequest): boolean {
  return (request.snapshotFileRef !== null || request.snapshotInput.kind === 'NO_ANSWER') &&
    request.currentRunId !== null &&
    (request.status === V2TutorRequestStatus.FAILED || request.status === V2TutorRequestStatus.TIMED_OUT);
}

/** 列表快照不允许把同一 request 已看到的较新 revision 覆盖。 */
export function phone_v2_merge_requests(current: V2TutorRequest[], incoming: V2TutorRequest[]): V2TutorRequest[] {
  const result: V2TutorRequest[] = [];
  for (let index: number = 0; index < incoming.length; index++) {
    let chosen: V2TutorRequest = incoming[index];
    for (let oldIndex: number = 0; oldIndex < current.length; oldIndex++) {
      const old: V2TutorRequest = current[oldIndex];
      if (old.requestId === chosen.requestId && (old.revision > chosen.revision ||
        old.revision === chosen.revision && (old.currentRunId !== chosen.currentRunId ||
          old.status !== chosen.status))) {
        chosen = old;
        break;
      }
    }
    result.push(chosen);
  }
  for (let index: number = 0; index < current.length; index++) {
    let found: boolean = false;
    for (let newIndex: number = 0; newIndex < result.length; newIndex++) {
      if (result[newIndex].requestId === current[index].requestId) {
        found = true;
        break;
      }
    }
    if (!found) { result.push(current[index]); }
  }
  return result;
}

/** 对未知 POST 回执只保留原操作标识，直到 GET 确认或用户修改表单。 */
export class PhoneV2CreateIntent {
  requestId: string = '';
  prompt: string = '';
  mode: string = '';
  snapshotKind: string = '';
  sourceRequestId: string = '';
  contextParentRequestId: string | null = null;
  confirmedMissing: boolean = false;

  matches(prompt: string, mode: string, snapshotKind: string, sourceRequestId: string,
    contextParentRequestId: string | null = null): boolean {
    return this.requestId.length > 0 && this.prompt === prompt && this.mode === mode &&
      this.snapshotKind === snapshotKind && this.sourceRequestId === sourceRequestId &&
      this.contextParentRequestId === contextParentRequestId;
  }

  clear(): void {
    this.requestId = '';
    this.prompt = '';
    this.mode = '';
    this.snapshotKind = '';
    this.sourceRequestId = '';
    this.contextParentRequestId = null;
    this.confirmedMissing = false;
  }
}

/** 同一进程内按账号和题目保留未知 POST 的幂等键；退出账号时清除。 */
const phoneV2Intents: Map<string, PhoneV2CreateIntent> = new Map<string, PhoneV2CreateIntent>();

export function phone_v2_intent_for(accountId: string, questionId: string): PhoneV2CreateIntent {
  const key: string = accountId + ':' + questionId;
  let intent: PhoneV2CreateIntent | undefined = phoneV2Intents.get(key);
  if (intent === undefined) {
    intent = new PhoneV2CreateIntent();
    phoneV2Intents.set(key, intent);
  }
  return intent;
}

export function phone_v2_clear_all_intents(): void {
  phoneV2Intents.clear();
  phoneV2Drafts.clear();
}

/** 首页切题时保留尚未发送的 V2 表单，不跨账号复用。 */
export class PhoneV2Draft {
  prompt: string = '';
  fullSolution: boolean = false;
  reuseRequestId: string = '';
  selectedRequestId: string = '';
  contextParentRequestId: string = '';
  independentQuestion: boolean = false;
  questionBody: string = '';
  questionLabel: string = '';
  questionExpanded: boolean = false;
  questionReturnAnchorId: string = '';
  questionReturnAnchorOffset: number = 0;
  failedPrompt: string = '';
  collapsedRequestIds: string[] = [];
  requests: V2TutorRequest[] = [];
  details: V2TutorRequestDetail[] = [];
  nextCursor: string | null = null;
  loadedOnce: boolean = false;
  anchorRequestId: string = '';
  anchorOffset: number = 0;
  followLatest: boolean = true;
}

export interface PhoneV2RecentQuestion {
  questionId: string;
  label: string;
  hasDraft: boolean;
  hasAnswer: boolean;
  active: boolean;
  loaded: boolean;
}

/** 只枚举实际打开过的同账号题目，不推断未查询的题目状态。 */
export function phone_v2_recent_questions(accountId: string): PhoneV2RecentQuestion[] {
  const result: PhoneV2RecentQuestion[] = [];
  const prefix: string = accountId + ':';
  phoneV2Drafts.forEach((draft: PhoneV2Draft, key: string) => {
    if (!key.startsWith(prefix)) { return; }
    result.push({ questionId: key.substring(prefix.length),
      label: draft.questionLabel.length > 0 ? draft.questionLabel : '题目 #' + key.substring(prefix.length),
      hasDraft: draft.prompt.trim().length > 0,
      hasAnswer: draft.details.some((detail: V2TutorRequestDetail) => phone_v2_verified(detail)),
      active: draft.requests.some((request: V2TutorRequest) => phone_v2_active(request.status)),
      loaded: draft.loadedOnce });
  });
  return result.reverse();
}

const phoneV2Drafts: Map<string, PhoneV2Draft> = new Map<string, PhoneV2Draft>();

export function phone_v2_draft_for(accountId: string, questionId: string): PhoneV2Draft {
  const key: string = accountId + ':' + questionId;
  let draft: PhoneV2Draft | undefined = phoneV2Drafts.get(key);
  if (draft === undefined) {
    draft = new PhoneV2Draft();
    phoneV2Drafts.set(key, draft);
  }
  return draft;
}

/** 选中的详情仅允许同 request 且较新 revision 的回写。 */
export function phone_v2_accept_selected(current: V2TutorRequestDetail | null,
  incoming: V2TutorRequestDetail, selectedRequestId: string): boolean {
  if (incoming.request.requestId !== selectedRequestId) {
    return false;
  }
  if (current === null) {
    return true;
  }
  if (current.request.requestId !== selectedRequestId) {
    return false;
  }
  return accept_v2_detail(current, incoming);
}

/** 创建 POST 和其后 GET 确认共用的不可回退状态合并。 */
export interface PhoneV2ViewState {
  requests: V2TutorRequest[];
  selectedRequestId: string;
  detail: V2TutorRequestDetail | null;
}

export function phone_v2_apply_created(state: PhoneV2ViewState,
  request: V2TutorRequest): PhoneV2ViewState {
  let requests: V2TutorRequest[] = phone_v2_merge_requests(state.requests, [request]);
  const sameSelection: boolean = state.selectedRequestId === request.requestId;
  const detail: V2TutorRequestDetail | null = sameSelection ? state.detail : null;
  if (detail !== null) {
    requests = phone_v2_merge_requests(requests, [detail.request]);
  }
  return { requests: requests, selectedRequestId: request.requestId, detail: detail };
}

export function phone_v2_apply_detail(state: PhoneV2ViewState,
  incoming: V2TutorRequestDetail): PhoneV2ViewState {
  for (let index: number = 0; index < state.requests.length; index++) {
    const current: V2TutorRequest = state.requests[index];
    if (current.requestId === incoming.request.requestId &&
      (current.revision > incoming.request.revision || current.revision === incoming.request.revision &&
        (current.currentRunId !== incoming.request.currentRunId ||
          current.status !== incoming.request.status))) { return state; }
  }
  if (!phone_v2_accept_selected(state.detail, incoming, state.selectedRequestId)) { return state; }
  const requests: V2TutorRequest[] = phone_v2_merge_requests(state.requests, [incoming.request]);
  return { requests: requests, selectedRequestId: state.selectedRequestId, detail: incoming };
}


/** 消息按确定时间从旧到新；稳定 requestId 不随 revision 重建气泡。 */
export function phone_v2_chronological(requests: V2TutorRequest[]): V2TutorRequest[] {
  const ordered: V2TutorRequest[] = requests.slice();
  ordered.sort((left: V2TutorRequest, right: V2TutorRequest): number => {
    const leftTime: number = Date.parse(left.createdAt);
    const rightTime: number = Date.parse(right.createdAt);
    if (Number.isFinite(leftTime) && Number.isFinite(rightTime) && leftTime !== rightTime) {
      return leftTime < rightTime ? -1 : 1;
    }
    // 同一时刻可有不同 ISO offset/小数格式，以不可变 requestId 打破平局。
    return left.requestId < right.requestId ? -1 : (left.requestId === right.requestId ? 0 : 1);
  });
  return ordered;
}

export function phone_v2_verified(detail: V2TutorRequestDetail | null): boolean {
  return detail !== null && detail.request.status === V2TutorRequestStatus.SUCCEEDED &&
    detail.request.currentRunId !== null && detail.run !== null &&
    detail.run.runId === detail.request.currentRunId && detail.run.requestId === detail.request.requestId &&
    detail.result !== null && detail.result.status === 'VERIFIED';
}

/** 每条详情独立比较实体 revision，保留原有换 run 证明链校验。 */
export function phone_v2_merge_details(current: V2TutorRequestDetail[], incoming: V2TutorRequestDetail,
  requests: V2TutorRequest[]): V2TutorRequestDetail[] {
  for (let index: number = 0; index < requests.length; index++) {
    const summary: V2TutorRequest = requests[index];
    if (summary.requestId === incoming.request.requestId &&
      (summary.revision > incoming.request.revision || summary.revision === incoming.request.revision &&
        (summary.currentRunId !== incoming.request.currentRunId || summary.status !== incoming.request.status))) {
      return current;
    }
  }
  const next: V2TutorRequestDetail[] = current.slice();
  for (let index: number = 0; index < next.length; index++) {
    if (next[index].request.requestId !== incoming.request.requestId) { continue; }
    if (!accept_v2_detail(next[index], incoming)) { return current; }
    next[index] = incoming;
    return next;
  }
  next.push(incoming);
  return next;
}

/** 默认父轮须有确切 VERIFIED 详情；较新成功轮尚未确认时不能静默跳到旧轮。 */
export function phone_v2_context_parent(requests: V2TutorRequest[], details: V2TutorRequestDetail[],
  explicitRequestId: string = '', independentQuestion: boolean = false): string | null {
  if (independentQuestion) { return null; }
  const ordered: V2TutorRequest[] = phone_v2_chronological(requests);
  let candidate: string = explicitRequestId;
  if (candidate.length === 0) {
    for (let index: number = ordered.length - 1; index >= 0; index--) {
      if (ordered[index].status === V2TutorRequestStatus.SUCCEEDED) {
        candidate = ordered[index].requestId;
        break;
      }
    }
  }
  if (candidate.length === 0) { return null; }
  for (let index: number = 0; index < details.length; index++) {
    const detail: V2TutorRequestDetail = details[index];
    if (detail.request.requestId !== candidate || !phone_v2_verified(detail)) { continue; }
    for (let summaryIndex: number = 0; summaryIndex < requests.length; summaryIndex++) {
      const summary: V2TutorRequest = requests[summaryIndex];
      if (summary.requestId === candidate && summary.revision === detail.request.revision &&
        summary.currentRunId === detail.request.currentRunId && summary.status === detail.request.status) {
        return candidate;
      }
    }
  }
  return null;
}

export function phone_v2_context_unconfirmed(requests: V2TutorRequest[], details: V2TutorRequestDetail[],
  explicitRequestId: string = '', independentQuestion: boolean = false): boolean {
  if (independentQuestion) { return false; }
  return (explicitRequestId.length > 0 || requests.some((request: V2TutorRequest) =>
    request.status === V2TutorRequestStatus.SUCCEEDED)) &&
    phone_v2_context_parent(requests, details, explicitRequestId, false) === null;
}

/** 只解释当前 run 内唯一 RUNNING 的真实步骤；不比较不同步骤的 revision。 */
export function phone_v2_stage_text(request: V2TutorRequest, detail: V2TutorRequestDetail | null): string {
  if (request.status === V2TutorRequestStatus.WAITING_INPUT) { return '等待平板同步作答'; }
  if (request.status === V2TutorRequestStatus.QUEUED) { return '正在排队'; }
  if (request.status === V2TutorRequestStatus.CANCELLING) { return '正在取消'; }
  if (request.status === V2TutorRequestStatus.SUCCEEDED) {
    return phone_v2_verified(detail) ? '讲解已完成' : '正在确认已发布的讲解';
  }
  if (request.status === V2TutorRequestStatus.FAILED) { return '未能完成讲解'; }
  if (request.status === V2TutorRequestStatus.TIMED_OUT) { return '等待超时'; }
  if (request.status === V2TutorRequestStatus.CANCELLED) { return '已取消'; }
  if (detail === null || detail.run === null || request.currentRunId === null ||
    detail.run.runId !== request.currentRunId || detail.run.requestId !== request.requestId ||
    detail.run.status !== 'RUNNING') { return '正在生成讲解'; }
  let running: number = 0;
  let role: string = '';
  for (let index: number = 0; index < detail.run.steps.length; index++) {
    if (detail.run.steps[index].status === 'RUNNING') {
      running++;
      role = detail.run.steps[index].role;
    }
  }
  if (running !== 1) { return '正在生成讲解'; }
  if (role === 'SOLVE') { return '正在分析题目'; }
  if (role === 'DIAGNOSE') { return '正在检查作答思路'; }
  if (role === 'RETRIEVE') { return '正在查找相关学习资料'; }
  if (role === 'TEACH') { return '正在整理讲解'; }
  if (role === 'REVIEW') { return '正在核对讲解'; }
  return '正在生成讲解';
}

/** 来自 create 的原子选择结果；无作答与已冻结的零笔画作答始终是两条路径。 */
export function phone_v2_input_text(request: V2TutorRequest | null): string {
  if (request === null) { return ''; }
  if (request.snapshotInput.kind === 'NO_ANSWER') {
    if (request.inputSelectionReason === 'OTHER_QUESTION') {
      return '平板正在其他题目；本轮使用题面、问题和已确认的问答上下文。';
    }
    if (request.inputSelectionReason === 'OFFLINE') {
      return '平板离线或可用状态已过期；本轮使用题面、问题和已确认的问答上下文。';
    }
    return '未检测到同账号可用平板；本轮使用题面、问题和已确认的问答上下文。';
  }
  if (request.snapshotInput.kind === 'REUSE_REQUEST') { return '本轮沿用所选历史作答。'; }
  if (request.snapshotFileRef === null) {
    return request.inputSelectionReason === 'TABLET_AVAILABLE' ? '已检测到本题平板，正在同步本轮作答。' :
      '本轮等待平板同步当前题作答。';
  }
  return '本轮使用平板新冻结的作答；未书写的画布也会作为真实作答同步。';
}
