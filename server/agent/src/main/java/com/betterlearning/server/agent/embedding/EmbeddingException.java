package com.betterlearning.server.agent.embedding;

/** 不携带供应商响应、请求正文或密钥的调用错误。 */
public final class EmbeddingException extends RuntimeException {
    public enum Reason { DISABLED, UNAVAILABLE, INVALID_RESPONSE, INVALID_INPUT }

    private final Reason reason;

    public EmbeddingException(Reason reason) {
        super("Embedding " + reason.name());
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
