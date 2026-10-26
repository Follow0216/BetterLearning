package com.betterlearning.server.agent.pipeline;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import com.betterlearning.server.agent.config.AgentProperties;
import com.betterlearning.server.agent.model.ModelImage;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Call;
import com.betterlearning.server.agent.pipeline.RoleProtocol.PipelineFailure;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Reason;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Reply;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Usage;

import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.TokenUsage;

/** 真实OpenAI兼容适配；一次调用，无SDK自动重试，不读取/保存模型thinking内容。 */
public final class LangChain4jRoleModel {
    private final AgentProperties.Provider provider;

    public LangChain4jRoleModel(AgentProperties.Provider provider) {
        this.provider = provider;
    }

    public Reply generate(Call call) {
        // 预算先于配置、Base64编码和SDK网络调用；与流水线使用同一完整图预算。
        TutoringInputBudget.validateModelImages(call.snapshot().images(), call.answerInputKind());
        validateConfiguration();
        List<Content> content = new ArrayList<>();
        content.add(TextContent.from(call.inputJson()));
        for (int index = 0; index < call.snapshot().images().size(); index++) {
            ModelImage image = call.snapshot().images().get(index);
            content.add(TextContent.from(call.imageCaption(index)));
            content.add(ImageContent.from(Base64.getEncoder().encodeToString(image.bytes()),
                    image.mediaType(), ImageContent.DetailLevel.HIGH));
        }
        OpenAiChatModel model;
        try {
            model = OpenAiChatModel.builder()
                    .baseUrl(provider.getBaseUrl()).apiKey(provider.getApiKey()).modelName(provider.getModel())
                    .timeout(Duration.ofSeconds(provider.getTimeoutSeconds())).maxRetries(0)
                    .maxCompletionTokens(4096).responseFormat("json_object")
                    .returnThinking(false).sendThinking(false)
                    .logRequests(false).logResponses(false).build();
        } catch (RuntimeException exception) {
            throw new PipelineFailure(Reason.MODEL_NOT_CONFIGURED);
        }
        ChatResponse response;
        try {
            response = model.chat(SystemMessage.from(call.systemPrompt()), UserMessage.from(content));
        } catch (RuntimeException exception) {
            // SDK没有可核验未发送证明；即便HTTP错误也保守记未知，不传播供应商异常或cause。
            throw new PipelineFailure(Reason.OUTCOME_UNKNOWN);
        }
        String text = response == null || response.aiMessage() == null ? null : response.aiMessage().text();
        if (text == null || text.isBlank() || text.length() > 32768) {
            throw new PipelineFailure(Reason.INVALID_OUTPUT);
        }
        TokenUsage usage = response.tokenUsage();
        return new Reply(text, response.modelName() == null ? provider.getModel() : response.modelName(),
                new Usage(usage == null ? null : usage.inputTokenCount(),
                        usage == null ? null : usage.outputTokenCount(), call.snapshot().images().size(), false));
    }

    public void validateConfiguration() {
        if (provider == null || blank(provider.getApiKey()) || blank(provider.getModel())
                || blank(provider.getBaseUrl()) || provider.getTimeoutSeconds() < 1
                || provider.getTimeoutSeconds() > 120) {
            throw new PipelineFailure(Reason.MODEL_NOT_CONFIGURED);
        }
        try {
            URI uri = URI.create(provider.getBaseUrl());
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || (!"https".equalsIgnoreCase(uri.getScheme()) && !"http".equalsIgnoreCase(uri.getScheme()))) {
                throw new PipelineFailure(Reason.MODEL_NOT_CONFIGURED);
            }
        } catch (IllegalArgumentException exception) {
            throw new PipelineFailure(Reason.MODEL_NOT_CONFIGURED);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
