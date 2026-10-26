package com.betterlearning.server.multiagent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.betterlearning.server.multiagent.MultiAgentModels.ConversationContext;
import com.betterlearning.server.multiagent.MultiAgentModels.ConversationTurn;

import tools.jackson.databind.json.JsonMapper;

/** 冻结完整问答，不截断正文，不生成摘要；预算覆盖实际序列化UTF-8。 */
public final class ConversationContexts {
    public static final int MAX_TURNS = 8;
    public static final int MAX_JSON_BYTES = 65536;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ConversationContexts() { }

    public static ConversationContext empty() {
        return new ConversationContext(null, List.of(), 0);
    }

    public static ConversationContext append(ConversationContext ancestors, ConversationTurn parent) {
        validate(ancestors);
        validateTurn(parent);
        if (ancestors.turns().stream().anyMatch(turn -> turn.requestId().equals(parent.requestId()))) {
            throw new IllegalArgumentException("cyclic conversation context");
        }
        List<ConversationTurn> turns = new ArrayList<>(ancestors.turns());
        turns.add(parent);
        long omitted = ancestors.omittedAncestorCount();
        while (turns.size() > MAX_TURNS || bytes(new ConversationContext(parent.requestId(), turns, omitted)) > MAX_JSON_BYTES) {
            if (turns.size() == 1) {
                throw new ContextTooLargeException();
            }
            turns.remove(0);
            omitted = Math.addExact(omitted, 1);
        }
        return new ConversationContext(parent.requestId(), turns, omitted);
    }

    public static String encode(ConversationContext context) {
        validate(context);
        return JSON.writeValueAsString(context);
    }

    public static ConversationContext decode(String json, String expectedParent) {
        if (json == null || json.getBytes(StandardCharsets.UTF_8).length > MAX_JSON_BYTES) {
            throw new IllegalArgumentException("conversation context unavailable");
        }
        ConversationContext context = JSON.readValue(json, ConversationContext.class);
        validate(context);
        if (!java.util.Objects.equals(expectedParent, context.parentRequestId())) {
            throw new IllegalArgumentException("conversation parent mismatch");
        }
        return context;
    }

    public static void validate(ConversationContext context) {
        if (context == null || context.omittedAncestorCount() < 0 || context.turns().size() > MAX_TURNS
                || context.turns().isEmpty() != (context.parentRequestId() == null)
                || context.turns().isEmpty() && context.omittedAncestorCount() != 0) {
            throw new IllegalArgumentException("invalid conversation context");
        }
        Set<String> ids = new HashSet<>();
        for (ConversationTurn turn : context.turns()) {
            validateTurn(turn);
            if (!ids.add(turn.requestId())) {
                throw new IllegalArgumentException("duplicate conversation turn");
            }
        }
        if (!context.turns().isEmpty()
                && !context.turns().get(context.turns().size() - 1).requestId().equals(context.parentRequestId())) {
            throw new IllegalArgumentException("conversation parent is not last");
        }
        if (bytes(context) > MAX_JSON_BYTES) {
            throw new ContextTooLargeException();
        }
    }

    private static void validateTurn(ConversationTurn turn) {
        if (turn == null || !uuid(turn.requestId()) || !uuid(turn.runId()) || !uuid(turn.resultId())
                || !uuid(turn.snapshotFileRef()) || !uuid(turn.replySourceId()) || turn.replyVersion() < 1
                || turn.mode() == null || turn.prompt() == null || turn.prompt().isBlank()
                || turn.prompt().length() > 4000 || turn.bodyMarkdown() == null || turn.bodyMarkdown().isBlank()) {
            throw new IllegalArgumentException("invalid conversation turn");
        }
    }

    private static boolean uuid(String value) {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private static int bytes(ConversationContext context) {
        return JSON.writeValueAsBytes(context).length;
    }

    public static final class ContextTooLargeException extends IllegalArgumentException {
        public ContextTooLargeException() {
            super("complete parent turn exceeds conversation budget");
        }
    }
}
