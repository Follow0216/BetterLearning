package com.betterlearning.server.agent.retrieval.handwriting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Collections;
import java.util.List;

import com.betterlearning.server.agent.service.HandwritingIndexOcr;
import com.betterlearning.server.agent.service.HandwritingIndexOcr.MethodCandidate;
import com.betterlearning.server.agent.service.HandwritingIndexOcr.Region;
import com.betterlearning.server.agent.service.HandwritingIndexOcr.Result;

import org.junit.jupiter.api.Test;

class HandwritingOcrValidatorTests {
    @Test
    void preservesTranscriptAndCanonicalizesOnlyControlledMethodName() {
        String transcript = "先 配方：x²+2x=(x+1)²-1\n结论保留  ";
        Result result = HandwritingOcrValidator.validate(new Result(HandwritingIndexOcr.SCHEMA_VERSION,
                "math-methods-v1", 2, transcript, "CLEAR", List.of(new MethodCandidate("配方", 2, 4,
                        new Region(1, 2, 30, 20), "CANDIDATE", "VISIBLE_STEPS"))), 2, 80, 40);
        assertThat(result.transcript()).isEqualTo(transcript);
        assertThat(result.methodCandidates().get(0).method()).isEqualTo("配方法");
        assertThat(HandwritingOcrValidator.parse(HandwritingOcrValidator.encode(result), 2, 80, 40))
                .isEqualTo(result);
    }

    @Test
    void acceptsFormulaOnlyAndUncertainOrRejectedObservationsWithoutInventingMethod() {
        Result formula = new Result(HandwritingIndexOcr.SCHEMA_VERSION, "math-methods-v1", 0,
                "$$x^2+2x=(x+1)^2-1$$", "UNCERTAIN", List.of());
        assertThat(HandwritingOcrValidator.validate(formula, 0, 20, 20)).isEqualTo(formula);
        Result negative = new Result(HandwritingIndexOcr.SCHEMA_VERSION, "math-methods-v1", 1,
                "这里不是换元法", "CLEAR", List.of(new MethodCandidate("换元法", 4, 7,
                        new Region(0, 0, 20, 20), "REJECTED", "NEGATED")));
        assertThat(HandwritingOcrValidator.validate(negative, 1, 20, 20)).isEqualTo(negative);
        Result uncertain = new Result(HandwritingIndexOcr.SCHEMA_VERSION, "math-methods-v1", 3,
                "可能用了归纳法？", "UNCERTAIN", List.of(new MethodCandidate("归纳法", 4, 7,
                        new Region(1, 1, 3, 3), "UNCERTAIN", "INCOMPLETE_STEPS")));
        assertThat(HandwritingOcrValidator.validate(uncertain, 3, 20, 20)
                .methodCandidates().get(0).method()).isEqualTo("数学归纳法");
    }

    @Test
    void candidateProtocolRepresentsFormulaTransformationsAcrossDomainsWithoutEditingThem() {
        // Synthetic protocol fixtures, not human labels or a method-recognition quality score.
        List<String> formulas = List.of("x^2+2x=(x+1)^2-1", "(sin(x^2))'=cos(x^2)·2x",
                "AB/DE=BC/EF=AC/DF", "P(1);P(k)⇒P(k+1)");
        List<String> methods = List.of("配方法", "链式法则", "相似三角形", "数学归纳法");
        for (int index = 0; index < formulas.size(); index++) {
            String formula = formulas.get(index);
            Result proposed = new Result(HandwritingIndexOcr.SCHEMA_VERSION, "math-methods-v1",
                    index, formula, "CLEAR", List.of(new MethodCandidate(methods.get(index),
                    0, formula.length(), new Region(0, 0, 20, 20), "CANDIDATE", "VISIBLE_STEPS")));
            Result checked = HandwritingOcrValidator.parse(HandwritingOcrValidator.encode(proposed),
                    index, 20, 20);
            assertThat(checked.transcript()).isEqualTo(formula);
            assertThat(checked.methodCandidates().get(0).method()).isEqualTo(methods.get(index));
        }
        Result incorrect = new Result(HandwritingIndexOcr.SCHEMA_VERSION, "math-methods-v1", 0,
                "x^2+2x=(x+1)^2", "CLEAR", List.of(new MethodCandidate("配方法", 0, 13,
                new Region(0, 0, 20, 20), "REJECTED", "CONTRADICTORY")));
        assertThat(HandwritingOcrValidator.validate(incorrect, 0, 20, 20)).isEqualTo(incorrect);
    }

