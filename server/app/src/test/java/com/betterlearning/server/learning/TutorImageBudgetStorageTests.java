package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.config.AppFilesProperties;
import com.betterlearning.server.learning.service.impl.AnswerFileStore;
import com.betterlearning.server.learning.validation.AnswerManifestValidator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

/** 真实完整 M2 文件包验证；32 MiB 是辅导模型预算，M2 存储仍接受至 64 MiB。 */
class TutorImageBudgetStorageTests {
    @TempDir
    Path directory;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void fullAnswerWithThreeTwelveMibCompositePngsStillPersists() throws Exception {
        AnswerFileStore store = store();
        TutorBudgetTestData.Fixture fixture = TutorBudgetTestData.fixture("1", UUID.randomUUID().toString(),
                List.of(12 * TutorBudgetTestData.MIB, 12 * TutorBudgetTestData.MIB,
                12 * TutorBudgetTestData.MIB), TutorBudgetTestData.smallPng().length, json);
        long compositeBytes = fixture.compositePngs().stream().mapToLong(bytes -> bytes.length).sum();
        assertThat(compositeBytes).isEqualTo(36L * TutorBudgetTestData.MIB);
        AnswerFileStore.Candidate saved = store.save(fixture.manifest(), fixture.snapshot(), fixture.parts());
        try {
            assertThat(store.manifest(saved.fileRef()).source()).isEqualTo(fixture.manifest());
            for (int index = 0; index < 3; index++) {
                String partName = String.format(java.util.Locale.ROOT, "chunk_%04d", index);
                assertThat(store.block(saved.fileRef(), partName).length)
                        .isEqualTo(12 * TutorBudgetTestData.MIB);
            }
            assertThat(store.block(saved.fileRef(), "question_0000").length)
                    .isEqualTo(fixture.rasterPng().length);
        } finally {
            store.discard(saved);
        }
    }

    @Test
    void exactlyThirtyTwoMibCompositeWithLargeQuestionRasterPersists() throws Exception {
        AnswerFileStore store = store();
        TutorBudgetTestData.Fixture fixture = TutorBudgetTestData.fixture("1", UUID.randomUUID().toString(),
                List.of(16 * TutorBudgetTestData.MIB, 16 * TutorBudgetTestData.MIB),
                12 * TutorBudgetTestData.MIB, json);
        assertThat(fixture.compositePngs().stream().mapToLong(bytes -> bytes.length).sum())
                .isEqualTo(32L * TutorBudgetTestData.MIB);
        assertThat(fixture.rasterPng().length).isEqualTo(12 * TutorBudgetTestData.MIB);
        AnswerFileStore.Candidate saved = store.save(fixture.manifest(), fixture.snapshot(), fixture.parts());
        try {
            assertThat(store.block(saved.fileRef(), "chunk_0000").length)
                    .isEqualTo(16 * TutorBudgetTestData.MIB);
            assertThat(store.block(saved.fileRef(), "chunk_0001").length)
                    .isEqualTo(16 * TutorBudgetTestData.MIB);
            assertThat(store.block(saved.fileRef(), "question_0000").length)
                    .isEqualTo(12 * TutorBudgetTestData.MIB);
        } finally {
            store.discard(saved);
        }
    }

    @Test
    void fullM2PackageOfExactlySixtyFourMibIsStillAccepted() throws Exception {
        AnswerFileStore store = store();
        int rasterBytes = TutorBudgetTestData.smallPng().length;
        TutorBudgetTestData.Fixture fixture = TutorBudgetTestData.fixture("1", UUID.randomUUID().toString(),
                List.of(16 * TutorBudgetTestData.MIB, 16 * TutorBudgetTestData.MIB,
                16 * TutorBudgetTestData.MIB, 16 * TutorBudgetTestData.MIB - rasterBytes),
                rasterBytes, json);
        long totalBytes = fixture.compositePngs().stream().mapToLong(bytes -> bytes.length).sum()
                + fixture.rasterPng().length;
        assertThat(totalBytes).isEqualTo(AnswerManifestValidator.MAX_TOTAL_PNG_BYTES);
        AnswerFileStore.Candidate saved = store.save(fixture.manifest(), fixture.snapshot(), fixture.parts());
        try {
            assertThat(store.manifest(saved.fileRef()).source().blocks()).hasSize(4);
            assertThat(store.block(saved.fileRef(), "chunk_0003").length)
                    .isEqualTo(16 * TutorBudgetTestData.MIB - rasterBytes);
        } finally {
            store.discard(saved);
        }
    }

    @Test
    void fullM2PackageOverSixtyFourMibIsStillRejectedWithoutCandidate() throws Exception {
        AnswerFileStore store = store();
        int rasterBytes = TutorBudgetTestData.smallPng().length;
        TutorBudgetTestData.Fixture fixture = TutorBudgetTestData.fixture("1", UUID.randomUUID().toString(),
                List.of(16 * TutorBudgetTestData.MIB, 16 * TutorBudgetTestData.MIB,
                16 * TutorBudgetTestData.MIB, 16 * TutorBudgetTestData.MIB),
                rasterBytes, json);
        long totalBytes = fixture.compositePngs().stream().mapToLong(bytes -> bytes.length).sum()
                + fixture.rasterPng().length;
        assertThat(totalBytes).isGreaterThan(AnswerManifestValidator.MAX_TOTAL_PNG_BYTES);
        assertThatThrownBy(() -> store.save(fixture.manifest(), fixture.snapshot(), fixture.parts()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.UPLOAD_TOO_LARGE);
        try (var objects = Files.list(directory.resolve("objects"));
                var staging = Files.list(directory.resolve("staging"))) {
            assertThat(objects.toList()).isEmpty();
            assertThat(staging.toList()).isEmpty();
        }
    }

    private AnswerFileStore store() {
        AppFilesProperties properties = new AppFilesProperties();
        properties.setRoot(directory.toString());
        return new AnswerFileStore(properties, json);
    }
}
