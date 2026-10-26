package com.betterlearning.server.agent.pipeline;

import static com.betterlearning.server.agent.pipeline.TutoringPipelineTests.EVIDENCE;
import static com.betterlearning.server.agent.pipeline.TutoringPipelineTests.REVIEW;
import static com.betterlearning.server.agent.pipeline.TutoringPipelineTests.TEACH;
import static com.betterlearning.server.agent.pipeline.TutoringPipelineTests.assertReason;
import static com.betterlearning.server.agent.pipeline.TutoringPipelineTests.input;
import static com.betterlearning.server.agent.pipeline.TutoringPipelineTests.normal;
import static com.betterlearning.server.agent.pipeline.TutoringPipelineTests.rejected;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.betterlearning.server.agent.pipeline.RoleProtocol.Call;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Reason;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Reply;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Role;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** TEACH返工只接收已校验前稿；独立求解与诊断继续隔离历史资料。 */
class TeachRepairContextTests {
    private static final String UNSUPPORTED = "46d4b4f5-b8c2-44a4-a20e-29dfc568d885";
    private static final String HISTORY_CLAIM = "同类方程可先核对等式两边的相同运算。";

    @Test
    void teachRepairReceivesValidatedPreviousCandidateAllowedEvidenceAndIssueThenKeepsCitation() {
        String firstDraft = citedTeach(EVIDENCE, "先核对等式两边是否用了相同运算。");
        String repaired = citedTeach(EVIDENCE, "检查移项时等式两边是否进行了相同运算。");
        TutoringPipelineTests.Boundary boundary = new TutoringPipelineTests.Boundary(call -> {
            if (call.role() == Role.TEACH) {
                return call.sequence() == 3 ? firstDraft : repaired;
            }
            return call.role() == Role.REVIEW && call.sequence() == 4 ? hintLeakReview() : normal(call);
        });
        boundary.evidence = List.of(historyEvidence());

        var candidate = new TutoringPipeline().run(input(null, Set.of(), null), boundary);

        assertThat(RoleProtocol.PROMPT_VERSION).isEqualTo("p3-roles-v9-answer-input");
        assertThat(candidate.evidenceIds()).containsExactly(EVIDENCE);
        assertThat(candidate.bodyMarkdown()).contains("[[evidence:" + EVIDENCE + "]]");
        assertThat(boundary.retrieveCount).isEqualTo(1);
        assertThat(boundary.authorizeCount).isEqualTo(3);
        assertThat(boundary.calls).extracting(Call::role).containsExactly(
                Role.SOLVE, Role.DIAGNOSE, Role.TEACH, Role.REVIEW, Role.TEACH, Role.REVIEW);
        JsonNode repairContext = context(boundary.calls.get(4));
        assertThat(repairContext.path("repair").path("previousCandidate"))
                .isEqualTo(RoleOutputValidator.JSON.readTree(firstDraft));
        assertThat(repairContext.path("repair").path("previousCandidate").path("hint").asString())
                .contains("[[evidence:" + EVIDENCE + "]]");
        assertThat(repairContext.path("repair").path("previousCandidate")
                .path("evidenceIds").get(0).asString()).isEqualTo(EVIDENCE);
        assertThat(repairContext.path("repair").path("reviewIssues").size()).isEqualTo(1);
        JsonNode issue = repairContext.path("repair").path("reviewIssues").get(0);
        assertThat(issue.path("target").asString()).isEqualTo("TEACH");
        assertThat(issue.path("code").asString()).isEqualTo("hint_leaks_method");
        assertThat(repairContext.path("repair").path("programViolations")).isEmpty();
        assertThat(repairContext.path("allowedEvidence").get(0).path("evidenceId").asString())
                .isEqualTo(EVIDENCE);
        assertThat(repairContext.path("allowedEvidence").get(0).path("claim").asString())
                .isEqualTo(HISTORY_CLAIM);
    }

