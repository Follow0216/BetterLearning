package com.betterlearning.server.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import com.betterlearning.server.config.AppAuthProperties;
import com.betterlearning.server.config.AppJwtProperties;
import com.betterlearning.server.config.JwtConfiguration;
import com.betterlearning.server.dao.UserDao;
import com.betterlearning.server.support.IntegrationContainers;
import com.betterlearning.server.support.MybatisTestContext;
import com.betterlearning.server.exception.ApiErrorResponseFactory;
import com.betterlearning.server.exception.GlobalExceptionHandler;
import com.betterlearning.server.manager.RateLimitManager;
import com.betterlearning.server.manager.SessionManager;
import com.betterlearning.server.security.JwtTokenService;
import com.betterlearning.server.security.TraceIdFilter;
import com.betterlearning.server.service.impl.SessionServiceImpl;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;


import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@Tag("integration")
class SessionLoginIntegrationTests {

    private static final String PASSWORD = " Passw0rd! ";

    private static final String SECRET =
            "0123456789abcdef0123456789abcdef";

    private static final int LOGIN_IP_LIMIT = 2;

    @Container
    static final PostgreSQLContainer POSTGRES = IntegrationContainers.postgres();

    @Container
    static final GenericContainer<?> REDIS = IntegrationContainers.redis();

    private static JdbcTemplate jdbcTemplate;

    private static UserDao userDao;

    private static MybatisTestContext mybatisContext;

    private static PasswordEncoder passwordEncoder;

    private static JwtDecoder jwtDecoder;

    private static JwtTokenService jwtTokenService;

    private static LettuceConnectionFactory redisConnectionFactory;

    private static StringRedisTemplate redisTemplate;

    private static RateLimitManager rateLimitManager;

    private static JsonMapper jsonMapper;

