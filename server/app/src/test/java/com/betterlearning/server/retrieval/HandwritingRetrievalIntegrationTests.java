package com.betterlearning.server.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.ImageIO;
import javax.sql.DataSource;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore.Chunk;
import com.betterlearning.server.config.MybatisConfiguration;
import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.config.AppFilesProperties;
import com.betterlearning.server.learning.config.LearningMapperConfiguration;
import com.betterlearning.server.learning.mapper.RetentionMapper;
import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.learning.retention.DeletionJournal;
import com.betterlearning.server.learning.retention.SourceDeletionService;
import com.betterlearning.server.learning.service.impl.AnswerFileStore;
import com.betterlearning.server.learning.service.impl.SourceStoreImpl;
import com.betterlearning.server.learning.validation.AnswerManifestValidator;
import com.betterlearning.server.learning.validation.SourceAnchorValidator;
import com.betterlearning.server.multiagent.MultiAgentModels.Anchor;
import com.betterlearning.server.multiagent.MultiAgentModels.AnchorLocator;
import com.betterlearning.server.multiagent.MultiAgentModels.PublishSource;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceType;
import com.betterlearning.server.multiagent.SourceStore;

import jakarta.servlet.http.Part;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.mock.web.MockPart;
import org.springframework.http.MediaType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.json.JsonMapper;

/** Real PG and immutable file mechanism; stub OCR and two-dimensional vectors do not test semantic quality. */
@Testcontainers
@Tag("integration")
class HandwritingRetrievalIntegrationTests {
    private static final String BODY = "求解二次方程";
    private static final String OCR_TEXT = "用配方法完成平方";
    private static final String MODEL = "synthetic-mechanism-only";
    private static final DockerImageName IMAGE = DockerImageName.parse(
            "pgvector/pgvector@sha256:42e7f6b4e1eceb02ff14e3e6bc6108bbe259abbe83879dc1845d0da1ddeb555d")
            .asCompatibleSubstituteFor("postgres");
    private static final Path FILE_ROOT = temporaryRoot();
    private static final Path JOURNAL_ROOT = temporaryRoot();

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(IMAGE)
            .withDatabaseName("betterlearning").withUsername("test").withPassword("test");

    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static JsonMapper json;
    private static AnswerFileStore files;
    private static SourceStore sources;
    private static RetrievalIndexStore indexes;
    private static CitationService citations;
    private long owner;
    private long stranger;

    @Configuration(proxyBeanMethods = false)
    @MapperScan("com.betterlearning.server.retrieval.mapper")
    @EnableTransactionManagement
    static class RetrievalTestConfiguration {
        @Bean(initMethod = "recoverBeforeServing")
        SourceDeletionService sourceDeletionService(RetentionMapper retention,
                PlatformTransactionManager transactionManager) throws IOException {
            return new SourceDeletionService(DeletionJournal.initialize(JOURNAL_ROOT), retention,
                    new TransactionTemplate(transactionManager));
        }
    }

