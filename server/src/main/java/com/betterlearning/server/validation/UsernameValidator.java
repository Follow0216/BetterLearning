package com.betterlearning.server.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * 用户名格式校验器。
 *
 * <p>{@code null} 返回 {@code true}，是否允许为空由 {@code @NotNull} 决定；非
 * {@code null} 时委托 {@link UserInputNormalizer#isValidUsername}，按去除首尾空白并
 * 转为小写后的值判断长度与字符集。校验不修改被校验对象。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
public class UsernameValidator implements ConstraintValidator<ValidUsername, String> {

    /**
     * 校验用户名格式。
     *
     * @param value 待校验用户名，可为 {@code null}
     * @param context 校验上下文
     * @return {@code null} 或规范化后满足长度与字符集要求时为 {@code true}
     */
    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return UserInputNormalizer.isValidUsername(value);
    }
}
