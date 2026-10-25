package com.betterlearning.server.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.dao.LearningDao;
import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.learning.model.dataobject.QuestionDO;
import com.betterlearning.server.learning.service.impl.AnswerFileStore;
import com.betterlearning.server.learning.validation.AnswerManifestValidator;
import com.betterlearning.server.multiagent.MultiAgentModels;
import com.betterlearning.server.multiagent.SnapshotPort;
import com.betterlearning.server.multiagent.SourceStore;
import com.betterlearning.server.multiagent.TutorLedger;

import jakarta.servlet.http.Part;

import org.springframework.stereotype.Service;

/** V2 HTTP 业务适配；文件完整原子落盘之后才调用账本绑定事务。 */
@Service
public class TutorRequestServiceImpl implements TutorRequestService, SnapshotPort {
    private final TutorLedger ledger;
    private final SourceStore sources;
    private final LearningDao questions;
    private final AnswerFileStore files;

    public TutorRequestServiceImpl(TutorLedger ledger, SourceStore sources, LearningDao questions, AnswerFileStore files) {
        this.ledger = ledger;
        this.sources = sources;
        this.questions = questions;
        this.files = files;
    }

    @Override
    public TutorRequestModels.Request create(long accountId, String questionId, String idempotencyKey,
            TutorRequestModels.CreateRequest input) {
        if (input == null || !AnswerManifestValidator.uuid(idempotencyKey)
                || !idempotencyKey.equals(input.requestId()) || input.prompt() == null
                || input.prompt().isBlank() || input.prompt().length() > 4000) {
            throw invalid();
        }
        MultiAgentModels.Mode mode = mode(input.mode());
        MultiAgentModels.SnapshotInput snapshot = snapshot(input.snapshotInput());
        MultiAgentModels.Request existing = null;
        try {
            existing = ledger.getByRequestId(accountId, input.requestId());
        } catch (BusinessException exception) {
            if (exception.getErrorCode() != ErrorCode.NOT_FOUND) {
                throw exception;
            }
        }
        if ("REUSE_REQUEST".equals(snapshot.kind())) {
            if (existing == null) {
                MultiAgentModels.Request source = ledger.get(accountId, questionId, snapshot.sourceRequestId());
                if (source.snapshotFileRef() == null) {
                    throw conflict();
                }
                verifyFile(source.snapshotFileRef());
            }
        }
        MultiAgentModels.CreateRequest command = new MultiAgentModels.CreateRequest(input.requestId(),
                input.prompt(), mode, snapshot);
        return request(ledger.create(accountId, questionId, idempotencyKey, command));
    }

    @Override
    public TutorRequestModels.Page list(long accountId, String questionId, String cursor, Integer limit) {
        int size = limit == null ? 20 : limit;
        if (size < 1 || size > 100) {
            throw invalid();
        }
        MultiAgentModels.RequestPage page = ledger.list(accountId, questionId, size, cursor);
        return new TutorRequestModels.Page(page.items().stream().map(TutorRequestServiceImpl::request).toList(),
                page.nextCursor());
    }

    @Override
    public TutorRequestModels.Pending pending(long accountId, String questionId) {
        MultiAgentModels.Request result = ledger.pending(accountId, questionId).request();
        return new TutorRequestModels.Pending(result == null ? null : request(result));
    }

    @Override
    public TutorRequestModels.Detail detail(long accountId, String questionId, String requestId) {
        return detail(ledger.detail(accountId, questionId, requestId));
    }

