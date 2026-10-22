package com.betterlearning.server.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.betterlearning.server.converter.UserConverter;
import com.betterlearning.server.dao.UserDao;
import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.manager.RateLimitManager;
import com.betterlearning.server.model.dataobject.UserDO;
import com.betterlearning.server.model.dto.ChangePasswordDTO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class UserServiceImplPasswordTests {

    private static final long ACCOUNT_ID = 42L;

    private static final int AUTHENTICATED_VERSION = 3;

    private static final String STORED_HASH = "$2a$10$stored-hash";

    private static final String NEW_HASH = "$2a$10$new-hash";

    private static final String OLD_PASSWORD = "OldPassw0rd!";

    private static final String NEW_PASSWORD = "NewPassw0rd!";

    private UserDao userDao;

    private PasswordEncoder passwordEncoder;

    private UserServiceImpl userService;

    @BeforeEach
    void setUp() {
        userDao = mock(UserDao.class);
        passwordEncoder = mock(PasswordEncoder.class);
        userService = new UserServiceImpl(userDao, passwordEncoder, new UserConverter(),
                mock(RateLimitManager.class));
    }

    @Test
    void changesPasswordUsingAuthenticatedVersionAndCurrentHash() {
        when(userDao.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(user(3)));
        when(passwordEncoder.matches(OLD_PASSWORD, STORED_HASH)).thenReturn(true);
        when(passwordEncoder.encode(NEW_PASSWORD)).thenReturn(NEW_HASH);
        when(userDao.updatePassword(ACCOUNT_ID, STORED_HASH, AUTHENTICATED_VERSION, NEW_HASH))
                .thenReturn(1);

        assertThatCode(() -> userService.changePassword(ACCOUNT_ID, AUTHENTICATED_VERSION,
                request())).doesNotThrowAnyException();

        verify(userDao).updatePassword(ACCOUNT_ID, STORED_HASH, AUTHENTICATED_VERSION, NEW_HASH);
    }

    @Test
    void rejectsWrongOldPasswordWithoutUpdating() {
        when(userDao.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(user(3)));
        when(passwordEncoder.matches(OLD_PASSWORD, STORED_HASH)).thenReturn(false);

        assertThatThrownBy(() -> userService.changePassword(ACCOUNT_ID, AUTHENTICATED_VERSION,
                request()))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.INVALID_PARAM);
                    assertThat(exception.getMessage()).isEqualTo("请求参数不合法");
                    assertThat(exception.getFieldErrors())
                            .containsEntry("oldPassword", "旧密码错误");
                });

        verify(userDao, never()).updatePassword(anyLong(), anyString(), anyInt(), anyString());
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void rejectsStaleAuthenticatedVersionBeforeCheckingOldPassword() {
        when(userDao.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(user(4)));

        assertThatThrownBy(() -> userService.changePassword(ACCOUNT_ID, AUTHENTICATED_VERSION,
                request()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode())
                .isEqualTo(ErrorCode.PASSWORD_CHANGE_CONFLICT));

        verify(passwordEncoder, never()).matches(anyString(), anyString());
        verify(userDao, never()).updatePassword(anyLong(), anyString(), anyInt(), anyString());
    }

    @Test
    void reportsConflictWhenConditionalUpdateAffectsNoRow() {
        when(userDao.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(user(3)));
        when(passwordEncoder.matches(OLD_PASSWORD, STORED_HASH)).thenReturn(true);
        when(passwordEncoder.encode(NEW_PASSWORD)).thenReturn(NEW_HASH);
        when(userDao.updatePassword(ACCOUNT_ID, STORED_HASH, AUTHENTICATED_VERSION, NEW_HASH))
                .thenReturn(0);

        assertThatThrownBy(() -> userService.changePassword(ACCOUNT_ID, AUTHENTICATED_VERSION,
                request()))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode())
                .isEqualTo(ErrorCode.PASSWORD_CHANGE_CONFLICT));
    }

    @Test
    void onlyOneConcurrentChangeSucceedsWhenBothUseSameAuthenticatedVersion() throws Exception {
        when(userDao.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(user(3)));
        when(passwordEncoder.matches(anyString(), eq(STORED_HASH))).thenReturn(true);
        when(passwordEncoder.encode(NEW_PASSWORD)).thenReturn(NEW_HASH);
        AtomicInteger casCalls = new AtomicInteger();
        when(userDao.updatePassword(eq(ACCOUNT_ID), eq(STORED_HASH),
                eq(AUTHENTICATED_VERSION), eq(NEW_HASH)))
                .thenAnswer(invocation -> casCalls.getAndIncrement() == 0 ? 1 : 0);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Callable<ErrorCode> attempt = () -> {
                ready.countDown();
                if (!start.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("并发测试未能同时开始");
                }
                try {
                    userService.changePassword(ACCOUNT_ID, AUTHENTICATED_VERSION, request());
                    return null;
                } catch (BusinessException exception) {
                    return exception.getErrorCode();
                }
            };
            Future<ErrorCode> first = executor.submit(attempt);
            Future<ErrorCode> second = executor.submit(attempt);
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<ErrorCode> results = Arrays.asList(first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS));

            assertThat(results.stream().filter(Objects::isNull).count()).isEqualTo(1);
            assertThat(results).filteredOn(Objects::nonNull)
                    .containsExactly(ErrorCode.PASSWORD_CHANGE_CONFLICT);
            assertThat(casCalls).hasValue(2);
            verify(userDao, times(2)).updatePassword(ACCOUNT_ID, STORED_HASH,
                    AUTHENTICATED_VERSION, NEW_HASH);
        } finally {
            executor.shutdownNow();
        }
    }

    private UserDO user(int tokenVersion) {
        UserDO user = new UserDO();
        user.setAccountId(ACCOUNT_ID);
        user.setUsername("alice_01");
        user.setPasswordHash(STORED_HASH);
        user.setTokenVersion(tokenVersion);
        return user;
    }

    private ChangePasswordDTO request() {
        ChangePasswordDTO request = new ChangePasswordDTO();
        request.setOldPassword(OLD_PASSWORD);
        request.setNewPassword(NEW_PASSWORD);
        request.setConfirmPassword(NEW_PASSWORD);
        return request;
    }
}