    @Test
    void unsupportedEvidenceIdInPreviousAndRepeatedTeachCannotPublish() {
        String unsupported = citedTeach(UNSUPPORTED, "检查等式两边的运算。");
        TutoringPipelineTests.Boundary boundary = new TutoringPipelineTests.Boundary(call -> {
            if (call.role() == Role.TEACH) {
                return unsupported;
            }
            return normal(call); // REVIEW可以批准；程序仍必须拒绝无支持的引用。
        });
        boundary.evidence = List.of(historyEvidence());

        assertReason(() -> new TutoringPipeline().run(input(null, Set.of(), null), boundary),
                Reason.REVIEW_REJECTED);

        assertThat(boundary.calls).extracting(Call::role).containsExactly(
                Role.SOLVE, Role.DIAGNOSE, Role.TEACH, Role.REVIEW, Role.TEACH, Role.REVIEW);
        JsonNode repairContext = context(boundary.calls.get(4));
        assertThat(repairContext.path("repair").path("previousCandidate").path("evidenceIds")
                .get(0).asString()).isEqualTo(UNSUPPORTED);
        assertThat(repairContext.path("repair").path("programViolations").toString())
                .contains("UNKNOWN_EVIDENCE_ID");
        assertThat(repairContext.path("allowedEvidence").get(0).path("evidenceId").asString())
                .isEqualTo(EVIDENCE);
    }

    @Test
    void unsupportedPreviousIdCanBeCorrectedWithoutAuthorizingTheHallucinatedId() {
        String firstDraft = citedTeach(UNSUPPORTED, "先核对等式两边的运算。");
        String repaired = citedTeach(EVIDENCE, "对照条件核对两侧运算。");
        TutoringPipelineTests.Boundary delegate = new TutoringPipelineTests.Boundary(call ->
                call.role() == Role.TEACH && call.sequence() == 3 ? firstDraft
                : call.role() == Role.TEACH ? repaired : normal(call));
        delegate.evidence = List.of(historyEvidence());
        List<Set<String>> authorizationSets = new ArrayList<>();
        GenerationBoundary boundary = new GenerationBoundary() {
            @Override
            public Reply generate(Call call) {
                return delegate.generate(call);
            }

            @Override
            public void checkActive() {
                delegate.checkActive();
            }

            @Override
            public List<JsonNode> retrieve(List<String> tags) {
                return delegate.retrieve(tags);
            }

            @Override
            public boolean authorizeEvidence(Set<String> ids) {
                authorizationSets.add(Set.copyOf(ids));
                return !ids.contains(UNSUPPORTED) && delegate.authorizeEvidence(ids);
            }
        };

        var candidate = new TutoringPipeline().run(input(null, Set.of(), null), boundary);

        assertThat(candidate.evidenceIds()).containsExactly(EVIDENCE);
        assertThat(delegate.calls).extracting(Call::role).containsExactly(
                Role.SOLVE, Role.DIAGNOSE, Role.TEACH, Role.REVIEW, Role.TEACH, Role.REVIEW);
        assertThat(authorizationSets).containsExactly(Set.of(EVIDENCE), Set.of(EVIDENCE), Set.of(EVIDENCE));
        JsonNode repair = context(delegate.calls.get(4)).path("repair");
        assertThat(repair.path("previousCandidate").path("evidenceIds").get(0).asString())
                .isEqualTo(UNSUPPORTED);
        assertThat(repair.path("programViolations").toString()).contains("UNKNOWN_EVIDENCE_ID");
    }

    @Test
    void teachRepairMayDropUnusedCitationAndPersonalRecordClaim() {
        String firstDraft = citedTeach(EVIDENCE, "旧个人记录可作提醒。");
        TutoringPipelineTests.Boundary boundary = new TutoringPipelineTests.Boundary(call -> {
            if (call.role() == Role.TEACH) {
                return call.sequence() == 3 ? firstDraft : TEACH;
            }
            return call.role() == Role.REVIEW && call.sequence() == 4 ? hintLeakReview() : normal(call);
        });
        boundary.evidence = List.of(RoleOutputValidator.JSON.readTree("{\"evidenceId\":\"" + EVIDENCE
                + "\",\"claim\":\"我以前把x=99误记为本题答案。\",\"sourceType\":\"SAVED_ANSWER\"}"));

        var candidate = new TutoringPipeline().run(input(null, Set.of(), null), boundary);

        assertThat(candidate.evidenceIds()).isEmpty();
        assertThat(candidate.bodyMarkdown()).doesNotContain("[[evidence:", "x=99", "旧个人记录");
        assertThat(context(boundary.calls.get(4)).path("repair").path("previousCandidate")
                .path("evidenceIds").get(0).asString()).isEqualTo(EVIDENCE);
        assertThat(boundary.calls).extracting(Call::role).containsExactly(
                Role.SOLVE, Role.DIAGNOSE, Role.TEACH, Role.REVIEW, Role.TEACH, Role.REVIEW);
    }

