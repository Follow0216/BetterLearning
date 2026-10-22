package com.betterlearning.server.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * 手机号格式约束。
 *
 * <p>由 {@link PhoneValidator} 实现：{@code null} 视为通过，交由 {@code @NotNull} 判空；
 * 非 {@code null} 时先去除首尾空白，国内 11 位手机号补 {@code +86}，再按 E.164 校验，
 * 不会修改被校验对象中的原值。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Documented
@Constraint(validatedBy = PhoneValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidPhone {

    /**
     * 校验失败时的默认提示信息。
     *
     * @return 校验失败时的默认提示信息
     */
    String message() default "手机号格式不正确";

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
