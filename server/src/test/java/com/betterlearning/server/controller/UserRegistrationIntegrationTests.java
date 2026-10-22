package com.betterlearning.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Map;

import javax.sql.DataSource;

import com.betterlearning.server.config.AppAuthProperties;
import com.betterlearning.server.converter.UserConverter;
import com.betterlearning.server.support.MybatisTestContext;
import com.betterlearning.server.exception.ApiErrorResponseFactory;
import com.betterlearning.server.exception.GlobalExceptionHandler;
import com.betterlearning.server.manager.RedisRateLimitManager;
import com.betterlearning.server.security.TraceIdFilter;
import com.betterlearning.server.service.impl.UserServiceImpl;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@Tag("integration")
class UserRegistrationIntegrationTests {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18.3")
            .asCompatibleSubstituteFor("postgres");

    private static final String PASSWORD = " Passw0rd! ";

    private static final int REGISTRATION_IP_LIMIT = 3;

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("betterlearning")
            .withUsername("test")
            .withPassword("test");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4.2"))
            .withExposedPorts(6379);

    private static JdbcTemplate jdbcTemplate;

    private static MybatisTestContext mybatisContext;

    private static LettuceConnectionFactory redisConnectionFactory;

    private static StringRedisTemplate redisTemplate;

    private static PasswordEncoder passwordEncoder;

    private static JsonMapper jsonMapper;

    private static MockMvc mockMvc;

    @BeforeAll
    static void setUpApplication() {
        DataSource dataSource = dataSource();
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        mybatisContext = MybatisTestContext.create(dataSource);
        passwordEncoder = new BCryptPasswordEncoder();
        jsonMapper = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES)
                .build();
        redisConnectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        redisConnectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(redisConnectionFactory);
        redisTemplate.afterPropertiesSet();
        AppAuthProperties authProperties = new AppAuthProperties();
        authProperties.getRegistration().setIpLimit(REGISTRATION_IP_LIMIT);
        authProperties.getRegistration().setWindow(Duration.ofHours(1));
        authProperties.getLogin().setIpLimit(10);
        authProperties.getLogin().setUsernameLimit(10);
        authProperties.getLogin().setWindow(Duration.ofMinutes(1));
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        UserServiceImpl userService = new UserServiceImpl(
                mybatisContext.userDao(), passwordEncoder, new UserConverter(),
                new RedisRateLimitManager(redisTemplate, authProperties));
        mockMvc = MockMvcBuilders.standaloneSetup(new UserController(userService))
                .setControllerAdvice(
                        new GlobalExceptionHandler(new ApiErrorResponseFactory()))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(jsonMapper))
                .setValidator(validator)
                .addFilters(new TraceIdFilter())
                .build();
    }

    @AfterAll
    static void tearDownRedis() {
        if (mybatisContext != null) {
            mybatisContext.close();
        }
        if (redisConnectionFactory != null) {
            redisConnectionFactory.destroy();
        }
    }

    @BeforeEach
    void cleanTable() {
        jdbcTemplate.update("TRUNCATE TABLE app_user RESTART IDENTITY");
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @Test
    void registersUserAndReturnsPublicProfileOnly() throws Exception {
        MvcResult result = register("{\"username\":\"  Alice_01  \",\"password\":\""
                + PASSWORD + "\",\"confirmPassword\":\"" + PASSWORD + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountId").isString())
                .andExpect(jsonPath("$.username").value("alice_01"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.phone").value(nullValue()))
                .andExpect(jsonPath("$.email").value(nullValue()))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(content().string(not(containsString("password"))))
                .andExpect(content().string(not(containsString("tokenVersion"))))
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Object> body = jsonMapper.readValue(
                result.getResponse().getContentAsString(), Map.class);
        assertThat(body.keySet()).containsExactlyInAnyOrder(
                "accountId", "username", "phone", "email", "role", "createdAt", "updatedAt");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT username, role FROM app_user WHERE account_id = ?",
                Long.parseLong((String) body.get("accountId")));
        assertThat(row.get("username")).isEqualTo("alice_01");
        assertThat(row.get("role")).isEqualTo("USER");
    }

    @Test
    void storesBcryptHashOfUntrimmedPassword() throws Exception {
        MvcResult result = register("{\"username\":\"alice_01\",\"password\":\""
                + PASSWORD + "\",\"confirmPassword\":\"" + PASSWORD + "\"}")
                .andExpect(status().isCreated())
                .andReturn();
        long accountId = Long.parseLong(jsonMapper.readValue(
                result.getResponse().getContentAsString(), Map.class)
                .get("accountId").toString());

        String passwordHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM app_user WHERE account_id = ?",
                String.class, accountId);
        assertThat(passwordHash).startsWith("$2");
        assertThat(passwordHash).isNotEqualTo(PASSWORD);
        assertThat(passwordEncoder.matches(PASSWORD, passwordHash)).isTrue();
        assertThat(passwordEncoder.matches(PASSWORD.strip(), passwordHash)).isFalse();
    }

    @Test
    void rejectsDuplicateNormalizedUsernameWithConflict() throws Exception {
        register("{\"username\":\"Alice_01\",\"password\":\"" + PASSWORD
                + "\",\"confirmPassword\":\"" + PASSWORD + "\"}")
                .andExpect(status().isCreated());

        register("{\"username\":\"  alice_01  \",\"password\":\"" + PASSWORD
                + "\",\"confirmPassword\":\"" + PASSWORD + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("A0111"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());
    }

    @Test
    void rejectsMismatchedConfirmation() throws Exception {
        register("{\"username\":\"alice_01\",\"password\":\"" + PASSWORD
                + "\",\"confirmPassword\":\"different1\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A0400"))
                .andExpect(jsonPath("$.fieldErrors.confirmPassword").exists());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"role\":\"ADMIN\"",
            "\"accountId\":\"1\"",
            "\"phone\":\"+8613800138000\"",
            "\"email\":\"alice@example.com\""})
    void rejectsExtraProfileFields(String extraField) throws Exception {
        register("{\"username\":\"alice_01\",\"password\":\"" + PASSWORD
                + "\",\"confirmPassword\":\"" + PASSWORD + "\"," + extraField + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A0427"));
    }

    @Test
    void rejectsRegistrationAfterIpLimitAndDoesNotCreateUser() throws Exception {
        for (int i = 0; i < REGISTRATION_IP_LIMIT; i++) {
            register(userJson("alice_" + i)).andExpect(status().isCreated());
        }

        register(userJson("alice_extra"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("A0501"))
                .andExpect(jsonPath("$.traceId").isNotEmpty());

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user", Integer.class))
                .isEqualTo(REGISTRATION_IP_LIMIT);
    }

    @Test
    void doesNotTrustForwardedForHeaderForRateLimit() throws Exception {
        for (int i = 0; i < REGISTRATION_IP_LIMIT; i++) {
            mockMvc.perform(post("/api/v1/users")
                            .header("X-Forwarded-For", "198.51.100." + i)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(userJson("bob_" + i)))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(post("/api/v1/users")
                        .header("X-Forwarded-For", "198.51.100.99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(userJson("bob_extra")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("A0501"));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user", Integer.class))
                .isEqualTo(REGISTRATION_IP_LIMIT);
    }

    private static String userJson(String username) {
        return "{\"username\":\"" + username + "\",\"password\":\"" + PASSWORD
                + "\",\"confirmPassword\":\"" + PASSWORD + "\"}";
    }

    private static ResultActions register(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/users")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private static DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }
}
