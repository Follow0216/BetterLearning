package com.betterlearning.server.model.vo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ModelVOTests {

    @Test
    void userVoToStringKeepsAccountIdButHidesContactAndUsername() {
        UserVO user = new UserVO("ACCOUNT_MARKER_1a2b", "USERNAME_MARKER_3c4d",
                "PHONE_MARKER_5e6f", "EMAIL_MARKER_7a8b", "USER",
                OffsetDateTime.parse("2026-10-22T10:00:00Z"),
                OffsetDateTime.parse("2026-10-22T11:00:00Z"));

        assertThat(user.toString())
                .startsWith("UserVO{")
                .contains("accountId='ACCOUNT_MARKER_1a2b'")
                .contains("role='USER'")
                .contains("[PROTECTED]")
                .doesNotContain("USERNAME_MARKER_3c4d")
                .doesNotContain("PHONE_MARKER_5e6f")
                .doesNotContain("EMAIL_MARKER_7a8b");
    }

    @Test
    void sessionVoToStringHidesTokenAndUser() {
        UserVO user = new UserVO("42", "USERNAME_MARKER_9c0d", null, null, "USER",
                OffsetDateTime.parse("2026-10-22T10:00:00Z"),
                OffsetDateTime.parse("2026-10-22T10:00:00Z"));
        SessionVO session = new SessionVO("ACCESS_TOKEN_MARKER_1e2f", 7200L, user);

        assertThat(session.toString())
                .startsWith("SessionVO{")
                .contains("expiresIn=7200")
                .contains("[PROTECTED]")
                .doesNotContain("ACCESS_TOKEN_MARKER_1e2f")
                .doesNotContain("USERNAME_MARKER_9c0d");
    }

    @Test
    void sessionVoRejectsMissingExpiresIn() {
        assertThatThrownBy(() -> new SessionVO("token", null, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("expiresIn");
    }

    @Test
    void apiErrorVoToStringKeepsCodeAndTraceIdButHidesDynamicText() {
        ApiErrorVO error = new ApiErrorVO("A0400", "MESSAGE_MARKER_3a4b",
                "TRACE_MARKER_5c6d", Map.of("oldPassword", "FIELD_ERROR_MARKER_7e8f"));

        assertThat(error.toString())
                .startsWith("ApiErrorVO{")
                .contains("code='A0400'")
                .contains("traceId='TRACE_MARKER_5c6d'")
                .contains("[PROTECTED]")
                .doesNotContain("MESSAGE_MARKER_3a4b")
                .doesNotContain("FIELD_ERROR_MARKER_7e8f");
    }
}
