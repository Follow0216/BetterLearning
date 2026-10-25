package com.betterlearning.server.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.betterlearning.server.agent.retrieval.RetrievalIndexStore;
import com.betterlearning.server.learning.service.impl.NoteServiceImpl;
import com.betterlearning.server.retrieval.HandwritingSourceReader;
import com.betterlearning.server.retrieval.MybatisRetrievalIndexStore;
import com.betterlearning.server.retrieval.RetrievalIndexWorker;
import com.betterlearning.server.retrieval.StructuredHandwritingIndexer;
import com.betterlearning.server.agent.config.HandwritingMethodsProperties;
import com.betterlearning.server.agent.service.HandwritingIndexOcr;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Verifies the production non-HTTP worker entry point with isolated PostgreSQL and the integrated P4/P6 schema. */
@Tag("integration")
class PureP4WorkerContextIntegrationTests {
    @Test
    void workerLoadsRetrievalWithoutHttpOnlyNoteService() {
        List<String> expectedVersions = List.of("1", "2", "3", "4", "5", "2026102501", "2026102502",
                "2026102503", "2026102504", "2026102505", "2026102506", "2026102507", "2026102508",
                "2026102509", "2026102510");
        try (RuntimeBusinessFixture fixture = RuntimeBusinessFixture.start()) {
            var context = fixture.context();
            assertThat(context.getBean(RetrievalIndexStore.class)).isInstanceOf(MybatisRetrievalIndexStore.class);
            assertThat(context.getBean(RetrievalIndexWorker.class)).isNotNull();
            assertThat(context.getBean(HandwritingSourceReader.class)).isNotNull();
            assertThat(context.getBean(StructuredHandwritingIndexer.class)).isNotNull();
            assertThat(context.getBean(HandwritingIndexOcr.class)).isNotNull();
            assertThat(context.getBean(HandwritingMethodsProperties.class).isEnabled()).isFalse();
            assertThat(context.getBean(HandwritingMethodsProperties.class).isRecallEnabled()).isFalse();
            assertThat(context.getBeansOfType(NoteServiceImpl.class)).isEmpty();
            assertThat(fixture.jdbc().queryForList(
                    "select version from flyway_schema_history where success = true order by installed_rank", String.class))
                    .containsExactlyElementsOf(expectedVersions);
            assertThat(fixture.jdbc().queryForObject(
                    "select count(*) from pg_extension where extname = 'vector'", Integer.class)).isEqualTo(1);
        }
    }
}
