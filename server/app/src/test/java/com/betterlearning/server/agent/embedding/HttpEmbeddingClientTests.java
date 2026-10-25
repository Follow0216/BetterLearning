package com.betterlearning.server.agent.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.betterlearning.server.agent.config.EmbeddingProperties;
import com.betterlearning.server.agent.embedding.EmbeddingException.Reason;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class HttpEmbeddingClientTests {
    private final ObjectMapper json = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsBatchesAndRestoresIndexOrder() throws IOException {
        List<JsonNode> requests = new ArrayList<>();
        List<String> rawBodies = new ArrayList<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/prefix/v1/embeddings", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer test-key");
            assertThat(exchange.getRequestHeaders().getFirst("Content-Type")).isEqualTo("application/json");
            String rawBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            rawBodies.add(rawBody);
            JsonNode request = json.readTree(rawBody);
            requests.add(request);
            int count = request.path("input").size();
            String response = count == 2
                    ? "{\"model\":\"qwen3.7-text-embedding\",\"usage\":{\"prompt_tokens\":4,\"total_tokens\":4},"
                    + "\"data\":[{\"index\":1,\"embedding\":[2,3]},{\"index\":0,\"embedding\":[1,2]}]}"
                    : "{\"model\":\"qwen3.7-text-embedding\",\"usage\":{\"prompt_tokens\":2,\"total_tokens\":2},"
                    + "\"data\":[{\"index\":0,\"embedding\":[3,4]}]}";
            respond(exchange, 200, response);
        });
        server.start();
        EmbeddingProperties properties = configured();
        properties.setBatchSize(2);
        HttpEmbeddingClient.EmbeddingResult result = new HttpEmbeddingClient(properties)
                .embedWithStats(List.of("一", "二", "三"));
        List<double[]> vectors = result.vectors();
        assertThat(vectors).hasSize(3);
        assertThat(vectors.get(0)).containsExactly(1, 2);
        assertThat(vectors.get(1)).containsExactly(2, 3);
        assertThat(vectors.get(2)).containsExactly(3, 4);
        assertThat(result.requestCount()).isEqualTo(2);
        assertThat(result.promptTokens()).isEqualTo(6);
        assertThat(result.totalTokens()).isEqualTo(6);
        assertThat(result.responseModel()).isEqualTo("qwen3.7-text-embedding");
        assertThat(requests).hasSize(2);
        assertThat(rawBodies).allSatisfy(body -> assertThat(body).contains("\"dimensions\":2"));
        assertThat(requests.get(0).path("model").asText()).isEqualTo("qwen3.7-text-embedding");
        assertThat(requests.get(0).path("encoding_format").asText()).isEqualTo("float");
        assertThat(requests.get(0).path("dimensions").intValue()).isEqualTo(2);
        assertThat(requests.get(1).path("dimensions").intValue()).isEqualTo(2);
        assertThat(requests.get(0).path("input").size()).isEqualTo(2);
        assertThat(requests.get(1).path("input").get(0).asText()).isEqualTo("三");
    }

    @Test
    void rejectsMissingOrDifferentResponseModelWithoutRetry() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/prefix/v1/embeddings", exchange -> {
            String model = switch (calls.incrementAndGet()) {
                case 1 -> "\"model\":\"unit-model\",";
                case 2 -> "\"model\":\"qwen3.7-text-embedding-v2\",";
                default -> "";
            };
            respond(exchange, 200, "{" + model + "\"data\":[{\"index\":0,\"embedding\":[1,2]}]}");
        });
        server.start();
        HttpEmbeddingClient client = new HttpEmbeddingClient(configured());
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> client.embed(List.of("a")))
                    .isInstanceOf(EmbeddingException.class)
                    .extracting("reason").isEqualTo(Reason.INVALID_RESPONSE);
        }
        assertThat(calls.get()).isEqualTo(3);
    }

    @Test
    void rejectsInvalidProviderDataAndDoesNotRetry() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/prefix/v1/embeddings", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 200, "{\"data\":[{\"index\":0,\"embedding\":[1,2]},"
                    + "{\"index\":0,\"embedding\":[3,4]}]}");
        });
        server.start();
        assertThatThrownBy(() -> new HttpEmbeddingClient(configured()).embed(List.of("a", "b")))
                .isInstanceOf(EmbeddingException.class)
                .extracting("reason").isEqualTo(Reason.INVALID_RESPONSE);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void rejectsWrongDimensionsNonFiniteNumbersAndZeroVectors() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/prefix/v1/embeddings", exchange -> {
            int call = calls.incrementAndGet();
            String response = switch (call) {
                case 1 -> "{\"data\":[{\"index\":0,\"embedding\":[1]}]}";
                case 2 -> "{\"data\":[{\"index\":0,\"embedding\":[1,1e999]}]}";
                case 3 -> "{\"data\":[{\"index\":0,\"embedding\":[0,-0.0]}]}";
                case 4 -> "{\"data\":[{\"index\":0,\"embedding\":[1e40,1]}]}";
                default -> "{\"data\":[{\"index\":0,\"embedding\":[1e-50,-1e-50]}]}";
            };
            respond(exchange, 200, response);
        });
        server.start();
        HttpEmbeddingClient client = new HttpEmbeddingClient(configured());
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThatThrownBy(() -> client.embed(List.of("a")))
                    .isInstanceOf(EmbeddingException.class)
                    .extracting("reason").isEqualTo(Reason.INVALID_RESPONSE);
        }
    }

    @Test
    void rejectsOversizedInputBeforeCallingProvider() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/prefix/v1/embeddings", exchange -> calls.incrementAndGet());
        server.start();
        HttpEmbeddingClient client = new HttpEmbeddingClient(configured());
        assertThatThrownBy(() -> client.embed(List.of("中".repeat(22000))))
                .isInstanceOf(EmbeddingException.class)
                .extracting("reason").isEqualTo(Reason.INVALID_INPUT);
        assertThat(calls.get()).isZero();
    }

    @Test
    void rejectsOversizedResponse() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/prefix/v1/embeddings", exchange ->
                respond(exchange, 200, "x".repeat(8 * 1024 * 1024 + 1)));
        server.start();
        assertThatThrownBy(() -> new HttpEmbeddingClient(configured()).embed(List.of("a")))
                .isInstanceOf(EmbeddingException.class)
                .extracting("reason").isEqualTo(Reason.INVALID_RESPONSE);
    }

    @Test
    void timesOutWhileWaitingForResponseBody() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/prefix/v1/embeddings", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try {
                Thread.sleep(1800);
                exchange.getResponseBody().write("{}".getBytes(StandardCharsets.UTF_8));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (IOException exception) {
                // 客户端超时主动关闭连接。
            } finally {
                exchange.close();
            }
        });
        server.start();
        EmbeddingProperties properties = configured();
        properties.setTimeoutSeconds(1);
        assertThatThrownBy(() -> new HttpEmbeddingClient(properties).embed(List.of("a")))
                .isInstanceOf(EmbeddingException.class)
                .extracting("reason").isEqualTo(Reason.UNAVAILABLE);
    }

    @Test
    void masksProviderFailureAndDoesNotRetry() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/prefix/v1/embeddings", exchange -> {
            calls.incrementAndGet();
            respond(exchange, 429, "provider-secret-detail");
        });
        server.start();
        assertThatThrownBy(() -> new HttpEmbeddingClient(configured()).embed(List.of("a")))
                .isInstanceOf(EmbeddingException.class)
                .hasMessageNotContaining("provider-secret-detail")
                .extracting("reason").isEqualTo(Reason.UNAVAILABLE);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void disabledAndMissingConfigurationFailSafely() {
        EmbeddingProperties disabled = new EmbeddingProperties();
        assertThatThrownBy(() -> new HttpEmbeddingClient(disabled).embed(List.of("a")))
                .isInstanceOf(EmbeddingException.class)
                .extracting("reason").isEqualTo(Reason.DISABLED);
        EmbeddingProperties missing = new EmbeddingProperties();
        missing.setEnabled(true);
        assertThatThrownBy(() -> new HttpEmbeddingClient(missing))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining("test-key");
    }

    private EmbeddingProperties configured() {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/prefix/v1/");
        properties.setApiKey("test-key");
        properties.setDimensions(2);
        return properties;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String response)
            throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var stream = exchange.getResponseBody()) {
            stream.write(bytes);
        }
    }
}
