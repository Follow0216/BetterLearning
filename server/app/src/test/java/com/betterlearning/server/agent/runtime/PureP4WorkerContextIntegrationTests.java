package com.betterlearning.server.agent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.betterlearning.server.agent.retrieval.RetrievalIndexStore;
import com.betterlearning.server.learning.service.impl.NoteServiceImpl;
import com.betterlearning.server.retrieval.HandwritingSourceReader;
import com.betterlearning.server.retrieval.MybatisRetrievalIndexStore;
import com.betterlearning.server.retrieval.RetrievalIndexWorker;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Verifies the production non-HTTP worker entry point with isolated PostgreSQL and P4 migration. */
@Tag("integration")
class PureP4WorkerContextIntegrationTests {
    @Test
    void workerLoadsRetrievalWithoutHttpOnlyNoteService() {
        try (RuntimeBusinessFixture fixture = RuntimeBusinessFixture.start()) {
            var context = fixture.context();
            assertThat(context.getBean(RetrievalIndexStore.class)).isInstanceOf(MybatisRetrievalIndexStore.class);
            assertThat(context.getBean(RetrievalIndexWorker.class)).isNotNull();
            assertThat(context.getBean(HandwritingSourceReader.class)).isNotNull();
            assertThat(context.getBeansOfType(NoteServiceImpl.class)).isEmpty();
            assertThat(fixture.jdbc().queryForObject(
                    "select count(*) from flyway_schema_history where success = true", Integer.class)).isEqualTo(7);
            assertThat(fixture.jdbc().queryForObject(
                    "select count(*) from pg_extension where extname = 'vector'", Integer.class)).isEqualTo(1);
        }
    }
}
