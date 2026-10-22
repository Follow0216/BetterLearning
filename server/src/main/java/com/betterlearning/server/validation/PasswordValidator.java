package com.betterlearning.server.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * 密码长度校验器。
 *
 * <p>从 {@link ValidPassword} 读取 {@code min} 与 {@code max}。{@code null} 返回
 * {@code true}，是否允许为空由 {@code @NotNull} 决定；非 {@code null} 时委托
 * {@link UserInputNormalizer#isValidPassword}，按 Unicode 码点计算字符数并限制
 * UTF-8 编码不超过 72 字节，不 trim。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
public class PasswordValidator implements ConstraintValidator<ValidPassword, String> {

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
