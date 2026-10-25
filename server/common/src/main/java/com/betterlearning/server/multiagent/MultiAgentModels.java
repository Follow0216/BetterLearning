package com.betterlearning.server.multiagent;

import java.time.Instant;
import java.util.List;

/** 跨模块纯 Java 契约；正文与供应商诊断不进入工作流历史。 */
public final class MultiAgentModels {
    private MultiAgentModels() {
    }

    public enum Mode { HINT, FULL_SOLUTION }
    public enum Role { RETRIEVE, SOLVE, DIAGNOSE, TEACH, REVIEW }
    public enum Status { WAITING_INPUT, QUEUED, RUNNING, CANCELLING, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT }
    public enum SourceType { QUESTION, SAVED_ANSWER, TUTOR_SNAPSHOT, TUTOR_REPLY, NOTE }

    public record SnapshotInput(String kind, String sourceRequestId) { }
    public record CreateRequest(String requestId, String prompt, Mode mode, SnapshotInput snapshotInput) { }
    public record Request(String requestId, String questionId, String prompt, Mode mode, SnapshotInput snapshotInput,
            String snapshotOriginRequestId, String snapshotFileRef, String currentRunId, Status status,
            long revision, Instant createdAt, Instant updatedAt) { }
    public record RequestPage(List<Request> items, String nextCursor) { }
    public record Pending(Request request) { }
    public record CancelRequest(String expectedRunId) { }
    public record RetryRequest(String targetRunId) { }
    public record Step(String stepId, Role role, String status, long revision) { }
    public record Run(String runId, String requestId, int runNo, String retryOfRunId, Status status,
            long revision, String reason, Instant cancelRequestedAt, List<Step> steps, List<Attempt> attempts) { }
    public record RequestDetail(Request request, Run run, List<Run> runs, Result result) { }
    public record SnapshotBinding(String fileRef, String contentSha256, String sourceId, long version) { }
    public record Execution(long accountId, String requestId, String questionId, String runId, Mode mode,
            String prompt, String snapshotFileRef, String snapshotSourceId, long snapshotVersion,
            Status status, long revision, Instant queueDeadlineAt, Instant runDeadlineAt,
            Instant cancelRequestedAt, int modelCalls) { }
    public record Attempt(String attemptId, String stepId, int attemptNo, String status, String providerCallState,
            long fence, Instant startedAt, Instant endedAt, String output, String outputDigest) { }
    public record Evidence(String evidenceId, String runId, String stepId, String type, String sourceId,
            Long version, String anchorId, String claim, Double confidence) { }
    public record Citation(String citationId, String sourceId, long version, String anchorId, String label, SourceType sourceType) { }
    public record Result(String resultId, String runId, Mode mode, String status, String bodyMarkdown,
            List<Citation> citations, Instant createdAt) { }
    public record ResultDraft(String bodyMarkdown, List<String> evidenceIds) { }
    public record OutboxEvent(String eventId, long accountId, String aggregateType, String aggregateId,
            long aggregateVersion, String kind, String payloadDigest, String status) { }
    public record Source(String sourceId, long accountId, SourceType type, String originId, String title,
            String status, long currentVersion, Instant createdAt) { }
    public record SourceVersion(String sourceId, long version, String contentSha256, String status,
            String answerState, String fileRef, String bodyMarkdown, Instant createdAt) { }
    public record Anchor(String anchorId, String sourceId, long version, String kind, int ordinal,
            String contentSha256, String locatorJson, String text) { }
    public record SourceContent(Source source, SourceVersion version, List<Anchor> anchors) { }
    public record PublishSource(SourceType type, String originId, String title, String bodyMarkdown,
            String contentSha256, String fileRef, String answerState, Long expectedVersion) { }
    public record Image(byte[] bytes, String mediaType, String position) {
        public Image {
            bytes = bytes.clone();
        }
        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }
    public record Snapshot(String fileRef, String questionMarkdown, List<Image> images) { }
    public record RetrievalQuery(long accountId, String runId, String questionId, String text,
            List<String> methodTags, int limit) { }
    public record PipelineContext(Execution execution, Snapshot snapshot, List<Evidence> evidence) { }
    public record PipelineResult(boolean verified, String bodyMarkdown, List<String> evidenceIds, String reason) { }
}
