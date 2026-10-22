package com.betterlearning.server.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;

import com.betterlearning.server.dao.UserDao;
import com.betterlearning.server.enums.UserUniqueField;
import com.betterlearning.server.exception.UserUniqueConflictException;
import com.betterlearning.server.model.dataobject.UserDO;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 验证 dev 账号初始化器的输入校验、幂等与冲突处理边界。
 *
 * <p>使用真实 BCrypt 编码器与受控环境，DAO 全部为 mock，不连接数据库。所有虚构凭据只用于
 * 断言，不写入日志。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
class DevUserAccountInitializerTests {

    private static final String RAW_USERNAME = "  Dev_Bootstrap_User  ";

    private static final String USERNAME = "dev_bootstrap_user";

    private static final String PASSWORD = "  DevPassw0rd!  ";

    private static final String TRIMMED_PASSWORD = PASSWORD.strip();

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Test
    void ignoresCanonicalSecurityPropertiesWithoutRawVariables() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.security.user.name", "${SPRING_SECURITY_USER_NAME}")
                .withProperty("spring.security.user.password", "${SPRING_SECURITY_USER_PASSWORD}");
        UserDao userDao = mock(UserDao.class);

        assertThatThrownBy(() -> initializer(environment, userDao).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(DevUserAccountInitializer.USERNAME_VARIABLE);
        verifyNoInteractions(userDao);
    }

    @Test
    void rejectsMissingUsernameBeforeDatabaseAccess() {
        assertRejectedBeforeDatabase(null, PASSWORD,
                DevUserAccountInitializer.USERNAME_VARIABLE);
    }

    @Test
    void rejectsBlankUsernameBeforeDatabaseAccess() {
        assertRejectedBeforeDatabase("   ", PASSWORD,
                DevUserAccountInitializer.USERNAME_VARIABLE);
    }

    @Test
    void rejectsInvalidUsernameBeforeDatabaseAccess() {
        assertRejectedBeforeDatabase("bad name!", PASSWORD,
                DevUserAccountInitializer.USERNAME_VARIABLE);
    }

    @Test
    void rejectsMissingPasswordBeforeDatabaseAccess() {
        assertRejectedBeforeDatabase(RAW_USERNAME, null,
                DevUserAccountInitializer.PASSWORD_VARIABLE);
    }

    @Test
    void rejectsBlankPasswordBeforeDatabaseAccess() {
        assertRejectedBeforeDatabase(RAW_USERNAME, "   ",
                DevUserAccountInitializer.PASSWORD_VARIABLE);
    }

    @Test
    void rejectsEightSpacesPasswordBeforeDatabaseAccess() {
        assertRejectedBeforeDatabase(RAW_USERNAME, " ".repeat(8),
                DevUserAccountInitializer.PASSWORD_VARIABLE);
    }

    @Test
    void rejectsMoreThanEightSpacesPasswordBeforeDatabaseAccess() {
        assertRejectedBeforeDatabase(RAW_USERNAME, " ".repeat(12),
                DevUserAccountInitializer.PASSWORD_VARIABLE);
    }

    @Test
    void rejectsUnicodeWhitespacePasswordBeforeDatabaseAccess() {
        assertRejectedBeforeDatabase(RAW_USERNAME, "\u3000".repeat(8),
                DevUserAccountInitializer.PASSWORD_VARIABLE);
    }

    @Test
    void createsUserWithResolvablePlaceholderPasswordVerbatim() {
        String password = "${HOME}Dev!";
        String hash = insertedHashFor(password);

        assertThat(passwordEncoder.matches(password, hash)).isTrue();
        String home = System.getenv("HOME");
        if (home != null) {
            assertThat(passwordEncoder.matches(home + "Dev!", hash)).isFalse();
        }
    }

    @Test
    void createsUserWithUnresolvablePlaceholderPasswordVerbatim() {
        String password = "${DEV_SEED_UNRESOLVED}";
        String hash = insertedHashFor(password);

        assertThat(passwordEncoder.matches(password, hash)).isTrue();
    }

    @Test
    void createsUserWithSurroundingWhitespacePasswordVerbatim() {
        String password = "  DevPassw0rd!  ";
        String hash = insertedHashFor(password);

        assertThat(passwordEncoder.matches(password, hash)).isTrue();
        assertThat(passwordEncoder.matches(password.strip(), hash)).isFalse();
    }

