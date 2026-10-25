package com.betterlearning.server.agent.retrieval.handwriting;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.betterlearning.server.agent.retrieval.ranking.HandwritingMethodTaxonomy;
import com.betterlearning.server.agent.service.HandwritingIndexOcr;
import com.betterlearning.server.agent.service.HandwritingIndexOcr.MethodCandidate;
import com.betterlearning.server.agent.service.HandwritingIndexOcr.Region;
import com.betterlearning.server.agent.service.HandwritingIndexOcr.Result;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 严格校验一次图像 OCR 的索引候选；验证只证明来源可追溯，不判断数学正确性。 */
public final class HandwritingOcrValidator {
    private static final int MAX_JSON_CHARS = 32768;
    private static final int MAX_TRANSCRIPT_CHARS = 8192;
    private static final int MAX_CANDIDATES = 8;
    private static final int MAX_METHOD_CHARS = 80;
    private static final Set<String> UNCERTAINTIES = Set.of("CLEAR", "UNCERTAIN");
    private static final Set<String> STATES = Set.of("CANDIDATE", "UNCERTAIN", "REJECTED");
    private static final Set<String> REASONS = Set.of("VISIBLE_STEPS", "INCOMPLETE_STEPS", "ILLEGIBLE",
            "CONTRADICTORY", "NEGATED");
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    private HandwritingOcrValidator() {
    }

