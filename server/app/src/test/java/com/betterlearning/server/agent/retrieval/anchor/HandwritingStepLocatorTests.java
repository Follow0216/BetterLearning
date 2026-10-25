package com.betterlearning.server.agent.retrieval.anchor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.betterlearning.server.agent.retrieval.anchor.HandwritingStepLocator.Block;
import com.betterlearning.server.agent.retrieval.anchor.HandwritingStepLocator.Bounds;
import com.betterlearning.server.agent.retrieval.anchor.HandwritingStepLocator.Locator;

import org.junit.jupiter.api.Test;

class HandwritingStepLocatorTests {
    @Test
    void locatesStepAcrossBlocksWithActualPixelDimensions() {
        Bounds step = new Bounds(-5, 2, 10, 3);
        Locator locator = HandwritingStepLocator.locate(step, List.of(
                new Block(0, "/asset/0", new Bounds(-10, 0, 10, 10), 100, 100),
                new Block(1, "/asset/1", new Bounds(0, 0, 10, 10), 200, 200)), List.of("s1"));
        assertThat(locator.segments()).hasSize(2);
        assertThat(locator.segments().get(0).regionWorldBounds()).isEqualTo(new Bounds(-5, 2, 5, 3));
        assertThat(locator.segments().get(1).pixelWidth()).isEqualTo(200);
        assertThat(locator.segments().get(1).strokeIds()).containsExactly("s1");
    }

    @Test
    void refusesGapsInvalidGeometryAndDuplicateBlocks() {
        Bounds step = new Bounds(0, 0, 10, 10);
        Block left = new Block(0, "/0", new Bounds(0, 0, 4, 10), 40, 100);
        Block right = new Block(1, "/1", new Bounds(5, 0, 5, 10), 50, 100);
        assertThatThrownBy(() -> HandwritingStepLocator.locate(step, List.of(left, right), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> HandwritingStepLocator.locate(step, List.of(left, left), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Bounds(Double.NaN, 0, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Block(0, "/0", step, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Block(0, "https://external.example/image", step, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Block(0, "/asset/../other", step, 1, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
