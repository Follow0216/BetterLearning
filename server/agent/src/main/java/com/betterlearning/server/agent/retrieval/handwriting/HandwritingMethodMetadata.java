package com.betterlearning.server.agent.retrieval.handwriting;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

import com.betterlearning.server.agent.retrieval.ranking.ChineseMathTerms;
import com.betterlearning.server.agent.retrieval.ranking.HandwritingMethodTaxonomy;
import com.betterlearning.server.agent.service.HandwritingIndexOcr;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Bound, versioned OCR method hypotheses. These are indexing hints, not the
 * anchor text, a mathematical proof, or a citation claim.
 */
public record HandwritingMethodMetadata(int formatVersion, long accountId, String sourceId,
        long sourceVersion, long indexRevision, String anchorId, String anchorSha256,
        String imageSha256, int imageWidth, int imageHeight, String model,
        HandwritingIndexOcr.Result result) {
    public static final int FORMAT_VERSION = 1;
    private static final int MAX_JSON_CHARS = 32768;
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    public HandwritingMethodMetadata {
        if (formatVersion != FORMAT_VERSION || accountId < 1 || sourceVersion < 1
                || indexRevision < 1 || !identifier(sourceId) || !identifier(anchorId)
                || !sha(anchorSha256) || !sha(imageSha256)
                || imageWidth < 1 || imageHeight < 1
                || model == null || model.isBlank() || model.length() > 256
                || result == null) {
            throw new IllegalArgumentException("Invalid handwriting method metadata");
        }
        result = HandwritingOcrValidator.validate(result, result.stepOrdinal(), imageWidth, imageHeight);
        if (!HandwritingMethodTaxonomy.VERSION.equals(result.taxonomyVersion())
                || !anchorSha256.equals(sha256(result.transcript()))) {
            throw new IllegalArgumentException("Handwriting method metadata does not match anchor text");
        }
    }

    public String encode() {
        return JSON.writeValueAsString(this);
    }

    /** Reject duplicate/extra fields, type coercion, and trailing JSON before binding. */
    public static HandwritingMethodMetadata parse(String raw) {
        if (raw == null || raw.length() > MAX_JSON_CHARS) {
            throw new IllegalArgumentException("Invalid handwriting method metadata JSON");
        }
        try {
            JsonNode root = JSON.readTree(raw);
            fields(root, "formatVersion", "accountId", "sourceId", "sourceVersion",
                    "indexRevision", "anchorId", "anchorSha256", "imageSha256",
                    "imageWidth", "imageHeight", "model", "result");
            number(root, "formatVersion", false);
            number(root, "accountId", true);
            number(root, "sourceVersion", true);
            number(root, "indexRevision", true);
            number(root, "imageWidth", false);
            number(root, "imageHeight", false);
            for (String key : Set.of("sourceId", "anchorId", "anchorSha256", "imageSha256", "model")) {
                if (!root.get(key).isString()) {
                    throw new IllegalArgumentException("Invalid handwriting method metadata field");
                }
            }
            JsonNode ocr = root.get("result");
            fields(ocr, "schemaVersion", "taxonomyVersion", "stepOrdinal", "transcript",
                    "uncertainty", "methodCandidates");
            for (String key : Set.of("schemaVersion", "taxonomyVersion", "transcript", "uncertainty")) {
                if (!ocr.get(key).isString()) {
                    throw new IllegalArgumentException("Invalid handwriting OCR field");
                }
            }
            number(ocr, "stepOrdinal", false);
            JsonNode candidates = ocr.get("methodCandidates");
            if (!candidates.isArray()) {
                throw new IllegalArgumentException("Invalid handwriting OCR candidates");
            }
            for (JsonNode candidate : candidates) {
                fields(candidate, "method", "evidenceStartUtf16", "evidenceEndUtf16",
                        "region", "state", "reason");
                for (String key : Set.of("method", "state", "reason")) {
                    if (!candidate.get(key).isString()) {
                        throw new IllegalArgumentException("Invalid handwriting OCR candidate");
                    }
                }
                number(candidate, "evidenceStartUtf16", false);
                number(candidate, "evidenceEndUtf16", false);
                JsonNode region = candidate.get("region");
                fields(region, "x", "y", "width", "height");
                for (String key : Set.of("x", "y", "width", "height")) {
                    number(region, key, false);
                }
            }
            return JSON.readValue(raw, HandwritingMethodMetadata.class);
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid handwriting method metadata JSON", exception);
        }
    }

    public void validateBindings(long expectedAccountId, String expectedSourceId,
            long expectedSourceVersion, long expectedIndexRevision, String expectedAnchorId,
            String expectedAnchorSha256, String anchorText) {
        if (accountId != expectedAccountId || !sourceId.equals(expectedSourceId)
                || sourceVersion != expectedSourceVersion || indexRevision != expectedIndexRevision
                || !anchorId.equals(expectedAnchorId) || !anchorSha256.equals(expectedAnchorSha256)
                || anchorText == null || !result.transcript().equals(anchorText)
                || !anchorSha256.equals(sha256(anchorText))) {
            throw new IllegalArgumentException("Handwriting method metadata binding mismatch");
        }
    }

    /** Shared SQL projection and ranker eligibility; raw OCR declarations remain unchanged. */
    public Set<String> eligibleMethods() {
        if (!"CLEAR".equals(result.uncertainty())) {
            return Set.of();
        }
        Set<String> methods = new HashSet<>();
        for (HandwritingIndexOcr.MethodCandidate candidate : result.methodCandidates()) {
            if ("CANDIDATE".equals(candidate.state()) && "VISIBLE_STEPS".equals(candidate.reason())
                    && HandwritingMethodTaxonomy.canonicalMethod(candidate.method())
                    .filter(candidate.method()::equals).isPresent()) {
                methods.add(candidate.method());
            }
        }
        methods.removeAll(ChineseMathTerms.negatedMethods(result.transcript()));
        return Set.copyOf(methods);
    }

    private static boolean identifier(String value) {
        return value != null && !value.isBlank() && value.length() <= 160;
    }

    private static boolean sha(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static void fields(JsonNode node, String... names) {
        if (node == null || !node.isObject() || !node.propertyNames().equals(Set.of(names))) {
            throw new IllegalArgumentException("Invalid handwriting method metadata fields");
        }
    }

    private static void number(JsonNode node, String key, boolean longValue) {
        JsonNode value = node.get(key);
        if (value == null || !value.isIntegralNumber()
                || (longValue ? !value.canConvertToLong() : !value.canConvertToInt())) {
            throw new IllegalArgumentException("Invalid handwriting method metadata number");
        }
    }
}
