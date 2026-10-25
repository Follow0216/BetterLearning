package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.learning.model.tutor.TutorStorageModels;
import com.betterlearning.server.learning.service.FrozenAnswerService;
import com.betterlearning.server.learning.service.QuestionAnswerService;
import com.betterlearning.server.learning.service.TutorTurnService;
import com.betterlearning.server.support.IntegrationContainers;
import com.betterlearning.server.support.IntegrationProperties;
import com.betterlearning.server.support.TestData;
import com.betterlearning.server.support.TestHttpClient;

import jakarta.servlet.http.Part;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockPart;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("integration")
class TutorStorageIntegrationTests {
    private static final Path FILE_ROOT = temporaryRoot();
    private static final String PASSWORD = "Learn-Pass123";
    private static final String SECRET = Base64.getEncoder().encodeToString(
            "betterlearning-m3-storage-integration-secret".getBytes(StandardCharsets.UTF_8));

    @Container
    static final PostgreSQLContainer POSTGRES = IntegrationContainers.postgres();

    @Container
    static final GenericContainer<?> REDIS = IntegrationContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationProperties.registerInfrastructure(registry, POSTGRES, REDIS);
        IntegrationProperties.registerJwt(registry, SECRET, "m3-test", "m3-client");
        IntegrationProperties.registerRateLimits(registry);
        IntegrationProperties.registerCorsOrigins(registry, "http://localhost:3000");
        registry.add("app.files.root", FILE_ROOT::toString);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JsonMapper json;

    @Autowired
    private TutorTurnService turns;

    @Autowired
    private FrozenAnswerService frozen;

    @Autowired
    private QuestionAnswerService current;

    private TestHttpClient http;

    @BeforeEach
    void clean() {
        http = new TestHttpClient(port, json);
        TestData.clean(jdbc, redis);
    }

