package com.betterlearning.server.learning.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.dao.LearningDao;
import com.betterlearning.server.learning.service.RolloutGate;
import com.betterlearning.server.learning.mapper.MultiAgentMapper;
import com.betterlearning.server.learning.model.dataobject.MultiAgentRows.*;
import com.betterlearning.server.multiagent.MultiAgentModels.*;
import com.betterlearning.server.multiagent.SourceStore;
import com.betterlearning.server.multiagent.CitationMarkers;
import com.betterlearning.server.multiagent.TutorLedger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 账号行锁串行化短事务；外部模型和文件I/O绝不在此调用。 */
@Service
@Transactional
public class PostgresTutorLedger implements TutorLedger {
    private static final int MAX_PUBLISHED_CITATIONS = 3;
    private static final Set<String> TERMINAL = Set.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT");
    private static final Set<String> REASONS = Set.of("OUTCOME_UNKNOWN", "MODEL_UNAVAILABLE", "INPUT_TIMEOUT",
            "QUEUE_TIMEOUT", "RUN_TIMEOUT", "BUDGET_EXCEEDED", "REVIEW_REJECTED", "RETRIEVAL_FAILED");
    private final MultiAgentMapper db;
    private final LearningDao questions;
    private final SourceStore sources;
    private final RolloutGate rollout;

    public PostgresTutorLedger(MultiAgentMapper db, LearningDao questions, SourceStore sources, RolloutGate rollout) {
        this.db = db;
        this.questions = questions;
        this.sources = sources;
        this.rollout = rollout;
    }

    @Override
    public Request create(long accountId, String questionId, String key, CreateRequest command) {
        rollout.require("V2");
        long question = question(accountId, questionId);
        if (command == null) {
            throw invalid();
        }
        uuid(key);
        uuid(command.requestId());
        if (!key.equals(command.requestId()) || command.prompt() == null || command.prompt().isBlank()
                || command.prompt().length() > 4000) {
            throw invalid();
        }
        Mode mode = command.mode() == null ? Mode.HINT : command.mode();
        SnapshotInput input = command.snapshotInput() == null ? new SnapshotInput("NEW_CAPTURE", null) : command.snapshotInput();
        boolean reuse = "REUSE_REQUEST".equals(input.kind());
        if (reuse) {
            uuid(input.sourceRequestId());
            if (key.equals(input.sourceRequestId())) {
                throw invalid();
            }
        } else if (!"NEW_CAPTURE".equals(input.kind()) || input.sourceRequestId() != null) {
            throw invalid();
        }
        lock(accountId);
        expire(accountId, Instant.now());
        RequestRow existing = db.request(accountId, key);
        if (existing != null) {
            if (existing.questionId() != question || !existing.prompt().equals(command.prompt())
                    || !existing.mode().equals(mode.name()) || !existing.snapshotKind().equals(input.kind())
                    || !Objects.equals(existing.sourceRequestId(), input.sourceRequestId())) {
                throw conflict();
            }
            return view(existing);
        }
        vacant(accountId);
        RequestRow origin = null;
        if (reuse) {
            origin = require(accountId, input.sourceRequestId());
            if (origin.questionId() != question) {
                throw missing();
            }
            if (!TERMINAL.contains(origin.status()) || origin.snapshotFileRef() == null) {
                throw conflict();
            }
            source(origin);
        }
        db.insertRequest(new RequestRow(key, accountId, question, command.prompt(), mode.name(), input.kind(),
                input.sourceRequestId(), null, null, null, null, null, null, "WAITING_INPUT", 0,
                Instant.now().plusSeconds(300), null, null));
        if (origin != null) {
            db.bind(accountId, key, origin.snapshotOriginRequestId(), origin.snapshotFileRef(), origin.snapshotSha256(),
                    origin.snapshotSourceId(), origin.snapshotVersion());
            newRun(require(accountId, key), null);
        }
        return view(require(accountId, key));
    }

    @Override
    public Request get(long accountId, String questionId, String requestId) {
        question(accountId, questionId);
        Request request = getByRequestId(accountId, requestId);
        if (!request.questionId().equals(questionId)) {
            throw missing();
        }
        return request;
    }

