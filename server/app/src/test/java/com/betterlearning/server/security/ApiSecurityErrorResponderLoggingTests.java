package com.betterlearning.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.betterlearning.server.exception.ApiErrorResponseFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import tools.jackson.databind.json.JsonMapper;

class ApiSecurityErrorResponderLoggingTests {

    private static final String TRACE_ID = "trace-security-9f3a";

    private static final String PASSWORD_MARKER = "PWD_MARKER_9f3a";

    private static final String TOKEN_MARKER = "TOKEN_MARKER_9f3a";

    private static final String SQL_MARKER = "SQL_MARKER_9f3a";

    private final ApiSecurityErrorResponder responder = new ApiSecurityErrorResponder(
            new ApiErrorResponseFactory(), JsonMapper.builder().build());

    private Logger logger;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(ApiSecurityErrorResponder.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    void logsDependencyFailureOnceWithoutSensitiveValues() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        responder.serviceUnavailable(request(), response, sensitiveFailure());

        assertThat(appender.list).hasSize(1);
        ILoggingEvent event = appender.list.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(event.getFormattedMessage())
                .contains("security_dependency_unavailable", TRACE_ID)
                .contains(DataAccessResourceFailureException.class.getName())
                .contains("suppressed[1]")
                .contains("at ")
                .doesNotContain(PASSWORD_MARKER, TOKEN_MARKER, SQL_MARKER, "jdbc:postgresql", "13800138000", "alice@example.com");

        assertThat(response.getStatus()).isEqualTo(503);

        String body = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).contains("\"code\":\"C0001\"")
                .contains("\"traceId\":\"" + TRACE_ID + "\"")
                .doesNotContain(PASSWORD_MARKER, TOKEN_MARKER, SQL_MARKER);
    }

    @Test
    void doesNotLogUnauthorizedResponse() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        responder.unauthorized(request(), response, true);

        assertThat(appender.list).isEmpty();
        assertThat(response.getStatus()).isEqualTo(401);
    }

    private static DataAccessResourceFailureException sensitiveFailure() {
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException(
                PASSWORD_MARKER + " jdbc:postgresql://secret-host/db " + TOKEN_MARKER
                + " 13800138000 alice@example.com");
        failure.addSuppressed(new IllegalArgumentException(
                SQL_MARKER + " SELECT * FROM app_user WHERE phone = '13800138000'"));
        return failure;
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TraceIdContext.REQUEST_ATTRIBUTE, TRACE_ID);
        return request;
    }
}
