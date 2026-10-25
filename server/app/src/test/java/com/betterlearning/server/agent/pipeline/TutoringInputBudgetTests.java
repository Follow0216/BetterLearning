package com.betterlearning.server.agent.pipeline;

import static com.betterlearning.server.agent.pipeline.TutoringPipelineTests.assertReason;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.betterlearning.server.agent.config.AgentProperties;
import com.betterlearning.server.agent.model.ModelImage;
import com.betterlearning.server.agent.model.TutorModels.FrozenAnswer;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Call;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Input;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Reason;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Role;
import com.betterlearning.server.multiagent.EvidenceRetriever;
import com.betterlearning.server.multiagent.MultiAgentModels;
import com.betterlearning.server.multiagent.MultiAgentModels.Execution;
import com.betterlearning.server.multiagent.MultiAgentModels.Image;
import com.betterlearning.server.multiagent.MultiAgentModels.PipelineContext;
import com.betterlearning.server.multiagent.MultiAgentModels.Snapshot;
import com.betterlearning.server.multiagent.SourceStore;
import com.betterlearning.server.multiagent.StepExecutor;
import com.betterlearning.server.multiagent.TutorLedger;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** 输入容量边界必须在图片转换、Base64 编码和外部调用之前统一拒绝。 */
class TutoringInputBudgetTests {
    private static final int MIB = 1024 * 1024;
    private static final String FILE_REF = "budget-snapshot";

    @Test
    void exactThirtyTwoMibAndTwoHundredFiftySixImagesAreAcceptedAtPreflight() {
        assertThat(TutoringInputBudget.MAX_IMAGE_COUNT).isEqualTo(256);
        assertThat(TutoringInputBudget.MAX_TOTAL_IMAGE_BYTES).isEqualTo(32L * MIB);
        assertThat(TutoringInputBudget.isWithinImageBudget(256, 32L * MIB)).isTrue();
        assertThat(TutoringInputBudget.isWithinImageBudget(257, 32L * MIB)).isFalse();
        assertThat(TutoringInputBudget.isWithinImageBudget(256, 32L * MIB + 1)).isFalse();
        assertThat(TutoringInputBudget.isWithinImageBudget(2, 64L * MIB)).isFalse();
        assertThat(TutoringInputBudget.isWithinImageBudget(0, 1)).isFalse();
        assertThat(TutoringInputBudget.isWithinImageBudget(1, 0)).isFalse();

        ModelImage modelSixteenMib = modelImage(new byte[16 * MIB]);
        TutoringInputBudget.validateModelImages(List.of(modelSixteenMib, modelSixteenMib));
        ModelImage oneByte = modelImage(new byte[] {1});
        TutoringInputBudget.validateModelImages(Collections.nCopies(256, oneByte));
        Image commonSixteenMib = commonImage(new byte[16 * MIB]);
        TutoringInputBudget.validateSnapshot(snapshot(List.of(commonSixteenMib, commonSixteenMib)));
        TutoringInputBudget.validateSnapshot(snapshot(Collections.nCopies(256, commonImage(new byte[] {1}))));
    }

    @Test
    void aggregateBytesAndCountFailWithoutRaisingTheCapToSixtyFourMib() {
        ModelImage sixteenMib = modelImage(new byte[16 * MIB]);
        ModelImage oneByte = modelImage(new byte[] {1});
        assertReason(() -> TutoringInputBudget.validateModelImages(
                List.of(sixteenMib, sixteenMib, oneByte)), Reason.MODEL_INPUT_TOO_LARGE);
        assertReason(() -> TutoringInputBudget.validateModelImages(
                Collections.nCopies(4, sixteenMib)), Reason.MODEL_INPUT_TOO_LARGE);
        assertReason(() -> TutoringInputBudget.validateModelImages(
                Collections.nCopies(257, oneByte)), Reason.MODEL_INPUT_TOO_LARGE);
        Image commonSixteenMib = commonImage(new byte[16 * MIB]);
        assertReason(() -> TutoringInputBudget.validateSnapshot(snapshot(List.of(
                commonSixteenMib, commonSixteenMib, commonImage(new byte[] {1})))),
                Reason.MODEL_INPUT_TOO_LARGE);
        assertReason(() -> TutoringInputBudget.validateSnapshot(snapshot(
                Collections.nCopies(257, commonImage(new byte[] {1})))), Reason.MODEL_INPUT_TOO_LARGE);
    }