    @Test
    void rejectsTooLongPasswordBeforeDatabaseAccess() {
        assertRejectedBeforeDatabase(RAW_USERNAME, "a".repeat(65),
                DevUserAccountInitializer.PASSWORD_VARIABLE);
    }

    @Test
    void rejectsPasswordExceedingUtf8ByteLimitBeforeDatabaseAccess() {
        assertRejectedBeforeDatabase(RAW_USERNAME, "密".repeat(25),
                DevUserAccountInitializer.PASSWORD_VARIABLE);
    }

    @Test
    void createsNormalizedUserWithRawPasswordHash() {
        UserDao userDao = mock(UserDao.class);
        when(userDao.findByUsername(USERNAME)).thenReturn(Optional.empty());
        when(userDao.insert(eq(USERNAME), anyString())).thenReturn(42L);

        initializer(environment(RAW_USERNAME, PASSWORD), userDao).run(null);

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(userDao).findByUsername(USERNAME);
        verify(userDao).insert(eq(USERNAME), hash.capture());
        assertThat(passwordEncoder.matches(PASSWORD, hash.getValue())).isTrue();
        assertThat(passwordEncoder.matches(TRIMMED_PASSWORD, hash.getValue())).isFalse();
        verifyNoMoreInteractions(userDao);
    }

    @Test
    void keepsExistingCompatibleUserUnchanged() {
        UserDao userDao = mock(UserDao.class);
        UserDO existing = existingUser(passwordEncoder.encode(PASSWORD), "USER");
        when(userDao.findByUsername(USERNAME)).thenReturn(Optional.of(existing));

        initializer(environment(RAW_USERNAME, PASSWORD), userDao).run(null);

        verify(userDao).findByUsername(USERNAME);
        verifyNoMoreInteractions(userDao);
        assertThat(existing.getAccountId()).isEqualTo(7L);
        assertThat(existing.getTokenVersion()).isEqualTo(3);
        assertThat(existing.getPhone()).isEqualTo("+8613800138000");
        assertThat(existing.getEmail()).isEqualTo("dev@example.com");
        assertThat(passwordEncoder.matches(PASSWORD, existing.getPasswordHash())).isTrue();
    }

