package com.betterlearning.server.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.betterlearning.server.dao.UserDao;

import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.enums.UserUniqueField;
import com.betterlearning.server.manager.RateLimitManager;
import com.betterlearning.server.mapper.UserMapper;
import com.betterlearning.server.model.dto.RegisterUserDTO;
import com.betterlearning.server.model.dto.UpdateProfileDTO;
import com.betterlearning.server.model.vo.ApiErrorVO;
import com.betterlearning.server.security.TraceIdContext;
import com.betterlearning.server.service.UserService;
import com.betterlearning.server.service.impl.UserServiceImpl;
import com.betterlearning.server.support.MybatisTestContext;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@Tag("integration")
class UniqueConstraintMappingIntegrationTests {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("pgvector/pgvector@sha256:42e7f6b4e1eceb02ff14e3e6bc6108bbe259abbe83879dc1845d0da1ddeb555d")
            .asCompatibleSubstituteFor("postgres");

    private static final String PASSWORD = "password1";

    private static final RateLimitManager NO_LIMIT_MANAGER = mock(RateLimitManager.class);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("betterlearning")
            .withUsername("test")
            .withPassword("test");

    private static DataSource dataSource;

    private static JdbcTemplate jdbcTemplate;

    private static UserDao userDao;

    private static MybatisTestContext mybatisContext;

    private static UserService userService;

    private static GlobalExceptionHandler handler;

    @BeforeAll
    static void setUpDatabase() {
        dataSource = dataSource();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        mybatisContext = MybatisTestContext.create(dataSource);
        userDao = mybatisContext.userDao();
        userService = new UserServiceImpl(userDao, new BCryptPasswordEncoder(),
                NO_LIMIT_MANAGER);
        handler = new GlobalExceptionHandler(new ApiErrorResponseFactory());
    }

    @AfterAll
    static void closeMybatisContext() {
        if (mybatisContext != null) {
            mybatisContext.close();
        }
    }

    @BeforeEach
    void cleanTable() {
        com.betterlearning.server.support.TestData.cleanDatabase(jdbcTemplate);
    }

