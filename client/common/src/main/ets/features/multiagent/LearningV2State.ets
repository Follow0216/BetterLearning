import { V2TutorRequestDetail, V2TutorRequestStatus, V2RunStatus } from './LearningV2Models';

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
  if (current.run === null && incoming.run !== null &&
    (current.request.status !== V2TutorRequestStatus.WAITING_INPUT ||
      incoming.run.runNo !== 1 || incoming.run.retryOfRunId !== undefined &&
        incoming.run.retryOfRunId !== null)) { return false; }
  if (current.run !== null && incoming.run === null) { return false; }
  if (current.run !== null && incoming.run !== null) {
    if (current.run.runId === incoming.run.runId) {
      if (incoming.run.revision < current.run.revision ||
        incoming.run.revision === current.run.revision && incoming.run.status !== current.run.status ||
        terminal(current.run.status) && incoming.run.status !== current.run.status) { return false; }
    } else if ((current.run.status !== V2RunStatus.FAILED && current.run.status !== V2RunStatus.TIMED_OUT) ||
      incoming.run.runNo !== current.run.runNo + 1 ||
      incoming.run.retryOfRunId !== current.run.runId ||
      incoming.request.revision <= current.request.revision) { return false; }
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
