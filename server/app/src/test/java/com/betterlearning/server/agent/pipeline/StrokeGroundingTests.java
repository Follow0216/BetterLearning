package com.betterlearning.server.agent.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.betterlearning.server.agent.model.ModelImage;
import com.betterlearning.server.agent.model.TutorModels.FrozenAnswer;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Call;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Input;
import com.betterlearning.server.agent.pipeline.RoleProtocol.PipelineFailure;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Reason;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Reply;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Role;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Usage;
import com.betterlearning.server.multiagent.MultiAgentModels.StrokeLocation;
import com.betterlearning.server.multiagent.MultiAgentModels.StrokeLocationsStatus;
import com.betterlearning.server.multiagent.MultiAgentModels.WorldBounds;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/** 不调用供应商；验证完整位置映射与模型输出之间的证据边界。 */
class StrokeGroundingTests {
    private static final int MAX_LOCATION_JSON_BYTES = 256 * 1024;
    private static final String SOLVE = """
            {"summary":"等式两边同减3","answer":"x=7","methodTags":["一元一次方程"],
            "checkpoints":["2x=14"],"sufficient":true,"answerComplete":true}
            """;
    private static final String OBSERVED_ERROR = """
            {"status":"OBSERVED","summary":"第一步移项方向有误",
            "observations":[{"kind":"ERROR","description":"把+3移项后仍写+3","strokeIds":["s1"]}],
            "clarification":""}
            """;
    private static final String UNCERTAIN_DIAGNOSIS = """
            {"status":"UNCERTAIN","summary":"末行符号不清楚","observations":[],
            "clarification":"请确认末行的符号。"}
            """;
    private static final String NO_WRITING_DIAGNOSIS = """
            {"status":"NO_WRITING","summary":"尚无学生笔迹","observations":[],"clarification":""}
            """;
    private static final String TEACH = """
            {"hint":"先检查移项后的常数项符号。","nextStep":"在等式两边同时减3。",
            "clarification":"","fullSolution":null,"evidenceIds":[]}
            """;
    private static final String REVIEW = """
            {"approved":true,"solutionComplete":true,"mathematicsOk":true,"diagnosisGrounded":true,"hintSafe":true,
            "citationsSupported":true,"historyNotAuthority":true,"issues":[]}
            """;

    @Test
    void completeNegativeCrossBlockLocationSupportsSpecificObservedError() {
        Input input = input(StrokeLocationsStatus.COMPLETE, List.of(location("s1", -12, 800, List.of(0, 1))),
                Set.of("s1"), true, 0.95, images());
        StubBoundary boundary = new StubBoundary(false);

        var result = new TutoringPipeline().run(input, boundary);

        assertThat(result.diagnosis().get("status").asString()).isEqualTo("OBSERVED");
        assertThat(result.diagnosis().get("observations").get(0).get("strokeIds").get(0).asString())
                .isEqualTo("s1");
        assertThat(result.bodyMarkdown()).contains("移项后的常数项符号");
        assertThat(boundary.calls).hasSize(4).allSatisfy(call -> {
            assertThat(call.inputJson()).contains("strokeLocationsStatus", "COMPLETE", "s1", "-12.0", "imageOrdinals");
            assertThat(call.inputJson()).contains("[0,1]");
            assertThat(call.inputDigest()).hasSize(64);
        });
        assertThat(boundary.calls.get(1).inputJson()).contains("worldBounds");
    }

    @Test
    void unavailableAndTooLargeNeverExposeSpecificStrokeError() {
        for (StrokeLocationsStatus status : List.of(StrokeLocationsStatus.UNAVAILABLE,
                StrokeLocationsStatus.TOO_LARGE)) {
            Input input = input(status, List.of(), Set.of("s1"), true, null, images());
            StubBoundary boundary = new StubBoundary(false);

            var result = new TutoringPipeline().run(input, boundary);

            assertThat(result.diagnosis().get("status").asString()).isEqualTo("UNCERTAIN");
            assertThat(result.diagnosis().get("observations")).isEmpty();
            assertThat(result.bodyMarkdown()).contains("请").doesNotContain("移项后的常数项符号");
            assertThat(boundary.calls).hasSize(4);
            assertThat(boundary.calls.get(1).inputJson()).contains(status.name());
        }
    }

