package com.betterlearning.server.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/**
 * 密码长度约束。
 *
 * <p>由 {@link Validator} 实现：{@code null} 视为通过，交由 {@code @NotNull} 判空；
 * 非 {@code null} 时按 Unicode 码点计算字符数，并要求 UTF-8 编码不超过 72 字节。
 * 密码不做 trim，空白字符也计入长度。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Documented
@Constraint(validatedBy = ValidPassword.Validator.class)
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

    /**
     * 密码长度校验器。
     *
     * <p>从 {@link ValidPassword} 读取 {@code min} 与 {@code max}。{@code null} 返回
     * {@code true}，是否允许为空由 {@code @NotNull} 决定；非 {@code null} 时委托
     * {@link UserInputNormalizer#isValidPassword}，按 Unicode 码点计算字符数并限制
     * UTF-8 编码不超过 72 字节，不 trim。</p>
     */
    public static class Validator implements ConstraintValidator<ValidPassword, String> {

        /** 允许的最小字符数。 */
        private int minLength;

        /** 允许的最大字符数。 */
        private int maxLength;

        /**
         * 读取约束注解上的长度范围。
         *
         * @param constraintAnnotation 约束注解
         */
        @Override
        public void initialize(ValidPassword constraintAnnotation) {
            this.minLength = constraintAnnotation.min();
            this.maxLength = constraintAnnotation.max();
        }

        /**
         * 校验密码长度与字节上限。
         *
         * @param value 待校验密码，可为 {@code null}
         * @param context 校验上下文
         * @return {@code null} 或满足长度与字节限制时为 {@code true}
         */
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            return UserInputNormalizer.isValidPassword(value, minLength, maxLength);
        }
    }
}
