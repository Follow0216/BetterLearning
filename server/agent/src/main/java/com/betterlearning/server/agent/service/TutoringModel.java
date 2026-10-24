package com.betterlearning.server.agent.service;

import com.betterlearning.server.agent.model.TutorModels;

/** 真实LangChain4j适配端口；必须发送完整图像，不得隐式改为纯文本。 */
public interface TutoringModel {
    String answer(String prompt, TutorModels.FrozenAnswer frozenAnswer);
}
