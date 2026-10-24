package com.betterlearning.server.learning.model.vo;

/** 题集浏览项；inbox 是虚拟固定待整理。 */
public record QuestionCollectionVO(String collectionId, String name, boolean system,
        long questionCount) {
}
