package com.betterlearning.server.learning.model.vo;

import java.util.List;

/** 当前账号的题集列表。 */
public record QuestionCollectionsVO(List<QuestionCollectionVO> items) {
    public QuestionCollectionsVO {
        items = List.copyOf(items);
    }
}
