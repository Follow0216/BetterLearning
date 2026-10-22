package com.betterlearning.server.config;

import com.betterlearning.server.dao.UserDao;
import com.betterlearning.server.enums.UserUniqueField;
import com.betterlearning.server.exception.UserUniqueConflictException;
import com.betterlearning.server.model.dataobject.UserDO;
import com.betterlearning.server.validation.UserInputNormalizer;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * dev profile 下的普通用户账号初始化器。
 *
 * <p>在 Flyway 迁移完成后读取 {@code SPRING_SECURITY_USER_NAME} 与
 * {@code SPRING_SECURITY_USER_PASSWORD} 原始环境变量，只创建或校验 {@code USER} 角色账号，
 * 不启用 HTTP Basic／表单登录，不创建 {@code ADMIN}。变量缺失、空白或格式非法时抛出
 * {@link IllegalStateException} 并阻止启动；异常信息只包含变量名与错误类型。</p>
 *
 * <p>变量按原始字面值读取，不解析嵌套占位符；密码中的 {@code ${...}} 会原样参与
 * BCrypt 编码，不会被替换为其他值。用户名仍按现有规则去空白、转小写并校验。</p>
 *
 * <p>本类不声明事务：插入是单条自动提交语句，用户名唯一冲突发生后可在干净事务中重新查询。
 * 已有同名账号只有在角色为 {@code USER} 且密码匹配时才视为成功，且不做任何更新。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Component
@Profile("dev")
public class DevUserAccountInitializer implements ApplicationRunner {

    /** 用户名环境变量名。 */
    static final String USERNAME_VARIABLE = "SPRING_SECURITY_USER_NAME";

    /** 密码环境变量名。 */
    static final String PASSWORD_VARIABLE = "SPRING_SECURITY_USER_PASSWORD";

    /** 允许的账号角色。 */
    private static final String USER_ROLE = "USER";

    /** 密码最小字符数。 */
    private static final int PASSWORD_MIN_LENGTH = 8;

    /** 密码最大字符数。 */
    private static final int PASSWORD_MAX_LENGTH = 64;

    private final Environment environment;

    private final LiteralPropertyResolver literalResolver;

    private final UserDao userDao;

    private final PasswordEncoder passwordEncoder;

    /**
     * 创建 dev 账号初始化器。
     *
     * @param environment 运行环境
     * @param userDao 用户数据访问
     * @param passwordEncoder 密码编码器
     */
    public DevUserAccountInitializer(Environment environment, UserDao userDao,
            PasswordEncoder passwordEncoder) {
        this.environment = environment;
        if (environment instanceof ConfigurableEnvironment configurable) {
            this.literalResolver = new LiteralPropertyResolver(configurable.getPropertySources());
        } else {
            this.literalResolver = null;
        }
        this.userDao = userDao;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 校验环境变量并确保普通用户账号存在。
     *
     * @param args 启动参数，本初始化器不使用
     */
    @Override
    public void run(ApplicationArguments args) {
        String rawUsername = readVariable(USERNAME_VARIABLE);
        if (rawUsername == null) {
            throw missing(USERNAME_VARIABLE);
        }
        if (!UserInputNormalizer.isValidUsername(rawUsername)) {
            throw invalid(USERNAME_VARIABLE);
        }
        String rawPassword = readVariable(PASSWORD_VARIABLE);
        if (rawPassword == null) {
            throw missing(PASSWORD_VARIABLE);
        }
        if (rawPassword.isBlank()) {
            throw invalid(PASSWORD_VARIABLE);
        }
        if (!UserInputNormalizer.isValidPassword(
                rawPassword, PASSWORD_MIN_LENGTH, PASSWORD_MAX_LENGTH)) {
            throw invalid(PASSWORD_VARIABLE);
        }
        String username = UserInputNormalizer.normalizeUsername(rawUsername);
        UserDO existing = userDao.findByUsername(username).orElse(null);
        if (existing != null) {
            ensureCompatible(existing, rawPassword);
            return;
        }
        String passwordHash = passwordEncoder.encode(rawPassword);
        try {
            userDao.insert(username, passwordHash);
        } catch (UserUniqueConflictException conflict) {
            if (conflict.getField() != UserUniqueField.USERNAME) {
                throw conflict;
            }
            UserDO concurrent = userDao.findByUsername(username)
                    .orElseThrow(() -> conflict);
            ensureCompatible(concurrent, rawPassword);
        }
    }

    private void ensureCompatible(UserDO user, String rawPassword) {
        if (!USER_ROLE.equals(user.getRole())) {
            throw new IllegalStateException("dev 账号初始化失败：同名账号角色不是 USER");
        }
        String passwordHash = user.getPasswordHash();
        if (passwordHash == null || !passwordEncoder.matches(rawPassword, passwordHash)) {
            throw new IllegalStateException("dev 账号初始化失败：同名账号密码不匹配");
        }
    }

    private String readVariable(String variable) {
        if (literalResolver != null) {
            return literalResolver.getLiteral(variable);
        }
        return environment.getProperty(variable);
    }

    private static IllegalStateException missing(String variable) {
        return new IllegalStateException("dev 账号初始化失败：缺少环境变量 " + variable);
    }

    private static IllegalStateException invalid(String variable) {
        return new IllegalStateException("dev 账号初始化失败：环境变量 " + variable + " 格式非法");
    }

    /**
     * 以字面值模式读取属性，跳过嵌套占位符解析。
     */
    private static final class LiteralPropertyResolver extends PropertySourcesPropertyResolver {

        LiteralPropertyResolver(PropertySources propertySources) {
            super(propertySources);
        }

        String getLiteral(String key) {
            return getProperty(key, String.class, false);
        }
    }
}