    @Override
    public Request getByRequestId(long accountId, String requestId) {
        uuid(requestId);
        lock(accountId);
        expire(accountId, Instant.now());
        RequestRow row = require(accountId, requestId);
        question(accountId, Long.toString(row.questionId()));
        return view(row);
    }

    @Override
    public RequestDetail detail(long accountId, String questionId, String requestId) {
        Request request = get(accountId, questionId, requestId);
        List<Run> runs = db.runs(accountId, requestId).stream().map(this::runView).toList();
        Run current = runs.stream().filter(run -> run.runId().equals(request.currentRunId())).findFirst().orElse(null);
        Result result = current == null ? null : result(current.runId());
        return new RequestDetail(request, current, runs, result);
    }

    @Override
    public RequestPage list(long accountId, String questionId, int limit, String cursor) {
        long question = question(accountId, questionId);
        if (limit < 1 || limit > 100) {
            throw invalid();
        }
        lock(accountId);
        expire(accountId, Instant.now());
        CursorRow anchor = null;
        if (cursor != null) {
            uuid(cursor);
            anchor = db.cursor(cursor, accountId, question);
            if (anchor == null) {
                throw invalid();
            }
        }
        List<RequestRow> rows = db.page(accountId, question, anchor == null ? null : anchor.beforeTime(),
                anchor == null ? null : anchor.beforeId(), limit + 1);
        String next = null;
        if (rows.size() > limit) {
            RequestRow last = rows.get(limit - 1);
            next = id();
            db.insertCursor(new CursorRow(next, accountId, question, last.createdAt(), last.requestId(), null));
            rows = rows.subList(0, limit);
        }
        return new RequestPage(rows.stream().map(PostgresTutorLedger::view).toList(), next);
    }

    @Override
    public Pending pending(long accountId, String questionId) {
        long question = question(accountId, questionId);
        lock(accountId);
        expire(accountId, Instant.now());
        return new Pending(db.active(accountId).stream().filter(row -> row.questionId() == question
                && "WAITING_INPUT".equals(row.status())).map(PostgresTutorLedger::view).findFirst().orElse(null));
    }

    @Override
    public Request bindSnapshot(long accountId, String questionId, String requestId, SnapshotBinding snapshot) {
        rollout.require("V2");
        get(accountId, questionId, requestId);
        if (snapshot == null) {
            throw invalid();
        }
        uuid(snapshot.fileRef());
        digest(snapshot.contentSha256());
        RequestRow row = require(accountId, requestId);
        if (!"NEW_CAPTURE".equals(row.snapshotKind())) {
            throw conflict();
        }
        if (row.snapshotFileRef() != null) {
            if (!row.snapshotSha256().equals(snapshot.contentSha256())) {
                throw new BusinessException(ErrorCode.ANSWER_SAVE_CONFLICT);
            }
            return view(row);
        }
        if (!"WAITING_INPUT".equals(row.status())) {
            throw conflict();
        }
        SourceContent content = sources.publish(accountId, new PublishSource(SourceType.TUTOR_SNAPSHOT, requestId,
                "辅导冻结作答", null, snapshot.contentSha256(), snapshot.fileRef(), "DRAFT", null, "snapshot:" + requestId));
        if (db.bind(accountId, requestId, requestId, snapshot.fileRef(), snapshot.contentSha256(),
                content.source().sourceId(), content.version().version()) != 1) {
            throw conflict();
        }
        newRun(require(accountId, requestId), null);
        return view(require(accountId, requestId));
    }

    @Override
    public Request cancel(long accountId, String questionId, String requestId, String expectedRunId) {
        get(accountId, questionId, requestId);
        if (expectedRunId != null) {
            uuid(expectedRunId);
        }
        RequestRow row = require(accountId, requestId);
        if (!Objects.equals(expectedRunId, row.currentRunId())) {
            throw conflict();
        }
        if (TERMINAL.contains(row.status()) || "CANCELLING".equals(row.status())) {
            return view(row);
        }
        if (expectedRunId == null) {
            if (!"WAITING_INPUT".equals(row.status())) {
                throw conflict();
            }
            db.project(accountId, requestId, null, "CANCELLED");
        } else {
            RunRow run = db.run(accountId, expectedRunId);
            transition(run, "CANCELLING", null);
            db.fence(run.runId());
            db.closeSteps(run.runId());
            event(accountId, "RUN", run.runId(), run.revision() + 1, "RUN_CANCEL");
        }
        return view(require(accountId, requestId));
    }

