package com.betterlearning.server.agent.retrieval.handwriting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import com.betterlearning.server.agent.retrieval.ranking.HandwritingMethodTaxonomy;
import com.betterlearning.server.agent.service.HandwritingIndexOcr;
import org.junit.jupiter.api.Test;

class HandwritingMethodMetadataTests {
    private static final String TRANSCRIPT = "x²+2x=(x+1)²-1";

    @Test
    void canonicalCandidateBindsToExactAnchorAndRoundTripsWithoutChangingTranscript() {
        HandwritingMethodMetadata metadata = metadata("CLEAR", "CANDIDATE", "VISIBLE_STEPS");
        assertEquals(Set.of("配方法"), metadata.eligibleMethods());
        assertEquals("配方法", metadata.result().methodCandidates().get(0).method());
        HandwritingMethodMetadata parsed = HandwritingMethodMetadata.parse(metadata.encode());
        assertEquals(metadata, parsed);
        parsed.validateBindings(7L, "source", 2L, 3L, "anchor", sha(TRANSCRIPT), TRANSCRIPT);
        assertThrows(IllegalArgumentException.class, () -> parsed.validateBindings(
                8L, "source", 2L, 3L, "anchor", sha(TRANSCRIPT), TRANSCRIPT));
        assertThrows(IllegalArgumentException.class, () -> parsed.validateBindings(
                7L, "source", 2L, 4L, "anchor", sha(TRANSCRIPT), TRANSCRIPT));
        assertThrows(IllegalArgumentException.class, () -> parsed.validateBindings(
                7L, "source", 2L, 3L, "anchor", sha(TRANSCRIPT), "另一段文字"));
    }

    @Test
    void uncertainOrRejectedCandidateNeverEntersRecall() {
        assertTrue(metadata("UNCERTAIN", "UNCERTAIN", "ILLEGIBLE").eligibleMethods().isEmpty());
        assertTrue(metadata("CLEAR", "REJECTED", "NEGATED").eligibleMethods().isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> metadata("UNCERTAIN", "CANDIDATE", "VISIBLE_STEPS"));
    }

    @Test
    void strictJsonAndHashRejectUnboundOrExtraMetadata() {
        HandwritingMethodMetadata metadata = metadata("CLEAR", "CANDIDATE", "VISIBLE_STEPS");
        String encoded = metadata.encode();
        assertThrows(IllegalArgumentException.class,
                () -> HandwritingMethodMetadata.parse(encoded.replace(
                        "\"formatVersion\":1", "\"formatVersion\":1,\"extra\":1")));
        assertThrows(IllegalArgumentException.class,
                () -> HandwritingMethodMetadata.parse(encoded.replace(
                        "\"formatVersion\":1", "\"formatVersion\":1,\"formatVersion\":1")));
        assertThrows(IllegalArgumentException.class,
                () -> HandwritingMethodMetadata.parse(encoded + "{}"));
        assertThrows(IllegalArgumentException.class, () -> new HandwritingMethodMetadata(
                HandwritingMethodMetadata.FORMAT_VERSION, 7L, "source", 2L, 3L, "anchor",
                "0".repeat(64), "a".repeat(64), 100, 80, "mock-ocr", metadata.result()));
    }

    @Test
    void taxonomySeparatesActualMethodsFromTaskNamesWithoutChangingLegacyQueryVocabulary() {
        assertEquals("math-methods-v1", HandwritingMethodTaxonomy.VERSION);
        assertEquals(12, HandwritingMethodTaxonomy.methods().size());
        assertEquals("配方法", HandwritingMethodTaxonomy.canonicalMethod("配方").orElseThrow());
        assertEquals("链式法则", HandwritingMethodTaxonomy.canonicalMethod("复合函数求导").orElseThrow());
        assertTrue(HandwritingMethodTaxonomy.canonicalMethod("一元一次方程").isEmpty());
        assertTrue(HandwritingMethodTaxonomy.canonicalMethod("二次函数最值").isEmpty());
        assertTrue(HandwritingMethodTaxonomy.canonicalMethod("未知方法").isEmpty());
    }

    private static HandwritingMethodMetadata metadata(String uncertainty, String state, String reason) {
        var candidate = new HandwritingIndexOcr.MethodCandidate("配方", 0,
                TRANSCRIPT.length(), new HandwritingIndexOcr.Region(1, 2, 20, 10), state, reason);
        var result = new HandwritingIndexOcr.Result(HandwritingIndexOcr.SCHEMA_VERSION,
                HandwritingMethodTaxonomy.VERSION, 0, TRANSCRIPT, uncertainty, List.of(candidate));
        return new HandwritingMethodMetadata(HandwritingMethodMetadata.FORMAT_VERSION,
                7L, "source", 2L, 3L, "anchor", sha(TRANSCRIPT), "a".repeat(64),
                100, 80, "mock-ocr", result);
    }

    private static String sha(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