    @Override
    public TutorRequestModels.Detail upload(long accountId, String questionId, String requestId,
            AnswerModels.SaveManifest manifest, Collection<Part> parts) {
        MultiAgentModels.Request request = ledger.get(accountId, questionId, requestId);
        if (!"NEW_CAPTURE".equals(request.snapshotInput().kind()) || manifest == null
                || !requestId.equals(manifest.saveRequestId()) || manifest.baseFileRef() != null) {
            throw invalid();
        }
        if (request.snapshotFileRef() == null && request.status() != MultiAgentModels.Status.WAITING_INPUT) {
            throw conflict();
        }
        QuestionDO question = questions.findById(accountId, Long.parseLong(questionId))
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
        AnswerManifestValidator.validate(manifest, question.getBodyMarkdown());
        AnswerFileStore.Candidate candidate = files.save(manifest, new AnswerModels.QuestionSnapshot(
                questionId, question.getSubject(), question.getBodyMarkdown()), parts);
        try {
            MultiAgentModels.Request bound = ledger.bindSnapshot(accountId, questionId, requestId,
                    new MultiAgentModels.SnapshotBinding(candidate.fileRef(), candidate.requestHash(), null, 0));
            if (!candidate.fileRef().equals(bound.snapshotFileRef())) {
                files.discard(candidate);
            }
            return detail(accountId, questionId, requestId);
        } catch (RuntimeException exception) {
            // 数据库提交结果未知时先读回引用；无法确认则保留候选，避免误删有效快照。
            try {
                MultiAgentModels.Request current = ledger.get(accountId, questionId, requestId);
                if (!candidate.fileRef().equals(current.snapshotFileRef())) {
                    files.discard(candidate);
                }
            } catch (RuntimeException lookupFailure) {
                // 保留可能已提交的文件，留给文件引用对账处理。
            }
            throw exception;
        }
    }

    @Override
    public TutorRequestModels.Detail cancel(long accountId, String questionId, String requestId,
            TutorRequestModels.CancelRequest input) {
        if (input == null || (input.expectedRunId() != null
                && !AnswerManifestValidator.uuid(input.expectedRunId()))) {
            throw invalid();
        }
        ledger.cancel(accountId, questionId, requestId, input.expectedRunId());
        return detail(accountId, questionId, requestId);
    }

    @Override
    public TutorRequestModels.Detail retry(long accountId, String questionId, String requestId,
            String idempotencyKey, TutorRequestModels.RetryRequest input) {
        if (!AnswerManifestValidator.uuid(idempotencyKey) || input == null
                || !AnswerManifestValidator.uuid(input.targetRunId())) {
            throw invalid();
        }
        MultiAgentModels.Request previous = ledger.get(accountId, questionId, requestId);
        if (previous.snapshotFileRef() == null) {
            throw conflict();
        }
        verifyFile(previous.snapshotFileRef());
        ledger.retry(accountId, questionId, requestId, idempotencyKey, input.targetRunId());
        return detail(accountId, questionId, requestId);
    }

