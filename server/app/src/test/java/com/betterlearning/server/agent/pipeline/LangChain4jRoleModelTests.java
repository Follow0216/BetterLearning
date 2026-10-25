package com.betterlearning.server.agent.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static com.betterlearning.server.agent.pipeline.TutoringPipelineTests.assertReason;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.betterlearning.server.agent.config.AgentProperties;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Call;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Reason;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Role;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

class LangChain4jRoleModelTests {
    @Test
    void realSdkSendsEveryImageAndCollectsUsageThroughPrefixPath() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/custom/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String response = """
                    {"id":"unit","object":"chat.completion","created":1,"model":"unit-role-v1",
                    "choices":[{"index":0,"message":{"role":"assistant","content":"{\\\"ok\\\":true}"},
                    "finish_reason":"stop"}],"usage":{"prompt_tokens":50,"completion_tokens":8,"total_tokens":58}}
                    """;
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var reply = new LangChain4jRoleModel(provider(server)).generate(call());
            assertThat(calls).hasValue(1);
            assertThat(reply.model()).isEqualTo("unit-role-v1");
            assertThat(reply.usage().inputTokens()).isEqualTo(50);
            assertThat(reply.usage().outputTokens()).isEqualTo(8);
            assertThat(reply.usage().costKnown()).isFalse();
            var json = RoleOutputValidator.JSON.readTree(body.get());
            assertThat(json.path("response_format").path("type").asString()).isEqualTo("json_object");
            assertThat(json.path("max_completion_tokens").asInt()).isEqualTo(4096);
            assertThat(body.get()).contains("x=-600", "viewport=outside", "data:image/png;base64,AQI=", "data:image/png;base64,AwQ=");
            assertThat(reply.toString()).doesNotContain("\"ok\"", "unit-secret");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void errorResponseIsUnknownSanitizedAndNeverAutomaticallyRetried() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/custom/v1/chat/completions", exchange -> {
            calls.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"error\":{\"message\":\"unit-secret private answer\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            assertReason(() -> new LangChain4jRoleModel(provider(server)).generate(call()), Reason.OUTCOME_UNKNOWN);
            assertThat(calls).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingConfigurationAndUnsafeUrlFailBeforeNetwork() {
        AgentProperties.Provider provider = new AgentProperties.Provider();
        assertReason(() -> new LangChain4jRoleModel(provider).generate(call()), Reason.MODEL_NOT_CONFIGURED);
        provider.setModel("unit-role");
        provider.setApiKey("unit-secret");
        for (String url : new String[] {"https://example.invalid/v1?key=secret", "file:///tmp/secret",
                "https://user:password@example.invalid/v1", "https://example.invalid/v1#secret"}) {
            provider.setBaseUrl(url);
            assertReason(() -> new LangChain4jRoleModel(provider).generate(call()), Reason.MODEL_NOT_CONFIGURED);
        }
    }

    private static AgentProperties.Provider provider(HttpServer server) {
        AgentProperties.Provider provider = new AgentProperties.Provider();
        provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/custom/v1");
        provider.setModel("unit-role");
        provider.setApiKey("unit-secret");
        provider.setTimeoutSeconds(5);
        return provider;
    }

    private static Call call() {
        return new Call(1, Role.SOLVE, RolePrompts.forRole(Role.SOLVE), "{}",
                TutoringPipelineTests.input(null, Set.of(), null).snapshot());
    }
}
