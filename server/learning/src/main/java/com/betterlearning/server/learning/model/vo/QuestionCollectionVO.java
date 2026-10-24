package com.betterlearning.server.learning.model.vo;

/** 题集浏览项；inbox 是虚拟固定待整理，真实节点带可空父 ID。 */
public record QuestionCollectionVO(String collectionId, String name, boolean system,
        long questionCount, String parentCollectionId) {

    /** 保留根级／系统节点的简洁构造。 */
    public QuestionCollectionVO(String collectionId, String name, boolean system, long questionCount) {
        this(collectionId, name, system, questionCount, null);
    }
}
