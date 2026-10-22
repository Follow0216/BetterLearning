package com.betterlearning.server.validation;

import java.lang.reflect.Field;
import java.util.Objects;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * 两次密码一致性校验器。
 *
 * <p>从注解读取两个字段名，通过反射沿继承层次查找字段并读取值。被校验对象为
 * {@code null}、任一字段为 {@code null}，或两值相等时返回 {@code true}；不一致时关闭
 * 默认约束违规，改为在确认字段的属性节点上报告注解的默认消息。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
public class PasswordConfirmationValidator
        implements ConstraintValidator<PasswordConfirmation, Object> {

    /** 密码字段名。 */
    private String passwordField;

    /** 确认密码字段名。 */
    private String confirmField;

    /**
     * 读取约束注解中配置的字段名。
     *
     * @param constraintAnnotation 约束注解
     */
    @Override
    public void initialize(PasswordConfirmation constraintAnnotation) {
        this.passwordField = constraintAnnotation.passwordField();
        this.confirmField = constraintAnnotation.confirmField();
    }

    /**
     * 校验两个密码字段是否一致。
     *
     * @param value 待校验对象，可为 {@code null}
     * @param context 校验上下文
     * @return 对象或任一字段为 {@code null}、或两值相等时为 {@code true}
     * @throws IllegalStateException 字段不存在，或读取字段时 {@link IllegalAccessException} 被包装；
     *         {@code setAccessible} 的访问限制异常可能原样传播
     */
    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        Object password = readField(value, passwordField);
        Object confirmation = readField(value, confirmField);
        if (password == null || confirmation == null || Objects.equals(password, confirmation)) {
            return true;
        }
        context.disableDefaultConstraintViolation();
        context.buildConstraintViolationWithTemplate(context.getDefaultConstraintMessageTemplate())
                .addPropertyNode(confirmField)
                .addConstraintViolation();
        return false;
    }

    /**
     * 沿类继承层次查找并读取字段值。
     *
     * @param bean 目标对象
     * @param fieldName 字段名
     * @return 字段值，可能为 {@code null}
     * @throws IllegalStateException 字段不存在，或读取字段时 {@link IllegalAccessException} 被包装；
     *         {@code setAccessible} 的访问限制异常可能原样传播
     */
    private static Object readField(Object bean, String fieldName) {
        Class<?> current = bean.getClass();
        while (current != null && current != Object.class) {
            try {
                Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(bean);
            } catch (NoSuchFieldException exception) {
                current = current.getSuperclass();
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException("无法读取字段: " + fieldName, exception);
            }
        }
        throw new IllegalStateException("找不到字段: " + fieldName);
    }
}
