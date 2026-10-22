package com.betterlearning.server.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;

import com.betterlearning.server.config.AppJwtProperties;
import com.betterlearning.server.config.JwtConfiguration;
import com.betterlearning.server.converter.UserConverter;
import com.betterlearning.server.dao.UserDao;
import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.manager.RateLimitManager;
import com.betterlearning.server.manager.SessionManager;
import com.betterlearning.server.model.dataobject.UserDO;
import com.betterlearning.server.model.dto.CreateSessionDTO;
import com.betterlearning.server.model.vo.SessionVO;
import com.betterlearning.server.security.IssuedToken;
import com.betterlearning.server.security.JwtTokenService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;

class SessionServiceImplTests {

    private static final String RAW_PASSWORD = " Passw0rd! ";

    private static final String STORED_HASH = "$2a$10$stored-hash";

    private static final String DUMMY_HASH = "$2a$10$dummy-hash";

    private static final Instant NOW = Instant.parse("2026-10-22T10:00:00Z");

    private UserDao userDao;

    private PasswordEncoder passwordEncoder;

    private SessionManager sessionManager;

    private RateLimitManager rateLimitManager;

    private JwtDecoder jwtDecoder;

    private SessionServiceImpl sessionService;

    @BeforeEach
    void setUp() {
        userDao = mock(UserDao.class);
        passwordEncoder = mock(PasswordEncoder.class);
        sessionManager = mock(SessionManager.class);
        rateLimitManager = mock(RateLimitManager.class);
        when(passwordEncoder.encode(anyString())).thenReturn(DUMMY_HASH);

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        AppJwtProperties properties = jwtProperties();
        JwtConfiguration configuration = new JwtConfiguration();
        JwtEncoder encoder = configuration.jwtEncoder(properties);
        jwtDecoder = configuration.jwtDecoder(properties, clock);
        JwtTokenService jwtTokenService = new JwtTokenService(encoder, properties, clock);
        sessionService = new SessionServiceImpl(userDao, passwordEncoder, jwtTokenService,
                sessionManager, rateLimitManager, new UserConverter());
    }

    @Test
    void doesNotReturnSessionWhenSessionStoreWriteFails() {
        UserDO user = user(42L, 3);
        when(userDao.findByUsername("alice_01")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(RAW_PASSWORD, STORED_HASH)).thenReturn(true);
        doThrow(new BusinessException(ErrorCode.SERVICE_UNAVAILABLE))
                .when(sessionManager).create(any(IssuedToken.class), eq(42L));

        assertThatThrownBy(() -> sessionService.login(request("alice_01"), "127.0.0.1"))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode())
                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE));

        verify(sessionManager).create(any(IssuedToken.class), eq(42L));
        verify(rateLimitManager).checkLogin("127.0.0.1", "alice_01");
    }

    @Test
    void usesDummyHashOncePerUnknownUserWithoutEncodingPerLogin() {
        when(userDao.findByUsername("nobody")).thenReturn(Optional.empty());
        when(passwordEncoder.matches(RAW_PASSWORD, DUMMY_HASH)).thenReturn(false);

        assertThatThrownBy(() -> sessionService.login(request("nobody"), "127.0.0.1"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.UNAUTHORIZED);
                    assertThat(exception.getMessage()).isEqualTo("用户名或密码错误");
                });

        verify(passwordEncoder).matches(RAW_PASSWORD, DUMMY_HASH);
        verify(passwordEncoder, times(1)).encode(anyString());
    }

    @Test
    void usesDatabaseTokenVersionAndIssuesTwoHourBearerToken() {
        UserDO user = user(42L, 9);
        when(userDao.findByUsername("alice_01")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(RAW_PASSWORD, STORED_HASH)).thenReturn(true);

        SessionVO session = sessionService.login(request("alice_01"), "127.0.0.1");

        assertThat(session.getTokenType()).isEqualTo("Bearer");
        assertThat(session.getExpiresIn()).isEqualTo(7200);
        Jwt jwt = jwtDecoder.decode(session.getAccessToken());
        assertThat(jwt.getSubject()).isEqualTo("42");
        assertThat(((Number) jwt.getClaims().get("token_version")).intValue()).isEqualTo(9);
        assertThat(session.toString())
                .contains("[PROTECTED]")
                .doesNotContain(session.getAccessToken())
                .doesNotContain("alice_01");
        verify(sessionManager).create(any(IssuedToken.class), eq(42L));
    }

    @Test
    void refusesToIssueTokenWhenAccountIdIsMissing() {
        UserDO user = user(null, 0);
        when(userDao.findByUsername("alice_01")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(RAW_PASSWORD, STORED_HASH)).thenReturn(true);

        assertThatThrownBy(() -> sessionService.login(request("alice_01"), "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("account_id");

        verifyNoInteractions(sessionManager);
    }

    @Test
    void refusesToIssueTokenWhenTokenVersionIsMissing() {
        UserDO user = user(42L, null);
        when(userDao.findByUsername("alice_01")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches(RAW_PASSWORD, STORED_HASH)).thenReturn(true);

        assertThatThrownBy(() -> sessionService.login(request("alice_01"), "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token_version");

        verifyNoInteractions(sessionManager);
    }

    private static CreateSessionDTO request(String username) {
        CreateSessionDTO request = new CreateSessionDTO();
        request.setUsername(username);
        request.setPassword(RAW_PASSWORD);
        return request;
    }

    private static UserDO user(Long accountId, Integer tokenVersion) {
        UserDO user = new UserDO();
        user.setAccountId(accountId);
        user.setUsername("alice_01");
        user.setPasswordHash(STORED_HASH);
        user.setRole("USER");
        user.setTokenVersion(tokenVersion);
        user.setCreatedAt(java.time.OffsetDateTime.parse("2026-10-22T10:00:00Z"));
        user.setUpdatedAt(java.time.OffsetDateTime.parse("2026-10-22T10:00:00Z"));
        return user;
    }

    private static AppJwtProperties jwtProperties() {
        AppJwtProperties properties = new AppJwtProperties();
        properties.setSecretBase64(Base64.getEncoder().encodeToString(
                "0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        properties.setIssuer("betterlearning-test");
        properties.setAudience("betterlearning-api");
        properties.setAccessTokenTtl(Duration.ofHours(2));
        return properties;
    }
}