    @Test
    void emptyBytesAndInvalidMediaRemainInvalidInput() {
        assertReason(() -> TutoringInputBudget.validateModelImages(List.of()), Reason.INVALID_INPUT);
        assertReason(() -> TutoringInputBudget.validateSnapshot(snapshot(List.of())), Reason.INVALID_INPUT);
        assertReason(() -> TutoringInputBudget.validateModelImages(List.of(modelImage(new byte[0]))),
                Reason.INVALID_INPUT);
        assertReason(() -> TutoringInputBudget.validateModelImages(List.of(
                new ModelImage("image/gif", new byte[] {1}, "x=0"))), Reason.INVALID_INPUT);
        assertReason(() -> TutoringInputBudget.validateSnapshot(snapshot(List.of(commonImage(new byte[0])))),
                Reason.INVALID_INPUT);
        assertReason(() -> TutoringInputBudget.validateSnapshot(snapshot(List.of(
                new Image(new byte[] {1}, "image/gif", "x=0")))), Reason.INVALID_INPUT);
    }

    @Test
    void directPipelineRejectsOversizeBeforeGenerationOrRetrieval() {
        ModelImage oneByte = modelImage(new byte[] {1});
        GenerationBoundary boundary = mock(GenerationBoundary.class);
        Input overCount = input(Collections.nCopies(257, oneByte));
        assertReason(() -> new TutoringPipeline().run(overCount, boundary), Reason.MODEL_INPUT_TOO_LARGE);
        ModelImage sixteenMib = modelImage(new byte[16 * MIB]);
        Input overBytes = input(List.of(sixteenMib, sixteenMib, oneByte));
        assertReason(() -> new TutoringPipeline().run(overBytes, boundary), Reason.MODEL_INPUT_TOO_LARGE);
        verifyNoInteractions(boundary);
    }

    @Test
    void commonAdapterRejectsOversizeWithExplicitReasonBeforeLedgerOrStepExecutor() {
        TutorLedger ledger = mock(TutorLedger.class);
        SourceStore sources = mock(SourceStore.class);
        EvidenceRetriever retriever = mock(EvidenceRetriever.class);
        StepExecutor steps = mock(StepExecutor.class);
        DefaultRolePipeline pipeline = new DefaultRolePipeline(new AgentProperties(),
                provider(ledger), provider(sources), provider(retriever));
        Execution execution = execution();
        Image oneByte = commonImage(new byte[] {1});
        var countResult = pipeline.execute(new PipelineContext(execution,
                snapshot(Collections.nCopies(257, oneByte)), List.of()), steps);
        assertThat(countResult.verified()).isFalse();
        assertThat(countResult.reason()).isEqualTo("BUDGET_EXCEEDED");

        Image sixteenMib = commonImage(new byte[16 * MIB]);
        var bytesResult = pipeline.execute(new PipelineContext(execution,
                snapshot(List.of(sixteenMib, sixteenMib, oneByte)), List.of()), steps);
        assertThat(bytesResult.verified()).isFalse();
        assertThat(bytesResult.reason()).isEqualTo("BUDGET_EXCEEDED");
        verifyNoInteractions(ledger, steps, sources, retriever);
    }

    @Test
    void sdkRejectsOversizeBeforeLocalHttpOrBase64Encoding() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            AgentProperties.Provider provider = new AgentProperties.Provider();
            provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            provider.setModel("local-budget-fixture");
            provider.setApiKey("local-only-key");
            provider.setTimeoutSeconds(5);
            LangChain4jRoleModel model = new LangChain4jRoleModel(provider);
            ModelImage oneByte = modelImage(new byte[] {1});
            assertReason(() -> model.generate(call(Collections.nCopies(257, oneByte))),
                    Reason.MODEL_INPUT_TOO_LARGE);
            ModelImage sixteenMib = modelImage(new byte[16 * MIB]);
            assertReason(() -> model.generate(call(List.of(sixteenMib, sixteenMib, oneByte))),
                    Reason.MODEL_INPUT_TOO_LARGE);
            assertThat(requests).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    private static ModelImage modelImage(byte[] bytes) {
        return new ModelImage("image/png", bytes, "x=-20,y=0,width=1,height=1");
    }

    private static Image commonImage(byte[] bytes) {
        return new Image(bytes, "image/png", "x=-20,y=0,width=1,height=1");
    }

    private static Snapshot snapshot(List<Image> images) {
        return new Snapshot(FILE_REF, "解方程 2x+3=17。", images);
    }

    private static Input input(List<ModelImage> images) {
        return new Input("请给下一步提示", "HINT", new FrozenAnswer(FILE_REF,
                "解方程 2x+3=17。", images), Set.of(), null);
    }

    private static Call call(List<ModelImage> images) {
        return new Call(1, Role.SOLVE, RolePrompts.forRole(Role.SOLVE), "{}",
                new FrozenAnswer(FILE_REF, "解方程 2x+3=17。", images));
    }

    private static Execution execution() {
        return new Execution(42L, "request-budget", "question-budget", "run-budget",
                MultiAgentModels.Mode.HINT, "请给下一步提示", FILE_REF, "source-budget", 1L,
                MultiAgentModels.Status.RUNNING, 1L, null, Instant.now().plusSeconds(300), null, 0);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> result = mock(ObjectProvider.class);
        when(result.getIfAvailable()).thenReturn(value);
        return result;
    }
}