    @Test
    void citedDraftCannotPassWhenReviewSaysCitationUnsupportedTwice() {
        String cited = citedTeach(EVIDENCE, "先核对等式两边的运算。");
        TutoringPipelineTests.Boundary boundary = new TutoringPipelineTests.Boundary(call ->
                call.role() == Role.TEACH ? cited
                : call.role() == Role.REVIEW ? citationRejectedReview() : normal(call));
        boundary.evidence = List.of(historyEvidence());

        assertReason(() -> new TutoringPipeline().run(input(null, Set.of(), null), boundary),
                Reason.REVIEW_REJECTED);

        assertThat(boundary.calls).extracting(Call::role).containsExactly(
                Role.SOLVE, Role.DIAGNOSE, Role.TEACH, Role.REVIEW, Role.TEACH, Role.REVIEW);
        assertThat(context(boundary.calls.get(4)).path("repair").path("reviewIssues")
                .get(0).path("code").asString()).isEqualTo("citation_unsupported");
    }

    @Test
    void revokedEvidenceBlocksTeachRepairBeforeAnotherGeneration() {
        String firstDraft = citedTeach(EVIDENCE, "先核对等式两边的运算。");
        TutoringPipelineTests.Boundary boundary = new TutoringPipelineTests.Boundary(call ->
                call.role() == Role.TEACH ? firstDraft
                : call.role() == Role.REVIEW ? rejected("TEACH") : normal(call));
        boundary.evidence = List.of(historyEvidence());
        boundary.revokeAfterAuthorization = 1;

        assertReason(() -> new TutoringPipeline().run(input(null, Set.of(), null), boundary),
                Reason.EVIDENCE_REJECTED);

        assertThat(boundary.calls).extracting(Call::role)
                .containsExactly(Role.SOLVE, Role.DIAGNOSE, Role.TEACH, Role.REVIEW);
        assertThat(boundary.authorizeCount).isEqualTo(2);
    }

    @Test
    void revocationAfterRepairedTeachBlocksFinalPublication() {
        String cited = citedTeach(EVIDENCE, "先核对等式两边的运算。");
        TutoringPipelineTests.Boundary boundary = new TutoringPipelineTests.Boundary(call ->
                call.role() == Role.TEACH ? cited
                : call.role() == Role.REVIEW && call.sequence() == 4 ? hintLeakReview() : normal(call));
        boundary.evidence = List.of(historyEvidence());
        boundary.revokeAfterAuthorization = 2;

        assertReason(() -> new TutoringPipeline().run(input(null, Set.of(), null), boundary),
                Reason.EVIDENCE_REJECTED);

        assertThat(boundary.calls).extracting(Call::role).containsExactly(
                Role.SOLVE, Role.DIAGNOSE, Role.TEACH, Role.REVIEW, Role.TEACH, Role.REVIEW);
        assertThat(boundary.authorizeCount).isEqualTo(3);
    }

    @Test
    void secondTeachReviewRejectionStopsAfterSixGenerations() {
        TutoringPipelineTests.Boundary boundary = new TutoringPipelineTests.Boundary(call ->
                call.role() == Role.REVIEW ? rejected("TEACH") : normal(call));

        assertReason(() -> new TutoringPipeline().run(input(null, Set.of(), null), boundary),
                Reason.REVIEW_REJECTED);

        assertThat(boundary.calls).extracting(Call::role).containsExactly(
                Role.SOLVE, Role.DIAGNOSE, Role.TEACH, Role.REVIEW, Role.TEACH, Role.REVIEW);
        assertThat(context(boundary.calls.get(4)).path("repair").path("previousCandidate"))
                .isEqualTo(RoleOutputValidator.JSON.readTree(TEACH));
    }

