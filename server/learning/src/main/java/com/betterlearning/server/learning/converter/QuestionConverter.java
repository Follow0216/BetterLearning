package com.betterlearning.server.learning.converter;

import com.betterlearning.server.learning.enums.QuestionSubject;
import com.betterlearning.server.learning.model.dataobject.QuestionDO;
import com.betterlearning.server.learning.model.vo.QuestionSummaryVO;
import com.betterlearning.server.learning.model.vo.QuestionVO;

/** DO 到对外模型的无状态转换；所有 ID 统一字符串化。 */
public final class QuestionConverter {

    /** 待整理的固定外部标识，不作为数据库 ID。 */
    public static final String INBOX_ID = "inbox";

    private QuestionConverter() {
    }

    /** 转换详情。 */
    public static QuestionVO detail(QuestionDO question) {
        return new QuestionVO(Long.toString(question.getQuestionId()),
                collectionId(question.getCollectionId()), QuestionSubject.valueOf(question.getSubject()),
                question.getBodyMarkdown(), question.getSummary(), question.getCreatedAt(), state(question));
    }

    /** 转换列表项，正文不传给客户端。 */
    public static QuestionSummaryVO summary(QuestionDO question) {
        return new QuestionSummaryVO(Long.toString(question.getQuestionId()),
                collectionId(question.getCollectionId()), QuestionSubject.valueOf(question.getSubject()),
                question.getSummary(), question.getCreatedAt(), state(question));
    }

    private static String state(QuestionDO question) {
        return question.getAnswerState() == null ? "NOT_STARTED" : question.getAnswerState();
    }

    private static String collectionId(Long id) {
        return id == null ? INBOX_ID : Long.toString(id);
    }
}
