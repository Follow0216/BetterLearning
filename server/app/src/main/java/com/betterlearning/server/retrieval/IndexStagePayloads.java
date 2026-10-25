package com.betterlearning.server.retrieval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.betterlearning.server.multiagent.MultiAgentModels.Anchor;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Canonical, bounded stage payloads shared by the indexing worker and durable store. */
public final class IndexStagePayloads {
    public static final String EMBEDDING_SCHEMA = "handwriting-embedding-v1";
    private static final int MAX_BATCH = 100;
    private static final int MAX_JSON_CHARS = 8 * 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public record EmbeddingInput(String sourceId, long sourceVersion, String anchorId,
            int ordinal, String anchorSha256, String locatorSha256) { }
    public record EmbeddingBatch(List<EmbeddingInput> inputs, List<double[]> vectors) { }

    private IndexStagePayloads() { }

    public static EmbeddingInput input(Anchor anchor) {
        if (anchor == null || anchor.sourceId() == null || anchor.sourceId().isBlank()
                || anchor.version() < 1 || anchor.anchorId() == null || anchor.anchorId().isBlank()
                || anchor.ordinal() < 0 || !digest(anchor.contentSha256()) || anchor.locatorJson() == null) {
            throw new IllegalArgumentException("Invalid embedding anchor");
        }
        return new EmbeddingInput(anchor.sourceId(), anchor.version(), anchor.anchorId(),
                anchor.ordinal(), anchor.contentSha256(), sha256(anchor.locatorJson()));
    }

    public static String inputDigest(List<Anchor> anchors) {
        requireAnchors(anchors);
        return inputDigestForInputs(anchors.stream().map(IndexStagePayloads::input).toList());
    }

    static String inputDigestForInputs(List<EmbeddingInput> inputs) {
        if (inputs == null || inputs.isEmpty() || inputs.size() > MAX_BATCH) {
            throw new IllegalArgumentException("Invalid embedding input count");
        }
        return sha256(JSON.writeValueAsString(inputs.stream()
                .map(IndexStagePayloads::inputObject).toList()));
    }

