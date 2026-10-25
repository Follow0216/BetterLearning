package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.config.AppFilesProperties;
import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.learning.service.impl.AnswerFileStore;

import jakarta.servlet.http.Part;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockPart;

import tools.jackson.databind.json.JsonMapper;

class AnswerFileStoreTests {
    @TempDir
    Path directory;

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void newStoreInstanceReadsCompletePackageFromSameDirectory() throws Exception {
        AnswerFileStore store = store();
        AnswerModels.SaveManifest manifest = AnswerTestData.manifest(UUID.randomUUID().toString(), null);
        AnswerModels.QuestionSnapshot snapshot = new AnswerModels.QuestionSnapshot("1", "OTHER", AnswerTestData.BODY);
        AnswerFileStore.Candidate saved = store.save(manifest, snapshot, parts(manifest));
        AnswerFileStore reopened = store();
        assertThat(reopened.manifest(saved.fileRef()).source()).isEqualTo(manifest);
        assertThat(reopened.manifest(saved.fileRef()).questionSnapshot()).isEqualTo(snapshot);
        assertThat(reopened.block(saved.fileRef(), "chunk_0000"))
                .isEqualTo(AnswerTestData.png(32));
        assertThat(reopened.block(saved.fileRef(), "question_0000"))
                .isEqualTo(AnswerTestData.png(16));
        assertThatThrownBy(() -> store.deletePublished(saved.fileRef()))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("数据库回收判定");
        store.holdUnknown(saved.fileRef());
        assertThat(reopened.hasUnknownHold(saved.fileRef())).isTrue();
        assertThat(directory.resolve("objects").resolve(saved.fileRef())).exists();
    }

    @Test
    void rejectsMissingOrDuplicatePartsAndLeavesNoCandidate() throws Exception {
        AnswerFileStore store = store();
        AnswerModels.SaveManifest manifest = AnswerTestData.manifest(UUID.randomUUID().toString(), null);
        AnswerModels.QuestionSnapshot snapshot = new AnswerModels.QuestionSnapshot("1", "OTHER", AnswerTestData.BODY);
        List<Part> missing = parts(manifest);
        missing.remove(2);
        assertThatThrownBy(() -> store.save(manifest, snapshot, missing)).isInstanceOf(BusinessException.class);
        List<Part> duplicate = parts(manifest);
        duplicate.add(duplicate.get(1));
        assertThatThrownBy(() -> store.save(manifest, snapshot, duplicate)).isInstanceOf(BusinessException.class);
        try (var objects = Files.list(directory.resolve("objects"));
                var staging = Files.list(directory.resolve("staging"))) {
            assertThat(objects.toList()).isEmpty();
            assertThat(staging.toList()).isEmpty();
        }
    }

    @Test
    void rejectsRelativeRootBeforeCreatingDirectories() {
        AppFilesProperties properties = new AppFilesProperties();
        properties.setRoot("relative-m2-root");
        assertThatThrownBy(() -> new AnswerFileStore(properties, json))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("绝对目录");
    }

    private AnswerFileStore store() {
        AppFilesProperties properties = new AppFilesProperties();
        properties.setRoot(directory.toString());
        return new AnswerFileStore(properties, json);
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
}
