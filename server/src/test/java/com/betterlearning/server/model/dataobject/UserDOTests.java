package com.betterlearning.server.model.dataobject;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

class UserDOTests {

    @Test
    void toStringIsOverriddenAndHidesSensitiveFields() {
        UserDO user = new UserDO();
        user.setAccountId(42L);
        user.setUsername("USERNAME_MARKER_7f3a");
        user.setPasswordHash("HASH_MARKER_91bc");
        user.setPhone("PHONE_MARKER_55de");
        user.setEmail("EMAIL_MARKER_2a90");
        user.setRole("USER");
        user.setTokenVersion(7);
        user.setCreatedAt(OffsetDateTime.parse("2026-10-22T10:00:00Z"));
        user.setUpdatedAt(OffsetDateTime.parse("2026-10-22T11:00:00Z"));

        assertThat(user.toString())
                .startsWith("UserDO{")
                .contains("accountId=42")
                .contains("role='USER'")
                .contains("tokenVersion=7")
                .contains("[PROTECTED]")
                .doesNotContain("USERNAME_MARKER_7f3a")
                .doesNotContain("HASH_MARKER_91bc")
                .doesNotContain("PHONE_MARKER_55de")
                .doesNotContain("EMAIL_MARKER_2a90");
    }

    @Test
    void toStringKeepsMissingWrapperValuesExplicit() {
        UserDO user = new UserDO();

        assertThat(user.getAccountId()).isNull();
        assertThat(user.getTokenVersion()).isNull();
        assertThat(user.toString())
                .startsWith("UserDO{")
                .contains("accountId=null")
                .contains("tokenVersion=null");
    }
}
