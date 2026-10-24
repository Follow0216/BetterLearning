package com.betterlearning.server.config;

import java.time.Clock;
import java.util.Base64;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.betterlearning.server.security.StrictJwtClaimsValidator;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * JWT 编解码与时钟配置。
 *
 * <p>编码器与解码器均固定使用 HS256，密钥由 Base64 配置解码得到；解码器挂载
 * {@link StrictJwtClaimsValidator} 校验声明。令牌有效期、签发者与受众来自
 * {@link AppJwtProperties}，本配置不实现令牌刷新。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Configuration(proxyBeanMethods = false)
public class JwtConfiguration {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /**
     * 提供 UTC 系统时钟，供令牌签发与声明校验使用。
     *
     * @return UTC 时钟
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * 创建 HS256 JWT 编码器。
     *
     * @param properties JWT 配置，提供 Base64 密钥
     * @return JWT 编码器
     */
    @Bean
    public JwtEncoder jwtEncoder(AppJwtProperties properties) {
        return NimbusJwtEncoder.withSecretKey(secretKey(properties))
                .algorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * 创建 HS256 JWT 解码器，并挂载严格的声明校验器。
     *
     * @param properties JWT 配置，提供 Base64 密钥、签发者与受众
     * @param clock 校验时间声明所用时钟
     * @return 配置了声明校验的 JWT 解码器
     */
    @Bean
    public JwtDecoder jwtDecoder(AppJwtProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(new StrictJwtClaimsValidator(
                properties.getIssuer(), properties.getAudience(), clock));
        return decoder;
    }

    /**
     * 解码 Base64 密钥并构造 HMAC 密钥。
     *
     * @param properties JWT 配置
     * @return HMAC-SHA256 密钥
     */
    private static SecretKey secretKey(AppJwtProperties properties) {
        byte[] keyBytes = Base64.getDecoder().decode(properties.getSecretBase64());
        return new SecretKeySpec(keyBytes, HMAC_ALGORITHM);
    }
}
