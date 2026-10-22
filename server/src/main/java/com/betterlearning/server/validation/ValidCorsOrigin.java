package com.betterlearning.server.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * CORS 来源格式约束。
 *
 * <p>用于校验配置中的来源字符串，要求为明确的 {@code http}／{@code https} origin：
 * 不得含通配符、用户信息、路径、查询串或片段，主机须为合法域名、IPv4 或方括号 IPv6，
 * 端口须在 1~65535 内。由 {@link CorsOriginValidator} 实现，空值与空白值不通过。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Documented
@Constraint(validatedBy = CorsOriginValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.TYPE_USE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidCorsOrigin {

    /**
     * 校验失败时的默认提示信息。
     *
     * @return 校验失败时的默认提示信息
     */
    String message() default "app.cors.allowed-origins 必须是明确的 http/https origin";

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
