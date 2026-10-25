package com.betterlearning.server.learning.operations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.function.Supplier;

import com.betterlearning.server.learning.mapper.LegacyGenerationRecoveryMapper;
import com.betterlearning.server.learning.validation.AnswerManifestValidator;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit operator action, intentionally not a Spring service or public HTTP endpoint. */
public final class LegacyGenerationRecoveryService {
    public static final String UNKNOWN = "GENERATION_OUTCOME_UNKNOWN";
    public static final Duration QUIET_PERIOD = Duration.ofMinutes(10);
    private final LegacyGenerationRecoveryMapper mapper;
    private final TransactionTemplate transactions;
    private final Supplier<Instant> databaseClock;

    public record Target(long accountId, long questionId, String turnId) { }
    public record Observation(Target target, String mode, String status, String fileRef,
            Instant updatedAt, Instant observedAt, Instant eligibleAt) { }
    public record Command(Target target, String expectedFileRef, Instant expectedUpdatedAt, String operationId,
            String operator, String incident, boolean executorsStopped, boolean acceptUnknownOutcome) { }
    public record Result(String operationId, String turnId, String status, boolean alreadyApplied) { }

    public LegacyGenerationRecoveryService(LegacyGenerationRecoveryMapper mapper, TransactionTemplate transactions) {
        this(mapper, transactions, mapper::databaseNow);
    }

    // Test-only clock seam; the public CLI always reads the database clock while holding the transaction locks.
    LegacyGenerationRecoveryService(LegacyGenerationRecoveryMapper mapper, TransactionTemplate transactions,
            Supplier<Instant> databaseClock) {
        this.mapper = Objects.requireNonNull(mapper);
        this.transactions = Objects.requireNonNull(transactions);
        this.databaseClock = Objects.requireNonNull(databaseClock);
    }

    public Observation inspect(Target target) {
        validate(target);
        return transactions.execute(status -> {
            String mode = mapper.sharedMode();
            LegacyGenerationRecoveryMapper.State row = state(target);
            return new Observation(target, mode, row.status(), row.fileRef(), row.updatedAt(), databaseClock.get(),
                    row.updatedAt().plus(QUIET_PERIOD));
        });
    }

    public Result apply(Command command) {
        validate(command);
        String fingerprint = fingerprint(command);
        return transactions.execute(status -> {
            if (!"DRAIN".equals(mapper.sharedMode())) {
                throw rejected("DRAIN_REQUIRED");
            }
            Target target = command.target();
            if (mapper.lockAccount(target.accountId()) == null) {
                throw rejected("TARGET_NOT_FOUND");
            }
            LegacyGenerationRecoveryMapper.State row = state(target);
            LegacyGenerationRecoveryMapper.Audit previous = mapper.audit(command.operationId());
            if (previous != null) {
                if (!previous.fingerprint().equals(fingerprint)) {
                    throw rejected("OPERATION_CONFLICT");
                }
                if (!"FAILED".equals(row.status()) || !UNKNOWN.equals(row.failureCode())
                        || !Objects.equals(row.fileRef(), command.expectedFileRef())) {
                    throw rejected("AUDIT_STATE_MISMATCH");
                }
                return new Result(command.operationId(), target.turnId(), UNKNOWN, true);
            }
            if (!"GENERATING".equals(row.status()) || !Objects.equals(row.fileRef(), command.expectedFileRef())
                    || !row.updatedAt().equals(command.expectedUpdatedAt())) {
                throw rejected("OBSERVATION_CHANGED");
            }
            if (databaseClock.get().isBefore(row.updatedAt().plus(QUIET_PERIOD))) {
                throw rejected("QUIET_PERIOD_REQUIRED");
            }
            if (mapper.recover(target.accountId(), target.questionId(), target.turnId(),
                    command.expectedFileRef(), command.expectedUpdatedAt()) != 1) {
                throw rejected("OBSERVATION_CHANGED");
            }
            mapper.record(command.operationId(), target.turnId(), target.accountId(), target.questionId(),
                    command.expectedFileRef(), command.expectedUpdatedAt(), fingerprint, command.operator(), command.incident());
            return new Result(command.operationId(), target.turnId(), UNKNOWN, false);
        });
    }

    private LegacyGenerationRecoveryMapper.State state(Target target) {
        LegacyGenerationRecoveryMapper.State row = mapper.state(target.accountId(), target.questionId(), target.turnId());
        if (row == null) {
            throw rejected("TARGET_NOT_FOUND");
        }
        return row;
    }

    private static void validate(Target target) {
        if (target == null || target.accountId() < 1 || target.questionId() < 1
                || !AnswerManifestValidator.uuid(target.turnId())) {
            throw rejected("INVALID_TARGET");
        }
    }

    private static void validate(Command command) {
        if (command == null) {
            throw rejected("INVALID_COMMAND");
        }
        validate(command.target());
        if (!AnswerManifestValidator.uuid(command.expectedFileRef()) || command.expectedUpdatedAt() == null
                || !AnswerManifestValidator.uuid(command.operationId()) || !reference(command.operator())
                || !reference(command.incident())) {
            throw rejected("INVALID_COMMAND");
        }
        if (!command.executorsStopped() || !command.acceptUnknownOutcome()) {
            throw rejected("EXPLICIT_CONFIRMATIONS_REQUIRED");
        }
    }

    private static boolean reference(String value) {
        return value != null && value.matches("[A-Za-z0-9._@:/-]{1,120}");
    }

    private static String fingerprint(Command command) {
        String value = command.target().accountId() + "\n" + command.target().questionId() + "\n"
                + command.target().turnId() + "\n" + command.expectedFileRef() + "\n" + command.expectedUpdatedAt()
                + "\n" + command.operator() + "\n" + command.incident();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA256_UNAVAILABLE", impossible);
        }
    }

    private static IllegalStateException rejected(String code) {
        return new IllegalStateException(code);
    }
}
