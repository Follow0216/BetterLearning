package com.betterlearning.server.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.betterlearning.server.enums.ErrorCode;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class SafeFailureDiagnosticsTests {

    private static final String PASSWORD_MARKER = "PWD_MARKER_9f3a";

    private static final String TOKEN_MARKER = "TOKEN_MARKER_9f3a";

    private static final String SQL_MARKER = "SQL_MARKER_9f3a";

    @Test
    void describesTypesFramesAndSuppressedWithoutMessages() {
        DataAccessResourceFailureException root = new DataAccessResourceFailureException(
                PASSWORD_MARKER + " jdbc:postgresql://secret-host/db " + TOKEN_MARKER
                + " 13800138000 alice@example.com");
        IllegalStateException middle = new IllegalStateException(TOKEN_MARKER, root);
        middle.addSuppressed(new IllegalArgumentException(
                SQL_MARKER + " INSERT INTO app_user (phone) VALUES ('13800138000')"));
        BusinessException top = new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                ErrorCode.SERVICE_UNAVAILABLE.getMessage(), null, middle);

        String text = SafeFailureDiagnostics.describe(top);

        assertThat(text).contains(BusinessException.class.getName())
                .contains(IllegalStateException.class.getName())
                .contains(DataAccessResourceFailureException.class.getName())
                .contains("suppressed[1]")
                .contains("cause:")
                .contains("at ")
                .contains("SafeFailureDiagnosticsTests.java:");
        assertThat(text).doesNotContain(PASSWORD_MARKER, TOKEN_MARKER, SQL_MARKER,
                "jdbc:postgresql", "13800138000", "alice@example.com");
    }

    @Test
    void stopsAtCauseCycle() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);

        String text = SafeFailureDiagnostics.describe(first);

        assertThat(text).contains("[cycle]");
        assertThat(text.length()).isLessThan(10_000);
    }

    @Test
    void boundsDeepCauseChain() {
        Throwable failure = new RuntimeException("leaf");
        for (int index = 0; index < 20; index++) {
            failure = new RuntimeException("layer-" + index, failure);
        }

        String text = SafeFailureDiagnostics.describe(failure);

        assertThat(text).contains("[depth limit]");
        assertThat(countOccurrences(text, "java.lang.RuntimeException")).isEqualTo(9);
    }

    @Test
    void boundsStackFramesPerException() {
        StackTraceElement[] frames = new StackTraceElement[20];
        for (int index = 0; index < frames.length; index++) {
            frames[index] = new StackTraceElement("example.Generated", "call" + index,
                    "Generated.java", index + 1);
        }
        RuntimeException failure = new RuntimeException("frames");
        failure.setStackTrace(frames);

        String text = SafeFailureDiagnostics.describe(failure);

        assertThat(countOccurrences(text, "at example.Generated.call")).isEqualTo(12);
        assertThat(text).contains("... 8 more frames");
    }

    @Test
    void describesAbsentFailureAsNone() {
        assertThat(SafeFailureDiagnostics.describe(null)).isEqualTo("none");
    }

    private static int countOccurrences(String text, String token) {
        int count = 0;
        int index = text.indexOf(token);
        while (index >= 0) {
            count++;
            index = text.indexOf(token, index + token.length());
        }
        return count;
    }
}
