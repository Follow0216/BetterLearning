package com.betterlearning.server.agent.model;

/** 只携带预定义安全错误，不透传供应商原始异常、响应或请求配置。 */
public final class AgentModelException extends RuntimeException {
    public enum Failure {
        MODEL_NOT_CONFIGURED("模型尚未配置，请联系管理员"),
        MODEL_IMAGE_UNSUPPORTED("辅导模型尚未启用图像能力，请配置支持图像的模型"),
        MODEL_INPUT_TOO_LARGE("本轮完整图像超出模型输入限制，请调整模型配置后重试"),
        MODEL_UNAVAILABLE("模型调用失败，请稍后重试"),
        MODEL_EMPTY_RESPONSE("模型未返回有效内容，请重试");

        private final String message;

        Failure(String message) {
            this.message = message;
        }

        public String message() {
            return message;
        }
    }

    private final Failure failure;

    public AgentModelException(Failure failure) {
        super(failure.message());
        this.failure = failure;
    }

    public Failure failure() {
        return failure;
    }
}
