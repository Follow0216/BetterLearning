package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.validation.AnswerManifestValidator;
import com.betterlearning.server.multiagent.MultiAgentModels.Anchor;
import com.betterlearning.server.multiagent.MultiAgentModels.PublishSource;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceType;
import com.betterlearning.server.multiagent.SourceStore;
import com.betterlearning.server.support.IntegrationContainers;
import com.betterlearning.server.support.IntegrationProperties;
import com.betterlearning.server.support.TestData;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** PostgreSQL 真事务验证版本、索引 CAS、账号隔离和撤权。 */
@Testcontainers
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Tag("integration")
class SourceStoreIntegrationTests {
    private static final String DIGEST_A = "a".repeat(64);
    private static final String DIGEST_B = "b".repeat(64);
    private static final String SECRET = Base64.getEncoder().encodeToString(
            "betterlearning-source-store-integration-secret".getBytes(StandardCharsets.UTF_8));
    private static final Path FILE_ROOT = temporaryRoot();

    @Container
    static final PostgreSQLContainer POSTGRES = IntegrationContainers.postgres();

    @Container
    static final GenericContainer<?> REDIS = IntegrationContainers.redis();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        IntegrationProperties.registerInfrastructure(registry, POSTGRES, REDIS);
        IntegrationProperties.registerJwt(registry, SECRET, "source", "source");
        IntegrationProperties.registerRateLimits(registry);
        IntegrationProperties.registerCorsOrigins(registry, "http://localhost:3000");
        registry.add("app.files.root", FILE_ROOT::toString);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private SourceStore sources;

