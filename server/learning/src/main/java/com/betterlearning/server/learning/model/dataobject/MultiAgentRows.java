package com.betterlearning.server.learning.model.dataobject;

import java.time.Instant;

/** Mapper 内部行模型，持久化层不泄露到 HTTP。 */
public final class MultiAgentRows {
    private MultiAgentRows() { }
    public record RequestRow(String requestId, long accountId, long questionId, String prompt, String mode,
            String snapshotKind, String sourceRequestId, String snapshotOriginRequestId, String snapshotFileRef,
            String snapshotSha256, String snapshotSourceId, Long snapshotVersion, String currentRunId,
            String status, long revision, Instant inputDeadlineAt, Instant createdAt, Instant updatedAt,
            String contextParentRequestId, String conversationContextJson) { }
    public record RunRow(String runId, String requestId, long accountId, int runNo, String retryOfRunId,
            String status, long revision, String reason, Instant cancelRequestedAt, Instant queueDeadlineAt,
            Instant runDeadlineAt, int modelCalls, Instant createdAt, Instant updatedAt) { }
    public record StepRow(String stepId, String runId, String role, int sequence, String status, long revision,
            String inputDigest, String outputDigest) { }
    public record RetryRow(long accountId, String idempotencyKey, String requestId, String targetRunId, String runId) { }
    public record CursorRow(String cursorId, long accountId, long questionId, Instant beforeTime,
            String beforeId, Instant expiresAt) { }
    public record ResultRow(String resultId, String runId, String mode, String status, String bodyMarkdown,
            Instant createdAt) { }
}
