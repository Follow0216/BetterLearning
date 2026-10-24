package com.betterlearning.server.config;

import com.betterlearning.server.agent.service.OcrModel;
import com.betterlearning.server.agent.service.OcrService;
import com.betterlearning.server.agent.service.TutorService;
import com.betterlearning.server.agent.service.TutorTurnStore;
import com.betterlearning.server.agent.service.TutoringModel;
import com.betterlearning.server.agent.service.impl.DefaultTutorService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 装配agent与learning适配端口；配置绑定由agent自身的Configuration负责。 */
@Configuration(proxyBeanMethods = false)
public class AgentApplicationConfiguration {
    @Bean
    TutorService tutorService(TutorTurnStore store, ObjectProvider<TutoringModel> models) {
        return new DefaultTutorService(store, models);
    }

    @Bean
    OcrService ocrService(ObjectProvider<OcrModel> models) {
        return new OcrService(models);
    }
}
