package com.betterlearning.server.config;

import com.betterlearning.server.dao.UserDao;
import com.betterlearning.server.enums.UserUniqueField;
import com.betterlearning.server.exception.UserUniqueConflictException;
import com.betterlearning.server.model.dataobject.UserDO;
import com.betterlearning.server.validation.UserInputNormalizer;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 仅 dev 的最小管理员初始化；默认凭据由用户明确指定，不影响公共注册规则。
 * 已有 USER 不提权，已有 ADMIN 不改密；冲突时失败，不输出密码或哈希。
 */
@Component
@Profile("dev")
@ConditionalOnProperty(name = "app.dev-admin.enabled", havingValue = "true", matchIfMissing = true)
public class DevAdminAccountInitializer implements ApplicationRunner {

    private static final String USERNAME_VARIABLE = "APP_DEV_ADMIN_USERNAME";
    private static final String PASSWORD_VARIABLE = "APP_DEV_ADMIN_PASSWORD";
    private static final String DEFAULT_USERNAME = "admin";
    private static final String DEFAULT_PASSWORD = "admin123";
    private static final String ADMIN_ROLE = "ADMIN";

    private final Environment environment;
    private final LiteralPropertyResolver literalResolver;
    private final UserDao userDao;
    private final PasswordEncoder passwordEncoder;

    public DevAdminAccountInitializer(Environment environment, UserDao userDao,
            PasswordEncoder passwordEncoder) {
        this.environment = environment;
        this.literalResolver = environment instanceof ConfigurableEnvironment configurable
                ? new LiteralPropertyResolver(configurable.getPropertySources()) : null;
        this.userDao = userDao;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
        String rawUsername = valueOrDefault(USERNAME_VARIABLE, DEFAULT_USERNAME);
        String rawPassword = valueOrDefault(PASSWORD_VARIABLE, DEFAULT_PASSWORD);
        if (!UserInputNormalizer.isValidUsername(rawUsername)) {
            throw new IllegalStateException(USERNAME_VARIABLE + " 格式非法");
        }
        // 默认 admin123 为用户指定的七字符开发凭据；公共注册仍要求至少八字符。
        if (rawPassword.isBlank() || !UserInputNormalizer.isValidPassword(rawPassword, 7, 64)) {
            throw new IllegalStateException(PASSWORD_VARIABLE + " 格式非法");
        }
        String username = UserInputNormalizer.normalizeUsername(rawUsername);
        UserDO existing = userDao.findByUsername(username).orElse(null);
        if (existing != null) {
            ensureCompatible(existing, rawPassword);
            return;
        }
        try {
            userDao.insertAdmin(username, passwordEncoder.encode(rawPassword));
        } catch (UserUniqueConflictException conflict) {
            if (conflict.getField() != UserUniqueField.USERNAME) {
                throw conflict;
            }
            UserDO concurrent = userDao.findByUsername(username).orElseThrow(() -> conflict);
            ensureCompatible(concurrent, rawPassword);
        }
    }

    private void ensureCompatible(UserDO user, String password) {
        if (!ADMIN_ROLE.equals(user.getRole())) {
            throw new IllegalStateException("dev 管理员初始化失败：同名账号不是 ADMIN，禁止自动提权");
        }
        if (user.getPasswordHash() == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new IllegalStateException("dev 管理员初始化失败：已有 ADMIN 密码与配置不匹配，未重置");
        }
    }

    private String valueOrDefault(String key, String defaultValue) {
        String value = literalResolver == null ? environment.getProperty(key) : literalResolver.literal(key);
        return value == null ? defaultValue : value;
    }

    private static final class LiteralPropertyResolver extends PropertySourcesPropertyResolver {
        LiteralPropertyResolver(PropertySources propertySources) {
            super(propertySources);
        }

        String literal(String key) {
            return getProperty(key, String.class, false);
        }
    }
}