    @Test
    void rejectsExistingUserWithDifferentPasswordWithoutUpdating() {
        UserDao userDao = mock(UserDao.class);
        UserDO existing = existingUser(passwordEncoder.encode("OtherPass1!"), "USER");
        String hashBefore = existing.getPasswordHash();
        when(userDao.findByUsername(USERNAME)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> initializer(environment(RAW_USERNAME, PASSWORD), userDao).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("密码不匹配")
                .hasMessageNotContaining(PASSWORD)
                .hasMessageNotContaining(TRIMMED_PASSWORD)
                .hasMessageNotContaining(USERNAME)
                .hasMessageNotContaining(hashBefore);
        verify(userDao).findByUsername(USERNAME);
        verifyNoMoreInteractions(userDao);
        assertThat(existing.getPasswordHash()).isEqualTo(hashBefore);
    }

    @Test
    void rejectsExistingAdminWithoutChangingRole() {
        UserDao userDao = mock(UserDao.class);
        UserDO existing = existingUser(passwordEncoder.encode(PASSWORD), "ADMIN");
        when(userDao.findByUsername(USERNAME)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> initializer(environment(RAW_USERNAME, PASSWORD), userDao).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("角色不是 USER")
                .hasMessageNotContaining(USERNAME);
        verify(userDao).findByUsername(USERNAME);
        verifyNoMoreInteractions(userDao);
        assertThat(existing.getRole()).isEqualTo("ADMIN");
    }

    @Test
    void acceptsConcurrentUsernameConflictWhenRereadUserMatches() {
        UserDao userDao = mock(UserDao.class);
        UserDO existing = existingUser(passwordEncoder.encode(PASSWORD), "USER");
        UserUniqueConflictException conflict = conflict(UserUniqueField.USERNAME);
        when(userDao.findByUsername(USERNAME))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(userDao.insert(eq(USERNAME), anyString())).thenThrow(conflict);

        assertThatCode(() -> initializer(environment(RAW_USERNAME, PASSWORD), userDao).run(null))
                .doesNotThrowAnyException();
        verify(userDao, times(2)).findByUsername(USERNAME);
        verify(userDao).insert(eq(USERNAME), anyString());
        verifyNoMoreInteractions(userDao);
    }

    @Test
    void rejectsRereadUserWithDifferentPasswordAfterConflict() {
        UserDao userDao = mock(UserDao.class);
        UserDO existing = existingUser(passwordEncoder.encode("OtherPass1!"), "USER");
        when(userDao.findByUsername(USERNAME))
                .thenReturn(Optional.empty(), Optional.of(existing));
        when(userDao.insert(eq(USERNAME), anyString()))
                .thenThrow(conflict(UserUniqueField.USERNAME));

        assertThatThrownBy(() -> initializer(environment(RAW_USERNAME, PASSWORD), userDao).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("密码不匹配");
    }

    @Test
    void rethrowsUsernameConflictWhenRereadFindsNothing() {
        UserDao userDao = mock(UserDao.class);
        UserUniqueConflictException conflict = conflict(UserUniqueField.USERNAME);
        when(userDao.findByUsername(USERNAME)).thenReturn(Optional.empty());
        when(userDao.insert(eq(USERNAME), anyString())).thenThrow(conflict);

        assertThatThrownBy(() -> initializer(environment(RAW_USERNAME, PASSWORD), userDao).run(null))
                .isSameAs(conflict);
    }

    @Test
    void propagatesNonUsernameConflict() {
        UserDao userDao = mock(UserDao.class);
        UserUniqueConflictException conflict = conflict(UserUniqueField.PHONE);
        when(userDao.findByUsername(USERNAME)).thenReturn(Optional.empty());
        when(userDao.insert(eq(USERNAME), anyString())).thenThrow(conflict);

        assertThatThrownBy(() -> initializer(environment(RAW_USERNAME, PASSWORD), userDao).run(null))
                .isSameAs(conflict);
        verify(userDao, times(1)).findByUsername(USERNAME);
    }

    @Test
    void propagatesLookupFailure() {
        UserDao userDao = mock(UserDao.class);
        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("database unavailable");
        when(userDao.findByUsername(USERNAME)).thenThrow(failure);

        assertThatThrownBy(() -> initializer(environment(RAW_USERNAME, PASSWORD), userDao).run(null))
                .isSameAs(failure);
    }

    @Test
    void propagatesInsertFailure() {
        UserDao userDao = mock(UserDao.class);
        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("database unavailable");
        when(userDao.findByUsername(USERNAME)).thenReturn(Optional.empty());
        when(userDao.insert(eq(USERNAME), anyString())).thenThrow(failure);

        assertThatThrownBy(() -> initializer(environment(RAW_USERNAME, PASSWORD), userDao).run(null))
                .isSameAs(failure);
    }

    private void assertRejectedBeforeDatabase(String username, String password,
            String expectedVariable) {
        UserDao userDao = mock(UserDao.class);

        assertThatThrownBy(() -> initializer(environment(username, password), userDao).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(expectedVariable)
                .hasMessageNotContaining(String.valueOf(username))
                .hasMessageNotContaining(String.valueOf(password));
        verifyNoInteractions(userDao);
    }

    private String insertedHashFor(String password) {
        UserDao userDao = mock(UserDao.class);
        when(userDao.findByUsername(USERNAME)).thenReturn(Optional.empty());
        when(userDao.insert(eq(USERNAME), anyString())).thenReturn(42L);

        initializer(environment(RAW_USERNAME, password), userDao).run(null);

        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(userDao).insert(eq(USERNAME), hash.capture());
        return hash.getValue();
    }

    private DevUserAccountInitializer initializer(MockEnvironment environment, UserDao userDao) {
        return new DevUserAccountInitializer(environment, userDao, passwordEncoder);
    }

    private static MockEnvironment environment(String username, String password) {
        MockEnvironment environment = new MockEnvironment();
        if (username != null) {
            environment.setProperty(DevUserAccountInitializer.USERNAME_VARIABLE, username);
        }
        if (password != null) {
            environment.setProperty(DevUserAccountInitializer.PASSWORD_VARIABLE, password);
        }
        return environment;
    }

    private static UserDO existingUser(String passwordHash, String role) {
        UserDO user = new UserDO();
        user.setAccountId(7L);
        user.setUsername(USERNAME);
        user.setPasswordHash(passwordHash);
        user.setRole(role);
        user.setTokenVersion(3);
        user.setPhone("+8613800138000");
        user.setEmail("dev@example.com");
        user.setCreatedAt(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        user.setUpdatedAt(OffsetDateTime.parse("2026-01-02T00:00:00Z"));
        return user;
    }

    private static UserUniqueConflictException conflict(UserUniqueField field) {
        return new UserUniqueConflictException(field,
                new DataIntegrityViolationException("unique constraint"));
    }
}
