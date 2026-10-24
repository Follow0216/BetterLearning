package com.betterlearning.server.learning.model.vo;

import java.util.List;

/** 当前账号范围的题库页，页码从 1 开始。 */
public record QuestionPageVO(List<QuestionSummaryVO> items, int page, int pageSize, long total) {
    public QuestionPageVO {
        items = List.copyOf(items);
    }
}
