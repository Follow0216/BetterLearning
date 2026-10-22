package com.betterlearning.server.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.security.IssuedToken;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@Tag("integration")
class RedisSessionManagerIntegrationTests {

    private static final String SESSION_KEY_PREFIX = "auth:session:";

    private static final DefaultRedisScript<Long> PEXPIRETIME_SCRIPT =
            new DefaultRedisScript<>("return redis.call('PEXPIRETIME', KEYS[1])", Long.class);

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4.2"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;

    private static StringRedisTemplate redisTemplate;

    private static Clock clock;

    private static RedisSessionManager sessionManager;

    @BeforeAll
    static void setUp() {
        connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        clock = Clock.systemUTC();
        sessionManager = new RedisSessionManager(redisTemplate, clock);
    }

    @AfterAll
    static void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void flushDatabase() {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @Test
    void createsSessionWithAccountValueAndTokenExpiry() {
        String jwtId = UUID.randomUUID().toString();
        IssuedToken issued = token(jwtId, Duration.ofSeconds(30));

        sessionManager.create(issued, 42L);

        assertThat(redisTemplate.opsForValue().get(key(jwtId))).isEqualTo("42");
        assertThat(pexpiretime(jwtId)).isEqualTo(issued.getExpiresAt().toEpochMilli());
        Long ttl = redisTemplate.getExpire(key(jwtId), TimeUnit.MILLISECONDS);
        assertThat(ttl).isNotNull().isPositive();
    }

    @Test
    void readingSessionDoesNotRenewTtl() {
        String jwtId = UUID.randomUUID().toString();
        IssuedToken issued = token(jwtId, Duration.ofSeconds(30));
        sessionManager.create(issued, 42L);
        Long before = pexpiretime(jwtId);

        assertThat(sessionManager.isActive(jwtId, 42L)).isTrue();

        Long after = pexpiretime(jwtId);
        assertThat(before).isEqualTo(issued.getExpiresAt().toEpochMilli());
        assertThat(after).isEqualTo(before);
    }

    @Test
    void rejectsExpiredTokenCreation() {
        String jwtId = UUID.randomUUID().toString();
        Instant now = Instant.now(clock);
        IssuedToken expired = new IssuedToken("token", jwtId, now.minusSeconds(7200),
                now.minusSeconds(1));

        assertThatThrownBy(() -> sessionManager.create(expired, 42L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(redisTemplate.hasKey(key(jwtId))).isFalse();
    }

    @Test
    void rejectsTokenExpiringExactlyNow() {
        String jwtId = UUID.randomUUID().toString();
        Clock fixedClock = Clock.fixed(Instant.now(clock), ZoneOffset.UTC);
        RedisSessionManager fixedManager = new RedisSessionManager(redisTemplate, fixedClock);
        IssuedToken expiringNow = new IssuedToken("token", jwtId,
                fixedClock.instant().minusSeconds(7200), fixedClock.instant());

        assertThatThrownBy(() -> fixedManager.create(expiringNow, 42L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(redisTemplate.hasKey(key(jwtId))).isFalse();
    }

    @Test
    void returnsFalseForMissingSessionOrDifferentAccount() {
        String jwtId = UUID.randomUUID().toString();
        sessionManager.create(token(jwtId, Duration.ofMinutes(5)), 42L);

        assertThat(sessionManager.isActive(jwtId, 43L)).isFalse();
        assertThat(sessionManager.isActive(UUID.randomUUID().toString(), 42L)).isFalse();
    }

    @Test
    void deletesOnlyCurrentSessionAndKeepsOtherSessionOfSameAccount() {
        String firstJwtId = UUID.randomUUID().toString();
        String secondJwtId = UUID.randomUUID().toString();
        sessionManager.create(token(firstJwtId, Duration.ofMinutes(5)), 42L);
        sessionManager.create(token(secondJwtId, Duration.ofMinutes(5)), 42L);

        sessionManager.delete(firstJwtId);

        assertThat(sessionManager.isActive(firstJwtId, 42L)).isFalse();
        assertThat(redisTemplate.hasKey(key(firstJwtId))).isFalse();
        assertThat(sessionManager.isActive(secondJwtId, 42L)).isTrue();
        assertThat(redisTemplate.opsForValue().get(key(secondJwtId))).isEqualTo("42");
    }

    @Test
    void sessionExpiresAtTokenExpiry() throws InterruptedException {
        String jwtId = UUID.randomUUID().toString();
        sessionManager.create(token(jwtId, Duration.ofMillis(1500)), 42L);

        assertThat(sessionManager.isActive(jwtId, 42L)).isTrue();
        Thread.sleep(1800);

        assertThat(sessionManager.isActive(jwtId, 42L)).isFalse();
    }

    @Test
    void mapsRedisConnectionFailureToServiceUnavailable() {
        LettuceConnectionFactory failingFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", 1), shortTimeoutConfiguration());
        failingFactory.afterPropertiesSet();
        try {
            StringRedisTemplate failingTemplate = new StringRedisTemplate(failingFactory);
            failingTemplate.afterPropertiesSet();
            RedisSessionManager failingManager = new RedisSessionManager(
                    failingTemplate, clock);
            String jwtId = UUID.randomUUID().toString();
            IssuedToken issued = token(jwtId, Duration.ofMinutes(5));

            assertUnavailable(() -> failingManager.create(issued, 42L), issued.getToken());
            assertUnavailable(() -> failingManager.isActive(jwtId, 42L), issued.getToken());
            assertUnavailable(() -> failingManager.delete(jwtId), issued.getToken());
        } finally {
            failingFactory.destroy();
        }
    }

    @Test
    void preservesOriginalFailureAsCauseWithoutLogging() {
        StringRedisTemplate failingTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(failingTemplate.opsForValue()).thenReturn(operations);
        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("PWD_MARKER_9f3a");
        when(operations.get(SESSION_KEY_PREFIX + "jwt-cause")).thenThrow(failure);
        RedisSessionManager failingManager = new RedisSessionManager(failingTemplate, clock);
        Logger logger = (Logger) LoggerFactory.getLogger(RedisSessionManager.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> failingManager.isActive("jwt-cause", 42L))
                    .isInstanceOfSatisfying(BusinessException.class, exception -> {
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                        assertThat(exception.getCause()).isSameAs(failure);
                    });
            assertThat(appender.list).isEmpty();
        } finally {
            logger.detachAppender(appender);
        }
    }

    private static void assertUnavailable(ThrowingCallable action, String token) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE))
                .hasMessage(ErrorCode.SERVICE_UNAVAILABLE.getMessage())
                .hasCauseInstanceOf(DataAccessException.class)
                .hasMessageNotContaining(token)
                .hasMessageNotContaining("127.0.0.1");
    }

    private static IssuedToken token(String jwtId, Duration ttl) {
        Instant now = Instant.now(clock);
        return new IssuedToken("token-" + jwtId, jwtId, now, now.plus(ttl));
    }

    private static Long pexpiretime(String jwtId) {
        return redisTemplate.execute(PEXPIRETIME_SCRIPT, List.of(key(jwtId)));
    }

    private static String key(String jwtId) {
        return SESSION_KEY_PREFIX + jwtId;
    }

    private static LettuceClientConfiguration shortTimeoutConfiguration() {
        return LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(300))
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder()
                                .connectTimeout(Duration.ofMillis(300))
                                .build())
                        .build())
                .build();
    }
}