    @Override
    public Request retry(long accountId, String questionId, String requestId, String key, String targetRunId) {
        rollout.require("V2");
        get(accountId, questionId, requestId);
        uuid(key);
        uuid(targetRunId);
        RetryRow previous = db.retry(accountId, key);
        if (previous != null) {
            if (!previous.requestId().equals(requestId) || !previous.targetRunId().equals(targetRunId)) {
                throw conflict();
            }
            RequestRow row = require(accountId, requestId);
            if (!previous.runId().equals(row.currentRunId())) {
                throw conflict();
            }
            return view(row);
        }
        RequestRow row = require(accountId, requestId);
        if (!targetRunId.equals(row.currentRunId()) || !("FAILED".equals(row.status()) || "TIMED_OUT".equals(row.status()))) {
            throw conflict();
        }
        source(row);
        vacant(accountId);
        String run = newRun(row, targetRunId);
        db.insertRetry(new RetryRow(accountId, key, requestId, targetRunId, run));
        return view(require(accountId, requestId));
    }

    @Override
    public Execution execution(long accountId, String runId) {
        uuid(runId);
        lock(accountId);
        expire(accountId, Instant.now());
        RunRow run = requireRun(accountId, runId);
        RequestRow request = require(accountId, run.requestId());
        return execution(request, run);
    }

    @Override
    public Optional<Execution> start(long accountId, String runId, long expectedRevision) {
        execution(accountId, runId);
        RunRow run = requireRun(accountId, runId);
        if (!current(run) || run.revision() != expectedRevision || !"QUEUED".equals(run.status())) {
            return Optional.empty();
        }
        source(require(accountId, run.requestId()));
        transition(run, "RUNNING", null);
        return Optional.of(execution(require(accountId, run.requestId()), requireRun(accountId, runId)));
    }

    @Override
    public Attempt beginAttempt(long accountId, String runId, Role role, int sequence, String inputDigest) {
        execution(accountId, runId);
        digest(inputDigest);
        if (role == null || sequence < 1 || sequence > 8) {
            throw invalid();
        }
        RunRow run = requireRun(accountId, runId);
        if (!current(run)) {
            throw conflict();
        }
        StepRow step = db.step(runId, role.name(), sequence);
        if (step != null) {
            if (!step.inputDigest().equals(inputDigest)) {
                throw conflict();
            }
            Attempt attempt = db.latestAttempt(step.stepId());
            if ("CALL_INTENT".equals(attempt.providerCallState()) && "RUNNING".equals(run.status())) {
                unknown(accountId, runId, attempt.attemptId(), attempt.fence());
                return db.latestAttempt(step.stepId());
            }
            return attempt;
        }
        if (!"RUNNING".equals(run.status())) {
            throw conflict();
        }
        step = new StepRow(id(), runId, role.name(), sequence, "RUNNING", 0, inputDigest, null);
        db.insertStep(step);
        Attempt attempt = new Attempt(id(), step.stepId(), 1, "RUNNING", "NOT_SENT", 1, null, null, null, null);
        db.insertAttempt(attempt);
        return db.latestAttempt(step.stepId());
    }

    @Override
    public boolean callIntent(long accountId, String runId, String attemptId, long fence) {
        execution(accountId, runId);
        RunRow run = requireRun(accountId, runId);
        Attempt attempt = db.attempt(runId, attemptId);
        if (!writable(run) || attempt == null || attempt.fence() != fence || !"RUNNING".equals(attempt.status())
                || !"NOT_SENT".equals(attempt.providerCallState())) {
            return false;
        }
        if (run.modelCalls() >= 8) {
            transition(run, "FAILED", "BUDGET_EXCEEDED");
            db.fence(runId);
            db.closeSteps(runId);
            return false;
        }
        db.lockCallBudget();
        if (db.inFlight(accountId) >= 1 || db.inFlight(null) >= 8) {
            return false;
        }
        if (db.intent(attemptId, fence) != 1 || db.incrementCalls(runId) != 1) {
            throw conflict();
        }
        return true;
    }

