package com.betterlearning.server.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * 手机号格式校验器。
 *
 * <p>{@code null} 返回 {@code true}，是否允许为空由 {@code @NotNull} 决定；非
 * {@code null} 时委托 {@link UserInputNormalizer#isValidPhone}：去除首尾空白，国内
 * 11 位手机号补 {@code +86}，再按 E.164 判断。校验不修改被校验对象。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
public class PhoneValidator implements ConstraintValidator<ValidPhone, String> {

    /**
     * 校验手机号格式。
     *
     * @param value 待校验手机号，可为 {@code null}
     * @param context 校验上下文
     * @return {@code null} 或规范化后符合 E.164 时为 {@code true}
     */
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return UserInputNormalizer.isValidPhone(value);
    }
}