    private static LocalValidatorFactoryBean validator;

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
        userDao = mybatisContext.userDao();
        passwordEncoder = new BCryptPasswordEncoder();
        Clock clock = Clock.systemUTC();
        AppJwtProperties jwtProperties = jwtProperties();
        JwtConfiguration jwtConfiguration = new JwtConfiguration();
        jwtDecoder = jwtConfiguration.jwtDecoder(jwtProperties, clock);
        jwtTokenService = new JwtTokenService(
                jwtConfiguration.jwtEncoder(jwtProperties), jwtProperties, clock);
        redisConnectionFactory = new LettuceConnectionFactory(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        redisConnectionFactory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(redisConnectionFactory);
        redisTemplate.afterPropertiesSet();
        AppAuthProperties authProperties = new AppAuthProperties();
        authProperties.getLogin().setIpLimit(LOGIN_IP_LIMIT);
        authProperties.getLogin().setUsernameLimit(10);
        authProperties.getLogin().setWindow(Duration.ofHours(1));
        authProperties.getRegistration().setIpLimit(3);
        authProperties.getRegistration().setWindow(Duration.ofHours(1));
        rateLimitManager = new RateLimitManager(redisTemplate, authProperties);
        jsonMapper = JsonMapper.builder()
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES)
                .build();
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = buildMockMvc(new SessionManager(redisTemplate, clock));
    }

    @AfterAll
    static void tearDown() {
        if (mybatisContext != null) {
            mybatisContext.close();
        }
        if (redisConnectionFactory != null) {
            redisConnectionFactory.destroy();
        }
    }

    @BeforeEach
    void cleanState() {
        jdbcTemplate.update("TRUNCATE TABLE question, question_collection, app_user RESTART IDENTITY");
        redisTemplate.execute((RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
    }

    @Test
    void logsInWithNormalizedUsernameAndCreatesSession() throws Exception {
        long accountId = insertUser("alice_01");

        MvcResult result = login("{\"username\":\"  Alice_01  \",\"password\":\""
                + PASSWORD + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isString())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(7200))
                .andExpect(jsonPath("$.user.accountId").value(Long.toString(accountId)))
                .andExpect(jsonPath("$.user.username").value("alice_01"))
                .andExpect(jsonPath("$.user.role").value("USER"))
                .andExpect(content().string(not(containsString("password"))))
                .andExpect(content().string(not(containsString("refreshToken"))))
                .andExpect(content().string(not(containsString("tokenVersion"))))
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Object> body = jsonMapper.readValue(
                result.getResponse().getContentAsString(), Map.class);
        assertThat(body.keySet()).containsExactlyInAnyOrder(
                "accessToken", "tokenType", "expiresIn", "user");
        assertThat(body.get("expiresIn")).isInstanceOf(Number.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> bodyUser = (Map<String, Object>) body.get("user");
        assertThat(bodyUser.get("accountId")).isInstanceOf(String.class)
                .isEqualTo(Long.toString(accountId));

        String token = (String) body.get("accessToken");
        Jwt jwt = jwtDecoder.decode(token);
        assertThat(jwt.getSubject()).isEqualTo(Long.toString(accountId));
        assertThat(((Number) jwt.getClaims().get("token_version")).intValue()).isZero();
        assertThat(Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).getSeconds())
                .isEqualTo(7200);

        String key = "auth:session:" + jwt.getId();
        assertThat(redisTemplate.opsForValue().get(key)).isEqualTo(Long.toString(accountId));
        assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS))
                .isBetween(7100L, 7200L);
    }

    @Test
    void rejectsUnknownUsernameAndWrongPasswordWithSameStatusAndMessage() throws Exception {
        insertUser("alice_01");

        MvcResult unknown = login("{\"username\":\"nobody\",\"password\":\"Passw0rd!\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(not(containsString("Passw0rd"))))
                .andReturn();
        MvcResult wrongPassword = login(
                "{\"username\":\"alice_01\",\"password\":\"WrongPass1!\"}")
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(not(containsString("WrongPass1"))))
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Object> unknownBody = jsonMapper.readValue(
                unknown.getResponse().getContentAsString(), Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> wrongBody = jsonMapper.readValue(
                wrongPassword.getResponse().getContentAsString(), Map.class);
        assertThat(unknownBody.get("code")).isEqualTo("A0200");
        assertThat(unknownBody.get("message")).isEqualTo("用户名或密码错误");
        assertThat(wrongBody.get("code")).isEqualTo(unknownBody.get("code"));
        assertThat(wrongBody.get("message")).isEqualTo(unknownBody.get("message"));
        assertThat(unknownBody).doesNotContainKey("accessToken");
        assertThat(wrongBody).doesNotContainKey("accessToken");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"role\":\"ADMIN\"",
            "\"accountId\":\"1\"",
            "\"phone\":\"+8613800138000\"",
            "\"email\":\"alice@example.com\""})
    void rejectsExtraFields(String extraField) throws Exception {
        login("{\"username\":\"alice_01\",\"password\":\"" + PASSWORD + "\","
                + extraField + "}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("A0427"));
    }

    @Test
    void issuesDistinctSessionsOnTwoLogins() throws Exception {
        insertUser("alice_01");

        String firstToken = loginToken();
        String secondToken = loginToken();

        assertThat(firstToken).isNotEqualTo(secondToken);
        String firstJwtId = jwtDecoder.decode(firstToken).getId();
        String secondJwtId = jwtDecoder.decode(secondToken).getId();
        assertThat(firstJwtId).isNotEqualTo(secondJwtId);
        assertThat(redisTemplate.hasKey("auth:session:" + firstJwtId)).isTrue();
        assertThat(redisTemplate.hasKey("auth:session:" + secondJwtId)).isTrue();
    }

    @Test
    void rateLimitsLoginAttemptsByRemoteAddress() throws Exception {
        insertUser("alice_01");

        login(loginJson(PASSWORD)).andExpect(status().isCreated());
        login(loginJson("WrongPass1!")).andExpect(status().isUnauthorized());
        login(loginJson(PASSWORD))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("A0501"));
    }

    @Test
    void returnsServiceUnavailableWithoutTokenWhenSessionStoreFails() throws Exception {
        insertUser("alice_01");
        LettuceConnectionFactory failingFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", 1),
                shortTimeoutConfiguration());
        failingFactory.afterPropertiesSet();
        try {
            StringRedisTemplate failingTemplate = new StringRedisTemplate(failingFactory);
            failingTemplate.afterPropertiesSet();
            MockMvc failingMvc = buildMockMvc(
                    new SessionManager(failingTemplate, Clock.systemUTC()));

            MvcResult result = failingMvc.perform(post("/api/v1/sessions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(loginJson(PASSWORD)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("C0001"))
                    .andReturn();

            assertThat(result.getResponse().getContentAsString())
                    .doesNotContain("accessToken")
                    .doesNotContain("Bearer");
        } finally {
            failingFactory.destroy();
        }
    }

    private static MockMvc buildMockMvc(SessionManager sessionManager) {
        SessionServiceImpl sessionService = new SessionServiceImpl(userDao, passwordEncoder,
                jwtTokenService, sessionManager, rateLimitManager);
        return MockMvcBuilders.standaloneSetup(new SessionController(sessionService))
                .setControllerAdvice(new GlobalExceptionHandler(new ApiErrorResponseFactory()))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(jsonMapper))
                .setValidator(validator)
                .addFilters(new TraceIdFilter())
                .build();
    }

    private static long insertUser(String username) {
        return userDao.insert(username, passwordEncoder.encode(PASSWORD));
    }

    private static String loginToken() throws Exception {
        MvcResult result = login(loginJson(PASSWORD))
                .andExpect(status().isCreated())
                .andReturn();
        @SuppressWarnings("unchecked")
        Map<String, Object> body = jsonMapper.readValue(
                result.getResponse().getContentAsString(), Map.class);
        return (String) body.get("accessToken");
    }

    private static String loginJson(String password) {
        return "{\"username\":\"alice_01\",\"password\":\"" + password + "\"}";
    }

    private static ResultActions login(String body) throws Exception {
        return mockMvc.perform(post("/api/v1/sessions")
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

    private static AppJwtProperties jwtProperties() {
        AppJwtProperties properties = new AppJwtProperties();
        properties.setSecretBase64(Base64.getEncoder().encodeToString(
                SECRET.getBytes(StandardCharsets.UTF_8)));
        properties.setIssuer("betterlearning-test");
        properties.setAudience("betterlearning-api");
        properties.setAccessTokenTtl(Duration.ofHours(2));
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
