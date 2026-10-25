package com.betterlearning.server.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.betterlearning.server.agent.config.EmbeddingProperties;
import com.betterlearning.server.agent.embedding.HttpEmbeddingClient;
import com.betterlearning.server.agent.retrieval.PersonalEvidenceRetriever;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.QueryEmbeddingKey;
import com.betterlearning.server.config.MybatisConfiguration;
import com.betterlearning.server.learning.config.LearningMapperConfiguration;
import com.betterlearning.server.learning.mapper.RetentionMapper;
import com.betterlearning.server.learning.retention.DeletionJournal;
import com.betterlearning.server.learning.retention.SourceDeletionService;
import com.betterlearning.server.learning.service.impl.AnswerFileStore;
import com.betterlearning.server.learning.service.impl.SourceStoreImpl;
import com.betterlearning.server.learning.validation.SourceAnchorValidator;
import com.betterlearning.server.multiagent.MultiAgentModels.RetrievalQuery;
import com.betterlearning.server.multiagent.StepExecutionException;
import com.sun.net.httpserver.HttpServer;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.json.JsonMapper;

/** Real PG adapter + production retriever + a local, counted embedding HTTP stub. No paid provider. */
@Testcontainers
@Tag("integration")
class QueryEmbeddingRecoveryIntegrationTests {
    private static final String MODEL = "local-query-smoke";
    private static final String TEXT = "怎样移项解一元一次方程？";
    private static final DockerImageName IMAGE = DockerImageName.parse(
            "pgvector/pgvector@sha256:42e7f6b4e1eceb02ff14e3e6bc6108bbe259abbe83879dc1845d0da1ddeb555d")
            .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(IMAGE)
            .withDatabaseName("betterlearning").withUsername("test").withPassword("test");

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static RetrievalIndexStore index;
    private static Path journalRoot;
    private HttpServer stub;
    private AtomicInteger requests;
    private CountDownLatch received;
    private CountDownLatch release;
    private long accountId;
    private String runId;
    private String stepId;

    @Configuration(proxyBeanMethods = false)
    @MapperScan("com.betterlearning.server.retrieval.mapper")
    @EnableTransactionManagement
    static class MapperConfiguration {
        @Bean(initMethod = "recoverBeforeServing")
        SourceDeletionService sourceDeletionService(RetentionMapper retention,
                PlatformTransactionManager transactionManager) throws IOException {
            return new SourceDeletionService(DeletionJournal.initialize(journalRoot), retention,
                    new TransactionTemplate(transactionManager));
        }
    }

    @BeforeAll
    static void database() throws IOException {
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        journalRoot = Files.createTempDirectory("bl-query-deletion-journal-");
        context = new AnnotationConfigApplicationContext();
        String secret = Base64.getEncoder().encodeToString(
                "integration-test-signing-key-32-bytes".getBytes(StandardCharsets.UTF_8));
        context.getEnvironment().getPropertySources().addFirst(
                new MapPropertySource("testCursorKey", Map.of("app.jwt.secret-base64", secret,
                "app.agent-retention.sweep-enabled", "false")));
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(JsonMapper.class, () -> JsonMapper.builder().build());
        context.registerBean(AnswerFileStore.class, () -> mock(AnswerFileStore.class));
        context.register(MybatisConfiguration.class, LearningMapperConfiguration.class,
                MapperConfiguration.class, MybatisPlusAutoConfiguration.class,
                SourceAnchorValidator.class, SourceStoreImpl.class, MybatisRetrievalIndexStore.class);
        context.refresh();
        index = context.getBean(RetrievalIndexStore.class);
    }

    @AfterAll
    static void closeDatabase() {
        if (context != null) {
            context.close();
        }
    }

    @BeforeEach
    void setup() throws IOException {
        jdbc.execute("TRUNCATE TABLE app_user RESTART IDENTITY CASCADE");
        requests = new AtomicInteger();
        received = new CountDownLatch(1);
        release = new CountDownLatch(0);
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/embeddings", exchange -> {
            try {
                assertThat(exchange.getRequestMethod()).isEqualTo("POST");
                exchange.getRequestBody().readAllBytes();
                requests.incrementAndGet();
                received.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) {
                    throw new IOException("stub release timed out");
                }
                byte[] response = ("{\"model\":\"" + MODEL + "\",\"usage\":{\"prompt_tokens\":2,"
                        + "\"total_tokens\":2},\"data\":[{\"index\":0,\"embedding\":[1,0]}]}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        stub.start();
        accountId = jdbc.queryForObject("INSERT INTO app_user(username,password_hash,role) "
                + "VALUES (?,?,?) RETURNING account_id", Long.class,
                "query_owner_" + UUID.randomUUID().toString().substring(0, 8), "test-hash", "USER");
        long questionId = jdbc.queryForObject("INSERT INTO question(account_id,request_id,subject,body_markdown,summary) "
                + "VALUES (?,?,?,?,?) RETURNING question_id", Long.class, accountId,
                UUID.randomUUID().toString(), "OTHER", "公开自造测试题", "公开自造测试题");
        String requestId = UUID.randomUUID().toString();
        runId = UUID.randomUUID().toString();
        stepId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ma_request(request_id,account_id,question_id,prompt,mode,snapshot_kind,"
                + "status,input_deadline_at) VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP + INTERVAL '5 minutes')",
                requestId, accountId, questionId, "提示", "HINT", "NEW_CAPTURE", "RUNNING");
        jdbc.update("INSERT INTO ma_run(run_id,request_id,account_id,run_no,status,queue_deadline_at,run_deadline_at) "
                + "VALUES (?,?,?,?,?,CURRENT_TIMESTAMP + INTERVAL '5 minutes',"
                + "CURRENT_TIMESTAMP + INTERVAL '10 minutes')", runId, requestId, accountId, 1, "RUNNING");
        jdbc.update("UPDATE ma_request SET current_run_id = ? WHERE account_id = ? AND request_id = ?",
                runId, accountId, requestId);
        stepId = addStep(1);
    }

    @AfterEach
    void stopStub() {
        if (stub != null) {
            release.countDown();
            stub.stop(0);
        }
    }

    @Test
    void confirmedVectorIsReusedAfterRetrieverRestartWithoutAnotherHttpRequest() {
        assertThat(retriever(MODEL).retrieve(query(TEXT))).isEmpty();
        assertThat(requests.get()).isEqualTo(1);
        assertThat(new PersonalEvidenceRetriever(index, client(MODEL), properties(MODEL))
                .retrieve(query(TEXT))).isEmpty();
        assertThat(requests.get()).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("SELECT call_state,embedding_requests,embedding_tokens "
                + "FROM ma_query_embedding WHERE step_id = ?", stepId);
        assertThat(row.get("call_state")).isEqualTo("CONFIRMED");
        assertThat(((Number) row.get("embedding_requests")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("embedding_tokens")).longValue()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT model_calls FROM ma_run WHERE run_id = ?",
                Integer.class, runId)).isZero();
    }

