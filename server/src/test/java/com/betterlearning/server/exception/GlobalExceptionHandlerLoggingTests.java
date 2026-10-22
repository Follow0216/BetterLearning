package com.betterlearning.server.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.model.vo.ApiErrorVO;
import com.betterlearning.server.security.TraceIdContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class GlobalExceptionHandlerLoggingTests {

    private static final String TRACE_ID = "trace-mvc-9f3a";

    private static final String PASSWORD_MARKER = "PWD_MARKER_9f3a";

    private static final String TOKEN_MARKER = "TOKEN_MARKER_9f3a";

    private static final String SQL_MARKER = "SQL_MARKER_9f3a";

    private final GlobalExceptionHandler handler =
            new GlobalExceptionHandler(new ApiErrorResponseFactory());

    private Logger logger;

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
    }

    @Test
    void logsUnavailableBusinessExceptionOnceWithoutSensitiveValues() {
        BusinessException exception = sensitiveChain("session store");

        ResponseEntity<ApiErrorVO> response = handler.handleBusiness(exception, request());

        assertThatSingleSafeEvent("business_unavailable");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("服务暂时不可用");
    }

    @Test
    void logsDatabaseDependencyFailureOnce() {
        DataAccessResourceFailureException exception = new DataAccessResourceFailureException(
                PASSWORD_MARKER + " jdbc:postgresql://secret-host/db", new SQLException(
                SQL_MARKER + " SELECT * FROM app_user WHERE phone = '13800138000'"));

        ResponseEntity<ApiErrorVO> response = handler.handleDependencyUnavailable(exception,
                request());

        assertThatSingleSafeEvent("dependency_unavailable");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void logsUnknownIntegrityViolationOnce() {
        DataIntegrityViolationException exception = new DataIntegrityViolationException(
                SQL_MARKER + " INSERT INTO app_user (email) VALUES ('alice@example.com')",
                new SQLException(TOKEN_MARKER));

        ResponseEntity<ApiErrorVO> response = handler.handleDataIntegrity(exception, request());

        assertThatSingleSafeEvent("data_integrity_violation");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessage()).isEqualTo("服务器内部错误");
    }

    @Test
    void logsUnhandledExceptionOnceWithStackTrace() {
        IllegalStateException exception = new IllegalStateException(TOKEN_MARKER,
                sensitiveChain("unhandled"));

        ResponseEntity<ApiErrorVO> response = handler.handleUnhandled(exception, request());

        assertThatSingleSafeEvent("unhandled_exception");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void doesNotLogRoutineBusinessConflict() {
        ResponseEntity<ApiErrorVO> response = handler.handleBusiness(
                new BusinessException(ErrorCode.USERNAME_EXISTS), request());

        assertThat(appender.list).isEmpty();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    private void assertThatSingleSafeEvent(String operation) {
        List<ILoggingEvent> events = appender.list;
        assertThat(events).hasSize(1);
        ILoggingEvent event = events.get(0);
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getThrowableProxy()).isNull();
        assertThat(event.getFormattedMessage()).contains(operation, TRACE_ID)
                .contains("exception:")
                .contains("cause:")
                .contains("at ")
                .contains("GlobalExceptionHandlerLoggingTests.java:");
        assertThat(event.getFormattedMessage()).doesNotContain(PASSWORD_MARKER, TOKEN_MARKER,
                SQL_MARKER, "jdbc:postgresql", "13800138000", "alice@example.com");
    }

    private static BusinessException sensitiveChain(String label) {
        DataAccessResourceFailureException root = new DataAccessResourceFailureException(
                PASSWORD_MARKER + " jdbc:postgresql://secret-host/db");
        IllegalStateException middle = new IllegalStateException(TOKEN_MARKER, root);
        middle.addSuppressed(new IllegalArgumentException(SQL_MARKER + " " + label));
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                ErrorCode.SERVICE_UNAVAILABLE.getMessage(), null, middle);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(TraceIdContext.REQUEST_ATTRIBUTE, TRACE_ID);
        return request;
    }
}
