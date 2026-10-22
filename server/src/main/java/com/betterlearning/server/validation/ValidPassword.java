package com.betterlearning.server.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * 密码长度约束。
 *
 * <p>由 {@link PasswordValidator} 实现：{@code null} 视为通过，交由 {@code @NotNull} 判空；
 * 非 {@code null} 时按 Unicode 码点计算字符数，并要求 UTF-8 编码不超过 72 字节。
 * 密码不做 trim，空白字符也计入长度。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Documented
@Constraint(validatedBy = PasswordValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPassword {

    /**
     * 校验失败时的默认提示信息。
     *
     * @return 校验失败时的默认提示信息
     */
    String message() default "密码需为8~64个字符且不超过72字节";

    /**
     * 允许的最小字符数（按 Unicode 码点计），默认 8。
     *
     * @return 允许的最小字符数（按 Unicode 码点计），默认 8
     */
    int min() default 8;

    /**
     * 允许的最大字符数（按 Unicode 码点计），默认 64。
     *
     * @return 允许的最大字符数（按 Unicode 码点计），默认 64
     */
    int max() default 64;

    /**
     * Bean Validation 分组。
     *
     * @return Bean Validation 分组
     */
    Class<?>[] groups() default {};

    /**
     * Bean Validation 负载。
     *
     * @return Bean Validation 负载
     */
    Class<? extends Payload>[] payload() default {};
}
