package com.betterlearning.server.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.betterlearning.server.dao.UserDao;
import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.manager.RateLimitManager;
import com.betterlearning.server.manager.SessionManager;
import com.betterlearning.server.security.JwtTokenService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class SessionServiceImplLogoutTests {

    private static final String JWT_ID = "6f1a7c52-1c9c-4f5e-9e0e-0f6b2f3a4c11";

    private UserDao userDao;

    private JwtTokenService jwtTokenService;

    private SessionManager sessionManager;

    private RateLimitManager rateLimitManager;

    private SessionServiceImpl sessionService;

    @BeforeEach
    void setUp() {
        userDao = mock(UserDao.class);
        jwtTokenService = mock(JwtTokenService.class);
        sessionManager = mock(SessionManager.class);
        rateLimitManager = mock(RateLimitManager.class);
        sessionService = new SessionServiceImpl(userDao, mock(PasswordEncoder.class),
                jwtTokenService, sessionManager, rateLimitManager);
    }

    @Test
    void deletesOnlyTheCurrentSession() {
        sessionService.logout(JWT_ID);

        verify(sessionManager).delete(JWT_ID);
        verifyNoMoreInteractions(sessionManager);
        verifyNoInteractions(userDao, jwtTokenService, rateLimitManager);
    }

    @Test
    void propagatesSessionStoreFailureInsteadOfReportingSuccess() {
        doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE))
                .when(sessionManager).delete(JWT_ID);

        assertThatThrownBy(() -> sessionService.logout(JWT_ID))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));

        verify(sessionManager).delete(JWT_ID);
    }
}
