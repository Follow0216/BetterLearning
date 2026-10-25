package com.betterlearning.server.multiagent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.betterlearning.server.multiagent.MultiAgentModels.*;

/** 业务主库为唯一状态权威；所有变更必须带账号且在短事务中检查当前 run/fence。 */
public interface TutorLedger {
    Request create(long accountId, String questionId, String idempotencyKey, CreateRequest command);
    Request get(long accountId, String questionId, String requestId);
    Request getByRequestId(long accountId, String requestId);
    RequestDetail detail(long accountId, String questionId, String requestId);
    RequestPage list(long accountId, String questionId, int limit, String cursor);
    Pending pending(long accountId, String questionId);
    Request bindSnapshot(long accountId, String questionId, String requestId, SnapshotBinding snapshot);
    Request cancel(long accountId, String questionId, String requestId, String expectedRunId);
    Request retry(long accountId, String questionId, String requestId, String key, String targetRunId);
    Execution execution(long accountId, String runId);
    Optional<Execution> start(long accountId, String runId, long expectedRevision);
    Attempt beginAttempt(long accountId, String runId, Role role, int sequence, String inputDigest);
    boolean callIntent(long accountId, String runId, String attemptId, long fence);
    /** 外部调用确认结束时释放执行预算，即使取消已废止发布fence也可释放；本方法不授予发布权。 */
    boolean finishProviderCall(long accountId, String runId, String attemptId);
    int providerCallsInFlight(Long accountId);
    boolean confirm(long accountId, String runId, String attemptId, long fence, String output, String outputDigest);
    /** 同事务将attempt置UNKNOWN、run置FAILED/OUTCOME_UNKNOWN并释放交互槽，禁止自动重发。 */
    boolean unknown(long accountId, String runId, String attemptId, long fence);
    boolean fail(long accountId, String runId, long expectedRevision, String reason);
    boolean publish(long accountId, String runId, long expectedRevision, ResultDraft result);
    void recordEvidence(long accountId, String runId, List<Evidence> evidence);
    List<Evidence> evidence(long accountId, String runId);
    List<OutboxEvent> outstandingEvents(int limit);
    boolean acknowledgeEvent(String eventId);
    int reconcileDeadlines(Instant now, int limit);
}