    @Test
    void solveAndDiagnoseRepairsNeverReceivePreviousCandidateOrHistoricalClaim() {
        for (Role target : List.of(Role.SOLVE, Role.DIAGNOSE)) {
            String firstDraft = citedTeach(EVIDENCE, "先核对等式两边的运算。");
            TutoringPipelineTests.Boundary boundary = new TutoringPipelineTests.Boundary(call -> {
                if (call.role() == Role.TEACH && call.sequence() == 3) {
                    return firstDraft;
                }
                if (call.role() == Role.REVIEW && call.sequence() == 4) {
                    return rejected(target.name());
                }
                if (target == Role.SOLVE && call.role() == Role.SOLVE && call.sequence() == 5) {
                    return TutoringPipelineTests.SOLVE.replace("用等式性质", "改用逆运算并代入校验")
                            .replace("一元一次方程", "逆运算校验")
                            .replace("x=7", "x=7（代入原方程成立）");
                }
                return normal(call);
            });
            boundary.evidence = List.of(historyEvidence());

            var candidate = new TutoringPipeline().run(input(null, Set.of(), null), boundary);

            assertThat(candidate.evidenceIds()).isEmpty();
            assertThat(boundary.retrieveCount).isEqualTo(1);
            for (Call call : boundary.calls) {
                if (call.role() == Role.SOLVE || call.role() == Role.DIAGNOSE) {
                    assertThat(call.inputJson()).doesNotContain(HISTORY_CLAIM, "previousCandidate",
                            "allowedEvidence", "[[evidence:");
                }
            }
            int repairedTeachIndex = target == Role.SOLVE ? 6 : 5;
            JsonNode repairedContext = context(boundary.calls.get(repairedTeachIndex));
            assertThat(repairedContext.path("repair").has("previousCandidate")).isFalse();
            assertThat(repairedContext.path("allowedEvidence").size())
                    .isEqualTo(target == Role.SOLVE ? 0 : 1);
            if (target == Role.SOLVE) {
                assertThat(candidate.solve().path("methodTags").get(0).asString())
                        .isEqualTo("逆运算校验");
                assertThat(candidate.solve().path("answer").asString())
                        .isEqualTo("x=7（代入原方程成立）");
                assertThat(repairedContext.path("solve").path("methodTags").get(0).asString())
                        .isEqualTo("逆运算校验");
            }
            assertThat(context(boundary.calls.get(4)).path("repair").path("reason").asString())
                    .isEqualTo("INDEPENDENT_RECHECK_REQUIRED");
        }
    }

    @Test
    void fullSolutionMaximumFieldsAndThreeMarkersReachTeachRepairWithoutTruncation() {
        List<String> ids = List.of(EVIDENCE, "00000000-0000-0000-0000-000000000002",
                "00000000-0000-0000-0000-000000000003");
        String markers = ids.stream().map(id -> "[[evidence:" + id + "]]")
                .collect(java.util.stream.Collectors.joining());
        String hint = "甲".repeat(1600 - markers.length()) + markers;
        String nextStep = "乙".repeat(1600);
        String clarification = "丙".repeat(1600);
        String fullSolution = "丁".repeat(12000);
        ObjectNode draftNode = (ObjectNode) RoleOutputValidator.JSON.readTree(TEACH);
        draftNode.put("hint", hint);
        draftNode.put("nextStep", nextStep);
        draftNode.put("clarification", clarification);
        draftNode.put("fullSolution", fullSolution);
        draftNode.set("evidenceIds", RoleOutputValidator.JSON.valueToTree(ids));
        String firstDraft = draftNode.toString();
        ObjectNode small = (ObjectNode) RoleOutputValidator.JSON.readTree(TEACH);
        small.put("fullSolution", "x=7，代入原方程成立。");
        String repaired = small.toString();
        TutoringPipelineTests.Boundary boundary = new TutoringPipelineTests.Boundary(call -> {
            if (call.role() == Role.TEACH) {
                return call.sequence() == 3 ? firstDraft : repaired;
            }
            return call.role() == Role.REVIEW && call.sequence() == 4 ? hintLeakReview() : normal(call);
        });
        boundary.evidence = ids.stream().map(id -> (JsonNode) RoleOutputValidator.JSON.valueToTree(
                java.util.Map.of("evidenceId", id, "claim", "同类题应检查等式性质"))).toList();

        var candidate = new TutoringPipeline().run(input("FULL_SOLUTION", Set.of(), null), boundary);

        assertThat(candidate.evidenceIds()).isEmpty();
        assertThat(candidate.bodyMarkdown()).contains("x=7，代入原方程成立。");
        assertThat(boundary.calls).extracting(Call::role).containsExactly(
                Role.SOLVE, Role.DIAGNOSE, Role.TEACH, Role.REVIEW, Role.TEACH, Role.REVIEW);
        JsonNode repairContext = context(boundary.calls.get(4));
        JsonNode previous = repairContext.path("repair").path("previousCandidate");
        assertThat(previous).isEqualTo(RoleOutputValidator.JSON.readTree(firstDraft));
        assertThat(previous.path("hint").asString()).isEqualTo(hint).hasSize(1600);
        assertThat(previous.path("nextStep").asString()).isEqualTo(nextStep).hasSize(1600);
        assertThat(previous.path("clarification").asString()).isEqualTo(clarification).hasSize(1600);
        assertThat(previous.path("fullSolution").asString()).isEqualTo(fullSolution).hasSize(12000);
        assertThat(previous.path("evidenceIds").size()).isEqualTo(3);
        assertThat(repairContext.path("allowedEvidence").size()).isEqualTo(3);
        int utf8Bytes = RoleOutputValidator.JSON.writeValueAsBytes(previous).length;
        assertThat(utf8Bytes).isEqualTo(50301).isLessThanOrEqualTo(101556);
        assertThat(firstDraft.getBytes(StandardCharsets.UTF_8).length).isEqualTo(utf8Bytes);
    }

