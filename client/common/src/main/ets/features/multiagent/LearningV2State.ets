import { V2TutorRequestDetail, V2TutorRequestStatus, V2Run, V2RunStatus } from './LearningV2Models';

/**
 * 请求详情只接受相同 request 的新 revision。
 * 同 revision 的重复轮询可覆盖同一 run 的相同状态；旧 run 的迟到回包不能覆盖 retry。
 */
function terminal(status: V2TutorRequestStatus | V2RunStatus): boolean {
  return status === V2TutorRequestStatus.SUCCEEDED || status === V2TutorRequestStatus.FAILED ||
    status === V2TutorRequestStatus.CANCELLED || status === V2TutorRequestStatus.TIMED_OUT;
}

export function accept_v2_detail(current: V2TutorRequestDetail | null,
  incoming: V2TutorRequestDetail): boolean {
  if (!incoming || !incoming.request || !Array.isArray(incoming.runs) ||
    incoming.request.currentRunId !== (incoming.run === null ? null : incoming.run.runId)) { return false; }
  if (current === null) { return true; }
  if (incoming.request.requestId !== current.request.requestId ||
    incoming.request.questionId !== current.request.questionId ||
    incoming.request.revision < current.request.revision) { return false; }
  if (incoming.request.revision === current.request.revision &&
    (incoming.request.currentRunId !== current.request.currentRunId ||
      incoming.request.status !== current.request.status)) { return false; }
  if (current.run !== null && incoming.run === null) { return false; }
  if (current.run !== null && incoming.run !== null) {
    if (current.run.runId === incoming.run.runId) {
      if (incoming.run.revision < current.run.revision ||
        incoming.run.revision === current.run.revision && incoming.run.status !== current.run.status ||
        terminal(current.run.status) && incoming.run.status !== current.run.status) { return false; }
    } else {
      if (current.run.status === V2RunStatus.SUCCEEDED || current.run.status === V2RunStatus.CANCELLED ||
        incoming.request.revision <= current.request.revision) { return false; }
      let previous: V2Run | null = null;
      let found: boolean = false;
      for (let index: number = 0; index < incoming.runs.length; index++) {
        const candidate: V2Run = incoming.runs[index];
        if (!found) {
          if (candidate.runId !== current.run.runId) { continue; }
          if (candidate.runNo !== current.run.runNo || candidate.revision < current.run.revision ||
            candidate.status !== V2RunStatus.FAILED && candidate.status !== V2RunStatus.TIMED_OUT ||
            terminal(current.run.status) && candidate.status !== current.run.status) { return false; }
          found = true;
        } else if (previous !== null) {
          if (previous.status !== V2RunStatus.FAILED && previous.status !== V2RunStatus.TIMED_OUT ||
            candidate.runNo !== previous.runNo + 1 || candidate.retryOfRunId !== previous.runId) {
            return false;
          }
        }
        previous = candidate;
      }
      if (!found || previous === null || previous.runId !== incoming.run.runId ||
        previous.runNo !== incoming.run.runNo || previous.revision !== incoming.run.revision ||
        previous.status !== incoming.run.status || previous.runId === current.run.runId) { return false; }
    }
  }
  if (current.run === null && incoming.run !== null) {
    if (current.request.status !== V2TutorRequestStatus.WAITING_INPUT ||
      incoming.request.revision <= current.request.revision) { return false; }
    let previous: V2Run | null = null;
    for (let index: number = 0; index < incoming.runs.length; index++) {
      const candidate: V2Run = incoming.runs[index];
      if (previous === null) {
        if (candidate.runNo !== 1 || candidate.retryOfRunId !== undefined &&
          candidate.retryOfRunId !== null) { return false; }
      } else if ((previous.status !== V2RunStatus.FAILED && previous.status !== V2RunStatus.TIMED_OUT) ||
        candidate.runNo !== previous.runNo + 1 || candidate.retryOfRunId !== previous.runId) { return false; }
      previous = candidate;
    }
    if (previous === null || previous.runId !== incoming.run.runId) { return false; }
  }
  if (terminal(current.request.status) && current.request.currentRunId === incoming.request.currentRunId &&
    incoming.request.status !== current.request.status) { return false; }
  if (current.result !== null && JSON.stringify(current.result) !== JSON.stringify(incoming.result)) { return false; }
  if (incoming.result !== null && (incoming.run === null ||
    incoming.run.status !== V2RunStatus.SUCCEEDED ||
    incoming.request.status !== V2TutorRequestStatus.SUCCEEDED)) { return false; }
  return true;
}

/** 不持有令牌或网络状态；切题/换账号由页面把 current 置 null 后重建绑定。 */
export function v2_detail_is_active(detail: V2TutorRequestDetail): boolean {
  return detail.request.status === V2TutorRequestStatus.WAITING_INPUT ||
    detail.request.status === V2TutorRequestStatus.QUEUED ||
    detail.request.status === V2TutorRequestStatus.RUNNING ||
    detail.request.status === V2TutorRequestStatus.CANCELLING;
}
