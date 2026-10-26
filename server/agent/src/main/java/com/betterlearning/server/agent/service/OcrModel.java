package com.betterlearning.server.agent.service;

import com.betterlearning.server.agent.model.ModelImage;

/** 真实图像识别端口；只返回待用户校对的Markdown，不创建正式题目。 */
public interface OcrModel {
    String recognize(ModelImage image);

    /** 批量原图图块可能含多题或半题；真实适配器使用独立忠实转写提示。 */
    default String recognizeTile(ModelImage image) {
        return recognize(image);
    }
}
