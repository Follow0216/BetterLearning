package com.betterlearning.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 口令编码器配置。
 *
 * <p>提供 BCrypt 口令编码器，使用其默认强度。BCrypt 只能处理不超过 72 字节的输入，
 * 该上限由输入校验层保证，本配置不做长度截断或预检查。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Configuration(proxyBeanMethods = false)
public class PasswordEncoderConfiguration {

    /**
     * 创建 BCrypt 口令编码器。
     *
     * @return BCrypt 口令编码器
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