    @BeforeEach
    void clean() {
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
    void versionsAreImmutableAndOnlyCurrentVersionCanBecomeReady() {
        long alice = account("alice_source");
        long bob = account("bob_source");
        var first = sources.publish(alice, question("q1", "第一版", null));
        var second = sources.publish(alice, question("q1", "第二版", 1L));
        assertThat(second.source().sourceId()).isEqualTo(first.source().sourceId());
        assertThat(first.version().version()).isEqualTo(1);
        assertThat(second.version().version()).isEqualTo(2);
        assertThat(sources.get(alice, first.source().sourceId(), 1).version().bodyMarkdown())
                .isEqualTo("第一版");
        assertThat(sources.markIndex(alice, first.source().sourceId(), 1, "READY")).isFalse();
        assertThat(sources.markIndex(alice, first.source().sourceId(), 2, "READY")).isTrue();
        var rebuilt = sources.reindex(alice, first.source().sourceId(), 2, 1);
        assertThat(rebuilt.indexRevision()).isEqualTo(2);
        assertThat(rebuilt.status()).isEqualTo("INDEX_PENDING");
        assertThat(sources.markIndex(alice, first.source().sourceId(), 2, "READY")).isFalse();
        assertThat(sources.markIndex(alice, first.source().sourceId(), 2, 1, "READY")).isFalse();
        assertThat(sources.markIndex(alice, first.source().sourceId(), 2, 2, "READY")).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_outbox WHERE aggregate_id = ? "
                + "AND index_revision = 2", Long.class, first.source().sourceId())).isEqualTo(1);
        assertThatThrownBy(() -> sources.publish(alice, question("q1", "旧 CAS", 1L)))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> sources.get(bob, first.source().sourceId(), 1))
                .isInstanceOf(BusinessException.class);
        assertThat(sources.findOrigin(bob, SourceType.QUESTION, "q1")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_source_version", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_outbox", Long.class)).isEqualTo(3);

        sources.withdraw(alice, first.source().sourceId());
        assertThat(sources.findOrigin(alice, SourceType.QUESTION, "q1")).isEmpty();
        assertThatThrownBy(() -> sources.get(alice, first.source().sourceId(), 1))
                .isInstanceOf(BusinessException.class);
        assertThat(sources.markIndex(alice, first.source().sourceId(), 2, "READY")).isFalse();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_outbox", Long.class)).isEqualTo(4);
    }

    @Test
    void transactionRollbackAndSnapshotIdempotency() {
        long accountId = account("snapshot_source");
        assertThatThrownBy(() -> sources.publish(accountId, new PublishSource(SourceType.QUESTION,
                "q-rollback", "题目", "正文", digest("正文"), null, "INVALID", null,
                UUID.randomUUID().toString())))
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_source", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_outbox", Long.class)).isZero();

        String fileRef = UUID.randomUUID().toString();
        PublishSource command = new PublishSource(SourceType.TUTOR_SNAPSHOT, "request-1", "冻结作答",
                null, DIGEST_A, fileRef, null, null, "snapshot:request-1");
        var first = sources.publish(accountId, command);
        var replay = sources.publish(accountId, command);
        assertThat(replay.version().version()).isEqualTo(1);
        assertThat(replay.source().sourceId()).isEqualTo(first.source().sourceId());
        assertThatThrownBy(() -> sources.publish(accountId, new PublishSource(SourceType.TUTOR_SNAPSHOT,
                "request-1", "冻结作答", null, DIGEST_B, fileRef, null, null,
                "snapshot:request-1")))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_source_version", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_outbox", Long.class)).isEqualTo(1);
    }

    @Test
    void operationReplayComparesCompleteNormalizedPayloadAndWithdrawalStartsNewGeneration() {
        long accountId = account("source_replay");
        String operationId = UUID.randomUUID().toString();
        PublishSource firstCommand = new PublishSource(SourceType.NOTE, "note-1", "标题", "甲\r\n乙",
                digest("甲\n乙"), null, null, 0L, operationId);
        var first = sources.publish(accountId, firstCommand);
        var replay = sources.publish(accountId, new PublishSource(SourceType.NOTE, "note-1", "标题",
                "甲\n乙", digest("甲\n乙"), null, null, 0L, operationId));
        assertThat(replay.version().version()).isEqualTo(1);
        assertThat(replay.source().sourceId()).isEqualTo(first.source().sourceId());
        assertThatThrownBy(() -> sources.publish(accountId, new PublishSource(SourceType.NOTE,
                "note-1", "改标题", "甲\n乙", digest("甲\n乙"), null, null, 0L, operationId)))
                .isInstanceOf(BusinessException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_source_version", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_source_operation", Long.class)).isEqualTo(1);
        sources.withdraw(accountId, first.source().sourceId());
        assertThatThrownBy(() -> sources.publish(accountId, firstCommand)).isInstanceOf(BusinessException.class);
        var replacement = sources.publish(accountId, new PublishSource(SourceType.NOTE, "note-1", "新标题",
                "新正文", digest("新正文"), null, null, 0L, UUID.randomUUID().toString()));
        assertThat(replacement.source().sourceId()).isNotEqualTo(first.source().sourceId());
        assertThat(replacement.version().version()).isEqualTo(1);
        assertThat(sources.findOrigin(accountId, SourceType.NOTE, "note-1").orElseThrow()
                .source().sourceId()).isEqualTo(replacement.source().sourceId());
    }

    @Test
    void persistentPageCursorBindsAccountAndTypeAndSkipsNewerInsertions() {
        long alice = account("page_alice");
        long bob = account("page_bob");
        sources.publish(alice, question("q1", "1", null));
        sources.publish(alice, question("q2", "2", null));
        sources.publish(alice, question("q3", "3", null));
        var first = sources.page(alice, SourceType.QUESTION, 2, null);
        assertThat(first.items()).hasSize(2);
        assertThat(first.nextCursor()).isNotNull();
        assertThatThrownBy(() -> sources.page(bob, SourceType.QUESTION, 2, first.nextCursor()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> sources.page(alice, SourceType.NOTE, 2, first.nextCursor()))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> sources.page(alice, SourceType.QUESTION, 2,
                UUID.randomUUID().toString())).isInstanceOf(BusinessException.class);
        sources.publish(alice, question("q4", "4", null));
        var second = sources.page(alice, SourceType.QUESTION, 2, first.nextCursor());
        assertThat(second.items()).hasSize(1);
        assertThat(second.nextCursor()).isNull();
        assertThat(second.items().stream().map(item -> item.sourceId()).toList())
                .doesNotContainAnyElementsOf(first.items().stream().map(item -> item.sourceId()).toList());
        assertThat(second.items().stream().map(item -> item.originId()).toList()).doesNotContain("q4");
    }

    @Test
    void failedFirstIndexCanBuildAnchorsInSecondRevisionButStaleWorkerCannot() {
        long accountId = account("reindex_source");
        var source = sources.publish(accountId, question("q-failed", "待索引", null));
        String sourceId = source.source().sourceId();
        assertThat(sources.markIndex(accountId, sourceId, 1, 1, "FAILED")).isTrue();
        var pending = sources.reindex(accountId, sourceId, 1, 1);
        assertThat(pending.indexRevision()).isEqualTo(2);
        assertThat(pending.status()).isEqualTo("INDEX_PENDING");
        String text = "待索引";
        Anchor anchor = new Anchor(UUID.randomUUID().toString(), sourceId, 1, "MARKDOWN_BLOCK", 0,
                digest(text), "{\"schemaVersion\":1,\"markdownLocator\":{\"blockOrdinal\":0,"
                + "\"startOffset\":0,\"endOffset\":3}}", text);
        assertThatThrownBy(() -> sources.addAnchors(accountId, sourceId, 1, List.of(anchor)))
                .isInstanceOf(BusinessException.class);
        assertThat(sources.get(accountId, sourceId, 1).anchors()).isEmpty();
        sources.addAnchors(accountId, sourceId, 1, 2, List.of(anchor, anchor));
        sources.addAnchors(accountId, sourceId, 1, 2, List.of(anchor));
        assertThat(sources.get(accountId, sourceId, 1).anchors()).containsExactly(anchor);
        assertThatThrownBy(() -> sources.addAnchors(accountId, sourceId, 1, 2,
                List.of(new Anchor(UUID.randomUUID().toString(), sourceId, 1, "MARKDOWN_BLOCK", 0,
                        DIGEST_B, anchor.locatorJson(), text)))).isInstanceOf(BusinessException.class);
        assertThat(sources.markIndex(accountId, sourceId, 1, "READY")).isFalse();
        assertThat(sources.markIndex(accountId, sourceId, 1, 2, "READY")).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ma_anchor WHERE source_id = ?",
                Long.class, sourceId)).isEqualTo(1);
    }

    private long account(String name) {
        return jdbc.queryForObject("INSERT INTO app_user(username,password_hash) VALUES (?, 'test-hash') "
                + "RETURNING account_id", Long.class, name);
    }

    private static PublishSource question(String origin, String body, Long expected) {
        return new PublishSource(SourceType.QUESTION, origin, "题目", body, digest(body),
                null, null, expected, UUID.randomUUID().toString());
    }

    private static String digest(String body) {
        return AnswerManifestValidator.sha256(body.getBytes(StandardCharsets.UTF_8));
    }

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("betterlearning-source-store-it-");
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
