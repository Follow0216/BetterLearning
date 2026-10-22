package com.betterlearning.server.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.SecurityProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 dev profile 中声明的默认用户属性绑定。
 *
 * <p>每个用例先把 {@code systemEnvironment} 替换为仅含虚构值的
 * {@link SystemEnvironmentPropertySource}，并清理系统属性中的同名键，
 * 避免宿主环境变量或默认 profile 干扰。只加载真实配置文件与
 * {@link SecurityProperties} 绑定，不启用自动配置，因此不触碰数据库、Redis
 * 或现有安全过滤链。dev 配置不声明 {@code spring.security.user.*}；本测试验证
 * 环境变量仍可经 relaxed binding 绑定，且密码中的 {@code ${...}} 按字面值保留。</p>
 */
class SecurityPropertiesConfigTests {

    private static final String DEV_USERNAME = "dev-security-user-fixture";

    private static final String DEV_PASSWORD = "dev-security-password-fixture";

    @Test
    void devProfileBindsEnvironmentValues() {
        runner(Map.of(
                "SPRING_PROFILES_ACTIVE", "dev",
                "SPRING_SECURITY_USER_NAME", DEV_USERNAME,
                "SPRING_SECURITY_USER_PASSWORD", DEV_PASSWORD))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getEnvironment().getProperty("spring.profiles.active"))
                            .isEqualTo("dev");
                    assertThat(context.getEnvironment().getProperty("spring.security.user.name"))
                            .isEqualTo(DEV_USERNAME);
                    SecurityProperties properties = context.getBean(SecurityProperties.class);
                    assertThat(properties.getUser().getName()).isEqualTo(DEV_USERNAME);
                    assertThat(properties.getUser().getPassword()).isEqualTo(DEV_PASSWORD);
                });
    }

    @Test
    void securityPropertiesBindEnvironmentPasswordVerbatim() {
        String password = "${HOME}Dev!";
        runner(Map.of(
                "SPRING_PROFILES_ACTIVE", "dev",
                "SPRING_SECURITY_USER_NAME", DEV_USERNAME,
                "SPRING_SECURITY_USER_PASSWORD", password))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(SecurityProperties.class)
                            .getUser().getPassword()).isEqualTo(password);
                });
    }

    @Test
    void nonDevProfileSkipsDevSourceAndBindsEnvironmentVariables() {
        runner(Map.of(
                "SPRING_PROFILES_ACTIVE", "test",
                "SPRING_SECURITY_USER_NAME", DEV_USERNAME,
                "SPRING_SECURITY_USER_PASSWORD", DEV_PASSWORD))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(propertySourceNames(context.getEnvironment()))
                            .noneMatch(name -> name.contains("application-dev.properties"));
                    assertThat(context.getEnvironment().getProperty("spring.profiles.active"))
                            .isEqualTo("test");
                    SecurityProperties properties = context.getBean(SecurityProperties.class);
                    assertThat(properties.getUser().getName()).isEqualTo(DEV_USERNAME);
                    assertThat(properties.getUser().getPassword()).isEqualTo(DEV_PASSWORD);
                });
    }

    @Test
    void missingCredentialsDoNotCreateDeclaredPlaceholders() {
        runner(Map.of("SPRING_PROFILES_ACTIVE", "dev")).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getEnvironment().getProperty("spring.security.user.name")).isNull();
            assertThat(context.getEnvironment().getProperty("spring.security.user.password"))
                    .isNull();
            SecurityProperties properties = context.getBean(SecurityProperties.class);
            assertThat(properties.getUser().getName()).isEqualTo("user");
            assertThat(properties.getUser().getPassword()).isNotBlank();
            assertThat(properties.getUser().isPasswordGenerated()).isTrue();
        });
    }

    private static ApplicationContextRunner runner(Map<String, String> environment) {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    isolateSystemEnvironment(context.getEnvironment(), environment);
                    new ConfigDataApplicationContextInitializer().initialize(context);
                })
                .withUserConfiguration(SecurityPropertiesConfiguration.class);
    }

    private static void isolateSystemEnvironment(
            ConfigurableEnvironment environment, Map<String, String> values) {
        String systemPropertiesName = StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME;
        String systemEnvironmentName = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;
        MutablePropertySources sources = environment.getPropertySources();
        PropertySource<?> systemProperties = sources.get(systemPropertiesName);
        if (systemProperties instanceof MapPropertySource mapPropertySource) {
            Map<String, Object> isolated = new LinkedHashMap<>(mapPropertySource.getSource());
            isolated.keySet().removeIf(SecurityPropertiesConfigTests::isSecurityOrProfileKey);
            sources.replace(systemPropertiesName, new MapPropertySource(systemPropertiesName, isolated));
        } else if (systemProperties != null) {
            sources.remove(systemPropertiesName);
        }
        sources.remove(systemEnvironmentName);
        sources.addLast(new SystemEnvironmentPropertySource(
                systemEnvironmentName, new LinkedHashMap<>(values)));
    }

    private static boolean isSecurityOrProfileKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replace('_', '.');
        return normalized.startsWith("spring.security.user.")
                || normalized.equals("spring.profiles.active")
                || normalized.equals("spring.profiles.default")
                || normalized.equals("spring.profiles.include");
    }

    private static List<String> propertySourceNames(ConfigurableEnvironment environment) {
        return StreamSupport.stream(environment.getPropertySources().spliterator(), false)
                .map(PropertySource::getName)
                .toList();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SecurityProperties.class)
    static class SecurityPropertiesConfiguration {
    }
}
