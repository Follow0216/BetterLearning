package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.learning.validation.AnswerManifestValidator;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

class AnswerPressureContractTests {
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES).build();

    @Test
    void oldManifestAndExplicitNullRemainFixedWidthWithoutNewSerializedNullFields() throws Exception {
        AnswerModels.SaveManifest old = AnswerTestData.manifest(UUID.randomUUID().toString(), null);
        String json = mapper.writeValueAsString(old);
        assertThat(json).doesNotContain("pressure", "penKind");
        AnswerModels.SaveManifest restored = mapper.readValue(json, AnswerModels.SaveManifest.class);
        assertThatCode(() -> AnswerManifestValidator.validate(restored, AnswerTestData.BODY)).doesNotThrowAnyException();
        AnswerModels.Stroke explicitNull = mapper.readValue(
                "{\"strokeId\":\"s\",\"color\":\"#152744\",\"width\":2,\"penKind\":null,"
                        + "\"points\":[{\"x\":0,\"y\":0,\"pressure\":null}]}", AnswerModels.Stroke.class);
        assertThat(explicitNull.penKind()).isNull();
        assertThat(explicitNull.points().get(0).pressure()).isNull();
        assertThat(mapper.writeValueAsString(explicitNull)).doesNotContain("pressure", "penKind");
    }

    @Test
    void allPensAndNormalizedPressureRoundTripThroughStoredAndReadDocuments() throws Exception {
        for (String kind : List.of("ballpoint", "fountain", "highlighter")) {
            AnswerModels.SaveManifest source = withStroke(new AnswerModels.Stroke("s", "#4DEAB308", 4.0,
                    List.of(new AnswerModels.Point(0.0, 0.0, 0.0), new AnswerModels.Point(1.0, 1.0, 1.0)), kind));
            assertThatCode(() -> AnswerManifestValidator.validate(source, AnswerTestData.BODY)).doesNotThrowAnyException();
            AnswerModels.StoredManifest stored = new AnswerModels.StoredManifest(source,
                    new AnswerModels.QuestionSnapshot("1", "MATH", AnswerTestData.BODY));
            AnswerModels.StoredManifest restored = mapper.readValue(mapper.writeValueAsString(stored),
                    AnswerModels.StoredManifest.class);
            assertThat(restored.source().document().strokes()).isEqualTo(source.document().strokes());
            AnswerModels.ReadDocument read = new AnswerModels.ReadDocument(1, null, restored.source().document().strokes());
            AnswerModels.ReadDocument reread = mapper.readValue(mapper.writeValueAsString(read), AnswerModels.ReadDocument.class);
            assertThat(reread.strokes()).isEqualTo(source.document().strokes());
        }
    }

    @Test
    void rejectsUnknownPensAndOutOfRangeOrNonFinitePressure() throws Exception {
        AnswerModels.SaveManifest unknown = withStroke(new AnswerModels.Stroke("s", "#152744", 4.0,
                List.of(new AnswerModels.Point(0.0, 0.0)), "brush"));
        assertThatThrownBy(() -> AnswerManifestValidator.validate(unknown, AnswerTestData.BODY))
                .isInstanceOf(BusinessException.class);
        for (Double pressure : List.of(-0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY)) {
            AnswerModels.SaveManifest invalid = withStroke(new AnswerModels.Stroke("s", "#152744", 4.0,
                    List.of(new AnswerModels.Point(0.0, 0.0, pressure)), "fountain"));
            assertThatThrownBy(() -> AnswerManifestValidator.validate(invalid, AnswerTestData.BODY))
                    .isInstanceOf(BusinessException.class);
        }
    }

    @Test
    void pressureCannotBypassMaximumWidthContainmentAndUnknownJsonStillFails() throws Exception {
        AnswerModels.SaveManifest outside = withStroke(new AnswerModels.Stroke("s", "#152744", 4.0,
                List.of(new AnswerModels.Point(-8.0, 0.0, 0.0)), "fountain"));
        assertThatThrownBy(() -> AnswerManifestValidator.validate(outside, AnswerTestData.BODY))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> mapper.readValue("{\"x\":0,\"y\":0,\"pressure\":0.5,\"extra\":1}",
                AnswerModels.Point.class)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> mapper.readValue("{\"strokeId\":\"s\",\"color\":\"#000000\",\"width\":2,"
                + "\"points\":[],\"pen_kind\":\"fountain\"}", AnswerModels.Stroke.class)).isInstanceOf(Exception.class);
    }

    private AnswerModels.SaveManifest withStroke(AnswerModels.Stroke stroke) throws Exception {
        AnswerModels.SaveManifest base = AnswerTestData.manifest(UUID.randomUUID().toString(), null);
        return new AnswerModels.SaveManifest(1, base.saveRequestId(), null, base.canvas(),
                new AnswerModels.Document(1, base.document().questionLayout(), List.of(stroke)), base.blocks());
    }
}
