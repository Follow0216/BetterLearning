package com.betterlearning.server.agent.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import com.betterlearning.server.multiagent.ConversationContexts;
import com.betterlearning.server.multiagent.MultiAgentModels.ConversationContext;
import com.betterlearning.server.multiagent.MultiAgentModels.ConversationTurn;
import com.betterlearning.server.multiagent.MultiAgentModels.Mode;

import org.junit.jupiter.api.Test;

class ConversationContextsTests {
    @Test
    void recentEightWholeTurnsKeepTheSelectedParentAndRecordOmittedAncestors() {
        ConversationContext context = ConversationContexts.empty();
        for (int index = 1; index <= 12; index++) {
            context = ConversationContexts.append(context, turn(index, "第" + index + "轮完整解释"));
        }
        assertThat(context.turns()).hasSize(8);
        assertThat(context.turns()).extracting(ConversationTurn::requestId)
                .containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(5, 12)
                .mapToObj(ConversationContextsTests::id).toList());
        assertThat(context.parentRequestId()).isEqualTo(id(12));
        assertThat(context.omittedAncestorCount()).isEqualTo(4);
        assertThat(ConversationContexts.decode(ConversationContexts.encode(context), id(12))).isEqualTo(context);
        assertThat(context.toString()).doesNotContain("完整解释");
    }

    @Test
    void utf8BudgetDropsOnlyWholeOldTurnsAndNeverTruncatesTheSelectedParent() {
        ConversationContext first = ConversationContexts.append(ConversationContexts.empty(), turn(1, "旧".repeat(13000)));
        String parentBody = "父".repeat(13000);
        ConversationContext selected = ConversationContexts.append(first, turn(2, parentBody));
        assertThat(selected.turns()).hasSize(1);
        assertThat(selected.turns().get(0).bodyMarkdown()).isEqualTo(parentBody);
        assertThat(selected.omittedAncestorCount()).isEqualTo(1);
        assertThat(ConversationContexts.encode(selected).getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(ConversationContexts.MAX_JSON_BYTES);
        assertThatThrownBy(() -> ConversationContexts.append(first, turn(3, "父".repeat(23000))))
                .isInstanceOf(ConversationContexts.ContextTooLargeException.class);
    }

    @Test
    void duplicateAndMismatchedParentsOrInvalidOmissionMetadataAreRejected() {
        ConversationContext first = ConversationContexts.append(ConversationContexts.empty(), turn(1, "正文"));
        assertThatThrownBy(() -> ConversationContexts.append(first, turn(1, "其他正文")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConversationContexts.decode(ConversationContexts.encode(first), id(2)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ConversationContexts.validate(new ConversationContext(null, List.of(), 2)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static ConversationTurn turn(int index, String body) {
        return new ConversationTurn(id(index), id(index + 100), id(index + 200), Mode.FULL_SOLUTION,
                "为什么做第二步？", body, id(index + 300), id(index + 400), 1);
    }

    static String id(int index) {
        return new UUID(0, index).toString();
    }
}
