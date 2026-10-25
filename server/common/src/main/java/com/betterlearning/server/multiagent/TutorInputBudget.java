package com.betterlearning.server.multiagent;

/** 当前辅导模型输入预算；只统计传入模型的完整合成 PNG，不包含题面恢复栅格。 */
public final class TutorInputBudget {
    public static final long MAX_IMAGE_BYTES = 32L * 1024 * 1024;

    private TutorInputBudget() {
    }
}