    @Override
    public boolean finishProviderCall(long accountId, String runId, String attemptId) {
        lock(accountId);
        requireRun(accountId, runId);
        if (db.attempt(runId, attemptId) == null) {
            return false;
        }
        return db.finishProvider(attemptId) == 1;
    }

    @Override
    @Transactional(readOnly = true)
    public int providerCallsInFlight(Long accountId) {
        return db.inFlight(accountId);
    }

    @Override
    public boolean confirm(long accountId, String runId, String attemptId, long fence, String output, String outputDigest) {
        execution(accountId, runId);
        digest(outputDigest);
        if (output == null || output.length() > 262144 || !sha(output).equals(outputDigest)) {
            throw invalid();
        }
        RunRow run = requireRun(accountId, runId);
        Attempt attempt = db.attempt(runId, attemptId);
        if (!writable(run) || attempt == null || attempt.fence() != fence) {
            return false;
        }
        if ("CONFIRMED".equals(attempt.providerCallState())) {
            return outputDigest.equals(attempt.outputDigest());
        }
        boolean retrieval = db.steps(runId).stream().anyMatch(step -> step.stepId().equals(attempt.stepId())
                && "RETRIEVE".equals(step.role()));
        if (!("CALL_INTENT".equals(attempt.providerCallState()) || retrieval)
                || db.endAttempt(attemptId, fence, "SUCCEEDED", "CONFIRMED", output, outputDigest) != 1) {
            return false;
        }
        db.finishProvider(attemptId);
        db.stepStatus(attempt.stepId(), "SUCCEEDED", outputDigest);
        return true;
    }

    @Override
    public boolean unknown(long accountId, String runId, String attemptId, long fence) {
        execution(accountId, runId);
        RunRow run = requireRun(accountId, runId);
        Attempt attempt = db.attempt(runId, attemptId);
        if (!writable(run) || attempt == null || attempt.fence() != fence
                || !"CALL_INTENT".equals(attempt.providerCallState())) {
            return false;
        }
        if (db.endAttempt(attemptId, fence, "UNKNOWN", "UNKNOWN", null, null) != 1) {
            return false;
        }
        db.stepStatus(attempt.stepId(), "UNKNOWN", null);
        transition(run, "FAILED", "OUTCOME_UNKNOWN");
        db.fence(runId);
        db.closeSteps(runId);
        return true;
    }

    @Override
    public boolean fail(long accountId, String runId, long expectedRevision, String reason) {
        execution(accountId, runId);
        if (!REASONS.contains(reason)) {
            throw invalid();
        }
        RunRow run = requireRun(accountId, runId);
        if (!current(run) || TERMINAL.contains(run.status()) || "CANCELLING".equals(run.status())
                || expectedRevision != run.revision()) {
            return false;
        }
        transition(run, "FAILED", reason);
        db.fence(runId);
        db.closeSteps(runId);
        return true;
    }

