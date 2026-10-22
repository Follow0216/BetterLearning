package com.betterlearning.server.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * 邮箱格式约束。
 *
 * <p>由 {@link EmailValidator} 实现：{@code null} 视为通过，交由 {@code @NotNull} 判空；
 * 非 {@code null} 时按去除首尾空白并转小写后的值执行保守格式与长度校验，
 * 不会修改被校验对象中的原值。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Documented
@Constraint(validatedBy = EmailValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidEmail {

    /**
     * 校验失败时的默认提示信息。
     *
     * @return 校验失败时的默认提示信息
     */
    String message() default "邮箱格式不正确";

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
