package com.betterlearning.server.agent.runtime;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.betterlearning.server.multiagent.MultiAgentModels.Role;
import com.betterlearning.server.multiagent.MultiAgentModels.StepInvocation;
import com.betterlearning.server.multiagent.MultiAgentModels.StepOutput;
import com.betterlearning.server.multiagent.StepExecutionException;
import com.betterlearning.server.multiagent.StepExecutor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderCallDeadlineTests {
    private static final StepInvocation SOLVE = new StepInvocation(Role.SOLVE, 0, "input-digest");
    private static final StepInvocation RETRIEVE = new StepInvocation(Role.RETRIEVE, 0, "input-digest");
    private static final StepOutput OUTPUT = new StepOutput("answer", "output-digest");
    private static final StepExecutor DURABLE = (invocation, action) -> action.call();

    @Test
    void successfulPaidCallReturnsThroughDurableExecutor() {
        try (ProviderCallDeadline deadline = new ProviderCallDeadline(1)) {
            StepExecutor bound = deadline.bind(DURABLE, Duration.ofSeconds(120));
            assertThat(bound.execute(SOLVE, () -> OUTPUT)).isSameAs(OUTPUT);
        }
    }

    @Test
    void timeoutIsBoundedAndDoesNotClaimProviderStopped() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        StepExecutor.StepAction blocked = () -> {
            entered.countDown();
            return awaitReleaseIgnoringInterrupt(release);
        };
        try (ProviderCallDeadline deadline = new ProviderCallDeadline(1)) {
            StepExecutor bound = deadline.bind(DURABLE, Duration.ofMillis(200));
            StepExecutionException failure = assertThrows(StepExecutionException.class,
                    () -> bound.execute(SOLVE, blocked));
            assertThat(failure.reason()).isEqualTo("PROVIDER_TIMEOUT");
            assertThat(failure.getCause()).isNull();
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            StepExecutionException stillRunning = assertThrows(StepExecutionException.class,
                    () -> bound.execute(SOLVE, () -> OUTPUT));
            assertThat(stillRunning.reason()).isEqualTo("PROVIDER_CAPACITY_EXCEEDED");
        } finally {
            release.countDown();
        }
    }

    @Test
    void supplierExceptionIsSanitizedWithoutCause() {
        StepExecutor.StepAction failed = ProviderCallDeadlineTests::throwSensitive;
        try (ProviderCallDeadline deadline = new ProviderCallDeadline(1)) {
            StepExecutor bound = deadline.bind(DURABLE, Duration.ofSeconds(1));
            StepExecutionException failure = assertThrows(StepExecutionException.class,
                    () -> bound.execute(SOLVE, failed));
            assertThat(failure.reason()).isEqualTo("PROVIDER_FAILED");
            assertThat(failure.getMessage()).doesNotContain("secret-provider-body");
            assertThat(failure.getCause()).isNull();
        }
    }

    @Test
    void fullPoolRejectsSecondCallWithoutInvokingItsAction() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger secondCalls = new AtomicInteger();
        AtomicInteger admitted = new AtomicInteger();
        StepExecutor countedDurable = (invocation, action) -> {
            admitted.incrementAndGet();
            return action.call();
        };
        StepExecutor.StepAction firstAction = () -> {
            entered.countDown();
            return awaitReleaseIgnoringInterrupt(release);
        };
        StepExecutor.StepAction secondAction = () -> {
            secondCalls.incrementAndGet();
            return OUTPUT;
        };
        try (ProviderCallDeadline deadline = new ProviderCallDeadline(1)) {
            StepExecutor bound = deadline.bind(countedDurable, Duration.ofSeconds(3));
            CompletableFuture<StepOutput> first = CompletableFuture.supplyAsync(() -> bound.execute(SOLVE, firstAction));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            StepExecutionException failure = assertThrows(StepExecutionException.class,
                    () -> bound.execute(SOLVE, secondAction));
            assertThat(failure.reason()).isEqualTo("PROVIDER_CAPACITY_EXCEEDED");
            assertThat(secondCalls).hasValue(0);
            assertThat(admitted).hasValue(1);
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isSameAs(OUTPUT);
        } finally {
            release.countDown();
        }
    }

    @Test
    void retrievalRunsDirectlyWithoutConsumingProviderPool() {
        try (ProviderCallDeadline deadline = new ProviderCallDeadline(1)) {
            StepExecutor bound = deadline.bind(DURABLE, Duration.ofSeconds(1));
            assertThat(bound.execute(RETRIEVE, () -> OUTPUT)).isSameAs(OUTPUT);
        }
    }

    @Test
    void controlledUnknownRetrievalOutcomeReachesDurableDelegateWithoutDiagnostics() {
        StepExecutionException unknown = new StepExecutionException("OUTCOME_UNKNOWN");
        unknown.initCause(new IllegalStateException("secret-provider-body"));
        unknown.addSuppressed(new IllegalStateException("secret-suppressed-body"));
        assertRetrievalFailureObservedByDelegate(unknown, "OUTCOME_UNKNOWN");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"MODEL_UNAVAILABLE", "RETRIEVAL_FAILED", " outcome_unknown ",
            "OUTCOME_UNKNOWN\nsecret-provider-body"})
    void otherRetrievalReasonsRemainSanitizedAtDelegateBoundary(String reason) {
        StepExecutionException other = new StepExecutionException(reason);
        other.initCause(new IllegalStateException("secret-provider-body"));
        assertRetrievalFailureObservedByDelegate(other, "RETRIEVAL_FAILED");
    }

    @Test
    void arbitraryRuntimeExceptionCannotImpersonateControlledUnknownReason() {
        assertRetrievalFailureObservedByDelegate(new IllegalStateException("OUTCOME_UNKNOWN",
                new IllegalStateException("secret-provider-body")), "RETRIEVAL_FAILED");
    }

    private void assertRetrievalFailureObservedByDelegate(RuntimeException original, String expectedReason) {
        AtomicReference<StepExecutionException> seenByDelegate = new AtomicReference<>();
        AtomicInteger actionCalls = new AtomicInteger();
        StepExecutor observingDelegate = (invocation, action) -> {
            assertThat(invocation).isSameAs(RETRIEVE);
            try {
                return action.call();
            } catch (StepExecutionException failure) {
                seenByDelegate.set(failure);
                throw failure;
            }
        };
        try (ProviderCallDeadline deadline = new ProviderCallDeadline(1)) {
            StepExecutor bound = deadline.bind(observingDelegate, Duration.ofSeconds(1));
            StepExecutionException failure = assertThrows(StepExecutionException.class,
                    () -> bound.execute(RETRIEVE, () -> {
                        actionCalls.incrementAndGet();
                        throw original;
                    }));
            assertThat(seenByDelegate.get()).isSameAs(failure).isNotSameAs(original);
            assertThat(failure.reason()).isEqualTo(expectedReason);
            assertThat(failure.getMessage()).isEqualTo(expectedReason);
            assertThat(failure.getCause()).isNull();
            assertThat(failure.getSuppressed()).isEmpty();
            assertThat(actionCalls).hasValue(1);
        }
    }

    @Test
    void confirmedReuseAndRejectedAdmissionReleaseLocalReservation() {
        StepExecutor cached = (invocation, action) -> OUTPUT;
        StepExecutor rejected = (invocation, action) -> {
            throw new StepExecutionException("RUN_NOT_ACTIVE");
        };
        try (ProviderCallDeadline deadline = new ProviderCallDeadline(1)) {
            assertThat(deadline.bind(cached, Duration.ofSeconds(1)).execute(SOLVE,
                    ProviderCallDeadlineTests::throwSensitive)).isSameAs(OUTPUT);
            assertThrows(StepExecutionException.class,
                    () -> deadline.bind(rejected, Duration.ofSeconds(1)).execute(SOLVE, () -> OUTPUT));
            assertThat(deadline.bind(DURABLE, Duration.ofSeconds(1)).execute(SOLVE, () -> OUTPUT)).isSameAs(OUTPUT);
        }
    }

    @Test
    void configurationRejectsZeroNegativeAndAboveTwoMinutes() {
        assertThrows(IllegalArgumentException.class, () -> new ProviderCallDeadline(0));
        assertThrows(IllegalArgumentException.class, () -> new ProviderCallDeadline(65));
        try (ProviderCallDeadline deadline = new ProviderCallDeadline(1)) {
            assertThrows(IllegalArgumentException.class, () -> deadline.bind(DURABLE, Duration.ZERO));
            assertThrows(IllegalArgumentException.class, () -> deadline.bind(DURABLE, Duration.ofSeconds(-1)));
            assertThrows(IllegalArgumentException.class,
                    () -> deadline.bind(DURABLE, Duration.ofSeconds(120).plusNanos(1)));
            assertThat(deadline.bind(DURABLE, Duration.ofSeconds(120))).isNotNull();
        }
    }

    private static StepOutput awaitReleaseIgnoringInterrupt(CountDownLatch release) {
        while (true) {
            try {
                release.await();
                return OUTPUT;
            } catch (InterruptedException ignored) {
                // Simulates a remote call that does not stop when the local thread is interrupted.
            }
        }
    }

    private static StepOutput throwSensitive() {
        throw new IllegalStateException("secret-provider-body");
    }
}
