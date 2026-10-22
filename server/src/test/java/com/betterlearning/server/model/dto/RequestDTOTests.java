package com.betterlearning.server.model.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RequestDTOTests {

    @Test
    void registerUserToStringHidesUsernameAndPasswords() {
        RegisterUserDTO request = new RegisterUserDTO();
        request.setUsername("USERNAME_MARKER_0a1b");
        request.setPassword("PASSWORD_MARKER_2c3d");
        request.setConfirmPassword("CONFIRM_MARKER_4e5f");

        assertThat(request.toString())
                .startsWith("RegisterUserDTO{")
                .contains("[PROTECTED]")
                .doesNotContain("USERNAME_MARKER_0a1b")
                .doesNotContain("PASSWORD_MARKER_2c3d")
                .doesNotContain("CONFIRM_MARKER_4e5f");
    }

    @Test
    void createSessionToStringHidesUsernameAndPassword() {
        CreateSessionDTO request = new CreateSessionDTO();
        request.setUsername("USERNAME_MARKER_6a7b");
        request.setPassword("PASSWORD_MARKER_8c9d");

        assertThat(request.toString())
                .startsWith("CreateSessionDTO{")
                .contains("[PROTECTED]")
                .doesNotContain("USERNAME_MARKER_6a7b")
                .doesNotContain("PASSWORD_MARKER_8c9d");
    }

    @Test
    void updateProfileToStringHidesContactValuesButKeepsPresenceFlags() {
        UpdateProfileDTO request = new UpdateProfileDTO();
        request.setPhone("PHONE_MARKER_1e2f");
        request.setEmail("EMAIL_MARKER_3a4b");

        assertThat(request.toString())
                .startsWith("UpdateProfileDTO{")
                .contains("phoneProvided=true")
                .contains("emailProvided=true")
                .contains("[PROTECTED]")
                .doesNotContain("PHONE_MARKER_1e2f")
                .doesNotContain("EMAIL_MARKER_3a4b");
    }

    @Test
    void updateProfileDefaultsToBothFieldsAbsent() {
        UpdateProfileDTO request = new UpdateProfileDTO();

        assertThat(request.isPhoneProvided()).isFalse();
        assertThat(request.isEmailProvided()).isFalse();
        assertThat(request.toString())
                .contains("phoneProvided=false")
                .contains("emailProvided=false");
    }

    @Test
    void changePasswordToStringHidesAllPasswordValues() {
        ChangePasswordDTO request = new ChangePasswordDTO();
        request.setOldPassword("OLD_PASSWORD_MARKER_5c6d");
        request.setNewPassword("NEW_PASSWORD_MARKER_7e8f");
        request.setConfirmPassword("CONFIRM_MARKER_9a0b");

        assertThat(request.toString())
                .startsWith("ChangePasswordDTO{")
                .contains("[PROTECTED]")
                .doesNotContain("OLD_PASSWORD_MARKER_5c6d")
                .doesNotContain("NEW_PASSWORD_MARKER_7e8f")
                .doesNotContain("CONFIRM_MARKER_9a0b");
    }
}