    public static Result parse(String raw, int expectedStepOrdinal, int pixelWidth, int pixelHeight) {
        if (raw == null || raw.length() > MAX_JSON_CHARS) {
            throw invalid();
        }
        try {
            JsonNode node = JSON.readTree(raw);
            fields(node, "schemaVersion", "taxonomyVersion", "stepOrdinal", "transcript",
                    "uncertainty", "methodCandidates");
            JsonNode candidateNodes = node.get("methodCandidates");
            if (candidateNodes == null || !candidateNodes.isArray()
                    || candidateNodes.size() > MAX_CANDIDATES) {
                throw invalid();
            }
            List<MethodCandidate> candidates = new ArrayList<>();
            for (JsonNode candidate : candidateNodes) {
                fields(candidate, "method", "evidenceStartUtf16", "evidenceEndUtf16",
                        "region", "state", "reason");
                JsonNode region = candidate.get("region");
                fields(region, "x", "y", "width", "height");
                candidates.add(new MethodCandidate(string(candidate, "method"),
                        integer(candidate, "evidenceStartUtf16"), integer(candidate, "evidenceEndUtf16"),
                        new Region(integer(region, "x"), integer(region, "y"),
                                integer(region, "width"), integer(region, "height")),
                        string(candidate, "state"), string(candidate, "reason")));
            }
            return validate(new Result(string(node, "schemaVersion"), string(node, "taxonomyVersion"),
                    integer(node, "stepOrdinal"), string(node, "transcript"),
                    string(node, "uncertainty"), candidates), expectedStepOrdinal, pixelWidth, pixelHeight);
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    /** encode 不知道原图尺寸；持久化前须先调用带实际尺寸的 validate。 */
    public static String encode(Result result) {
        Result normalized = validateInternal(result, result == null ? -1 : result.stepOrdinal(), null, null);
        try {
            var node = JSON.createObjectNode();
            node.put("schemaVersion", normalized.schemaVersion());
            node.put("taxonomyVersion", normalized.taxonomyVersion());
            node.put("stepOrdinal", normalized.stepOrdinal());
            node.put("transcript", normalized.transcript());
            node.put("uncertainty", normalized.uncertainty());
            var candidates = node.putArray("methodCandidates");
            for (MethodCandidate candidate : normalized.methodCandidates()) {
                var item = candidates.addObject();
                item.put("method", candidate.method());
                item.put("evidenceStartUtf16", candidate.evidenceStartUtf16());
                item.put("evidenceEndUtf16", candidate.evidenceEndUtf16());
                var region = item.putObject("region");
                region.put("x", candidate.region().x());
                region.put("y", candidate.region().y());
                region.put("width", candidate.region().width());
                region.put("height", candidate.region().height());
                item.put("state", candidate.state());
                item.put("reason", candidate.reason());
            }
            return JSON.writeValueAsString(node);
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    public static Result validate(Result result, int expectedStepOrdinal, int pixelWidth, int pixelHeight) {
        if (pixelWidth < 1 || pixelHeight < 1) {
            throw invalid();
        }
        return validateInternal(result, expectedStepOrdinal, pixelWidth, pixelHeight);
    }

    private static Result validateInternal(Result result, int expectedStepOrdinal,
            Integer pixelWidth, Integer pixelHeight) {
        if (result == null || !HandwritingIndexOcr.SCHEMA_VERSION.equals(result.schemaVersion())
                || !HandwritingMethodTaxonomy.VERSION.equals(result.taxonomyVersion())
                || expectedStepOrdinal < 0 || result.stepOrdinal() != expectedStepOrdinal
                || result.transcript() == null || result.transcript().isBlank()
                || result.transcript().length() > MAX_TRANSCRIPT_CHARS
                || result.transcript().indexOf('\r') >= 0 || !validUnicode(result.transcript())
                || !UNCERTAINTIES.contains(result.uncertainty())
                || result.methodCandidates() == null || result.methodCandidates().size() > MAX_CANDIDATES) {
            throw invalid();
        }
        Set<String> seen = new HashSet<>();
        List<MethodCandidate> normalized = new ArrayList<>();
        for (MethodCandidate candidate : result.methodCandidates()) {
            if (candidate == null || candidate.method() == null || candidate.method().isBlank()
                    || candidate.method().length() > MAX_METHOD_CHARS || !validUnicode(candidate.method())
                    || candidate.method().chars().anyMatch(Character::isISOControl)
                    || !STATES.contains(candidate.state()) || !REASONS.contains(candidate.reason())
                    || candidate.region() == null) {
                throw invalid();
            }
            String canonical = HandwritingMethodTaxonomy.canonicalMethod(candidate.method())
                    .orElseThrow(HandwritingOcrValidator::invalid);
            if (!HandwritingMethodTaxonomy.methods().contains(canonical) || !seen.add(canonical)) {
                throw invalid();
            }
            if ("CANDIDATE".equals(candidate.state())
                    && (!"CLEAR".equals(result.uncertainty())
                    || !"VISIBLE_STEPS".equals(candidate.reason()))) {
                throw invalid();
            }
            int start = candidate.evidenceStartUtf16();
            int end = candidate.evidenceEndUtf16();
            String transcript = result.transcript();
            if (start < 0 || end <= start || end > transcript.length()
                    || !utf16Boundary(transcript, start) || !utf16Boundary(transcript, end)
                    || transcript.substring(start, end).isBlank()) {
                throw invalid();
            }
            Region region = candidate.region();
            if (region.x() < 0 || region.y() < 0 || region.width() < 1 || region.height() < 1
                    || (long) region.x() + region.width() > Integer.MAX_VALUE
                    || (long) region.y() + region.height() > Integer.MAX_VALUE
                    || pixelWidth != null && (long) region.x() + region.width() > pixelWidth
                    || pixelHeight != null && (long) region.y() + region.height() > pixelHeight) {
                throw invalid();
            }
            normalized.add(new MethodCandidate(canonical, start, end, region,
                    candidate.state(), candidate.reason()));
        }
        return new Result(result.schemaVersion(), result.taxonomyVersion(), result.stepOrdinal(),
                result.transcript(), result.uncertainty(), List.copyOf(normalized));
    }

    private static void fields(JsonNode node, String... names) {
        if (node == null || !node.isObject() || !node.propertyNames().equals(Set.of(names))) {
            throw invalid();
        }
    }

    private static String string(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isString()) {
            throw invalid();
        }
        return value.asString();
    }

    private static int integer(JsonNode node, String name) {
        JsonNode value = node.get(name);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid();
        }
        return value.intValue();
    }

    private static boolean utf16Boundary(String text, int offset) {
        return offset == 0 || offset == text.length()
                || !(Character.isHighSurrogate(text.charAt(offset - 1))
                && Character.isLowSurrogate(text.charAt(offset)));
    }

    private static boolean validUnicode(String text) {
        for (int index = 0; index < text.length(); index++) {
            char symbol = text.charAt(index);
            if (Character.isHighSurrogate(symbol)) {
                if (index + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(index + 1))) {
                    return false;
                }
                index++;
            } else if (Character.isLowSurrogate(symbol) || symbol != '\n' && symbol != '\t'
                    && Character.isISOControl(symbol)) {
                return false;
            }
        }
        return true;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("Invalid handwriting OCR output");
    }
}
