package com.betterlearning.server.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.betterlearning.server.multiagent.MultiAgentModels.Anchor;

import org.junit.jupiter.api.Test;

class IndexStagePayloadsTests {
    @Test
    void encodedBatchPinsOrderedAnchorsAndFloat32Vectors() {
        Anchor first = anchor("one", 0, "第一步");
        Anchor second = anchor("two", 1, "第二步");
        String encoded = IndexStagePayloads.encodeEmbedding(List.of(first, second),
                List.of(new double[] {0.1, 0.2}, new double[] {0.3, 0.4}), 2);

        var decoded = IndexStagePayloads.decodeEmbedding(encoded, 2);

        assertThat(decoded.inputs()).containsExactly(IndexStagePayloads.input(first),
                IndexStagePayloads.input(second));
        assertThat(decoded.vectors()).hasSize(2);
        assertThat(decoded.vectors().get(0)).containsExactly((double) 0.1f, (double) 0.2f);
        assertThat(IndexStagePayloads.inputDigest(List.of(first, second)))
                .isNotEqualTo(IndexStagePayloads.inputDigest(List.of(second, first)));
    }

    @Test
    void malformedSchemaAndZeroVectorFailClosed() {
        Anchor anchor = anchor("one", 0, "第一步");
        assertThatThrownBy(() -> IndexStagePayloads.encodeEmbedding(List.of(anchor),
                List.of(new double[] {0, 0}), 2)).isInstanceOf(IllegalArgumentException.class);
        String valid = IndexStagePayloads.encodeEmbedding(List.of(anchor),
                List.of(new double[] {1, 0}), 2);
        assertThatThrownBy(() -> IndexStagePayloads.decodeEmbedding(
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"), 2))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IndexStagePayloads.decodeEmbedding(valid, 3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Anchor anchor(String id, int ordinal, String text) {
        return new Anchor(id, "source", 1, "HANDWRITING_STEP", ordinal,
                IndexStagePayloads.sha256(text), "{\"ordinal\":" + ordinal + "}", text);
    }
}