    @Test
    void rejectsUncontrolledTopicsDuplicateMethodsAndUnsupportedCandidateClaims() {
        assertInvalid(candidate("一元一次方程", 0, 2, new Region(0, 0, 1, 1),
                "CANDIDATE", "VISIBLE_STEPS"), "配方", "CLEAR");
        assertInvalid(candidate("柯西施瓦茨不等式", 0, 2, new Region(0, 0, 1, 1),
                "CANDIDATE", "VISIBLE_STEPS"), "配方", "CLEAR");
        assertThatThrownBy(() -> HandwritingOcrValidator.validate(new Result(
                HandwritingIndexOcr.SCHEMA_VERSION, "math-methods-v1", 0, "配方法", "CLEAR",
                List.of(candidate("配方", 0, 2, new Region(0, 0, 1, 1), "CANDIDATE", "VISIBLE_STEPS"),
                        candidate("配方法", 0, 3, new Region(1, 1, 1, 1), "CANDIDATE", "VISIBLE_STEPS"))),
                0, 10, 10)).isInstanceOf(IllegalArgumentException.class);
        assertInvalid(candidate("配方法", 0, 2, new Region(0, 0, 1, 1),
                "CANDIDATE", "VISIBLE_STEPS"), "配方", "UNCERTAIN");
        assertInvalid(candidate("配方法", 0, 2, new Region(0, 0, 1, 1),
                "CANDIDATE", "NEGATED"), "配方", "CLEAR");
        MethodCandidate repeated = candidate("配方法", 0, 2, new Region(0, 0, 1, 1),
                "CANDIDATE", "VISIBLE_STEPS");
        assertThatThrownBy(() -> HandwritingOcrValidator.validate(new Result(
                HandwritingIndexOcr.SCHEMA_VERSION, "math-methods-v1", 0, "配方", "CLEAR",
                Collections.nCopies(9, repeated)), 0, 10, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidRegionsSpansUnicodeAndOversizedTranscript() {
        assertInvalid(candidate("配方法", 0, 2, new Region(9, 0, 2, 1),
                "CANDIDATE", "VISIBLE_STEPS"), "配方", "CLEAR");
        assertInvalid(candidate("配方法", 0, 2, new Region(0, 0, 0, 1),
                "CANDIDATE", "VISIBLE_STEPS"), "配方", "CLEAR");
        assertInvalid(candidate("配方法", 1, 3, new Region(0, 0, 1, 1),
                "CANDIDATE", "VISIBLE_STEPS"), "😀配方", "CLEAR");
        for (String transcript : List.of("x\ry", "x\ud83d", "x".repeat(8193))) {
            assertThatThrownBy(() -> HandwritingOcrValidator.validate(new Result(
                    HandwritingIndexOcr.SCHEMA_VERSION, "math-methods-v1", 0, transcript,
                    "CLEAR", List.of()), 0, 10, 10)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void rejectsDuplicateExtraMalformedAndMismatchedJson() {
        String valid = HandwritingOcrValidator.encode(new Result(HandwritingIndexOcr.SCHEMA_VERSION,
                "math-methods-v1", 0, "配方", "CLEAR", List.of(candidate("配方法", 0, 2,
                        new Region(0, 0, 2, 2), "CANDIDATE", "VISIBLE_STEPS"))));
        assertThat(HandwritingOcrValidator.parse(valid, 0, 10, 10).transcript()).isEqualTo("配方");
        for (String invalid : List.of(
                valid.replace("\"stepOrdinal\":0", "\"stepOrdinal\":0,\"stepOrdinal\":0"),
                valid.replace("\"stepOrdinal\":0", "\"stepOrdinal\":\"0\""),
                valid.replace("\"taxonomyVersion\":\"math-methods-v1\"",
                        "\"taxonomyVersion\":\"math-methods-v1\",\"confidence\":0.99"),
                valid.replace("\"height\":2", "\"height\":2,\"confidence\":0.99"),
                valid.replace("\"schemaVersion\":\"handwriting-ocr-v1\"",
                        "\"schemaVersion\":\"handwriting-ocr-v2\""),
                valid + "{}")) {
            assertThatThrownBy(() -> HandwritingOcrValidator.parse(invalid, 0, 10, 10))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> HandwritingOcrValidator.parse(valid, 1, 10, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static MethodCandidate candidate(String method, int start, int end, Region region,
            String state, String reason) {
        return new MethodCandidate(method, start, end, region, state, reason);
    }

    private static void assertInvalid(MethodCandidate candidate, String transcript, String uncertainty) {
        assertThatThrownBy(() -> HandwritingOcrValidator.validate(new Result(
                HandwritingIndexOcr.SCHEMA_VERSION, "math-methods-v1", 0, transcript,
                uncertainty, List.of(candidate)), 0, 10, 10))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