    @Test
    void oversizedLocationTableExplainsCapacityWithoutPretendingToDiagnoseOrTruncatingStrokes() {
        Set<String> manyStrokes = java.util.stream.IntStream.rangeClosed(1, 2001)
                .mapToObj(index -> "s" + index).collect(java.util.stream.Collectors.toSet());
        for (Set<String> ids : List.of(Set.of("s1"), manyStrokes)) {
            for (Double confidence : new Double[] {null, 0.4}) {
                Input oversized = input(StrokeLocationsStatus.TOO_LARGE, List.of(), ids, true, confidence, images());
                StubBoundary boundary = new StubBoundary(false);
                var result = new TutoringPipeline().run(oversized, boundary);
                assertThat(result.diagnosis().get("status").asString()).isEqualTo("UNCERTAIN");
                assertThat(result.diagnosis().get("observations")).isEmpty();
                assertThat(result.bodyMarkdown()).contains("定位数据超过", "保留原作答", "不能解除容量限制", "独立作答")
                        .doesNotContain("移项后的常数项符号", "补充清晰作答图片", "有误");
                assertThat(result.evidenceIds()).isEmpty();
                assertThat(boundary.calls).hasSize(4).allSatisfy(call -> {
                    JsonNode envelope = RoleOutputValidator.JSON.readTree(call.inputJson());
                    assertThat(envelope.get("strokeIds").size()).isEqualTo(ids.size());
                    assertThat(envelope.get("strokeLocations")).isEmpty();
                    assertThat(call.snapshot().images()).isEqualTo(oversized.snapshot().images());
                });
            }
        }
    }

    @Test
    void unavailableGroundingDiscardsInventedIdsButStillRejectsUnexpectedFields() {
        Input legacy = input(StrokeLocationsStatus.UNAVAILABLE, List.of(), Set.of("s1"), true, null, images());
        StubBoundary invented = new StubBoundary(OBSERVED_ERROR.replace("s1", "invented"));
        var result = new TutoringPipeline().run(legacy, invented);
        assertThat(result.diagnosis().get("status").asString()).isEqualTo("UNCERTAIN");
        assertThat(result.diagnosis().get("observations")).isEmpty();
        assertThat(result.bodyMarkdown()).doesNotContain("invented", "移项后的常数项符号");
        assertReason(() -> RoleOutputValidator.parse(Role.DIAGNOSE,
                OBSERVED_ERROR.replace("\"status\":", "\"analysis\":\"private\",\"status\":"), legacy), Reason.INVALID_OUTPUT);
        Input unknown = input(StrokeLocationsStatus.UNAVAILABLE, List.of(), Set.of(), false, null, images());
        var unknownResult = new TutoringPipeline().run(unknown, new StubBoundary(NO_WRITING_DIAGNOSIS));
        assertThat(unknownResult.diagnosis().get("status").asString()).isEqualTo("UNCERTAIN");
        assertThat(unknownResult.bodyMarkdown()).doesNotContain("移项后的常数项符号");
    }

    @Test
    void lowConfidenceOcrRequiresClarificationDespiteCompleteLocation() {
        Input input = input(StrokeLocationsStatus.COMPLETE, List.of(location("s1", -12, 800, List.of(0, 1))),
                Set.of("s1"), true, 0.4, images());
        StubBoundary boundary = new StubBoundary(true);

        var result = new TutoringPipeline().run(input, boundary);

        assertThat(result.diagnosis().get("status").asString()).isEqualTo("UNCERTAIN");
        assertThat(result.diagnosis().get("observations")).isEmpty();
        assertThat(result.bodyMarkdown()).contains("请").doesNotContain("移项后的常数项符号");
    }

