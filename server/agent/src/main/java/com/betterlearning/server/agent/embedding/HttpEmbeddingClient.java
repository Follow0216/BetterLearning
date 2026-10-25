package com.betterlearning.server.agent.embedding;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.betterlearning.server.agent.config.EmbeddingProperties;
import com.betterlearning.server.agent.embedding.EmbeddingException.Reason;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** OpenAI 兼容 /embeddings 协议；结果按输入顺序返回。 */
public final class HttpEmbeddingClient {
    private static final int MAX_INPUTS_PER_CALL = 100;
    private static final int MAX_INPUT_BYTES = 64 * 1024;
    private static final int MAX_RESPONSE_BYTES = 8 * 1024 * 1024;

    public record EmbeddingResult(List<double[]> vectors, int requestCount, Long promptTokens,
            Long totalTokens, String responseModel) {
    }

    private record BatchResult(List<double[]> vectors, Long promptTokens, Long totalTokens,
            String responseModel) {
    }

    private final EmbeddingProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper json = new ObjectMapper();
    private final URI endpoint;

    public HttpEmbeddingClient(EmbeddingProperties properties) {
        this.properties = properties;
        properties.validate();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(properties.getTimeoutSeconds())).build();
        this.endpoint = properties.isEnabled()
                ? URI.create(properties.getBaseUrl().replaceAll("/+$", "") + "/embeddings") : null;
    }

    public List<double[]> embed(List<String> inputs) {
        return embedWithStats(inputs).vectors();
    }

    /** usage 由供应商响应提供；缺失时为 null，不虚构 token 用量。 */
    public EmbeddingResult embedWithStats(List<String> inputs) {
        if (!properties.isEnabled()) {
            throw new EmbeddingException(Reason.DISABLED);
        }
        if (inputs == null || inputs.isEmpty() || inputs.size() > MAX_INPUTS_PER_CALL) {
            throw new EmbeddingException(Reason.INVALID_INPUT);
        }
        for (String input : inputs) {
            if (input == null || input.isBlank() || input.length() > MAX_INPUT_BYTES
                    || input.getBytes(StandardCharsets.UTF_8).length > MAX_INPUT_BYTES) {
                throw new EmbeddingException(Reason.INVALID_INPUT);
            }
        }
        List<double[]> result = new ArrayList<>(inputs.size());
        Long promptTokens = 0L;
        Long totalTokens = 0L;
        String responseModel = null;
        int requestCount = 0;
        for (int start = 0; start < inputs.size(); start += properties.getBatchSize()) {
            int end = Math.min(start + properties.getBatchSize(), inputs.size());
            BatchResult batch = embedBatch(inputs.subList(start, end));
            result.addAll(batch.vectors());
            requestCount++;
            try {
                promptTokens = promptTokens == null || batch.promptTokens() == null
                        ? null : Math.addExact(promptTokens, batch.promptTokens());
                totalTokens = totalTokens == null || batch.totalTokens() == null
                        ? null : Math.addExact(totalTokens, batch.totalTokens());
            } catch (ArithmeticException exception) {
                throw new EmbeddingException(Reason.INVALID_RESPONSE);
            }
            responseModel = batch.responseModel() == null ? responseModel : batch.responseModel();
        }
        return new EmbeddingResult(List.copyOf(result), requestCount, promptTokens, totalTokens, responseModel);
    }

    private BatchResult embedBatch(List<String> inputs) {
        try {
            String body = json.writeValueAsString(Map.of("model", properties.getModel(),
                    "input", inputs, "encoding_format", "float", "dimensions", properties.getDimensions()));
            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            long deadline = System.nanoTime() + Duration.ofSeconds(properties.getTimeoutSeconds()).toNanos();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                if (response.statusCode() != 200) {
                    throw new EmbeddingException(Reason.UNAVAILABLE);
                }
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new EmbeddingException(Reason.UNAVAILABLE);
                }
                FutureTask<byte[]> readTask = new FutureTask<>(() -> stream.readNBytes(MAX_RESPONSE_BYTES + 1));
                Thread reader = new Thread(readTask, "embedding-response-read");
                reader.setDaemon(true);
                reader.start();
                byte[] bytes;
                try {
                    bytes = readTask.get(remaining, TimeUnit.NANOSECONDS);
                } catch (TimeoutException | ExecutionException exception) {
                    throw new EmbeddingException(Reason.UNAVAILABLE);
                } finally {
                    readTask.cancel(true);
                }
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new EmbeddingException(Reason.INVALID_RESPONSE);
                }
                return decode(new String(bytes, StandardCharsets.UTF_8), inputs.size());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new EmbeddingException(Reason.UNAVAILABLE);
        } catch (IOException | IllegalArgumentException exception) {
            throw new EmbeddingException(Reason.UNAVAILABLE);
        }
    }

    private BatchResult decode(String body, int count) {
        try {
            JsonNode root = json.readTree(body);
            if (root == null) {
                throw new EmbeddingException(Reason.INVALID_RESPONSE);
            }
            JsonNode data = root.path("data");
            if (!data.isArray() || data.size() != count) {
                throw new EmbeddingException(Reason.INVALID_RESPONSE);
            }
            double[][] ordered = new double[count][];
            for (JsonNode item : data) {
                JsonNode index = item.path("index");
                JsonNode values = item.path("embedding");
                if (!index.isIntegralNumber() || !index.canConvertToInt() || !values.isArray()
                        || values.size() != properties.getDimensions()) {
                    throw new EmbeddingException(Reason.INVALID_RESPONSE);
                }
                int position = index.intValue();
                if (position < 0 || position >= count || ordered[position] != null) {
                    throw new EmbeddingException(Reason.INVALID_RESPONSE);
                }
                double[] vector = new double[properties.getDimensions()];
                boolean nonzero = false;
                for (int dimension = 0; dimension < vector.length; dimension++) {
                    JsonNode value = values.get(dimension);
                    if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
                        throw new EmbeddingException(Reason.INVALID_RESPONSE);
                    }
                    float storedValue = (float) value.doubleValue();
                    if (!Float.isFinite(storedValue)) {
                        throw new EmbeddingException(Reason.INVALID_RESPONSE);
                    }
                    vector[dimension] = value.doubleValue();
                    nonzero |= storedValue != 0.0f;
                }
                if (!nonzero) {
                    throw new EmbeddingException(Reason.INVALID_RESPONSE);
                }
                ordered[position] = vector;
            }
            JsonNode usage = root.path("usage");
            Long promptTokens = tokenCount(usage.path("prompt_tokens"));
            Long totalTokens = tokenCount(usage.path("total_tokens"));
            JsonNode model = root.path("model");
            String responseModel = model.isTextual() ? model.textValue() : null;
            if (!properties.getModel().equals(responseModel)) {
                throw new EmbeddingException(Reason.INVALID_RESPONSE);
            }
            return new BatchResult(List.of(ordered), promptTokens, totalTokens, responseModel);
        } catch (IOException | IllegalArgumentException exception) {
            throw new EmbeddingException(Reason.INVALID_RESPONSE);
        }
    }

    private static Long tokenCount(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 0) {
            throw new EmbeddingException(Reason.INVALID_RESPONSE);
        }
        return node.longValue();
    }
}
