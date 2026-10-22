package com.betterlearning.server.model.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.exc.IgnoredPropertyException;
import tools.jackson.databind.exc.UnrecognizedPropertyException;
import tools.jackson.databind.json.JsonMapper;

class UserRequestJacksonTests {

    private final JsonMapper jsonMapper = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_IGNORED_PROPERTIES)
            .build();

    @Test
    void bindsRegisterRequestWithKnownFields() {
        RegisterUserDTO request = jsonMapper.readValue(
                "{\"username\":\"Alice_01\",\"password\":\"password1\","
                + "\"confirmPassword\":\"password1\"}",
                RegisterUserDTO.class);

        assertThat(request.getUsername()).isEqualTo("Alice_01");
        assertThat(request.getPassword()).isEqualTo("password1");
        assertThat(request.getConfirmPassword()).isEqualTo("password1");
    }

    @Test
    void rejectsRoleInRegisterRequest() {
        assertUnknownField(
                "{\"username\":\"alice\",\"password\":\"password1\","
                + "\"confirmPassword\":\"password1\",\"role\":\"ADMIN\"}",
                RegisterUserDTO.class,
                "role");
    }

    @Test
    void rejectsAccountIdInRegisterRequest() {
        assertUnknownField(
                "{\"username\":\"alice\",\"password\":\"password1\","
                + "\"confirmPassword\":\"password1\",\"accountId\":\"1\"}",
                RegisterUserDTO.class,
                "accountId");
    }

    @Test
    void rejectsPhoneInRegisterRequest() {
        assertUnknownField(
                "{\"username\":\"alice\",\"password\":\"password1\","
                + "\"confirmPassword\":\"password1\",\"phone\":\"+8613800138000\"}",
                RegisterUserDTO.class,
                "phone");
    }

    @Test
    void rejectsEmailInRegisterRequest() {
        assertUnknownField(
                "{\"username\":\"alice\",\"password\":\"password1\","
                + "\"confirmPassword\":\"password1\",\"email\":\"a@example.com\"}",
                RegisterUserDTO.class,
                "email");
    }

    @Test
    void bindsSessionRequest() {
        CreateSessionDTO request = jsonMapper.readValue(
                "{\"username\":\"alice\",\"password\":\"password1\"}",
                CreateSessionDTO.class);

        assertThat(request.getUsername()).isEqualTo("alice");
        assertThat(request.getPassword()).isEqualTo("password1");
    }

    @Test
    void rejectsUnknownFieldInSessionRequest() {
        assertUnknownField(
                "{\"username\":\"alice\",\"password\":\"password1\",\"accountId\":\"1\"}",
                CreateSessionDTO.class,
                "accountId");
    }

    @Test
    void tracksMissingProfileFields() {
        UpdateProfileDTO request = jsonMapper.readValue("{}", UpdateProfileDTO.class);

        assertThat(request.isPhoneProvided()).isFalse();
        assertThat(request.isEmailProvided()).isFalse();
        assertThat(request.getPhone()).isNull();
        assertThat(request.getEmail()).isNull();
    }

    @Test
    void tracksExplicitNullProfileFieldsAsProvided() {
        UpdateProfileDTO request = jsonMapper.readValue(
                "{\"phone\":null,\"email\":null}",
                UpdateProfileDTO.class);

        assertThat(request.isPhoneProvided()).isTrue();
        assertThat(request.isEmailProvided()).isTrue();
        assertThat(request.getPhone()).isNull();
        assertThat(request.getEmail()).isNull();
    }

    @Test
    void tracksOnlyTheProvidedProfileField() {
        UpdateProfileDTO request = jsonMapper.readValue(
                "{\"phone\":\"+8613800138000\"}",
                UpdateProfileDTO.class);

        assertThat(request.isPhoneProvided()).isTrue();
        assertThat(request.isEmailProvided()).isFalse();
        assertThat(request.getPhone()).isEqualTo("+8613800138000");
    }

    @Test
    void rejectsPhoneProvidedFlag() {
        assertThatThrownBy(() -> jsonMapper.readValue(
                "{\"phoneProvided\":true}",
                UpdateProfileDTO.class))
                .isInstanceOf(IgnoredPropertyException.class)
                .hasMessageContaining("phoneProvided");
    }

    @Test
    void rejectsEmailProvidedFlag() {
        assertThatThrownBy(() -> jsonMapper.readValue(
                "{\"emailProvided\":true}",
                UpdateProfileDTO.class))
                .isInstanceOf(IgnoredPropertyException.class)
                .hasMessageContaining("emailProvided");
    }

    @Test
    void rejectsUnknownFieldInProfileRequest() {
        assertUnknownField(
                "{\"username\":\"alice\"}",
                UpdateProfileDTO.class,
                "username");
    }

    @Test
    void rejectsRoleInProfileRequest() {
        assertUnknownField(
                "{\"role\":\"ADMIN\"}",
                UpdateProfileDTO.class,
                "role");
    }

    @Test
    void rejectsAccountIdInProfileRequest() {
        assertUnknownField(
                "{\"accountId\":\"1\"}",
                UpdateProfileDTO.class,
                "accountId");
    }

    @Test
    void bindsChangePasswordRequest() {
        ChangePasswordDTO request = jsonMapper.readValue(
                "{\"oldPassword\":\"password1\",\"newPassword\":\"password2\","
                + "\"confirmPassword\":\"password2\"}",
                ChangePasswordDTO.class);

        assertThat(request.getOldPassword()).isEqualTo("password1");
        assertThat(request.getNewPassword()).isEqualTo("password2");
        assertThat(request.getConfirmPassword()).isEqualTo("password2");
    }

    @Test
    void rejectsUnknownFieldInChangePasswordRequest() {
        assertUnknownField(
                "{\"oldPassword\":\"password1\",\"newPassword\":\"password2\","
                + "\"confirmPassword\":\"password2\",\"username\":\"alice\"}",
                ChangePasswordDTO.class,
                "username");
    }

    private void assertUnknownField(String json, Class<?> type, String fieldName) {
        assertThatThrownBy(() -> jsonMapper.readValue(json, type))
                .isInstanceOf(UnrecognizedPropertyException.class)
                .hasMessageContaining(fieldName);
    }
}
