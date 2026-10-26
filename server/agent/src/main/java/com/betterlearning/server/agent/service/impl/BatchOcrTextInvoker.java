package com.betterlearning.server.agent.service.impl;

import java.net.URI;
import java.time.Duration;

import com.betterlearning.server.agent.config.AgentProperties.Provider;
import com.betterlearning.server.agent.model.AgentModelException;
import com.betterlearning.server.agent.model.AgentModelException.Failure;

import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;

/** 文本重组专用调用，保持原图调用器契约及独立开发边界。 */
final class BatchOcrTextInvoker {
    private BatchOcrTextInvoker() {
    }

    static String invoke(Provider provider, String system, String text) {
        validate(provider);
        try {
            OpenAiChatModel model = OpenAiChatModel.builder()
                    .baseUrl(provider.getBaseUrl()).apiKey(provider.getApiKey()).modelName(provider.getModel())
                    .timeout(Duration.ofSeconds(provider.getTimeoutSeconds())).maxRetries(0)
                    .logRequests(false).logResponses(false).build();
            ChatResponse response = model.chat(SystemMessage.from(system), UserMessage.from(text));
            String answer = response == null || response.aiMessage() == null ? null : response.aiMessage().text();
            if (answer == null || answer.isBlank()) {
                throw new AgentModelException(Failure.MODEL_EMPTY_RESPONSE);
            }
            if (answer.length() > 300000) {
                throw new AgentModelException(Failure.MODEL_RESPONSE_TOO_LARGE);
            }
            return answer;
        } catch (AgentModelException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AgentModelException(Failure.MODEL_UNAVAILABLE);
        }
    }

    private static void validate(Provider provider) {
        if (provider == null || blank(provider.getBaseUrl()) || blank(provider.getModel())
                || blank(provider.getApiKey()) || provider.getTimeoutSeconds() < 1
                || provider.getTimeoutSeconds() > 600) {
            throw new AgentModelException(Failure.MODEL_NOT_CONFIGURED);
        }
        try {
            URI uri = URI.create(provider.getBaseUrl());
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))) {
                throw new AgentModelException(Failure.MODEL_NOT_CONFIGURED);
            }
        } catch (IllegalArgumentException exception) {
            throw new AgentModelException(Failure.MODEL_NOT_CONFIGURED);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