    @Test
    void translatesUsernameConflictThroughService() {
        userDao.insert("user_a", "hash-a");

        assertThatThrownBy(() -> userService.register(registerRequest("user_a"), "127.0.0.1"))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.USERNAME_EXISTS);
                    assertThat(exception.getCause())
                            .isInstanceOfSatisfying(UserUniqueConflictException.class, conflict ->
                            assertThat(conflict.getField()).isEqualTo(UserUniqueField.USERNAME));
                });
    }

    @Test
    void translatesEmailConflictEvenWhenValueContainsAnotherConstraintName() {
        long aliceId = userDao.insert("user_a", "hash-a");
        userDao.updateContactInfo(aliceId, false, null, true,
                "uk_app_user_username@example.com");
        long bobId = userDao.insert("user_b", "hash-b");

        assertThatThrownBy(() -> userService.updateProfile(bobId,
                emailOnly("uk_app_user_username@example.com")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.EMAIL_ALREADY_BOUND));
    }

    @Test
    void translatesPhoneConflictThroughService() {
        long aliceId = userDao.insert("user_a", "hash-a");
        userDao.updateContactInfo(aliceId, true, "+8613800138000", false, null);
        long bobId = userDao.insert("user_b", "hash-b");

        assertThatThrownBy(() -> userService.updateProfile(bobId,
                phoneOnly("+8613800138000")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PHONE_ALREADY_BOUND));
    }

    @Test
    void keepsBothContactFieldsWhenEmailConflictsInSameRequest() {
        long aliceId = userDao.insert("user_a", "hash-a");
        userDao.updateContactInfo(aliceId, false, null, true, "a@example.com");
        long bobId = userDao.insert("user_b", "hash-b");
        userDao.updateContactInfo(bobId, true, "+8613900139000", true, "b@example.com");

        assertThatThrownBy(() -> userService.updateProfile(bobId,
                phoneAndEmail("+8613700137000", "a@example.com")))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.EMAIL_ALREADY_BOUND));

        var bob = userDao.findByAccountId(bobId).orElseThrow();
        assertThat(bob.getPhone()).isEqualTo("+8613900139000");
        assertThat(bob.getEmail()).isEqualTo("b@example.com");
    }

    @Test
    void rollsBackEarlierInsertInSameTransaction() {
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            userDao.insert("tx_user", "hash-a");
            userDao.insert("tx_user", "hash-b");
        })).isInstanceOf(UserUniqueConflictException.class);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE username = ?", Integer.class, "tx_user");
        assertThat(count).isZero();
    }

    @Test
    void allowsOnlyOneConcurrentRegistrationOfSameUsername() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> first = executor.submit(
                    () -> registerConcurrently("user_c", ready, start));
            Future<String> second = executor.submit(
                    () -> registerConcurrently("user_c", ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<String> results = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder("CREATED", "USERNAME_EXISTS");

            Integer count = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM app_user WHERE username = ?", Integer.class, "user_c");
            assertThat(count).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void leavesUnknownUniqueConstraintUntranslated() {
        jdbcTemplate.execute(
                "CREATE UNIQUE INDEX uk_app_user_password_hash ON app_user (password_hash)");
        try {
            userDao.insert("user_a", "same-hash");

            assertThatThrownBy(() -> userDao.insert("user_b", "same-hash"))
                    .isInstanceOf(DuplicateKeyException.class)
                    .isNotInstanceOf(UserUniqueConflictException.class);
        } finally {
            jdbcTemplate.execute("DROP INDEX uk_app_user_password_hash");
        }
    }

    @Test
    void leavesNonPostgresIntegrityViolationUntranslated() {
        UserMapper userMapper = mock(UserMapper.class);
        DataIntegrityViolationException exception =
                new DataIntegrityViolationException("null value in column username");
        when(userMapper.insertReturningAccountId(anyString(), anyString())).thenThrow(exception);

        UserDao mockBackedDao = new UserDao(userMapper);

        assertThatThrownBy(() -> mockBackedDao.insert("user_a", "hash"))
                .isSameAs(exception);
    }

    @Test
    void returnsInternalErrorForNonUniqueIntegrityViolation() {
        Throwable failure = catchThrowable(() -> jdbcTemplate.update(
                "INSERT INTO app_user (username, password_hash, role) VALUES (?, ?, ?)",
                "user_a", "hash", "SUPER"));
        assertThat(failure).isInstanceOf(DataIntegrityViolationException.class);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TraceIdContext.REQUEST_ATTRIBUTE, "trace-test");
        ResponseEntity<ApiErrorVO> response = handler.handleDataIntegrity(
                (DataIntegrityViolationException) failure, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("B0001");
        assertThat(response.getBody().getMessage()).isEqualTo("服务器内部错误");
    }

    @Test
    void keepsConstraintDiagnosticsFreeOfSqlAndSensitiveValues() {
        String sensitiveUsername = "sensitive_marker_user";
        String sensitivePassword = "SensitivePassw0rd!";
        userDao.insert(sensitiveUsername, "hash-marker");

        RegisterUserDTO request = new RegisterUserDTO();
        request.setUsername(sensitiveUsername);
        request.setPassword(sensitivePassword);
        request.setConfirmPassword(sensitivePassword);

        Throwable failure = catchThrowable(
                () -> userService.register(request, "127.0.0.1"));
        assertThat(failure).isInstanceOf(BusinessException.class);

        String diagnostics = SafeFailureDiagnostics.describe(failure);
        assertThat(diagnostics).doesNotContain(sensitiveUsername, sensitivePassword,
                "hash-marker", "INSERT", "SELECT", "app_user", "uk_app_user_username");

        MockHttpServletRequest httpRequest = new MockHttpServletRequest();
        httpRequest.setAttribute(TraceIdContext.REQUEST_ATTRIBUTE, "trace-leak");
        ResponseEntity<ApiErrorVO> response = handler.handleBusiness(
                (BusinessException) failure, httpRequest);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).doesNotContain(sensitiveUsername,
                sensitivePassword, "hash-marker", "INSERT", "SELECT", "app_user",
                "uk_app_user_username");
        assertThat(response.getBody().getFieldErrors()).isNullOrEmpty();
    }

    private static String registerConcurrently(String username, CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("并发测试未能同时开始");
        }
        try {
            userService.register(registerRequest(username), "127.0.0.1");
            return "CREATED";
        } catch (BusinessException exception) {
            return exception.getErrorCode().name();
        }
    }

    private static RegisterUserDTO registerRequest(String username) {
        RegisterUserDTO request = new RegisterUserDTO();
        request.setUsername(username);
        request.setPassword(PASSWORD);
        request.setConfirmPassword(PASSWORD);
        return request;
    }

    private static UpdateProfileDTO phoneOnly(String phone) {
        UpdateProfileDTO request = new UpdateProfileDTO();
        request.setPhone(phone);
        return request;
    }

    private static UpdateProfileDTO emailOnly(String email) {
        UpdateProfileDTO request = new UpdateProfileDTO();
        request.setEmail(email);
        return request;
    }

    private static UpdateProfileDTO phoneAndEmail(String phone, String email) {
        UpdateProfileDTO request = new UpdateProfileDTO();
        request.setPhone(phone);
        request.setEmail(email);
        return request;
    }

    private static DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(POSTGRES.getDriverClassName());
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }
}