    public static String encodeEmbedding(List<Anchor> anchors, List<double[]> vectors, int dimensions) {
        requireAnchors(anchors);
        if (vectors == null || vectors.size() != anchors.size() || dimensions < 1) {
            throw new IllegalArgumentException("Invalid embedding batch");
        }
        List<List<Float>> bounded = new ArrayList<>(vectors.size());
        for (double[] vector : vectors) {
            if (vector == null || vector.length != dimensions) {
                throw new IllegalArgumentException("Invalid embedding dimensions");
            }
            List<Float> values = new ArrayList<>(dimensions);
            boolean nonzero = false;
            for (double value : vector) {
                float stored = (float) value;
                if (!Double.isFinite(value) || !Float.isFinite(stored)) {
                    throw new IllegalArgumentException("Invalid embedding value");
                }
                nonzero |= stored != 0;
                values.add(stored);
            }
            if (!nonzero) {
                throw new IllegalArgumentException("Zero embedding vector");
            }
            bounded.add(List.copyOf(values));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("inputs", anchors.stream().map(IndexStagePayloads::input)
                .map(IndexStagePayloads::inputObject).toList());
        payload.put("vectors", List.copyOf(bounded));
        String encoded = JSON.writeValueAsString(payload);
        if (encoded.length() > MAX_JSON_CHARS) {
            throw new IllegalArgumentException("Embedding batch too large");
        }
        return encoded;
    }

    public static EmbeddingBatch decodeEmbedding(String raw, int dimensions) {
        if (raw == null || raw.length() > MAX_JSON_CHARS || dimensions < 1) {
            throw new IllegalArgumentException("Invalid embedding batch JSON");
        }
        try {
            JsonNode root = JSON.readTree(raw);
            fields(root, "schemaVersion", "inputs", "vectors");
            if (!root.get("schemaVersion").isIntegralNumber()
                    || !root.get("schemaVersion").canConvertToInt()
                    || root.get("schemaVersion").intValue() != 1) {
                throw new IllegalArgumentException("Unsupported embedding batch schema");
            }
            JsonNode inputs = root.get("inputs");
            JsonNode vectors = root.get("vectors");
            if (!inputs.isArray() || inputs.size() == 0 || inputs.size() > MAX_BATCH
                    || !vectors.isArray() || vectors.size() != inputs.size()) {
                throw new IllegalArgumentException("Invalid embedding batch size");
            }
            List<EmbeddingInput> parsedInputs = new ArrayList<>(inputs.size());
            List<double[]> parsedVectors = new ArrayList<>(inputs.size());
            for (int position = 0; position < inputs.size(); position++) {
                JsonNode item = inputs.get(position);
                fields(item, "sourceId", "sourceVersion", "anchorId", "ordinal",
                        "anchorSha256", "locatorSha256");
                if (!item.get("sourceId").isString() || !item.get("anchorId").isString()
                        || !item.get("anchorSha256").isString() || !item.get("locatorSha256").isString()
                        || !item.get("sourceVersion").isIntegralNumber()
                        || !item.get("sourceVersion").canConvertToLong()
                        || !item.get("ordinal").isIntegralNumber()
                        || !item.get("ordinal").canConvertToInt()) {
                    throw new IllegalArgumentException("Invalid embedding input type");
                }
                EmbeddingInput parsed = new EmbeddingInput(item.get("sourceId").textValue(),
                        item.get("sourceVersion").longValue(), item.get("anchorId").textValue(),
                        item.get("ordinal").intValue(), item.get("anchorSha256").textValue(),
                        item.get("locatorSha256").textValue());
                if (parsed.sourceId().isBlank() || parsed.anchorId().isBlank()
                        || parsed.sourceVersion() < 1 || parsed.ordinal() < 0
                        || !digest(parsed.anchorSha256()) || !digest(parsed.locatorSha256())) {
                    throw new IllegalArgumentException("Invalid embedding input");
                }
                parsedInputs.add(parsed);
                JsonNode values = vectors.get(position);
                if (!values.isArray() || values.size() != dimensions) {
                    throw new IllegalArgumentException("Invalid embedding dimensions");
                }
                double[] vector = new double[dimensions];
                boolean nonzero = false;
                for (int dimension = 0; dimension < dimensions; dimension++) {
                    JsonNode value = values.get(dimension);
                    if (!value.isNumber()) {
                        throw new IllegalArgumentException("Invalid embedding value");
                    }
                    double number = value.doubleValue();
                    float stored = (float) number;
                    if (!Double.isFinite(number) || !Float.isFinite(stored)) {
                        throw new IllegalArgumentException("Invalid embedding float32 value");
                    }
                    vector[dimension] = stored;
                    nonzero |= stored != 0;
                }
                if (!nonzero) {
                    throw new IllegalArgumentException("Zero embedding vector");
                }
                parsedVectors.add(vector);
            }
            return new EmbeddingBatch(List.copyOf(parsedInputs), List.copyOf(parsedVectors));
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("Invalid embedding batch JSON", exception);
        }
    }

    public static String sha256(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Missing digest input");
        }
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("Missing digest input");
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static Map<String, Object> inputObject(EmbeddingInput input) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceId", input.sourceId());
        result.put("sourceVersion", input.sourceVersion());
        result.put("anchorId", input.anchorId());
        result.put("ordinal", input.ordinal());
        result.put("anchorSha256", input.anchorSha256());
        result.put("locatorSha256", input.locatorSha256());
        return result;
    }

    private static void requireAnchors(List<Anchor> anchors) {
        if (anchors == null || anchors.isEmpty() || anchors.size() > MAX_BATCH) {
            throw new IllegalArgumentException("Invalid embedding batch size");
        }
        anchors.forEach(IndexStagePayloads::input);
    }

    private static boolean digest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static void fields(JsonNode node, String... names) {
        if (node == null || !node.isObject() || !node.propertyNames().equals(Set.of(names))) {
            throw new IllegalArgumentException("Invalid embedding batch fields");
        }
    }
}