    @Test
    void lowConfidenceOcrDiscardsObservedErrorsBeforeSemanticChecks() {
        Input complete = input(StrokeLocationsStatus.COMPLETE,
                List.of(location("s1", -12, 800, List.of(0, 1))), Set.of("s1"), true, 0.4, images());
        for (String diagnosis : List.of(OBSERVED_ERROR, OBSERVED_ERROR.replace("s1", "invented"),
                NO_WRITING_DIAGNOSIS)) {
            StubBoundary boundary = new StubBoundary(diagnosis);
            var result = new TutoringPipeline().run(complete, boundary);
            assertThat(boundary.calls).hasSize(4);
            assertThat(result.diagnosis().get("status").asString()).isEqualTo("UNCERTAIN");
            assertThat(result.diagnosis().get("observations")).isEmpty();
            assertThat(result.diagnosis().get("summary").asString()).isEqualTo("当前笔迹识别不可靠，尚不能判断是否有误。");
            assertThat(result.bodyMarkdown()).contains("请确认不清晰的符号")
                    .doesNotContain("移项后的常数项符号", "减3", "有误", "invented");
            assertThat(result.evidenceIds()).isEmpty();
            JsonNode reviewInput = RoleOutputValidator.JSON.readTree(boundary.calls.get(3).inputJson());
            assertThat(reviewInput.path("context").path("diagnosis").path("observations")).isEmpty();
            assertThat(reviewInput.path("context").path("candidate").path("hint").asString()).isEmpty();
        }
        for (String malformed : List.of(
                OBSERVED_ERROR.replace("\"status\":", "\"analysis\":\"private\",\"status\":"),
                OBSERVED_ERROR.replace("\"description\":", "\"analysis\":\"private\",\"description\":"),
                OBSERVED_ERROR.replace("\"clarification\":\"\"", "\"clarification\":false"),
                OBSERVED_ERROR.replace("[\"s1\"]", "[\"s1\",\"s1\"]"))) {
            assertReason(() -> RoleOutputValidator.parse(Role.DIAGNOSE, malformed, complete), Reason.INVALID_OUTPUT);
        }
    }

    @Test
    void lowOcrThresholdDoesNotTurnKnownEmptyWritingIntoUncertainty() {
        for (double confidence : List.of(0.799999, 0.8)) {
            Input complete = input(StrokeLocationsStatus.COMPLETE,
                    List.of(location("s1", -12, 800, List.of(0, 1))), Set.of("s1"), true, confidence, images());
            var result = RoleOutputValidator.parse(Role.DIAGNOSE, OBSERVED_ERROR, complete);
            assertThat(result.get("status").asString()).isEqualTo(confidence < 0.8 ? "UNCERTAIN" : "OBSERVED");
            assertThat(result.get("observations").size()).isEqualTo(confidence < 0.8 ? 0 : 1);
        }
        for (StrokeLocationsStatus status : List.of(StrokeLocationsStatus.COMPLETE,
                StrokeLocationsStatus.UNAVAILABLE)) {
            Input empty = input(status, List.of(), Set.of(), true, 0.4, images());
            var result = new TutoringPipeline().run(empty, new StubBoundary(NO_WRITING_DIAGNOSIS));
            assertThat(result.diagnosis().get("status").asString()).isEqualTo("NO_WRITING");
            assertThat(result.bodyMarkdown()).contains("移项后的常数项符号");
        }
    }

    @Test
    void unknownWritingMetadataRequiresClarification() {
        Input input = input(StrokeLocationsStatus.UNAVAILABLE, List.of(), Set.of("s1"), false, null, images());
        StubBoundary boundary = new StubBoundary(true);

        var result = new TutoringPipeline().run(input, boundary);

        assertThat(result.diagnosis().get("status").asString()).isEqualTo("UNCERTAIN");
        assertThat(result.diagnosis().get("observations")).isEmpty();
        assertThat(result.bodyMarkdown()).contains("请").doesNotContain("移项后的常数项符号");
    }

    @Test
    void knownNoWritingAllowsUnavailableLocationTable() {
        Input input = input(StrokeLocationsStatus.UNAVAILABLE, List.of(), Set.of(), true, null, images());
        StubBoundary boundary = new StubBoundary(NO_WRITING_DIAGNOSIS);

        var result = new TutoringPipeline().run(input, boundary);

        assertThat(result.diagnosis().get("status").asString()).isEqualTo("NO_WRITING");
        assertThat(result.bodyMarkdown()).contains("移项后的常数项符号");
        assertThat(boundary.calls).hasSize(4);
    }

    @Test
    void equivalentLocationAndOrdinalOrderProduceIdenticalRoleDigests() {
        List<StrokeLocation> first = List.of(location("s2", 200, 100, List.of(1)),
                location("s1", -12, 800, List.of(1, 0)));
        List<StrokeLocation> reordered = List.of(location("s1", -12, 800, List.of(0, 1)),
                location("s2", 200, 100, List.of(1)));
        StubBoundary left = new StubBoundary(false);
        StubBoundary right = new StubBoundary(false);

        new TutoringPipeline().run(input(StrokeLocationsStatus.COMPLETE, first,
                Set.of("s1", "s2"), true, null, images()), left);
        new TutoringPipeline().run(input(StrokeLocationsStatus.COMPLETE, reordered,
                Set.of("s1", "s2"), true, null, images()), right);

        assertThat(left.calls).hasSize(4);
        assertThat(right.calls).hasSize(4);
        for (int index = 0; index < 4; index++) {
            assertThat(left.calls.get(index).inputJson()).isEqualTo(right.calls.get(index).inputJson());
            assertThat(left.calls.get(index).inputDigest()).isEqualTo(right.calls.get(index).inputDigest());
        }
    }

