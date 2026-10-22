package com.betterlearning.server.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * 邮箱格式校验器。
 *
 * <p>{@code null} 返回 {@code true}，表示该值不参与格式判断，是否允许为空由字段上的
 * {@code @NotNull} 决定；非 {@code null} 时委托 {@link UserInputNormalizer#isValidEmail}
 * 按去空白并转小写后的值校验，校验过程不修改被校验对象。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
public class EmailValidator implements ConstraintValidator<ValidEmail, String> {

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
