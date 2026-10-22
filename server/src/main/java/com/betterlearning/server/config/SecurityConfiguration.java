package com.betterlearning.server.config;

import java.util.List;

import com.betterlearning.server.security.AccountAuthenticationChecker;
import com.betterlearning.server.security.ApiSecurityErrorResponder;
import com.betterlearning.server.security.JsonCorsProcessor;
import com.betterlearning.server.security.LiveAccountAuthenticationFilter;
import com.betterlearning.server.security.RestAccessDeniedHandler;
import com.betterlearning.server.security.RestAuthenticationEntryPoint;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * 无状态安全链与 CORS 配置。
 *
 * <p>禁用 CSRF、表单登录、HTTP Basic、请求缓存与服务端会话，安全上下文只保存在当前请求属性中。
 * 注册与登录接口及接口文档公开，{@code /api/v1/admin/**} 要求 {@code ROLE_ADMIN}，其余请求要求认证。
 * 资源服务器完成 JWT 解码校验后，再执行 {@link LiveAccountAuthenticationFilter} 复核实时账号状态；
 * CORS 拒绝由 {@link JsonCorsProcessor} 输出标准 403 JSON。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {

    private static final List<String> ALLOWED_METHODS =
            List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS");

    private static final List<String> ALLOWED_HEADERS =
            List.of("Authorization", "Content-Type");

    /**
     * 创建 BCrypt 口令编码器，使用其默认强度。
     *
     * <p>BCrypt 只能处理不超过 72 字节的输入，该上限由输入校验层保证，本配置不做长度截断或预检查。</p>
     *
     * @return BCrypt 口令编码器
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 构建无状态安全过滤链。
     *
     * @param http HttpSecurity 构建器
     * @param accountAuthenticationChecker 实时账号校验器
     * @param errorResponder 安全错误响应器
     * @param authenticationEntryPoint 未认证入口
     * @param accessDeniedHandler 无权限处理器
     * @return 安全过滤链
     * @throws Exception 构建安全链失败
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
            AccountAuthenticationChecker accountAuthenticationChecker,
            ApiSecurityErrorResponder errorResponder,
            RestAuthenticationEntryPoint authenticationEntryPoint,
            RestAccessDeniedHandler accessDeniedHandler) throws Exception {
        LiveAccountAuthenticationFilter liveAccountFilter =
                new LiveAccountAuthenticationFilter(accountAuthenticationChecker, errorResponder);
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context
                        .securityContextRepository(new RequestAttributeSecurityContextRepository()))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .exceptionHandling(exception -> exception
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(HttpMethod.POST, "/api/v1/users", "/api/v1/sessions")
                        .permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**",
                                "/swagger-ui.html")
                        .permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(Customizer.withDefaults()))
                .addFilterAfter(liveAccountFilter, BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /**
     * 创建使用标准 JSON 拒绝响应的 CORS 过滤器。
     *
     * @param configurationSource CORS 配置来源
     * @param errorResponder 安全错误响应器
     * @return CORS 过滤器
     */
    @Bean
    public CorsFilter corsFilter(
            @Qualifier("corsConfigurationSource") CorsConfigurationSource configurationSource,
            ApiSecurityErrorResponder errorResponder) {
        CorsFilter filter = new CorsFilter(configurationSource);
        filter.setCorsProcessor(new JsonCorsProcessor(errorResponder));
        return filter;
    }

    /**
     * 禁用 CORS 过滤器的独立 Servlet 注册，避免与安全链中的 CORS 处理重复。
     *
     * @param filter 已创建的 CORS 过滤器
     * @return 已禁用的注册对象
     */
    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration(CorsFilter filter) {
        FilterRegistrationBean<CorsFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    /**
     * 根据配置构建全局 CORS 规则。
     *
     * <p>允许来源来自 {@link AppCorsProperties}；固定允许 GET、POST、PATCH、DELETE、OPTIONS 与
     * Authorization、Content-Type 请求头，不携带凭据，预检缓存 3600 秒。</p>
     *
     * @param properties CORS 来源配置
     * @return 应用于 {@code /**} 的 CORS 配置来源
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(AppCorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.copyOf(properties.getAllowedOrigins()));
        configuration.setAllowedMethods(ALLOWED_METHODS);
        configuration.setAllowedHeaders(ALLOWED_HEADERS);
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