    @Override
    public MultiAgentModels.Snapshot load(long accountId, String requestId) {
        MultiAgentModels.Request request = ledger.getByRequestId(accountId, requestId);
        if (request.snapshotFileRef() == null || request.snapshotOriginRequestId() == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        MultiAgentModels.Execution execution = ledger.execution(accountId, request.currentRunId());
        MultiAgentModels.SourceContent current = sources.get(accountId, execution.snapshotSourceId(),
                execution.snapshotVersion());
        if (!request.snapshotFileRef().equals(current.version().fileRef())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        AnswerModels.StoredManifest stored = files.manifest(request.snapshotFileRef());
        List<MultiAgentModels.Image> images = new ArrayList<>();
        AnswerModels.Canvas canvas = stored.source().canvas();
        for (AnswerModels.Block block : stored.source().blocks()) {
            double worldX = canvas.originX() + block.x();
            double worldY = canvas.originY() + block.y();
            String position = "index=" + block.index() + ", worldX=" + worldX + ", worldY=" + worldY
                    + ", width=" + block.width() + ", height=" + block.height()
                    + ", pixelWidth=" + block.pixelWidth() + ", pixelHeight=" + block.pixelHeight();
            images.add(new MultiAgentModels.Image(files.block(request.snapshotFileRef(), block.partName()),
                    "image/png", position));
        }
        sources.get(accountId, execution.snapshotSourceId(), execution.snapshotVersion());
        List<String> strokeIds = stored.source().document().strokes().stream()
                .map(AnswerModels.Stroke::strokeId).toList();
        return new MultiAgentModels.Snapshot(request.snapshotFileRef(),
                stored.questionSnapshot().bodyMarkdown(), List.copyOf(images), strokeIds,
                !strokeIds.isEmpty(), null, null);
    }

    private static MultiAgentModels.Mode mode(String value) {
        if (value == null || "HINT".equals(value)) {
            return MultiAgentModels.Mode.HINT;
        }
        if ("FULL_SOLUTION".equals(value)) {
            return MultiAgentModels.Mode.FULL_SOLUTION;
        }
        throw invalid();
    }

    private void verifyFile(String fileRef) {
        AnswerModels.StoredManifest stored = files.manifest(fileRef);
        AnswerManifestValidator.validate(stored.source(), stored.questionSnapshot().bodyMarkdown());
        List<AnswerModels.Block> blocks = new ArrayList<>(stored.source().blocks());
        blocks.addAll(stored.source().document().questionLayout().rasterBlocks());
        for (AnswerModels.Block block : blocks) {
            byte[] bytes = files.block(fileRef, block.partName());
            if (!block.sha256().equals(AnswerManifestValidator.sha256(bytes))) {
                throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
            }
        }
    }

    private static MultiAgentModels.SnapshotInput snapshot(TutorRequestModels.SnapshotInput value) {
        if (value == null || ("NEW_CAPTURE".equals(value.kind()) && value.sourceRequestId() == null)) {
            return new MultiAgentModels.SnapshotInput("NEW_CAPTURE", null);
        }
        if ("REUSE_REQUEST".equals(value.kind()) && AnswerManifestValidator.uuid(value.sourceRequestId())) {
            return new MultiAgentModels.SnapshotInput("REUSE_REQUEST", value.sourceRequestId());
        }
        throw invalid();
    }

    private static TutorRequestModels.Request request(MultiAgentModels.Request source) {
        MultiAgentModels.SnapshotInput input = source.snapshotInput();
        return new TutorRequestModels.Request(source.requestId(), source.questionId(), source.prompt(),
                source.mode().name(), new TutorRequestModels.SnapshotInput(input.kind(), input.sourceRequestId()),
                source.snapshotOriginRequestId(), source.snapshotFileRef(), source.currentRunId(),
                source.status().name(), source.revision(), source.createdAt(), source.updatedAt());
    }

    private static TutorRequestModels.Detail detail(MultiAgentModels.RequestDetail source) {
        List<TutorRequestModels.Run> runs = new ArrayList<>();
        for (MultiAgentModels.Run run : source.runs()) {
            runs.add(run(run));
        }
        TutorRequestModels.Run current = runs.stream()
                .filter(run -> Objects.equals(run.runId(), source.request().currentRunId())).findFirst().orElse(null);
        MultiAgentModels.Result saved = source.result();
        TutorRequestModels.Result result = saved == null ? null
                : new TutorRequestModels.Result(saved.resultId(), saved.mode().name(), saved.status(),
                saved.bodyMarkdown(), new ArrayList<>(saved.citations()), List.of(), saved.createdAt());
        return new TutorRequestModels.Detail(request(source.request()), current, List.copyOf(runs), result);
    }

    private static TutorRequestModels.Run run(MultiAgentModels.Run source) {
        List<TutorRequestModels.Step> steps = source.steps().stream()
                .map(step -> new TutorRequestModels.Step(step.stepId(), step.role().name(),
                step.status(), step.revision())).toList();
        List<TutorRequestModels.Attempt> attempts = source.attempts().stream()
                .map(attempt -> new TutorRequestModels.Attempt(attempt.attemptId(), attempt.stepId(),
                attempt.attemptNo(), attempt.status(), attempt.providerCallState(), attempt.fence(),
                attempt.startedAt(), attempt.endedAt())).toList();
        return new TutorRequestModels.Run(source.runId(), source.requestId(), source.runNo(),
                source.retryOfRunId(), source.status().name(), source.reason(), source.revision(),
                source.cancelRequestedAt(), steps, attempts);
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_PARAM);
    }

    private static BusinessException conflict() {
        return new BusinessException(ErrorCode.TUTOR_TURN_CONFLICT);
    }
}
