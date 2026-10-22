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
 * 邮箱格式约束。
 *
 * <p>由 {@link Validator} 实现：{@code null} 视为通过，交由 {@code @NotNull} 判空；
 * 非 {@code null} 时按去除首尾空白并转小写后的值执行保守格式与长度校验，
 * 不会修改被校验对象中的原值。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Documented
@Constraint(validatedBy = ValidEmail.Validator.class)
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

    /**
     * 邮箱格式校验器。
     *
     * <p>{@code null} 返回 {@code true}，表示该值不参与格式判断，是否允许为空由字段上的
     * {@code @NotNull} 决定；非 {@code null} 时委托 {@link UserInputNormalizer#isValidEmail}
     * 按去空白并转小写后的值校验，校验过程不修改被校验对象。</p>
     */
    public static class Validator implements ConstraintValidator<ValidEmail, String> {

        /**
         * 校验邮箱格式。
         *
         * @param value 待校验邮箱，可为 {@code null}
         * @param context 校验上下文
         * @return {@code null} 或格式合法时为 {@code true}
         */
        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            return UserInputNormalizer.isValidEmail(value);
        }
    }
}