    @AfterAll
    static void removeOwnedFiles() throws IOException {
        try (var paths = Files.walk(FILE_ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void oneActiveTurnAppliesAcrossQuestionsAndDuplicateRequestKeepsOriginal() throws Exception {
        Account alice = account("alice_01");
        Account bob = account("bob_02");
        String firstQuestion = question(alice);
        String secondQuestion = question(alice);
        String request = UUID.randomUUID().toString();
        TutorStorageModels.Creation first = turns.createOrGet(alice.id(), firstQuestion, request, "解释这一步");
        assertThat(first.created()).isTrue();
        assertThat(turns.createOrGet(alice.id(), firstQuestion, request, "解释这一步").created()).isFalse();
        assertConflict(() -> turns.createOrGet(alice.id(), firstQuestion, request, "changed"));
        assertConflict(() -> turns.createOrGet(alice.id(), secondQuestion, UUID.randomUUID().toString(), "another"));
        assertThat(turns.pending(alice.id(), firstQuestion)).isPresent();
        assertThat(turns.pending(alice.id(), secondQuestion)).isEmpty();
        String bobQuestion = question(bob);
        assertThat(turns.createOrGet(bob.id(), bobQuestion, UUID.randomUUID().toString(), "独立账号").created()).isTrue();
        assertThatThrownBy(() -> turns.get(bob.id(), firstQuestion, first.turn().getTurnId()))
                .isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND));
        assertThat(turns.cancel(alice.id(), firstQuestion, first.turn().getTurnId()).getFailureCode())
                .isEqualTo("TABLET_CANCELLED");
        assertThat(turns.createOrGet(alice.id(), secondQuestion, UUID.randomUUID().toString(), "next").created()).isTrue();
    }

    @Test
    void frozenFilesSurviveCurrentAnswerReplacementAndNewTurnFreeze() throws Exception {
        Account alice = account("alice_01");
        Account bob = account("bob_02");
        String question = question(alice);
        String turn = turns.createOrGet(alice.id(), question, UUID.randomUUID().toString(), "第一轮").turn().getTurnId();
        AnswerModels.SaveManifest first = AnswerTestData.manifest(turn, null);
        TutorStorageModels.Acceptance accepted = frozen.accept(alice.id(), question, turn, first, parts(first));
        String ref = accepted.turn().getFileRef();
        assertThat(accepted.newlyBound()).isTrue();
        assertThat(turns.pending(alice.id(), question)).isEmpty();
        assertThat(frozen.accept(alice.id(), question, turn, first, parts(first)).newlyBound()).isFalse();
        assertConflict(() -> turns.finish(alice.id(), question, turn, ref, "提前结果", null, null));
        assertThat(turns.claim(alice.id(), question, turn, false).claimed()).isTrue();
        assertConflict(() -> turns.cancel(alice.id(), question, turn));
        turns.finish(alice.id(), question, turn, ref, "第一轮回答", null, null);
        String second = turns.createOrGet(alice.id(), question, UUID.randomUUID().toString(), "第二轮").turn().getTurnId();
        AnswerModels.SaveManifest next = AnswerTestData.manifest(second, null);
        String secondRef = frozen.accept(alice.id(), question, second, next, parts(next)).turn().getFileRef();
        AnswerModels.SaveManifest ordinary = AnswerTestData.manifest(UUID.randomUUID().toString(), null);
        String currentRef = current.save(alice.id(), question, ordinary, parts(ordinary)).fileRef();
        AnswerModels.SaveManifest replacement = AnswerTestData.manifest(UUID.randomUUID().toString(), currentRef);
        String replacementRef = current.save(alice.id(), question, replacement, parts(replacement)).fileRef();
        assertThat(List.of(ref, secondRef, currentRef, replacementRef)).doesNotHaveDuplicates();
        assertThat(frozen.block(alice.id(), question, turn, ref, 0, false)).isEqualTo(AnswerTestData.png(32));
        assertThat(frozen.block(alice.id(), question, turn, ref, 0, true)).isEqualTo(AnswerTestData.png(16));
        assertThat(frozen.block(alice.id(), question, second, secondRef, 0, false)).isEqualTo(AnswerTestData.png(32));
        assertThat(frozen.manifest(alice.id(), question, turn).saveRequestId()).isEqualTo(turn);
        assertThat(frozen.modelInput(alice.id(), question, turn).images()).hasSize(1);
        assertThatThrownBy(() -> frozen.block(bob.id(), question, turn, ref, 0, false))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> frozen.block(alice.id(), question, turn, secondRef, 0, false))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void missingBlockNeverBindsAndManualRetryReusesFrozenReference() throws Exception {
        Account alice = account("alice_01");
        String question = question(alice);
        String turn = turns.createOrGet(alice.id(), question, UUID.randomUUID().toString(), "辅导").turn().getTurnId();
        AnswerModels.SaveManifest manifest = AnswerTestData.manifest(turn, null);
        List<Part> missing = parts(manifest);
        missing.remove(2);
        assertThatThrownBy(() -> frozen.accept(alice.id(), question, turn, manifest, missing))
                .isInstanceOf(BusinessException.class);
        assertThat(turns.get(alice.id(), question, turn).getFileRef()).isNull();
        String ref = frozen.accept(alice.id(), question, turn, manifest, parts(manifest)).turn().getFileRef();
        assertThat(turns.claim(alice.id(), question, turn, false).claimed()).isTrue();
        assertThat(turns.claim(alice.id(), question, turn, false).claimed()).isFalse();
        assertThatThrownBy(() -> turns.finish(alice.id(), question, turn, ref, " ", "MODEL_UNAVAILABLE", "失败"))
                .isInstanceOf(BusinessException.class);
        turns.finish(alice.id(), question, turn, ref, null, "MODEL_UNAVAILABLE", "模型调用失败，请稍后重试");
        assertConflict(() -> turns.claim(alice.id(), question, turn, false));
        String otherQuestion = question(alice);
        String otherTurn = turns.createOrGet(alice.id(), otherQuestion, UUID.randomUUID().toString(), "其他题").turn().getTurnId();
        assertConflict(() -> turns.claim(alice.id(), question, turn, true));
        turns.cancel(alice.id(), otherQuestion, otherTurn);
        assertThat(turns.claim(alice.id(), question, turn, true).claimed()).isTrue();
        assertThat(turns.finish(alice.id(), question, turn, ref, "重试成功", null, null).getFileRef()).isEqualTo(ref);
        assertThat(turns.finish(alice.id(), question, turn, ref, null, "LATE_FAILURE", "迟到失败").getAnswerMarkdown())
                .isEqualTo("重试成功");
    }

    private List<Part> parts(AnswerModels.SaveManifest manifest) throws Exception {
        List<Part> parts = new ArrayList<>();
        parts.add(part("manifest", json.writeValueAsBytes(manifest), MediaType.APPLICATION_JSON));
        parts.add(part("chunk_0000", AnswerTestData.png(32), MediaType.IMAGE_PNG));
        parts.add(part("question_0000", AnswerTestData.png(16), MediaType.IMAGE_PNG));
        return parts;
    }

    private static Part part(String name, byte[] bytes, MediaType type) {
        MockPart part = new MockPart(name, name, bytes);
        part.getHeaders().setContentType(type);
        return part;
    }

    private Account account(String username) throws Exception {
        assertThat(http.post("/api/v1/users", TestData.registerJson(username, PASSWORD)).statusCode()).isEqualTo(201);
        HttpResponse<String> login = http.post("/api/v1/sessions", TestData.loginJson(username, PASSWORD));
        assertThat(login.statusCode()).isEqualTo(201);
        long id = jdbc.queryForObject("SELECT account_id FROM app_user WHERE username = ?", Long.class, username);
        return new Account(id, (String) http.jsonBody(login).get("accessToken"));
    }

    private String question(Account account) throws Exception {
        HttpResponse<String> response = http.post("/api/v1/learning/questions", json.writeValueAsString(Map.of(
                "requestId", UUID.randomUUID().toString(), "subject", "OTHER", "bodyMarkdown", AnswerTestData.BODY)), account.token());
        assertThat(response.statusCode()).isEqualTo(201);
        return (String) http.jsonBody(response).get("questionId");
    }

    private static void assertConflict(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(BusinessException.class, failure -> assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.TUTOR_TURN_CONFLICT));
    }

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("betterlearning-m3-storage-it-");
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record Account(long id, String token) {
        @Override
        public String toString() {
            return "Account[id=" + id + ", token=[PROTECTED]]";
        }
    }
}
