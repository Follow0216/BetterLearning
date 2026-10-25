import { accept_v2_detail, V2TutorRequest, V2TutorRequestDetail, V2TutorRequestStatus } from 'common';

/** UI、网络回调共同使用的纯状态规则。 */
export function phone_v2_active(status: V2TutorRequestStatus): boolean {
  return status === V2TutorRequestStatus.WAITING_INPUT || status === V2TutorRequestStatus.QUEUED ||
    status === V2TutorRequestStatus.RUNNING || status === V2TutorRequestStatus.CANCELLING;
}

export function phone_v2_retryable(request: V2TutorRequest): boolean {
  return request.snapshotFileRef !== null && request.currentRunId !== null &&
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

  matches(prompt: string, mode: string, snapshotKind: string, sourceRequestId: string): boolean {
    return this.requestId.length > 0 && this.prompt === prompt && this.mode === mode &&
      this.snapshotKind === snapshotKind && this.sourceRequestId === sourceRequestId;
  }

  clear(): void {
    this.requestId = '';
    this.prompt = '';
    this.mode = '';
    this.snapshotKind = '';
    this.sourceRequestId = '';
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
