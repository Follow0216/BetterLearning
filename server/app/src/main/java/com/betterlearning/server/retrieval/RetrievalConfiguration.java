package com.betterlearning.server.retrieval;

import com.betterlearning.server.agent.config.EmbeddingProperties;
import com.betterlearning.server.agent.embedding.HttpEmbeddingClient;
import com.betterlearning.server.agent.retrieval.PersonalEvidenceRetriever;
import com.betterlearning.server.agent.retrieval.RetrievalIndexStore;
import com.betterlearning.server.multiagent.EvidenceRetriever;
import com.betterlearning.server.agent.service.OcrModel;
import com.betterlearning.server.learning.service.impl.AnswerFileStore;
import tools.jackson.databind.json.JsonMapper;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmbeddingProperties.class)
@EnableScheduling
@MapperScan("com.betterlearning.server.retrieval.mapper")
public class RetrievalConfiguration {
    @Bean
    HttpEmbeddingClient embeddingClient(EmbeddingProperties properties) {
        return new HttpEmbeddingClient(properties);
    }

    @Bean
    HandwritingSourceReader handwritingSourceReader(AnswerFileStore files, OcrModel ocr, JsonMapper json) {
        return new HandwritingSourceReader(files, ocr, json);
    }

    @Bean
    EvidenceRetriever evidenceRetriever(RetrievalIndexStore indexes, HttpEmbeddingClient embedding,
            EmbeddingProperties properties) {
        return new PersonalEvidenceRetriever(indexes, embedding, properties);
    }
}
