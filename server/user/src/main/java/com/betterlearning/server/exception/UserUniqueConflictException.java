package com.betterlearning.server.exception;

import com.betterlearning.server.enums.UserUniqueField;

/**
 * 用户表唯一约束冲突，仅由持久层翻译产生。
 *
 * <p>不包含 HTTP 状态码、错误码或数据库约束名等上层信息，原始数据库异常保存在 cause 中。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
public class UserUniqueConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final UserUniqueField field;

    /**
     * 创建唯一冲突异常。
     *
     * @param field 发生冲突的唯一字段
     * @param cause 原始数据库异常
     */
    public UserUniqueConflictException(UserUniqueField field, Throwable cause) {
        super("用户唯一字段冲突: " + field, cause);
        this.field = field;
    }

    /**
     * 返回发生冲突的唯一字段。
     *
     * @return 唯一字段枚举
     */
    public UserUniqueField getField() {
        return field;
    }
}