    @Test
    void boundingBoxOrdinalStatusCaptionAndImageOrderChangeSolveDigest() {
        List<StrokeLocation> original = List.of(location("s1", -12, 800, List.of(0, 1)));
        Input baseline = input(StrokeLocationsStatus.COMPLETE, original, Set.of("s1"), true, null, images());
        String expected = solveDigest(baseline);

        assertThat(solveDigest(input(StrokeLocationsStatus.COMPLETE,
                List.of(location("s1", -11, 800, List.of(0, 1))), Set.of("s1"), true, null, images())))
                .isNotEqualTo(expected);
        assertThat(solveDigest(input(StrokeLocationsStatus.COMPLETE,
                List.of(location("s1", -12, 800, List.of(0))), Set.of("s1"), true, null, images())))
                .isNotEqualTo(expected);
        assertThat(solveDigest(input(StrokeLocationsStatus.UNAVAILABLE, List.of(),
                Set.of("s1"), true, null, images()))).isNotEqualTo(expected);
        List<ModelImage> changedCaption = List.of(new ModelImage("image/png", new byte[] {1, 2},
                "imageOrdinal=0, x=-999"), images().get(1));
        assertThat(solveDigest(input(StrokeLocationsStatus.COMPLETE, original,
                Set.of("s1"), true, null, changedCaption))).isNotEqualTo(expected);
        List<ModelImage> swapped = List.of(images().get(1), images().get(0));
        assertThat(solveDigest(input(StrokeLocationsStatus.COMPLETE, original,
                Set.of("s1"), true, null, swapped))).isNotEqualTo(expected);
    }

    @Test
    void completeRejectsMissingDuplicateExtraOutOfRangeAndUnknownMetadata() {
        StrokeLocation one = location("s1", -12, 800, List.of(0, 1));
        assertReason(() -> input(StrokeLocationsStatus.COMPLETE, List.of(),
                Set.of("s1"), true, null, images()), Reason.INVALID_INPUT);
        assertReason(() -> input(StrokeLocationsStatus.COMPLETE, List.of(one, one),
                Set.of("s1"), true, null, images()), Reason.INVALID_INPUT);
        assertReason(() -> input(StrokeLocationsStatus.COMPLETE, List.of(one, location("extra", 4, 5, List.of(0))),
                Set.of("s1"), true, null, images()), Reason.INVALID_INPUT);
        assertReason(() -> input(StrokeLocationsStatus.COMPLETE, List.of(location("s1", 4, 5, List.of(2))),
                Set.of("s1"), true, null, images()), Reason.INVALID_INPUT);
        assertReason(() -> input(StrokeLocationsStatus.COMPLETE, List.of(one),
                Set.of("s1"), false, null, images()), Reason.INVALID_INPUT);
        assertReason(() -> input(StrokeLocationsStatus.UNAVAILABLE, List.of(one),
                Set.of("s1"), true, null, images()), Reason.INVALID_INPUT);
        assertReason(() -> input(StrokeLocationsStatus.TOO_LARGE, List.of(one),
                Set.of("s1"), true, null, images()), Reason.INVALID_INPUT);
    }

    @Test
    void locationProjectionBudgetAllows2000ButRejects2001() {
        List<StrokeLocation> locations = new ArrayList<>();
        Map<String, Boolean> ids = new HashMap<>();
        for (int index = 0; index < 2001; index++) {
            String id = "s" + index;
            ids.put(id, true);
            locations.add(location(id, -12, 2, List.of(0)));
        }
        List<StrokeLocation> allowed = List.copyOf(locations.subList(0, 2000));
        assertThat(RoleOutputValidator.JSON.writeValueAsBytes(allowed).length)
                .isLessThanOrEqualTo(MAX_LOCATION_JSON_BYTES);
        input(StrokeLocationsStatus.COMPLETE, allowed, ids.keySet().stream().filter(id -> !id.equals("s2000"))
                .collect(java.util.stream.Collectors.toSet()), true, null, images());
        assertReason(() -> input(StrokeLocationsStatus.COMPLETE, locations, ids.keySet(),
                true, null, images()), Reason.INVALID_INPUT);
    }

