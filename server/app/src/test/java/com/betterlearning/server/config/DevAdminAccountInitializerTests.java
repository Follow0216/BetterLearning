package com.betterlearning.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.betterlearning.server.dao.UserDao;
import com.betterlearning.server.model.dataobject.UserDO;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class DevAdminAccountInitializerTests {

    private final UserDao dao = mock(UserDao.class);
    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    @Test
    void createsDefaultAdminWithEncodedPassword() {
        new DevAdminAccountInitializer(new MockEnvironment(), dao, encoder)
                .run(new DefaultApplicationArguments());
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(dao).insertAdmin(eq("admin"), hash.capture());
        assertThat(hash.getValue()).isNotEqualTo("admin123");
        assertThat(encoder.matches("admin123", hash.getValue())).isTrue();
        verify(dao, never()).insert(anyString(), anyString());
    }

    @Test
    void overridePasswordIsLiteralAndExistingAdminRemainsUnchanged() {
        String password = "${literal}-Pass123";
        UserDO existing = user("ADMIN", password);
        when(dao.findByUsername("custom_admin")).thenReturn(Optional.of(existing));
        MockEnvironment env = new MockEnvironment()
                .withProperty("APP_DEV_ADMIN_USERNAME", " Custom_Admin ")
                .withProperty("APP_DEV_ADMIN_PASSWORD", password);
        new DevAdminAccountInitializer(env, dao, encoder).run(new DefaultApplicationArguments());
        verify(dao, never()).insertAdmin(anyString(), anyString());
        assertThat(existing.getTokenVersion()).isEqualTo(4);
        assertThat(encoder.matches(password, existing.getPasswordHash())).isTrue();
    }

    @Test
    void refusesUserPromotionAndPasswordReset() {
        when(dao.findByUsername("admin")).thenReturn(Optional.of(user("USER", "admin123")));
        assertThatThrownBy(() -> new DevAdminAccountInitializer(new MockEnvironment(), dao, encoder)
                .run(new DefaultApplicationArguments())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("禁止自动提权");
        when(dao.findByUsername("admin")).thenReturn(Optional.of(user("ADMIN", "different-pass")));
        assertThatThrownBy(() -> new DevAdminAccountInitializer(new MockEnvironment(), dao, encoder)
                .run(new DefaultApplicationArguments())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("未重置");
        verify(dao, never()).insertAdmin(anyString(), anyString());
    }

    @Test
    void blankOverrideIsRejectedInsteadOfFallingBack() {
        assertThatThrownBy(() -> new DevAdminAccountInitializer(new MockEnvironment()
                .withProperty("APP_DEV_ADMIN_PASSWORD", ""), dao, encoder)
                .run(new DefaultApplicationArguments())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_DEV_ADMIN_PASSWORD");
        verify(dao, never()).insertAdmin(anyString(), anyString());
    }

    @Test
    void onlyRegistersForEnabledDevProfile() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withBean(UserDao.class, () -> dao)
                .withBean(PasswordEncoder.class, () -> encoder)
                .withUserConfiguration(DevAdminAccountInitializer.class);
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("test"))
                .run(context -> assertThat(context).doesNotHaveBean(DevAdminAccountInitializer.class));
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("dev"))
                .withPropertyValues("app.dev-admin.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(DevAdminAccountInitializer.class));
        runner.withInitializer(context -> context.getEnvironment().setActiveProfiles("dev"))
                .run(context -> assertThat(context).hasSingleBean(DevAdminAccountInitializer.class));
    }

    private UserDO user(String role, String password) {
        UserDO user = new UserDO();
        user.setRole(role);
        user.setPasswordHash(encoder.encode(password));
        user.setTokenVersion(4);
        return user;
    }
}
