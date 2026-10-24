package com.betterlearning.server.agent.service;

import com.betterlearning.server.agent.model.ModelImage;

/** 真实图像识别端口；只返回待用户校对的Markdown，不创建正式题目。 */
public interface OcrModel {
    String recognize(ModelImage image);
}
