package com.betterlearning.server.learning.service.impl;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.mapper.FileRetentionMapper;
import com.betterlearning.server.learning.validation.AnswerManifestValidator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable file tombstone and reference barrier; no directory scanning. */
@Service
public class FileRetentionService {
    public enum Outcome {
        DELETED, CURRENT, V1, SNAPSHOT, ACTIVE_VERSION, UNPURGED_VERSION, UNKNOWN, PENDING_IO, FILE_IO
    }

    public record KnownFileAudit(int checked, int missing) {
    }

    private final FileRetentionMapper mapper;
    private final AnswerFileStore files;
    private final TransactionTemplate transactions;

    public FileRetentionService(FileRetentionMapper mapper, AnswerFileStore files,
            PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.files = files;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Failed or duplicate upload candidates lack proven provenance and stay on a durable hold. */
    public Outcome requestCandidateCleanup(String fileRef) {
        validate(fileRef);
        files.holdUnknown(fileRef);
        transactions.executeWithoutResult(status -> mapper.holdUnknown(fileRef));
        return Outcome.UNKNOWN;
    }

    /** Read-only operational status; DELETED means physical deletion is recorded. */
    public Outcome status(String fileRef) {
        validate(fileRef);
        String state = mapper.state(fileRef);
        String reason = mapper.ledgerReason(fileRef);
        if ("DELETED".equals(state)) {
            if (Boolean.TRUE.equals(mapper.physicallyDeleted(fileRef))) {
                return Outcome.DELETED;
            }
            if ("UNKNOWN".equals(reason) || files.hasUnknownHold(fileRef)) {
                return Outcome.UNKNOWN;
            }
            return "FILE_IO".equals(reason) ? Outcome.FILE_IO : Outcome.PENDING_IO;
        }
        if (reason != null) {
            return Outcome.valueOf(reason);
        }
        String liveReason = mapper.holdReason(fileRef);
        return liveReason == null ? Outcome.UNKNOWN : Outcome.valueOf(liveReason);
    }

    /** Bounded retry list for purged or withdrawn sources; UNKNOWN candidates are excluded. */
    public List<String> pendingFileRefs(int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        return mapper.pendingFileRefs(limit);
    }

    /** Counts only; never exposes a file ref or path to monitoring callers. */
    public Map<String, Long> groupedCounts() {
        Map<String, Long> counts = new TreeMap<>();
        for (Map<String, Object> row : mapper.groupedCounts()) {
            counts.put((String) row.get("outcome"), ((Number) row.get("total")).longValue());
        }
        return Map.copyOf(counts);
    }

    /** Bounded read-only audit of refs already present in business tables. */
    public KnownFileAudit knownFileAudit(int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be between 1 and 1000");
        }
        List<String> refs = mapper.knownFileRefsForAudit(limit);
        int missing = 0;
        for (String ref : refs) {
            try {
                if (!AnswerManifestValidator.uuid(ref) || !files.existsPublished(ref)) {
                    missing++;
                }
            } catch (RuntimeException exception) {
                missing++;
            }
        }
        return new KnownFileAudit(refs.size(), missing);
    }

    /** Called only for known source-version file refs after the version has been purged. */
    public Outcome collect(String fileRef) {
        validate(fileRef);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("文件回收必须在外层事务提交后执行");
        }
        Outcome decision = transactions.execute(status -> {
            mapper.register(fileRef);
            String state = mapper.lockState(fileRef);
            if ("DELETED".equals(state)) {
                return Outcome.DELETED;
            }
            if ("HOLD".equals(state) && "UNKNOWN".equals(holdReasonFromLedger(fileRef))) {
                return Outcome.UNKNOWN;
            }
            if (files.hasUnknownHold(fileRef)) {
                mapper.hold(fileRef, "UNKNOWN");
                return Outcome.UNKNOWN;
            }
            String reason = mapper.holdReason(fileRef);
            if (reason == null && !mapper.knownSourceFile(fileRef)) {
                reason = "UNKNOWN";
            }
            if (reason != null) {
                mapper.hold(fileRef, reason);
                return Outcome.valueOf(reason);
            }
            mapper.tombstone(fileRef);
            return Outcome.DELETED;
        });
        // A failed/unknown commit never reaches filesystem I/O.
        if (decision != Outcome.DELETED) {
            return decision;
        }
        if (files.hasUnknownHold(fileRef)) {
            transactions.executeWithoutResult(status -> mapper.deletedUnknownHold(fileRef));
            return Outcome.UNKNOWN;
        }
        if (Boolean.TRUE.equals(mapper.physicallyDeleted(fileRef))) {
            return Outcome.DELETED;
        }
        try {
            files.deletePublished(fileRef);
        } catch (IllegalStateException exception) {
            if (files.hasUnknownHold(fileRef)) {
                transactions.executeWithoutResult(status -> mapper.deletedUnknownHold(fileRef));
                return Outcome.UNKNOWN;
            }
            throw exception;
        } catch (BusinessException exception) {
            try {
                transactions.executeWithoutResult(status -> mapper.fileIoFailed(fileRef));
            } catch (RuntimeException ledgerFailure) {
                exception.addSuppressed(ledgerFailure);
            }
            throw exception;
        }
        transactions.executeWithoutResult(status -> mapper.markPhysicallyDeleted(fileRef));
        return Outcome.DELETED;
    }

    private String holdReasonFromLedger(String fileRef) {
        return mapper.ledgerReason(fileRef);
    }

    private static void validate(String fileRef) {
        if (!AnswerManifestValidator.uuid(fileRef)) {
            throw new IllegalArgumentException("invalid file ref");
        }
    }
}
