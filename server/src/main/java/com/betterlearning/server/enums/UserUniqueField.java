package com.betterlearning.server.enums;

/**
 * 用户表唯一约束对应的业务字段。
 *
 * <p>由持久层在识别到受支持的唯一约束后产生，供业务层转换为错误码。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
public enum UserUniqueField {

    /** 用户名唯一约束。 */
    USERNAME,

    /** 手机号唯一约束。 */
    PHONE,

    /** 邮箱唯一约束。 */
    EMAIL
}