    @Test
    void acceptedButUnreturnedRequestCannotBeAutomaticallyResent() throws Exception {
        release = new CountDownLatch(1);
        QueryEmbeddingKey key = key(TEXT, MODEL);
        assertThat(index.beginQueryEmbedding(key).shouldCall()).isTrue();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> pending = executor.submit(() -> client(MODEL).embedWithStats(List.of(TEXT)));
            assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
            assertOutcomeUnknown(() -> retriever(MODEL).retrieve(query(TEXT)));
            assertThat(requests.get()).isEqualTo(1);
            pending.cancel(true); // Simulate process death after the stub accepted the request.
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void returnedButUnconfirmedRequestCannotBeAutomaticallyResent() {
        QueryEmbeddingKey key = key(TEXT, MODEL);
        assertThat(index.beginQueryEmbedding(key).shouldCall()).isTrue();
        assertThat(client(MODEL).embedWithStats(List.of(TEXT)).requestCount()).isEqualTo(1);
        assertOutcomeUnknown(() -> retriever(MODEL).retrieve(query(TEXT)));
        assertThat(requests.get()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT call_state FROM ma_query_embedding WHERE step_id = ?",
                String.class, stepId)).isEqualTo("CALL_INTENT");
    }

    @Test
    void keyMismatchNeverBorrowsOldVectorAndNewStepHasIndependentCall() {
        assertThat(retriever(MODEL).retrieve(query(TEXT))).isEmpty();
        assertThat(requests.get()).isEqualTo(1);
        assertOutcomeUnknown(() -> retriever(MODEL).retrieve(query("另一个求解请求")));
        assertOutcomeUnknown(() -> retriever("other-local-model").retrieve(query(TEXT)));
        assertOutcomeUnknown(() -> index.beginQueryEmbedding(new QueryEmbeddingKey(accountId,
                runId, UUID.randomUUID().toString(), sha256(TEXT), MODEL, 2)));
        assertThat(requests.get()).isEqualTo(1);

        String oldStep = stepId;
        stepId = addStep(2);
        assertThat(retriever(MODEL).retrieve(query("另一个求解请求"))).isEmpty();
        assertThat(requests.get()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_query_embedding WHERE step_id IN (?,?)",
                Integer.class, oldStep, stepId)).isEqualTo(2);
    }

    private String addStep(int sequence) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ma_step(step_id,run_id,role,sequence,status,input_digest) "
                + "VALUES (?,?,?,?,?,?)", id, runId, "RETRIEVE", sequence, "RUNNING", "0".repeat(64));
        return id;
    }

    private RetrievalQuery query(String text) {
        return new RetrievalQuery(accountId, runId, "question", text, List.of(), 5);
    }

    private QueryEmbeddingKey key(String text, String model) {
        return new QueryEmbeddingKey(accountId, runId, stepId, sha256(text), model, 2);
    }

    private PersonalEvidenceRetriever retriever(String model) {
        return new PersonalEvidenceRetriever(index, client(model), properties(model));
    }

    private HttpEmbeddingClient client(String model) {
        return new HttpEmbeddingClient(properties(model));
    }

    private EmbeddingProperties properties(String model) {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setEnabled(true);
        properties.setBaseUrl("http://127.0.0.1:" + stub.getAddress().getPort());
        properties.setApiKey("local-test-key");
        properties.setModel(model);
        properties.setDimensions(2);
        properties.setTimeoutSeconds(15);
        return properties;
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void assertOutcomeUnknown(Runnable operation) {
        assertThatThrownBy(operation::run).isInstanceOfSatisfying(StepExecutionException.class,
                error -> assertThat(error.reason()).isEqualTo("OUTCOME_UNKNOWN"));
    }
}