    @Override
    public boolean publish(long accountId, String runId, long expectedRevision, ResultDraft draft) {
        execution(accountId, runId);
        RunRow run = requireRun(accountId, runId);
        if (!writable(run) || run.revision() != expectedRevision) {
            return false;
        }
        if (draft == null || draft.bodyMarkdown() == null || draft.bodyMarkdown().isBlank()
                || draft.bodyMarkdown().length() > 65536 || draft.evidenceIds() == null
                || draft.evidenceIds().size() > MAX_PUBLISHED_CITATIONS
                || draft.evidenceIds().stream().anyMatch(Objects::isNull)
                || draft.evidenceIds().stream().distinct().count() != draft.evidenceIds().size()) {
            throw invalid();
        }
        RequestRow request = require(accountId, run.requestId());
        source(request);
        List<Evidence> allowed = db.evidence(runId);
        List<Citation> citations = new ArrayList<>();
        Map<String, String> markerIds = new LinkedHashMap<>();
        for (String evidenceId : draft.evidenceIds()) {
            uuid(evidenceId);
            Evidence evidence = allowed.stream().filter(item -> item.evidenceId().equals(evidenceId)).findFirst().orElseThrow(PostgresTutorLedger::conflict);
            if (evidence.sourceId() == null || evidence.version() == null || evidence.anchorId() == null) {
                throw conflict();
            }
            SourceContent content = referencedEvidence(accountId, request, evidence);
            String citationId = id();
            markerIds.put(evidenceId, citationId);
            citations.add(new Citation(citationId, evidence.sourceId(), evidence.version(), evidence.anchorId(),
                    Integer.toString(citations.size() + 1), content.source().type()));
        }
        String body;
        try {
            body = CitationMarkers.resolve(draft.bodyMarkdown(), markerIds);
        } catch (IllegalArgumentException exception) {
            throw conflict();
        }
        String result = id();
        db.insertResult(new ResultRow(result, runId, require(accountId, run.requestId()).mode(), "VERIFIED", body, null));
        for (Citation citation : citations) {
            db.insertCitation(result, citation);
        }
        sources.publish(accountId, new PublishSource(SourceType.TUTOR_REPLY, result, "历史辅导建议", body,
                sha(body), null, null, null, "result:" + result));
        transition(run, "SUCCEEDED", null);
        event(accountId, "RESULT", result, 1, "RESULT_PUBLISHED");
        return true;
    }

    @Override
    public void recordEvidence(long accountId, String runId, List<Evidence> evidence) {
        execution(accountId, runId);
        RunRow run = requireRun(accountId, runId);
        if (!writable(run) || evidence == null || evidence.size() > 50) {
            throw conflict();
        }
        List<StepRow> steps = db.steps(runId);
        RequestRow request = require(accountId, run.requestId());
        for (Evidence item : evidence) {
            if (item == null) {
                throw invalid();
            }
            uuid(item.evidenceId());
            if (!runId.equals(item.runId()) || steps.stream().noneMatch(step -> step.stepId().equals(item.stepId()))
                    || item.claim() == null || item.claim().length() > 16000) {
                throw invalid();
            }
            if (item.sourceId() != null) {
                if (item.version() == null || item.anchorId() == null) {
                    throw invalid();
                }
                referencedEvidence(accountId, request, item);
            } else if (!"DERIVATION".equals(item.type()) || item.version() != null || item.anchorId() != null) {
                throw invalid();
            }
            Evidence existing = db.evidence(runId).stream().filter(value -> value.evidenceId().equals(item.evidenceId())).findFirst().orElse(null);
            if (existing == null) {
                db.insertEvidence(item);
            } else if (!existing.equals(item)) {
                throw conflict();
            }
        }
    }