    @Test
    void previousCandidateAloneChangesRepairedTeachInputDigest() {
        Call first = repairedTeachCallAfter("先检查左侧常数项。");
        Call second = repairedTeachCallAfter("先核对右侧常数项。");
        ObjectNode firstEnvelope = (ObjectNode) RoleOutputValidator.JSON.readTree(first.inputJson());
        ObjectNode secondEnvelope = (ObjectNode) RoleOutputValidator.JSON.readTree(second.inputJson());
        ObjectNode firstRepair = (ObjectNode) firstEnvelope.path("context").path("repair");
        ObjectNode secondRepair = (ObjectNode) secondEnvelope.path("context").path("repair");
        JsonNode firstPrevious = firstRepair.path("previousCandidate").deepCopy();
        JsonNode secondPrevious = secondRepair.path("previousCandidate").deepCopy();
        assertThat(firstPrevious.path("hint").asString()).isNotEqualTo(secondPrevious.path("hint").asString());
        ((ObjectNode) firstPrevious).remove("hint");
        ((ObjectNode) secondPrevious).remove("hint");
        assertThat(firstPrevious).isEqualTo(secondPrevious);
        firstRepair.remove("previousCandidate");
        secondRepair.remove("previousCandidate");
        assertThat(firstEnvelope).isEqualTo(secondEnvelope);
        assertThat(first.inputDigest()).isNotEqualTo(second.inputDigest());
    }

    private static Call repairedTeachCallAfter(String initialHint) {
        ObjectNode first = (ObjectNode) RoleOutputValidator.JSON.readTree(TEACH);
        first.put("hint", initialHint);
        TutoringPipelineTests.Boundary boundary = new TutoringPipelineTests.Boundary(call -> {
            if (call.role() == Role.TEACH) {
                return call.sequence() == 3 ? first.toString() : TEACH;
            }
            return call.role() == Role.REVIEW && call.sequence() == 4 ? hintLeakReview() : normal(call);
        });
        new TutoringPipeline().run(input(null, Set.of(), null), boundary);
        assertThat(boundary.calls).extracting(Call::role).containsExactly(
                Role.SOLVE, Role.DIAGNOSE, Role.TEACH, Role.REVIEW, Role.TEACH, Role.REVIEW);
        return boundary.calls.get(4);
    }

    private static String hintLeakReview() {
        return REVIEW.replace("\"approved\":true", "\"approved\":false")
                .replace("\"hintSafe\":true", "\"hintSafe\":false")
                .replace("\"issues\":[]", "\"issues\":[{\"target\":\"TEACH\","
                        + "\"code\":\"hint_leaks_method\",\"detail\":\"提示泄露方法，请修订\"}]");
    }

    private static String citationRejectedReview() {
        return REVIEW.replace("\"approved\":true", "\"approved\":false")
                .replace("\"citationsSupported\":true", "\"citationsSupported\":false")
                .replace("\"issues\":[]", "\"issues\":[{\"target\":\"TEACH\","
                        + "\"code\":\"citation_unsupported\",\"detail\":\"引用不支持主张\"}]");
    }

    private static JsonNode historyEvidence() {
        return RoleOutputValidator.JSON.readTree("{\"evidenceId\":\"" + EVIDENCE
                + "\",\"claim\":\"" + HISTORY_CLAIM + "\",\"sourceType\":\"NOTE\"}");
    }

    private static String citedTeach(String evidenceId, String hint) {
        var node = (tools.jackson.databind.node.ObjectNode) RoleOutputValidator.JSON.readTree(TEACH);
        node.put("hint", hint + "[[evidence:" + evidenceId + "]]");
        node.set("evidenceIds", RoleOutputValidator.JSON.valueToTree(List.of(evidenceId)));
        return node.toString();
    }

    private static JsonNode context(Call call) {
        return RoleOutputValidator.JSON.readTree(call.inputJson()).path("context");
    }
}
