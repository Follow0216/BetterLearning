package com.betterlearning.server.agent;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.betterlearning.server.multiagent.MultiAgentModels;

/** V2 辅导 HTTP 契约；所有 ID 均为服务端校验后的字符串。 */
public final class TutorRequestModels {
    private TutorRequestModels() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SnapshotInput(String kind, String sourceRequestId) {
    }

    public record CreateRequest(String requestId, String prompt, String mode, SnapshotInput snapshotInput,
            String contextParentRequestId) {
        public CreateRequest(String requestId, String prompt, String mode, SnapshotInput snapshotInput) {
            this(requestId, prompt, mode, snapshotInput, null);
        }
    }

    public record CancelRequest(String expectedRunId) {
    }

    public record RetryRequest(String targetRunId) {
    }

    public record Request(String requestId, String questionId, String prompt, String mode,
            SnapshotInput snapshotInput, String snapshotOriginRequestId, String snapshotFileRef,
            String currentRunId, String status, long revision, Instant createdAt, Instant updatedAt, String contextParentRequestId) {
    }

    public record Page(List<Request> items, String nextCursor) {
    }

    public record Pending(Request request) {
    }

    public record Step(String stepId, String role, String status, long revision) {
    }

    public record Attempt(String attemptId, String stepId, int attemptNo, String status,
            String providerCallState, long fence, Instant startedAt, Instant endedAt) {
    }

    public record Run(String runId, String requestId, int runNo, String retryOfRunId, String status,
            @JsonInclude(JsonInclude.Include.NON_NULL) String reason, long revision, Instant cancelRequestedAt, List<Step> steps, List<Attempt> attempts) {
    }

    public record Result(String resultId, String mode, String status, String bodyMarkdown,
            List<MultiAgentModels.Citation> citations, List<MultiAgentModels.Evidence> evidence, Instant createdAt) {
    }

    public record Detail(Request request, Run run, List<Run> runs, Result result) {
    }
}
