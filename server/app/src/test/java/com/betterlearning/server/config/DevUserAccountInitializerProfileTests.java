package com.betterlearning.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.betterlearning.server.dao.UserDao;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 验证 dev 账号初始化器只在 dev profile 注册。
 *
 * <p>只检查 bean 注册，不执行 ApplicationRunner，因此不会触发数据库访问。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
class DevUserAccountInitializerProfileTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(UserDao.class, () -> mock(UserDao.class))
            .withBean(PasswordEncoder.class, BCryptPasswordEncoder::new)
            .withUserConfiguration(DevUserAccountInitializer.class);

    @Test
    void isNotRegisteredWithoutDevProfile() {
        runner.withPropertyValues("spring.profiles.active=test").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(DevUserAccountInitializer.class);
        });
    }

    @Test
    void isRegisteredWithDevProfile() {
        runner.withPropertyValues("spring.profiles.active=dev").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(DevUserAccountInitializer.class);
        });
    }
}
