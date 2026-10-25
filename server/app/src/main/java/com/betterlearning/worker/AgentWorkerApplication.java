package com.betterlearning.worker;

import com.betterlearning.server.agent.TutorDeadlineReconciler;
import com.betterlearning.server.config.DataSourcePasswordProperties;
import com.betterlearning.server.config.AgentRetentionConfiguration;
import com.betterlearning.server.config.JacksonConfiguration;
import com.betterlearning.server.config.MybatisConfiguration;
import com.betterlearning.server.config.MultiAgentRolloutConfiguration;
import com.betterlearning.server.config.TemporalClientConfiguration;
import com.betterlearning.server.config.TemporalWorkerConfiguration;
import com.betterlearning.server.learning.config.AppFilesProperties;
import com.betterlearning.server.learning.service.impl.NoteServiceImpl;
import com.betterlearning.server.retrieval.MybatisRetrievalIndexStore;
import com.betterlearning.server.retrieval.RetrievalConfiguration;
import com.betterlearning.server.retrieval.RetrievalIndexWorker;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.ControllerAdvice;

/** Separate process entry point; it deliberately does not scan HTTP/authentication configuration. */
@Configuration(proxyBeanMethods = false)
@EnableAutoConfiguration
@EnableConfigurationProperties({DataSourcePasswordProperties.class, AppFilesProperties.class})
@Import({TemporalClientConfiguration.class, TemporalWorkerConfiguration.class,
        MybatisConfiguration.class, JacksonConfiguration.class,
        RetrievalConfiguration.class, MybatisRetrievalIndexStore.class, RetrievalIndexWorker.class,
        AgentRetentionConfiguration.class, MultiAgentRolloutConfiguration.class})
@ComponentScan(basePackages = {"com.betterlearning.server.agent", "com.betterlearning.server.learning"},
        excludeFilters = {
            @ComponentScan.Filter(type = FilterType.ANNOTATION, classes = {Controller.class, ControllerAdvice.class}),
            @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
                    classes = {TutorDeadlineReconciler.class, NoteServiceImpl.class})
        })
public class AgentWorkerApplication {
    public static void main(String[] args) {
        new SpringApplicationBuilder(AgentWorkerApplication.class).web(WebApplicationType.NONE).run(args);
    }
}
