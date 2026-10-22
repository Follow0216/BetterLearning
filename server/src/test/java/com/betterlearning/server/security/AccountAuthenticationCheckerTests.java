package com.betterlearning.server.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;

import com.betterlearning.server.dao.UserDao;
import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.manager.SessionManager;
import com.betterlearning.server.model.dataobject.UserDO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.springframework.dao.DataAccessResourceFailureException;

class AccountAuthenticationCheckerTests {

    private static final long ACCOUNT_ID = 42L;

    private static final String JWT_ID = "6f1a7c52-1c9c-4f5e-9e0e-0f6b2f3a4c11";

    private static final int TOKEN_VERSION = 2;

    private SessionManager sessionManager;

    private UserDao userDao;

    private AccountAuthenticationChecker checker;

    @BeforeEach
    void setUp() {
        sessionManager = mock(SessionManager.class);
        userDao = mock(UserDao.class);
        checker = new AccountAuthenticationChecker(sessionManager, userDao);
    }

    @Test
    void returnsAccountSnapshotWhenSessionAndDatabaseMatch() {
        when(sessionManager.isActive(JWT_ID, ACCOUNT_ID)).thenReturn(true);
        when(userDao.findByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(user("alice", "USER", TOKEN_VERSION)));

        Optional<AuthenticatedAccount> result =
                checker.authenticate(ACCOUNT_ID, JWT_ID, TOKEN_VERSION);

        assertThat(result).contains(
                new AuthenticatedAccount(ACCOUNT_ID, "alice", "USER", TOKEN_VERSION, JWT_ID));
        InOrder order = inOrder(sessionManager, userDao);
        order.verify(sessionManager).isActive(JWT_ID, ACCOUNT_ID);
        order.verify(userDao).findByAccountId(ACCOUNT_ID);
    }

    @Test
    void rejectsInactiveSessionWithoutReadingAccount() {
        when(sessionManager.isActive(JWT_ID, ACCOUNT_ID)).thenReturn(false);

        assertThat(checker.authenticate(ACCOUNT_ID, JWT_ID, TOKEN_VERSION)).isEmpty();

        verifyNoInteractions(userDao);
    }

    @Test
    void rejectsMissingAccount() {
        when(sessionManager.isActive(JWT_ID, ACCOUNT_ID)).thenReturn(true);
        when(userDao.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.empty());

        assertThat(checker.authenticate(ACCOUNT_ID, JWT_ID, TOKEN_VERSION)).isEmpty();
    }

    @Test
    void rejectsTokenVersionMismatch() {
        when(sessionManager.isActive(JWT_ID, ACCOUNT_ID)).thenReturn(true);
        when(userDao.findByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(user("alice", "USER", TOKEN_VERSION + 1)));

        assertThat(checker.authenticate(ACCOUNT_ID, JWT_ID, TOKEN_VERSION)).isEmpty();
    }

    @Test
    void rejectsAccountWithoutRole() {
        when(sessionManager.isActive(JWT_ID, ACCOUNT_ID)).thenReturn(true);
        when(userDao.findByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(user("alice", null, TOKEN_VERSION)));

        assertThat(checker.authenticate(ACCOUNT_ID, JWT_ID, TOKEN_VERSION)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void rejectsAccountWithBlankRole(String role) {
        when(sessionManager.isActive(JWT_ID, ACCOUNT_ID)).thenReturn(true);
        when(userDao.findByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(user("alice", role, TOKEN_VERSION)));

        assertThat(checker.authenticate(ACCOUNT_ID, JWT_ID, TOKEN_VERSION)).isEmpty();
    }

    @Test
    void propagatesUnavailableSessionStore() {
        when(sessionManager.isActive(JWT_ID, ACCOUNT_ID))
                .thenThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE));

        assertThatThrownBy(() -> checker.authenticate(ACCOUNT_ID, JWT_ID, TOKEN_VERSION))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));

        verifyNoInteractions(userDao);
    }

    @Test
    void propagatesSessionStoreDataAccessFailure() {
        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("redis down");
        when(sessionManager.isActive(JWT_ID, ACCOUNT_ID)).thenThrow(failure);

        assertThatThrownBy(() -> checker.authenticate(ACCOUNT_ID, JWT_ID, TOKEN_VERSION))
                .isSameAs(failure);
    }

    @Test
    void propagatesAccountLookupFailure() {
        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("database down");
        when(sessionManager.isActive(JWT_ID, ACCOUNT_ID)).thenReturn(true);
        when(userDao.findByAccountId(ACCOUNT_ID)).thenThrow(failure);

        assertThatThrownBy(() -> checker.authenticate(ACCOUNT_ID, JWT_ID, TOKEN_VERSION))
                .isSameAs(failure);
    }

    @Test
    void rejectsAccountRecordWithoutAccountId() {
        when(sessionManager.isActive(JWT_ID, ACCOUNT_ID)).thenReturn(true);
        UserDO user = user("alice", "USER", TOKEN_VERSION);
        user.setAccountId(null);
        when(userDao.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(user));

        assertThat(checker.authenticate(ACCOUNT_ID, JWT_ID, TOKEN_VERSION)).isEmpty();
    }

    @Test
    void rejectsAccountRecordWithoutTokenVersion() {
        when(sessionManager.isActive(JWT_ID, ACCOUNT_ID)).thenReturn(true);
        UserDO user = user("alice", "USER", null);
        when(userDao.findByAccountId(ACCOUNT_ID)).thenReturn(Optional.of(user));

        assertThat(checker.authenticate(ACCOUNT_ID, JWT_ID, TOKEN_VERSION)).isEmpty();
    }

    @Test
    void acceptsTokenVersionOutsideIntegerCache() {
        int version = 1000;
        when(sessionManager.isActive(JWT_ID, ACCOUNT_ID)).thenReturn(true);
        when(userDao.findByAccountId(ACCOUNT_ID))
                .thenReturn(Optional.of(user("alice", "USER", version)));

        assertThat(checker.authenticate(ACCOUNT_ID, JWT_ID, version))
                .contains(new AuthenticatedAccount(ACCOUNT_ID, "alice", "USER", version, JWT_ID));
    }

    private static UserDO user(String username, String role, Integer tokenVersion) {
        UserDO user = new UserDO();
        user.setAccountId(ACCOUNT_ID);
        user.setUsername(username);
        user.setRole(role);
        user.setTokenVersion(tokenVersion);
        return user;
    }
}
