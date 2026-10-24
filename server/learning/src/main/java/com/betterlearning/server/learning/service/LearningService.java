package com.betterlearning.server.learning.service;

import com.betterlearning.server.learning.model.dto.ConfirmQuestionDTO;
import com.betterlearning.server.learning.model.vo.QuestionCollectionsVO;
import com.betterlearning.server.learning.model.vo.QuestionPageVO;
import com.betterlearning.server.learning.model.vo.QuestionVO;

/** 最小真实题库；账号参数只能来自 HTTP 适配层的已认证 principal。 */
public interface LearningService {

    QuestionVO confirm(long accountId, ConfirmQuestionDTO request);

    QuestionVO detail(long accountId, String questionId);

    QuestionPageVO page(long accountId, String collectionId, int page, int pageSize);

    QuestionCollectionsVO collections(long accountId);
}
