package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.learning.validation.AnswerManifestValidator;

import org.junit.jupiter.api.Test;

class AnswerManifestValidatorTests {
    @Test
    void acceptsCompleteNegativeWorldDocumentWithBothRasterGroups() throws Exception {
        AnswerModels.SaveManifest manifest = AnswerTestData.manifest(UUID.randomUUID().toString(), null);
        assertThatCode(() -> AnswerManifestValidator.validate(manifest, AnswerTestData.BODY))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingCompositeAndChangedQuestionBody() throws Exception {
        AnswerModels.SaveManifest valid = AnswerTestData.manifest(UUID.randomUUID().toString(), null);
        AnswerModels.SaveManifest missing = new AnswerModels.SaveManifest(1, valid.saveRequestId(), null,
                valid.canvas(), valid.document(), List.of());
        assertThatThrownBy(() -> AnswerManifestValidator.validate(missing, AnswerTestData.BODY))
                .isInstanceOfSatisfying(BusinessException.class, failure -> org.assertj.core.api.Assertions.assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.INVALID_PARAM));
        assertThatThrownBy(() -> AnswerManifestValidator.validate(valid, "changed question"))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsMissingCleanQuestionRasterInsteadOfAcceptingFlattenedOnlyDocument() throws Exception {
        AnswerModels.SaveManifest valid = AnswerTestData.manifest(UUID.randomUUID().toString(), null);
        AnswerModels.QuestionLayout layout = valid.document().questionLayout();
        AnswerModels.QuestionLayout missing = new AnswerModels.QuestionLayout(layout.questionBodySha256(),
                layout.contentWidth(), layout.fontSize(), layout.rendererId(), layout.bounds(), layout.blocks(), List.of());
        AnswerModels.SaveManifest manifest = new AnswerModels.SaveManifest(1, valid.saveRequestId(), null,
                valid.canvas(), new AnswerModels.Document(1, missing, valid.document().strokes()), valid.blocks());
        assertThatThrownBy(() -> AnswerManifestValidator.validate(manifest, AnswerTestData.BODY))
                .isInstanceOf(BusinessException.class);
    }
}
