package com.betterlearning.server.agent.runtime;

import com.google.protobuf.Duration;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.EventType;
import io.temporal.api.workflowservice.v1.GetWorkflowExecutionHistoryRequest;
import io.temporal.api.workflowservice.v1.RegisterNamespaceRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 真实 Temporal Server 与独立 PostgreSQL 的历史恢复冒烟测试。 */
@Tag("integration")
class PersistentTemporalFixtureIntegrationTests {
    private static final String QUEUE = "persistent-temporal-fixture";

    @WorkflowInterface
    public interface PendingWorkflow {
        @WorkflowMethod
        String run(String input);

        @SignalMethod
        void release();
    }

    public static class PendingWorkflowImpl implements PendingWorkflow {
        private boolean released;

        @Override
        public String run(String input) {
            Workflow.await(() -> released);
            return "done:" + input;
        }

        @Override
        public void release() {
            released = true;
        }
    }

    @Test
    @Timeout(value = 300)
    void workflowHistorySurvivesTemporalServerRestart() throws Exception {
        try (PersistentTemporalFixture fixture = PersistentTemporalFixture.start()) {
            String namespace = "p2-" + UUID.randomUUID();
            fixture.service().blockingStub().registerNamespace(RegisterNamespaceRequest.newBuilder()
                    .setNamespace(namespace)
                    .setWorkflowExecutionRetentionPeriod(Duration.newBuilder()
                    .setSeconds(TimeUnit.DAYS.toSeconds(7)).build())
                    .build());
            WorkflowClient firstClient = WorkflowClient.newInstance(fixture.service(),
                    WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
            WorkerFactory firstFactory = WorkerFactory.newInstance(firstClient);
            try {
                Worker worker = firstFactory.newWorker(QUEUE);
                worker.registerWorkflowImplementationTypes(PendingWorkflowImpl.class);
                firstFactory.start();
                String workflowId = "history-" + UUID.randomUUID();
                PendingWorkflow pending = firstClient.newWorkflowStub(PendingWorkflow.class,
                        WorkflowOptions.newBuilder().setWorkflowId(workflowId)
                        .setTaskQueue(QUEUE).build());
                WorkflowExecution execution = WorkflowClient.start(pending::run, "probe");
                List<Long> before = awaitCompletedTaskHistory(fixture, namespace, execution);
                assertFalse(before.isEmpty());
                String serverContainerId = fixture.serverContainerId();
                firstFactory.shutdown();
                firstFactory.awaitTermination(10, TimeUnit.SECONDS);
                assertTrue(firstFactory.isShutdown());

                fixture.restartServer();
                assertEquals(serverContainerId, fixture.serverContainerId());
                List<Long> after = historyEventIds(fixture, namespace, execution);
                assertTrue(after.size() >= before.size());
                assertEquals(before, after.subList(0, before.size()));

                WorkflowClient secondClient = WorkflowClient.newInstance(fixture.service(),
                        WorkflowClientOptions.newBuilder().setNamespace(namespace).build());
                WorkerFactory secondFactory = WorkerFactory.newInstance(secondClient);
                try {
                    Worker replacement = secondFactory.newWorker(QUEUE);
                    replacement.registerWorkflowImplementationTypes(PendingWorkflowImpl.class);
                    secondFactory.start();
                    PendingWorkflow resumed = secondClient.newWorkflowStub(PendingWorkflow.class, workflowId);
                    resumed.release();
                    assertEquals("done:probe", WorkflowStub.fromTyped(resumed)
                            .getResult(30, TimeUnit.SECONDS, String.class));
                } finally {
                    secondFactory.shutdown();
                    secondFactory.awaitTermination(10, TimeUnit.SECONDS);
                }
            } finally {
                if (!firstFactory.isShutdown()) {
                    firstFactory.shutdown();
                }
            }
        }
    }

    private List<Long> awaitCompletedTaskHistory(PersistentTemporalFixture fixture,
            String namespace, WorkflowExecution execution) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        do {
            var history = fixture.service().blockingStub().getWorkflowExecutionHistory(
                    GetWorkflowExecutionHistoryRequest.newBuilder().setNamespace(namespace)
                    .setExecution(execution).build()).getHistory();
            if (history.getEventsList().stream().anyMatch(event -> event.getEventType()
                    == EventType.EVENT_TYPE_WORKFLOW_TASK_COMPLETED)) {
                return history.getEventsList().stream().map(event -> event.getEventId()).toList();
            }
            TimeUnit.MILLISECONDS.sleep(100);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Workflow Task 未在期限内完成");
    }

    private List<Long> historyEventIds(PersistentTemporalFixture fixture,
            String namespace, WorkflowExecution execution) {
        return fixture.service().blockingStub().getWorkflowExecutionHistory(
                GetWorkflowExecutionHistoryRequest.newBuilder().setNamespace(namespace)
                        .setExecution(execution).build()).getHistory().getEventsList().stream()
                .map(event -> event.getEventId()).toList();
    }
}