    @Test
    void utf8JsonBudgetAcceptsExactLimitAndRejectsOneMoreByte() {
        int fixed = jsonBytes(List.of(location("中", -12, 2, List.of(0))));
        int perChinese = jsonBytes(List.of(location("中中", -12, 2, List.of(0)))) - fixed;
        int chineseCount = 1 + (MAX_LOCATION_JSON_BYTES - fixed) / perChinese;
        String base = "中".repeat(chineseCount);
        int remainder = MAX_LOCATION_JSON_BYTES - jsonBytes(List.of(location(base, -12, 2, List.of(0))));
        String exactId = base + "a".repeat(remainder);
        StrokeLocation exact = location(exactId, -12, 2, List.of(0));
        assertThat(jsonBytes(List.of(exact))).isEqualTo(MAX_LOCATION_JSON_BYTES);
        input(StrokeLocationsStatus.COMPLETE, List.of(exact), Set.of(exactId), true, null, images());
        String tooLong = exactId + "a";
        assertThat(jsonBytes(List.of(location(tooLong, -12, 2, List.of(0)))))
                .isEqualTo(MAX_LOCATION_JSON_BYTES + 1);
        assertReason(() -> input(StrokeLocationsStatus.COMPLETE,
                List.of(location(tooLong, -12, 2, List.of(0))), Set.of(tooLong), true, null, images()),
                Reason.INVALID_INPUT);
    }

    private static Input input(StrokeLocationsStatus status, List<StrokeLocation> locations,
            Set<String> ids, boolean known, Double ocrConfidence, List<ModelImage> images) {
        return new Input("帮我检查移项", "HINT", new FrozenAnswer("frozen-file", "解方程2x+3=17", images),
                ids, ocrConfidence, known, null, status, locations);
    }

    private static List<ModelImage> images() {
        return List.of(new ModelImage("image/png", new byte[] {1, 2}, "imageOrdinal=0, x=-1000"),
                new ModelImage("image/png", new byte[] {3, 4}, "imageOrdinal=1, x=0"));
    }

    private static StrokeLocation location(String id, double x, double width, List<Integer> ordinals) {
        return new StrokeLocation(id, new WorldBounds(x, -3, width, 6), ordinals);
    }

    private static int jsonBytes(List<StrokeLocation> locations) {
        return RoleOutputValidator.JSON.writeValueAsBytes(locations).length;
    }

    private static String solveDigest(Input input) {
        StubBoundary boundary = new StubBoundary(false);
        new TutoringPipeline().run(input, boundary);
        return boundary.calls.get(0).inputDigest();
    }

    private static void assertReason(Runnable task, Reason reason) {
        assertThatThrownBy(task::run).isInstanceOf(PipelineFailure.class)
                .satisfies(error -> assertThat(((PipelineFailure) error).reason()).isEqualTo(reason));
    }

    private static final class StubBoundary implements GenerationBoundary {
        private final boolean lowOcr;
        private final String diagnosisOverride;
        private final List<Call> calls = new ArrayList<>();

        private StubBoundary(boolean lowOcr) {
            this.lowOcr = lowOcr;
            this.diagnosisOverride = null;
        }

        private StubBoundary(String diagnosisOverride) {
            this.lowOcr = false;
            this.diagnosisOverride = diagnosisOverride;
        }

        @Override
        public Reply generate(Call call) {
            calls.add(call);
            String response = switch (call.role()) {
                case SOLVE -> SOLVE;
                case DIAGNOSE -> diagnosisOverride != null ? diagnosisOverride
                    : lowOcr ? UNCERTAIN_DIAGNOSIS : OBSERVED_ERROR;
                case TEACH -> TEACH;
                case REVIEW -> REVIEW;
            };
            return new Reply(response, "stub", new Usage(1, 1, 2, false));
        }

        @Override
        public void checkActive() {
        }

        @Override
        public List<JsonNode> retrieve(List<String> methodTags) {
            return List.of();
        }

        @Override
        public boolean authorizeEvidence(Set<String> ids) {
            return ids.isEmpty();
        }
    }
}
