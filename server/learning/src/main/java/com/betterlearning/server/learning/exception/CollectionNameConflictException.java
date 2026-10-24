package com.betterlearning.server.learning.exception;

/** 专用题集写 SQL 已识别的同级名称冲突，不用于其他写操作。 */
public class CollectionNameConflictException extends RuntimeException {
    public CollectionNameConflictException(Throwable cause) {
        super("同级题集名称已存在", cause);
    }
}
