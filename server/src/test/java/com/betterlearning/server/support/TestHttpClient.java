package com.betterlearning.server.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

/**
 * 集成测试 HTTP 客户端。
 *
 * <p>封装固定的 JSON 请求发送、可选 Bearer 认证、连接超时与 JSON 响应解析。
 * 特殊请求头（Cookie、Origin、预检等）通过 {@link #send(HttpRequest.Builder)} 由调用方自行组装。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
public final class TestHttpClient {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);

    private static final String JSON_CONTENT_TYPE = "application/json";

    private final HttpClient httpClient;

    private final String baseUrl;

    private final JsonMapper jsonMapper;

    /**
     * 创建指向随机端口测试服务的客户端。
     *
     * @param port 本地服务端口
     * @param jsonMapper JSON 映射器
     */
    public TestHttpClient(int port, JsonMapper jsonMapper) {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        this.baseUrl = "http://127.0.0.1:" + port;
        this.jsonMapper = jsonMapper;
    }

    /**
     * 构造指向测试服务的绝对地址。
     *
     * @param path 请求路径
     * @return 绝对地址
     */
    public URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    /**
     * 发送可选 Bearer 认证的 GET 请求。
     *
     * @param path 请求路径
     * @param token JWT，为 {@code null} 时不携带认证头
     * @return HTTP 响应
     * @throws Exception 发送请求失败
     */
    public HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path)).GET();
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return send(builder);
    }

    /**
     * 发送匿名 JSON POST 请求。
     *
     * @param path 请求路径
     * @param body JSON 请求体
     * @return HTTP 响应
     * @throws Exception 发送请求失败
     */
    public HttpResponse<String> post(String path, String body) throws Exception {
        return post(path, body, null);
    }

    /**
     * 发送可选 Bearer 认证的 JSON POST 请求。
     *
     * @param path 请求路径
     * @param body JSON 请求体
     * @param token JWT，为 {@code null} 时不携带认证头
     * @return HTTP 响应
     * @throws Exception 发送请求失败
     */
    public HttpResponse<String> post(String path, String body, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", JSON_CONTENT_TYPE)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return send(builder);
    }

    /**
     * 发送可选 Bearer 认证的 JSON PATCH 请求。
     *
     * @param path 请求路径
     * @param body JSON 请求体
     * @param token JWT，为 {@code null} 时不携带认证头
     * @return HTTP 响应
     * @throws Exception 发送请求失败
     */
    public HttpResponse<String> patch(String path, String body, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", JSON_CONTENT_TYPE)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return send(builder);
    }

    /**
     * 发送可选 Bearer 认证的 DELETE 请求。
     *
     * @param path 请求路径
     * @param token JWT，为 {@code null} 时不携带认证头
     * @return HTTP 响应
     * @throws Exception 发送请求失败
     */
    public HttpResponse<String> delete(String path, String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri(path))
                .method("DELETE", HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return send(builder);
    }

    /**
     * 发送调用方自行组装的请求。
     *
     * @param builder 请求构建器
     * @return HTTP 响应
     * @throws Exception 发送请求失败
     */
    public HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
        return httpClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /**
     * 把响应体解析为 JSON 对象。
     *
     * @param response HTTP 响应
     * @return JSON 对象
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> jsonBody(HttpResponse<String> response) {
        return jsonMapper.readValue(response.body(), Map.class);
    }

    /**
     * 断言统一错误响应结构。
     *
     * @param response HTTP 响应
     * @param status 期望的 HTTP 状态码
     * @param code 期望的业务错误码
     */
    public void assertErrorEnvelope(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        Map<String, Object> body = jsonBody(response);
        assertThat(body.get("code")).isEqualTo(code);
        assertThat((String) body.get("message")).isNotBlank();
        String traceId = (String) body.get("traceId");
        assertThat(traceId).isNotBlank();
        assertThat(response.headers().firstValue("X-Request-Id").orElse(null))
                .isEqualTo(traceId);
        assertThat(body).doesNotContainKeys("accessToken", "stackTrace", "exception");
    }
}