    @BeforeAll
    static void setupDatabase() {
        DataSource dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        json = JsonMapper.builder().build();
        AppFilesProperties fileProperties = new AppFilesProperties();
        fileProperties.setRoot(FILE_ROOT.toString());
        files = new AnswerFileStore(fileProperties, json);
        context = new AnnotationConfigApplicationContext();
        String secret = Base64.getEncoder().encodeToString(
                "handwriting-retrieval-test-signing-key".getBytes(StandardCharsets.UTF_8));
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                "testCursorKey", Map.of("app.jwt.secret-base64", secret,
                "app.agent-retention.sweep-enabled", "false")));
        context.registerBean(DataSource.class, () -> dataSource);
        context.registerBean(PlatformTransactionManager.class, () -> new DataSourceTransactionManager(dataSource));
        context.registerBean(JsonMapper.class, () -> json);
        context.registerBean(AnswerFileStore.class, () -> files);
        context.register(MybatisConfiguration.class, LearningMapperConfiguration.class,
                RetrievalTestConfiguration.class, MybatisPlusAutoConfiguration.class,
                SourceAnchorValidator.class, SourceStoreImpl.class,
                MybatisRetrievalIndexStore.class, CitationService.class);
        context.refresh();
        sources = context.getBean(SourceStore.class);
        indexes = context.getBean(RetrievalIndexStore.class);
        citations = context.getBean(CitationService.class);
    }

    @AfterAll
    static void cleanup() throws IOException {
        if (context != null) {
            context.close();
        }
        try (var paths = Files.walk(FILE_ROOT)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE TABLE app_user RESTART IDENTITY CASCADE");
        owner = account("hand_owner");
        stranger = account("hand_stranger");
    }

    @Test
    void savedAnswerHandwritingIndexesResolvesOriginalBlocksAndRevokesAfterDeletion() throws Exception {
        long questionId = question(owner);
        byte[] left = png(1024, 32, Color.RED);
        byte[] right = png(16, 32, Color.BLUE);
        byte[] raster = png(16, 16, Color.GREEN);
        AnswerModels.SaveManifest manifest = manifest(left, right, raster);
        AnswerManifestValidator.validate(manifest, BODY);
        AnswerFileStore.Candidate saved = files.save(manifest,
                new AnswerModels.QuestionSnapshot(Long.toString(questionId), "OTHER", BODY),
                parts(manifest, left, right, raster));
        var published = sources.publish(owner, new PublishSource(SourceType.SAVED_ANSWER,
                Long.toString(questionId), "手写作答", null, saved.requestHash(), saved.fileRef(),
                "DRAFT", 0L, UUID.randomUUID().toString()));

        AtomicInteger ocrCalls = new AtomicInteger();
        HandwritingSourceReader reader = new HandwritingSourceReader(files, image -> {
            assertThat(image.mediaType()).isEqualTo("image/png");
            try {
                BufferedImage cropped = ImageIO.read(new ByteArrayInputStream(image.bytes()));
                assertThat(cropped).isNotNull();
                assertThat(cropped.getRGB(cropped.getWidth() / 4, cropped.getHeight() / 2))
                        .isEqualTo(Color.RED.getRGB());
                assertThat(cropped.getRGB(cropped.getWidth() * 3 / 4, cropped.getHeight() / 2))
                        .isEqualTo(Color.BLUE.getRGB());
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
            ocrCalls.incrementAndGet();
            return OCR_TEXT;
        }, json);
        HandwritingSourceReader.ParsedHandwriting parsed = reader.read(saved.fileRef());
        assertThat(parsed.ocrCalls()).isEqualTo(1);
        assertThat(ocrCalls.get()).isEqualTo(1);
        assertThat(parsed.anchors()).hasSize(1);
        var draft = parsed.anchors().get(0);
        AnchorLocator locator = json.readValue(draft.locatorJson(), AnchorLocator.class);
        assertThat(locator.schemaVersion()).isEqualTo(1);
        assertThat(locator.handwritingLocator().segments()).hasSize(2);
        assertThat(locator.handwritingLocator().segments()).allSatisfy(segment -> {
            assertThat(segment.questionRaster()).isFalse();
            assertThat(segment.strokeIds()).containsExactly("stroke-1");
        });
        assertThat(locator.handwritingLocator().segments().get(0).regionWorldBounds().x())
                .isLessThan(0);

        Anchor anchor = new Anchor(UUID.randomUUID().toString(), published.source().sourceId(), 1,
                draft.kind(), draft.ordinal(), draft.contentSha256(), draft.locatorJson(), draft.text());
        RetrievalIndexStore.Job job = indexes.claim().orElseThrow();
        assertThat(job.sourceId()).isEqualTo(published.source().sourceId());
        assertThat(indexes.complete(job, List.of(new Chunk(anchor, Long.toString(questionId),
                draft.text(), Set.of("配方法"), Set.of("完全平方"), new double[]{1, 0})), MODEL, 2))
                .isTrue();
        assertThat(sources.get(owner, published.source().sourceId(), 1).anchors())
                .extracting(Anchor::anchorId).containsExactly(anchor.anchorId());
        assertThat(indexes.candidates(owner, Set.of("完全平方"), Set.of("配方法"),
                new double[]{1, 0}, MODEL, 2)).extracting(item -> item.anchorId())
                .contains(anchor.anchorId());

        String citationId = publishedCitation(owner, questionId, published.source().sourceId(),
                anchor.anchorId());
        CitationService.Resolution resolved = citations.resolve(owner, citationId);
        assertThat(resolved.answerState()).isEqualTo("DRAFT");
        assertThat(resolved.handwritingLocator().segments()).hasSize(2);
        assertThat(resolved.handwritingLocator().segments()).extracting(segment -> segment.assetUrl())
                .containsExactly("/api/v2/learning/citations/" + citationId + "/assets/0",
                "/api/v2/learning/citations/" + citationId + "/assets/1");
        assertThat(citations.asset(owner, citationId, 0)).isEqualTo(left);
        assertThat(citations.asset(owner, citationId, 1)).isEqualTo(right);
        assertNotFound(() -> citations.resolve(stranger, citationId));
        assertNotFound(() -> citations.asset(stranger, citationId, 0));

        sources.withdraw(owner, published.source().sourceId());
        assertNotFound(() -> citations.resolve(owner, citationId));
        assertNotFound(() -> citations.asset(owner, citationId, 0));
    }

    private static AnswerModels.SaveManifest manifest(byte[] left, byte[] right, byte[] raster) {
        // Composite chunk coordinates are canvas-local grid offsets; stroke coordinates remain world-space.
        AnswerModels.Block first = block(0, "chunk_0000", 0, 0, 1024, 32, left);
        AnswerModels.Block second = block(1, "chunk_0001", 1024, 0, 16, 32, right);
        AnswerModels.Block question = block(0, "question_0000", -1024, -8, 16, 16, raster);
        AnswerModels.QuestionLayout layout = new AnswerModels.QuestionLayout(
                AnswerManifestValidator.sha256(BODY.getBytes(StandardCharsets.UTF_8)), 16.0, 16.0,
                "local-markdown-v1", new AnswerModels.Bounds(-1024.0, -8.0, 16.0, 16.0),
                List.of(new AnswerModels.LayoutBlock("body-0", -1024.0, -8.0, 16.0, 16.0)),
                List.of(question));
        AnswerModels.Stroke stroke = new AnswerModels.Stroke("stroke-1", "#000000", 2.0,
                List.of(new AnswerModels.Point(-2.0, 0.0), new AnswerModels.Point(2.0, 0.0)));
        return new AnswerModels.SaveManifest(1, UUID.randomUUID().toString(), null,
                new AnswerModels.Canvas(1040.0, 32.0, -1024.0, -8.0, "logical"),
                new AnswerModels.Document(1, layout, List.of(stroke)), List.of(first, second));
    }

    private static AnswerModels.Block block(int index, String partName, double x, double y,
            double width, double height, byte[] image) {
        return new AnswerModels.Block(index, partName, x, y, width, height, (int) width, (int) height,
                AnswerManifestValidator.sha256(image));
    }

    private static List<Part> parts(AnswerModels.SaveManifest manifest, byte[] left,
            byte[] right, byte[] raster) {
        return List.of(part("manifest", json.writeValueAsBytes(manifest), MediaType.APPLICATION_JSON),
                part("chunk_0000", left, MediaType.IMAGE_PNG),
                part("chunk_0001", right, MediaType.IMAGE_PNG),
                part("question_0000", raster, MediaType.IMAGE_PNG));
    }

    private static Part part(String name, byte[] bytes, MediaType type) {
        MockPart part = new MockPart(name, name, bytes);
        part.getHeaders().setContentType(type);
        return part;
    }

    private static byte[] png(int width, int height, Color color) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, color.getRGB());
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        image.flush();
        return output.toByteArray();
    }

    private static String publishedCitation(long accountId, long questionId,
            String sourceId, String anchorId) {
        String requestId = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();
        String stepId = UUID.randomUUID().toString();
        String resultId = UUID.randomUUID().toString();
        String citationId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ma_request(request_id,account_id,question_id,prompt,mode,snapshot_kind,"
                + "status,input_deadline_at) VALUES (?,?,?,?,?,?,?,CURRENT_TIMESTAMP + INTERVAL '5 minutes')",
                requestId, accountId, questionId, "提示", "HINT", "NEW_CAPTURE", "SUCCEEDED");
        jdbc.update("INSERT INTO ma_run(run_id,request_id,account_id,run_no,status,queue_deadline_at,run_deadline_at) "
                + "VALUES (?,?,?,?,?,CURRENT_TIMESTAMP + INTERVAL '5 minutes',"
                + "CURRENT_TIMESTAMP + INTERVAL '10 minutes')", runId, requestId, accountId, 1, "SUCCEEDED");
        jdbc.update("UPDATE ma_request SET current_run_id = ? WHERE account_id = ? AND request_id = ?",
                runId, accountId, requestId);
        jdbc.update("INSERT INTO ma_step(step_id,run_id,role,sequence,status,input_digest) "
                + "VALUES (?,?,?,?,?,?)", stepId, runId, "RETRIEVE", 1, "SUCCEEDED", "0".repeat(64));
        String claim = jdbc.queryForObject("SELECT COALESCE(text, '') FROM ma_anchor WHERE anchor_id = ?",
                String.class, anchorId);
        jdbc.update("INSERT INTO ma_evidence(evidence_id,run_id,step_id,type,source_id,version,anchor_id,claim) "
                + "VALUES (?,?,?,?,?,?,?,?)", UUID.randomUUID().toString(), runId, stepId,
                "HISTORY", sourceId, 1, anchorId, claim);
        jdbc.update("INSERT INTO ma_result(result_id,run_id,mode,status,body_markdown) VALUES (?,?,?,?,?)",
                resultId, runId, "HINT", "VERIFIED", "见引用");
        jdbc.update("INSERT INTO ma_citation(citation_id,result_id,source_id,version,anchor_id,label,source_type) "
                + "VALUES (?,?,?,?,?,?,?)", citationId, resultId, sourceId, 1, anchorId,
                "引用 1", "SAVED_ANSWER");
        return citationId;
    }

    private static long question(long accountId) {
        Long id = jdbc.queryForObject("INSERT INTO question(account_id,request_id,subject,body_markdown,summary) "
                + "VALUES (?,?,?,?,?) RETURNING question_id", Long.class, accountId,
                UUID.randomUUID().toString(), "OTHER", BODY, BODY);
        if (id == null) {
            fail("fixture question was not inserted");
        }
        return id;
    }

    private static long account(String name) {
        Long id = jdbc.queryForObject("INSERT INTO app_user(username,password_hash,role) "
                + "VALUES (?,?,?) RETURNING account_id", Long.class,
                name + UUID.randomUUID().toString().substring(0, 8), "test-hash", "USER");
        if (id == null) {
            fail("fixture account was not inserted");
        }
        return id;
    }

    private static void assertNotFound(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                error -> {
                    assertThat(error.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(error.getErrorCode().getStatus().value()).isEqualTo(404);
                });
    }

    private static Path temporaryRoot() {
        try {
            return Files.createTempDirectory("betterlearning-handwriting-rag-it-");
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
