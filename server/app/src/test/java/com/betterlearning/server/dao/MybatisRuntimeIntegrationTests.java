package com.betterlearning.server.dao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Base64;

import javax.sql.DataSource;

import com.betterlearning.server.model.dataobject.UserDO;
import com.betterlearning.server.model.dto.RegisterUserDTO;
import com.betterlearning.server.service.UserService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * MyBatis-Plus 运行时专项验收：Spring 事务回滚与本地缓存作用域。
 *
 * <p>使用完整 Spring Boot 上下文、真实 UserService 事务代理与自动装配的 Mapper；
 * 数据库准备和独立验证使用 JdbcTemplate，外部提交使用独立 JDBC 连接。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Testcontainers
@SpringBootTest
@Tag("integration")
class MybatisRuntimeIntegrationTests {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18.3")
            .asCompatibleSubstituteFor("postgres");

    private static final String PASSWORD = "Passw0rd!";

    private static final String READ_BACK_MARKER = "m4a-read-back-failure";

    private static final String SECRET = Base64.getEncoder().encodeToString(
            "betterlearning-mybatis-integration-secret".getBytes(StandardCharsets.UTF_8));

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("betterlearning")
            .withUsername("test")
            .withPassword("test");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4.2"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("app.jwt.secret-base64", () -> SECRET);
        registry.add("app.jwt.issuer", () -> "betterlearning-test");
        registry.add("app.jwt.audience", () -> "betterlearning-client");
        registry.add("app.auth.registration.ip-limit", () -> "1000");
        registry.add("app.auth.login.ip-limit", () -> "1000");
    }

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @MockitoSpyBean
    private UserDao userDao;

    @BeforeEach
    void cleanState() {
        jdbcTemplate.update("TRUNCATE TABLE question_answer, question, question_collection, app_user RESTART IDENTITY");
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @AfterEach
    void restoreDao() {
        reset(userDao);
    }

    @Test
    void rollsBackRegistrationWhenReadBackFails() {
        assertThat(AopUtils.isAopProxy(userService)).isTrue();

        doAnswer(invocation -> {
            Long requestedAccountId = invocation.getArgument(0);
            String username = jdbcTemplate.queryForObject(
                    "SELECT username FROM app_user WHERE account_id = ?", String.class,
                    requestedAccountId);
            assertThat(username).isEqualTo("rollback_user");
            throw new IllegalStateException(READ_BACK_MARKER);
        }).when(userDao).findByAccountId(anyLong());

        RegisterUserDTO request = new RegisterUserDTO();
        request.setUsername("rollback_user");
        request.setPassword(PASSWORD);
        request.setConfirmPassword(PASSWORD);

        assertThatThrownBy(() -> userService.register(request, "127.0.0.1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(READ_BACK_MARKER);

        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM app_user WHERE username = ?", Integer.class,
                "rollback_user")).isZero();
    }

    @Test
    void seesCommittedExternalUpdateWithinSameTransaction() {
        Long accountId = jdbcTemplate.queryForObject(
                "INSERT INTO app_user (username, password_hash) VALUES (?, ?) "
                + "RETURNING account_id", Long.class, "cache_user", "hash");

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        template.executeWithoutResult(status -> {
            try {
                Connection transactionConnection = DataSourceUtils.getConnection(dataSource);
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
                assertThat(transactionConnection.getTransactionIsolation())
                        .isEqualTo(Connection.TRANSACTION_READ_COMMITTED);

                UserDO before = userDao.findByAccountId(accountId).orElseThrow();
                assertThat(before.getRole()).isEqualTo("USER");
                assertThat(before.getTokenVersion()).isZero();

                try (Connection external = dataSource.getConnection()) {
                    assertThat(external).isNotSameAs(transactionConnection);
                    external.setAutoCommit(false);
                    external.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
                    try (PreparedStatement statement = external.prepareStatement(
                            "UPDATE app_user SET role = 'ADMIN', "
                            + "token_version = token_version + 1 WHERE account_id = ?")) {
                        statement.setLong(1, accountId);
                        assertThat(statement.executeUpdate()).isEqualTo(1);
                    }
                    external.commit();
                }

                UserDO after = userDao.findByAccountId(accountId).orElseThrow();
                assertThat(after.getRole()).isEqualTo("ADMIN");
                assertThat(after.getTokenVersion()).isEqualTo(1);
            } catch (SQLException exception) {
                throw new IllegalStateException(exception);
            }
        });
    }

    @Test
    void readsInsertedUserByUsernameInSameTransactionAndRollsBack() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> template.executeWithoutResult(status -> {
            long accountId = userDao.insert("tx_read_user", "hash");

            UserDO created = userDao.findByUsername("tx_read_user").orElseThrow();
            assertThat(created.getAccountId()).isEqualTo(accountId);
            assertThat(created.getUsername()).isEqualTo("tx_read_user");

            throw new IllegalStateException("forced rollback");
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("forced rollback");

        assertThat(userDao.findByUsername("tx_read_user")).isEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM app_user WHERE username = ?", Integer.class,
                "tx_read_user")).isZero();
    }
}
