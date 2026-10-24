package com.betterlearning.server.agent.service.impl;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import com.betterlearning.server.agent.config.AgentProperties;
import com.betterlearning.server.agent.model.AgentModelException;
import com.betterlearning.server.agent.model.AgentModelException.Failure;
import com.betterlearning.server.agent.model.ModelImage;

import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;

/** 仅使用LangChain4j调用OpenAI兼容模型，不自建HTTP、不记录请求/响应。 */
final class LangChain4jModelInvoker {
    private static final int MAX_IMAGES = 256;
    private static final long MAX_IMAGE_BYTES = 32L * 1024 * 1024;

    private LangChain4jModelInvoker() {
    }

    static String invoke(AgentProperties.Provider provider, String systemPrompt, String text,
            List<ModelImage> images) {
        validate(provider);
        long totalBytes = 0;
        if (images.isEmpty() || images.size() > MAX_IMAGES) {
            throw new AgentModelException(Failure.MODEL_INPUT_TOO_LARGE);
        }
        for (ModelImage image : images) {
            if (image.byteCount() == 0 || (!"image/png".equals(image.mediaType())
                    && !"image/jpeg".equals(image.mediaType()))) {
                throw new AgentModelException(Failure.MODEL_IMAGE_UNSUPPORTED);
            }
            totalBytes += image.byteCount();
        }
        // base64与JSON有额外开销；完整输入超出此MVP上限就失败，不能截块或降为纯文本。
        if (totalBytes > MAX_IMAGE_BYTES) {
            throw new AgentModelException(Failure.MODEL_INPUT_TOO_LARGE);
        }
        List<Content> contents = new ArrayList<>();
        contents.add(TextContent.from(text));
        for (int index = 0; index < images.size(); index++) {
            ModelImage image = images.get(index);
            contents.add(TextContent.from("图块 " + (index + 1) + "/" + images.size() + "；" + image.position()));
            contents.add(ImageContent.from(Base64.getEncoder().encodeToString(image.bytes()),
                    image.mediaType(), ImageContent.DetailLevel.HIGH));
        }
        try {
            OpenAiChatModel model = OpenAiChatModel.builder()
                    .baseUrl(provider.getBaseUrl())
                    .apiKey(provider.getApiKey())
                    .modelName(provider.getModel())
                    .timeout(Duration.ofSeconds(provider.getTimeoutSeconds()))
                    .maxRetries(0)
                    .logRequests(false)
                    .logResponses(false)
                    .build();
            ChatResponse response = model.chat(SystemMessage.from(systemPrompt), UserMessage.from(contents));
            String answer = response == null || response.aiMessage() == null ? null : response.aiMessage().text();
            if (answer == null || answer.isBlank()) {
                throw new AgentModelException(Failure.MODEL_EMPTY_RESPONSE);
            }
            return answer;
        } catch (AgentModelException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            // 不传播cause或供应商正文，防止接口地址、输入内容和凭据进入HTTP错误/日志。
            throw new AgentModelException(Failure.MODEL_UNAVAILABLE);
        }
    }

    private static void validate(AgentProperties.Provider provider) {
        if (provider == null || blank(provider.getBaseUrl()) || blank(provider.getModel())
                || blank(provider.getApiKey()) || provider.getTimeoutSeconds() < 1
                || provider.getTimeoutSeconds() > 600) {
            throw new AgentModelException(Failure.MODEL_NOT_CONFIGURED);
        }
        try {
            URI uri = URI.create(provider.getBaseUrl());
            if (!uri.isAbsolute() || uri.getHost() == null
                    || (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new AgentModelException(Failure.MODEL_NOT_CONFIGURED);
            }
        } catch (IllegalArgumentException exception) {
            throw new AgentModelException(Failure.MODEL_NOT_CONFIGURED);
        }
        // 此处只校验，不解析/重建URL；供应商完整路径前缀原样交给LangChain4j。
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
