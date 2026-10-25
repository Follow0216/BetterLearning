package com.betterlearning.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.betterlearning.server.learning.operations.LegacyGenerationRecoveryService.Command;
import com.betterlearning.server.learning.operations.LegacyGenerationRecoveryService.Observation;
import com.betterlearning.server.learning.operations.LegacyGenerationRecoveryService.Result;
import com.betterlearning.server.learning.operations.LegacyGenerationRecoveryService.Target;

import org.junit.jupiter.api.Test;

class LegacyGenerationRecoveryCommandTests {
    private static final String TURN = "cf5f1b1b-c1c0-4530-badb-888a535b9d11";
    private static final String FILE = "06599803-1ed1-44cc-bb8a-e42d79739fe6";
    private static final String OPERATION = "4c10dfc0-21e8-4cfa-9f06-e146301d010f";
    private static final Map<String, String> ENV = Map.of(
            "SPRING_DATASOURCE_URL", "jdbc:postgresql://localhost:5432/recovery_fixture",
            "SPRING_DATASOURCE_USERNAME", "fixture",
            "SPRING_DATASOURCE_PASSWORD", "never-print-this-secret");

    @Test
    void inspectIsReadOnlyAndOutputsOnlyMetadata() {
        AtomicReference<Target> inspected = new AtomicReference<>();
        AtomicReference<Command> applied = new AtomicReference<>();
        Capture capture = invoke(new String[] {"inspect", "--account-id", "7",
                "--question-id", "9", "--turn-id", TURN}, ENV, env -> new LegacyGenerationRecoveryCommand.Gateway() {
                    @Override
                    public Observation inspect(Target target) {
                        inspected.set(target);
                        return new Observation(target, "DRAIN", "GENERATING", FILE,
                                Instant.parse("2026-10-25T00:00:00Z"), Instant.parse("2026-10-25T00:01:00Z"),
                                Instant.parse("2026-10-25T00:10:00Z"));
                    }

                    @Override
                    public Result apply(Command command) {
                        applied.set(command);
                        throw new AssertionError("inspect must not apply");
                    }
                });
        assertThat(capture.code()).isZero();
        assertThat(inspected.get()).isEqualTo(new Target(7, 9, TURN));
        assertThat(applied.get()).isNull();
        assertThat(capture.out()).contains("accountId=7", "questionId=9", "turnId=" + TURN,
                "mode=DRAIN", "status=GENERATING", "eligibleAt=2026-10-25T00:10:00Z")
                .doesNotContain("never-print-this-secret");
        assertThat(capture.err()).isEmpty();
    }

    @Test
    void applyNeedsBothExplicitRiskFlagsAndExactExpectedState() {
        String[] valid = applyArgs();
        Capture missingFlag = invoke(java.util.Arrays.copyOf(valid, valid.length - 1), ENV,
                env -> {
                    throw new AssertionError("invalid arguments must not connect");
                });
        assertThat(missingFlag.code()).isEqualTo(2);
        assertThat(missingFlag.err()).isEqualTo("E_ARGUMENT" + System.lineSeparator());

        AtomicReference<Command> applied = new AtomicReference<>();
        Capture accepted = invoke(valid, ENV, env -> new LegacyGenerationRecoveryCommand.Gateway() {
            @Override
            public Observation inspect(Target target) {
                throw new AssertionError("apply must not inspect");
            }

            @Override
            public Result apply(Command command) {
                applied.set(command);
                return new Result(command.operationId(), command.target().turnId(), "GENERATION_OUTCOME_UNKNOWN", false);
            }
        });
        assertThat(accepted.code()).isZero();
        assertThat(applied.get().target()).isEqualTo(new Target(7, 9, TURN));
        assertThat(applied.get().expectedFileRef()).isEqualTo(FILE);
        assertThat(applied.get().expectedUpdatedAt()).isEqualTo(Instant.parse("2026-10-25T00:00:00Z"));
        assertThat(applied.get().executorsStopped()).isTrue();
        assertThat(applied.get().acceptUnknownOutcome()).isTrue();
        assertThat(accepted.out()).contains("operationId=" + OPERATION, "alreadyApplied=false")
                .doesNotContain("never-print-this-secret");
    }

