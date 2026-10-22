package com.betterlearning.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuthenticatedAccountTests {

    @Test
    void toStringIsOverriddenAndHidesUsernameAndJwtId() {
        AuthenticatedAccount account = new AuthenticatedAccount(42L, "USERNAME_MARKER_1a2b",
                "USER", 1000, "JWT_ID_MARKER_3c4d");

        assertThat(account.toString())
                .startsWith("AuthenticatedAccount{")
                .contains("accountId=42")
                .contains("role='USER'")
                .contains("tokenVersion=1000")
                .contains("[PROTECTED]")
                .doesNotContain("USERNAME_MARKER_1a2b")
                .doesNotContain("JWT_ID_MARKER_3c4d");
    }
}
