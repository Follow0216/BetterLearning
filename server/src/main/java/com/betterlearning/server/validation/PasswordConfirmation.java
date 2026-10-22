package com.betterlearning.server.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * 两次密码一致性约束，作用于类型。
 *
 * <p>由 {@link PasswordConfirmationValidator} 实现：通过反射读取 {@link #passwordField()}
 * 与 {@link #confirmField()} 指定的字段，任一为 {@code null} 时视为通过，空值交由
 * {@code @NotNull} 判断；不一致时在确认字段的路径上报告错误。默认字段名为
 * {@code password} 与 {@code confirmPassword}，改密请求通过
 * {@code passwordField = "newPassword"} 复用本约束。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Documented
@Constraint(validatedBy = PasswordConfirmationValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface PasswordConfirmation {

    /**
     * 校验失败时的默认提示信息。
     *
     * @return 校验失败时的默认提示信息
     */
    String message() default "两次输入的密码不一致";

    /**
     * 密码字段名，默认 {@code password}。
     *
     * @return 密码字段名，默认 {@code password}
     */
    String passwordField() default "password";

    /**
     * 确认密码字段名，默认 {@code confirmPassword}。
     *
     * @return 确认密码字段名，默认 {@code confirmPassword}
     */
    String confirmField() default "confirmPassword";

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