    @Override
    public List<Evidence> evidence(long accountId, String runId) {
        execution(accountId, runId);
        return db.evidence(runId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OutboxEvent> outstandingEvents(int limit) {
        if (limit < 1 || limit > 1000) {
            throw invalid();
        }
        return db.outstanding(limit);
    }

    @Override
    @Transactional(readOnly = true)
    public List<OutboxEvent> outstandingEvents(Set<String> kinds, int limit) {
        if (kinds == null || kinds.isEmpty() || kinds.size() > 20 || limit < 1 || limit > 1000
                || kinds.stream().anyMatch(kind -> kind == null || !kind.matches("[A-Z_]{1,32}"))) {
            throw invalid();
        }
        return db.outstandingKinds(kinds, limit);
    }

    @Override
    public boolean acknowledgeEvent(String eventId) {
        uuid(eventId);
        return db.acknowledge(eventId) == 1;
    }

    @Override
    public int reconcileDeadlines(Instant now, int limit) {
        if (now == null || limit < 1 || limit > 1000) {
            throw invalid();
        }
        int changed = 0;
        for (long account : db.expiredAccounts(now, now.minusSeconds(30), limit)) {
            lock(account);
            changed += expire(account, now);
        }
        return changed;
    }

    private int expire(long account, Instant now) {
        int changed = 0;
        for (RequestRow request : db.active(account)) {
            if (request.currentRunId() == null) {
                if (!request.inputDeadlineAt().isAfter(now)) {
                    db.project(account, request.requestId(), null, "TIMED_OUT");
                    changed++;
                }
                continue;
            }
            RunRow run = db.run(account, request.currentRunId());
            String status = null;
            String reason = null;
            List<Attempt> expiredCalls = "RUNNING".equals(run.status()) ? db.expiredIntents(run.runId(), now) : List.of();
            if (!expiredCalls.isEmpty()) {
                for (Attempt attempt : expiredCalls) {
                    db.endAttempt(attempt.attemptId(), attempt.fence(), "UNKNOWN", "UNKNOWN", null, null);
                    db.stepStatus(attempt.stepId(), "UNKNOWN", null);
                }
                transition(run, "FAILED", "OUTCOME_UNKNOWN");
                db.fence(run.runId());
                db.closeSteps(run.runId());
                changed++;
                continue;
            }
            if ("QUEUED".equals(run.status()) && !run.queueDeadlineAt().isAfter(now)) {
                status = "TIMED_OUT";
                reason = "QUEUE_TIMEOUT";
            } else if ("RUNNING".equals(run.status()) && !run.runDeadlineAt().isAfter(now)) {
                boolean unknown = db.attempts(run.runId()).stream().anyMatch(attempt -> "CALL_INTENT".equals(attempt.providerCallState()));
                status = unknown ? "FAILED" : "TIMED_OUT";
                reason = unknown ? "OUTCOME_UNKNOWN" : "RUN_TIMEOUT";
                if (unknown) {
                    for (Attempt attempt : db.attempts(run.runId())) {
                        if ("CALL_INTENT".equals(attempt.providerCallState()) && "RUNNING".equals(attempt.status())) {
                            db.endAttempt(attempt.attemptId(), attempt.fence(), "UNKNOWN", "UNKNOWN", null, null);
                            db.stepStatus(attempt.stepId(), "UNKNOWN", null);
                        }
                    }
                }
            } else if ("CANCELLING".equals(run.status()) && !run.cancelRequestedAt().plusSeconds(30).isAfter(now)) {
                status = "CANCELLED";
            }
            if (status != null) {
                transition(run, status, reason);
                db.fence(run.runId());
                db.closeSteps(run.runId());
                changed++;
            }
        }
        return changed;
    }

    private String newRun(RequestRow request, String previous) {
        String runId = id();
        int number = db.runs(request.accountId(), request.requestId()).size() + 1;
        Instant now = Instant.now();
        db.insertRun(new RunRow(runId, request.requestId(), request.accountId(), number, previous, "QUEUED", 0,
                null, null, now.plusSeconds(120), now.plusSeconds(600), 0, null, null));
        db.project(request.accountId(), request.requestId(), runId, "QUEUED");
        event(request.accountId(), "RUN", runId, 1, "RUN_START");
        return runId;
    }

    private void transition(RunRow run, String status, String reason) {
        if (db.transition(run.accountId(), run.runId(), run.revision(), status, reason) != 1) {
            throw conflict();
        }
        db.project(run.accountId(), run.requestId(), run.runId(), status);
    }

    private void event(long account, String type, String aggregate, long version, String kind) {
        db.outbox(new OutboxEvent(id(), account, type, aggregate, version, kind, sha(aggregate + ":" + version + ":" + kind), "PENDING"));
    }

    private Result result(String runId) {
        ResultRow row = db.result(runId);
        return row == null ? null : new Result(row.resultId(), row.runId(), Mode.valueOf(row.mode()), row.status(),
                row.bodyMarkdown(), db.citations(row.resultId()), row.createdAt());
    }

    private Run runView(RunRow row) {
        List<Step> steps = db.steps(row.runId()).stream().map(step -> new Step(step.stepId(), Role.valueOf(step.role()), step.status(), step.revision())).toList();
        return new Run(row.runId(), row.requestId(), row.runNo(), row.retryOfRunId(), Status.valueOf(row.status()),
                row.revision(), row.reason(), row.cancelRequestedAt(), steps, db.attempts(row.runId()));
    }

    private static Execution execution(RequestRow request, RunRow run) {
        return new Execution(request.accountId(), request.requestId(), Long.toString(request.questionId()), run.runId(),
                Mode.valueOf(request.mode()), request.prompt(), request.snapshotFileRef(), request.snapshotSourceId(),
                request.snapshotVersion(), Status.valueOf(run.status()), run.revision(), run.queueDeadlineAt(),
                run.runDeadlineAt(), run.cancelRequestedAt(), run.modelCalls());
    }

    private static Request view(RequestRow row) {
        return new Request(row.requestId(), Long.toString(row.questionId()), row.prompt(), Mode.valueOf(row.mode()),
                new SnapshotInput(row.snapshotKind(), row.sourceRequestId()), row.snapshotOriginRequestId(),
                row.snapshotFileRef(), row.currentRunId(), Status.valueOf(row.status()), row.revision(), row.createdAt(), row.updatedAt());
    }

    /** 有来源的证据只引用已保存的锚点原文；推断必须使用不绑定来源的DERIVATION。 */
    private SourceContent referencedEvidence(long accountId, RequestRow request, Evidence evidence) {
        SourceContent content = sources.get(accountId, evidence.sourceId(), evidence.version());
        Anchor anchor = content.anchors().stream().filter(item -> item.anchorId().equals(evidence.anchorId()))
                .findFirst().orElseThrow(PostgresTutorLedger::conflict);
        if (!Objects.equals(anchor.text(), evidence.claim())) {
            throw conflict();
        }
        boolean snapshot = content.source().type() == SourceType.TUTOR_SNAPSHOT;
        if ("HISTORY".equals(evidence.type())) {
            if (snapshot) {
                throw conflict();
            }
        } else if ("CURRENT_SNAPSHOT".equals(evidence.type())) {
            if (!snapshot || !Objects.equals(request.snapshotSourceId(), evidence.sourceId())
                    || !Objects.equals(request.snapshotVersion(), evidence.version())) {
                throw conflict();
            }
        } else {
            throw conflict();
        }
        return content;
    }

    private void source(RequestRow row) {
        if (row.snapshotSourceId() == null || row.snapshotVersion() == null) {
            throw conflict();
        }
        SourceContent content = sources.get(row.accountId(), row.snapshotSourceId(), row.snapshotVersion());
        if (!Objects.equals(content.version().fileRef(), row.snapshotFileRef())
                || !content.version().contentSha256().equals(row.snapshotSha256())) {
            throw conflict();
        }
    }

    private boolean current(RunRow run) {
        return run.runId().equals(require(run.accountId(), run.requestId()).currentRunId());
    }

    private boolean writable(RunRow run) {
        return current(run) && "RUNNING".equals(run.status()) && run.cancelRequestedAt() == null;
    }

    private RunRow requireRun(long account, String id) {
        RunRow run = db.run(account, id);
        if (run == null) {
            throw missing();
        }
        return run;
    }

    private RequestRow require(long account, String id) {
        RequestRow request = db.request(account, id);
        if (request == null) {
            throw missing();
        }
        return request;
    }

    private long question(long account, String question) {
        if (question == null || !question.matches("[1-9][0-9]{0,18}")) {
            throw invalid();
        }
        try {
            long id = Long.parseLong(question);
            questions.findById(account, id).orElseThrow(PostgresTutorLedger::missing);
            return id;
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    private void lock(long account) {
        if (db.lock(account) == null) {
            throw missing();
        }
    }

    private void vacant(long account) {
        if (!db.active(account).isEmpty() || db.legacyActive(account)) {
            throw conflict();
        }
    }

    private static void uuid(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw invalid();
        }
    }

    private static void digest(String value) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw invalid();
        }
    }

    private static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String id() { return UUID.randomUUID().toString(); }
    private static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_PARAM); }
    private static BusinessException missing() { return new BusinessException(ErrorCode.NOT_FOUND); }
    private static BusinessException conflict() { return new BusinessException(ErrorCode.TUTOR_TURN_CONFLICT); }
}