    @Test
    void rejectsDuplicateUnknownAndUnexpectedInspectOptionsBeforeConnecting() {
        String[] baseline = {"inspect", "--account-id", "7", "--question-id", "9", "--turn-id", TURN};
        assertBad(new String[] {"inspect", "--account-id", "7", "--account-id", "8",
                "--question-id", "9", "--turn-id", TURN});
        assertBad(new String[] {"inspect", "--account-id", "7", "--question-id", "9",
                "--turn-id", TURN, "--incident", "INC-1"});
        assertBad(new String[] {"inspect", "--account-id", "7", "--question-id", "9",
                "--turn-id", TURN, "--unknown", "x"});
        assertBad(new String[] {"inspect", "--account-id", "0", "--question-id", "9", "--turn-id", TURN});
        assertBad(java.util.Arrays.copyOf(baseline, baseline.length - 1));
    }

    @Test
    void knownServiceRejectionUsesWhitelistedCodeOnly() {
        Capture rejected = invoke(applyArgs(), ENV, env -> new LegacyGenerationRecoveryCommand.Gateway() {
            @Override
            public Observation inspect(Target target) {
                throw new AssertionError("apply must not inspect");
            }

            @Override
            public Result apply(Command command) {
                throw new IllegalStateException("QUIET_PERIOD_REQUIRED");
            }
        });
        assertThat(rejected.code()).isEqualTo(4);
        assertThat(rejected.err()).isEqualTo("E_QUIET_PERIOD_REQUIRED" + System.lineSeparator());
        assertThat(rejected.out()).isEmpty();
    }

    @Test
    void rejectsCredentialsEmbeddedInUrlAndNeverEchoesConnectionError() {
        Capture badUrl = invoke(new String[] {"inspect", "--account-id", "7",
                "--question-id", "9", "--turn-id", TURN},
                Map.of("SPRING_DATASOURCE_URL", "jdbc:postgresql://fixture:secret@localhost/db",
                "SPRING_DATASOURCE_USERNAME", "fixture",
                "SPRING_DATASOURCE_PASSWORD", "password-fixture"), env -> {
                    throw new AssertionError("invalid configuration must not connect");
                });
        assertThat(badUrl.code()).isEqualTo(3);
        assertThat(badUrl.err()).isEqualTo("E_CONFIG" + System.lineSeparator());
        assertThat(badUrl.err()).doesNotContain("secret", "password-fixture");

        Capture failed = invoke(new String[] {"inspect", "--account-id", "7",
                "--question-id", "9", "--turn-id", TURN}, ENV,
                env -> {
                    throw new IllegalStateException("never-print-this-secret");
                });
        assertThat(failed.code()).isEqualTo(4);
        assertThat(failed.err()).isEqualTo("E_EXECUTION" + System.lineSeparator());
    }

    private static String[] applyArgs() {
        return new String[] {"apply", "--account-id", "7", "--question-id", "9", "--turn-id", TURN,
                "--expected-file-ref", FILE, "--expected-updated-at", "2026-10-25T00:00:00Z",
                "--operation-id", OPERATION, "--operator", "oncall-1", "--incident", "INC-42",
                "--confirm-executors-stopped", "--accept-unknown-outcome"};
    }

    private static void assertBad(String[] args) {
        Capture result = invoke(args, ENV, env -> {
            throw new AssertionError("invalid arguments must not connect");
        });
        assertThat(result.code()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.err()).isEqualTo("E_ARGUMENT" + System.lineSeparator());
    }

    private static Capture invoke(String[] args, Map<String, String> env,
            LegacyGenerationRecoveryCommand.GatewayFactory factory) {
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        int code = LegacyGenerationRecoveryCommand.run(args, env,
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8), factory);
        return new Capture(code, stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
    }

    private record Capture(int code, String out, String err) {
    }
}
