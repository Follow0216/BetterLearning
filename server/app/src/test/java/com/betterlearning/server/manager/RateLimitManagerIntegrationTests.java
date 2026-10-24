package com.betterlearning.server.manager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.betterlearning.server.config.AppAuthProperties;
import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;

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
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@Tag("integration")
class RateLimitManagerIntegrationTests {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4.2"))
            .withExposedPorts(6379);

    private static LettuceConnectionFactory connectionFactory;

    private static StringRedisTemplate redisTemplate;

    private RateLimitManager rateLimitManager;

    @BeforeAll
    static void setUp() {
        connectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
    }

    @AfterAll
    static void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
    }

    @BeforeEach
    void setUpManager() {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        rateLimitManager = new RateLimitManager(redisTemplate,
                properties(3, Duration.ofHours(1), 2, 2, Duration.ofHours(1)));
    }

    @Test
    void allowsLimitRequestsThenRejectsNextRequest() {
        for (int i = 0; i < 3; i++) {
            rateLimitManager.checkRegistration("203.0.113.10");
        }

        assertRateLimited(() -> rateLimitManager.checkRegistration("203.0.113.10"));
    }

    @Test
    void recoversAfterWindowExpires() throws InterruptedException {
        RateLimitManager manager = new RateLimitManager(redisTemplate,
                properties(2, Duration.ofMillis(500), 2, 2, Duration.ofHours(1)));
        manager.checkRegistration("203.0.113.11");
        manager.checkRegistration("203.0.113.11");
        assertRateLimited(() -> manager.checkRegistration("203.0.113.11"));

        Thread.sleep(700);

        manager.checkRegistration("203.0.113.11");
        manager.checkRegistration("203.0.113.11");
    }

    @Test
    void doesNotExtendWindowOnRepeatedRequests() throws InterruptedException {
        RateLimitManager manager = new RateLimitManager(redisTemplate,
                properties(10, Duration.ofSeconds(3), 2, 2, Duration.ofHours(1)));
        manager.checkRegistration("203.0.113.12");
        Set<String> keys = redisTemplate.keys("rate:registration:ip:*");
        assertThat(keys).hasSize(1);
        String key = keys.iterator().next();
        Long before = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);

        Thread.sleep(100);
        manager.checkRegistration("203.0.113.12");
        manager.checkRegistration("203.0.113.12");

        Long after = redisTemplate.getExpire(key, TimeUnit.MILLISECONDS);
        assertThat(after).isNotNull().isLessThanOrEqualTo(before);
    }

    @Test
    void differentRegistrationIpsAreIndependent() {
        RateLimitManager manager = new RateLimitManager(redisTemplate,
                properties(1, Duration.ofHours(1), 2, 2, Duration.ofHours(1)));

        manager.checkRegistration("203.0.113.13");
        assertRateLimited(() -> manager.checkRegistration("203.0.113.13"));
        manager.checkRegistration("203.0.113.14");
    }

    @Test
    void differentLoginUsernamesAreIndependent() {
        RateLimitManager manager = new RateLimitManager(redisTemplate,
                properties(10, Duration.ofHours(1), 100, 1, Duration.ofHours(1)));

        manager.checkLogin("198.51.100.10", "alice");
        assertRateLimited(() -> manager.checkLogin("198.51.100.10", "alice"));
        manager.checkLogin("198.51.100.10", "bob");
    }

    @Test
    void loginRejectsWhenEitherDimensionIsExceeded() {
        RateLimitManager manager = new RateLimitManager(redisTemplate,
                properties(10, Duration.ofHours(1), 2, 1, Duration.ofHours(1)));

        manager.checkLogin("198.51.100.11", "alice");
        assertRateLimited(() -> manager.checkLogin("198.51.100.11", "alice"));
        assertRateLimited(() -> manager.checkLogin("198.51.100.12", "alice"));
        assertRateLimited(() -> manager.checkLogin("198.51.100.11", "bob"));
    }

    @Test
    void concurrentRequestsNeverExceedLimit() throws Exception {
        RateLimitManager manager = new RateLimitManager(redisTemplate,
                properties(5, Duration.ofSeconds(10), 2, 2, Duration.ofHours(1)));
        ExecutorService executor = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ErrorCode>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 10; i++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        manager.checkRegistration("10.0.0.1");
                        return null;
                    } catch (BusinessException exception) {
                        return exception.getErrorCode();
                    }
                }));
            }
            start.countDown();
            long allowed = 0;
            long rejected = 0;
            for (Future<ErrorCode> future : futures) {
                ErrorCode errorCode = future.get(5, TimeUnit.SECONDS);
                if (errorCode == null) {
                    allowed++;
                } else {
                    assertThat(errorCode).isEqualTo(ErrorCode.RATE_LIMITED);
                    rejected++;
                }
            }
            assertThat(allowed).isEqualTo(5);
            assertThat(rejected).isEqualTo(5);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void mapsRedisFailureToServiceUnavailable() {
        LettuceConnectionFactory failingFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", 1), shortTimeoutConfiguration());
        failingFactory.afterPropertiesSet();
        try {
            StringRedisTemplate failingTemplate = new StringRedisTemplate(failingFactory);
            failingTemplate.afterPropertiesSet();
            RateLimitManager failingManager = new RateLimitManager(
                    failingTemplate, properties(3, Duration.ofHours(1), 2, 2,
                    Duration.ofHours(1)));

            assertUnavailable(() -> failingManager.checkRegistration("203.0.113.15"),
                    "203.0.113.15");
            assertUnavailable(() -> failingManager.checkLogin("203.0.113.15", "alice"),
                    "alice");
        } finally {
            failingFactory.destroy();
        }
    }

    @Test
    void storesDigestedKeysWithoutRawIpOrUsername() {
        RateLimitManager manager = new RateLimitManager(redisTemplate,
                properties(10, Duration.ofHours(1), 100, 100, Duration.ofHours(1)));

        manager.checkLogin("192.0.2.10", "alice");

        Set<String> keys = redisTemplate.keys("rate:*");
        assertThat(keys).hasSize(2)
                .allMatch(key -> key.matches("rate:login:(ip|username):[0-9a-f]{64}"))
                .noneMatch(key -> key.contains("alice") || key.contains("192.0.2.10"));

        manager.checkLogin("192.0.2.11", "alice");

        assertThat(redisTemplate.keys("rate:*")).hasSize(3);
    }

    @Test
    void preservesOriginalFailureAsCauseWithoutLogging() {
        StringRedisTemplate failingTemplate = mock(StringRedisTemplate.class);
        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("PWD_MARKER_9f3a");
        when(failingTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
                .thenThrow(failure);
        RateLimitManager failingManager = new RateLimitManager(failingTemplate,
                properties(3, Duration.ofHours(1), 2, 2, Duration.ofHours(1)));
        Logger logger = (Logger) LoggerFactory.getLogger(RateLimitManager.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThatThrownBy(() -> failingManager.checkRegistration("203.0.113.10"))
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

    private static void assertRateLimited(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.RATE_LIMITED))
                .hasMessage(ErrorCode.RATE_LIMITED.getMessage());
    }

    private static void assertUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
            String sensitiveValue) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE))
                .hasMessage(ErrorCode.SERVICE_UNAVAILABLE.getMessage())
                .hasCauseInstanceOf(DataAccessException.class)
                .hasMessageNotContaining(sensitiveValue)
                .hasMessageNotContaining("127.0.0.1");
    }

    private static AppAuthProperties properties(int registrationIpLimit,
            Duration registrationWindow, int loginIpLimit, int loginUsernameLimit,
            Duration loginWindow) {
        AppAuthProperties properties = new AppAuthProperties();
        properties.getRegistration().setIpLimit(registrationIpLimit);
        properties.getRegistration().setWindow(registrationWindow);
        properties.getLogin().setIpLimit(loginIpLimit);
        properties.getLogin().setUsernameLimit(loginUsernameLimit);
        properties.getLogin().setWindow(loginWindow);
        return properties;
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
