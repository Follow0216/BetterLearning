package com.betterlearning.server.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.stream.Collectors;

import com.betterlearning.server.model.dto.ChangePasswordDTO;
import com.betterlearning.server.model.dto.CreateSessionDTO;
import com.betterlearning.server.model.dto.RegisterUserDTO;
import com.betterlearning.server.model.dto.UpdateProfileDTO;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class UserRequestValidationTests {

    private static Validator validator;

    @BeforeAll
    static void createValidator() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void acceptsValidRegistration() {
        RegisterUserDTO request = registration("Alice_01", "password1", "password1");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void acceptsUsernameSurroundedByWhitespace() {
        RegisterUserDTO request = registration("  Alice_01  ", "password1", "password1");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void acceptsThreeCharacterChineseUsername() {
        RegisterUserDTO request = registration("张三丰", "password1", "password1");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsTwoCharacterChineseUsername() {
        RegisterUserDTO request = registration("张三", "password1", "password1");

        assertThat(violatedFields(validator.validate(request))).contains("username");
    }

    @Test
    void rejectsTooLongUsername() {
        RegisterUserDTO request = registration("a".repeat(33), "password1", "password1");

        assertThat(violatedFields(validator.validate(request))).contains("username");
    }

    @Test
    void rejectsUsernameWithUnsupportedCharacter() {
        RegisterUserDTO request = registration("alice-01", "password1", "password1");

        assertThat(violatedFields(validator.validate(request))).contains("username");
    }

    @Test
    void rejectsBlankUsername() {
        RegisterUserDTO request = registration("   ", "password1", "password1");

        assertThat(violatedFields(validator.validate(request))).contains("username");
    }

    @Test
    void rejectsSevenCharacterPassword() {
        RegisterUserDTO request = registration("alice", "passwd1", "passwd1");

        assertThat(violatedFields(validator.validate(request))).contains("password");
    }

    @Test
    void acceptsEightCharacterPassword() {
        RegisterUserDTO request = registration("alice", "passwd12", "passwd12");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void acceptsSixtyFourCharacterPassword() {
        String password = "a".repeat(64);
        RegisterUserDTO request = registration("alice", password, password);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsSixtyFiveCharacterPassword() {
        String password = "a".repeat(65);
        RegisterUserDTO request = registration("alice", password, password);

        assertThat(violatedFields(validator.validate(request))).contains("password");
    }

    @Test
    void acceptsPasswordAtSeventyTwoUtf8Bytes() {
        String password = "密".repeat(24);
        RegisterUserDTO request = registration("alice", password, password);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsPasswordOverSeventyTwoUtf8Bytes() {
        String password = "密".repeat(25);
        RegisterUserDTO request = registration("alice", password, password);

        assertThat(violatedFields(validator.validate(request))).contains("password");
    }

    @Test
    void rejectsMismatchedPasswordConfirmation() {
        RegisterUserDTO request = registration("alice", "password1", "password2");

        Set<ConstraintViolation<RegisterUserDTO>> violations = validator.validate(request);

        assertThat(violatedFields(violations)).contains("confirmPassword");
        assertThat(violations.stream()
                .filter(violation -> "confirmPassword".equals(
                        violation.getPropertyPath().toString()))
                .map(ConstraintViolation::getMessage))
                .contains("两次输入的密码不一致");
    }

    @Test
    void acceptsPasswordWithSurroundingSpaces() {
        String password = " password1 ";
        RegisterUserDTO request = registration("alice", password, password);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void acceptsSingleCharacterSessionPassword() {
        CreateSessionDTO request = new CreateSessionDTO();
        request.setUsername("alice");
        request.setPassword("x");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsTooLongSessionPassword() {
        CreateSessionDTO request = new CreateSessionDTO();
        request.setUsername("alice");
        request.setPassword("a".repeat(65));

        assertThat(violatedFields(validator.validate(request))).contains("password");
    }

    @Test
    void rejectsInvalidSessionUsername() {
        CreateSessionDTO request = new CreateSessionDTO();
        request.setUsername("alice!");
        request.setPassword("password1");

        assertThat(violatedFields(validator.validate(request))).contains("username");
    }

    @Test
    void acceptsEmptyProfileUpdate() {
        UpdateProfileDTO request = new UpdateProfileDTO();

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void acceptsDomesticAndInternationalPhoneNumbers() {
        UpdateProfileDTO domestic = new UpdateProfileDTO();
        domestic.setPhone("13800138000");
        UpdateProfileDTO international = new UpdateProfileDTO();
        international.setPhone("+8613800138000");

        assertThat(validator.validate(domestic)).isEmpty();
        assertThat(validator.validate(international)).isEmpty();
    }

    @Test
    void rejectsBlankPhoneInsteadOfTreatingItAsUnbound() {
        UpdateProfileDTO request = new UpdateProfileDTO();
        request.setPhone("");

        assertThat(violatedFields(validator.validate(request))).contains("phone");
    }

    @Test
    void rejectsMalformedPhone() {
        UpdateProfileDTO request = new UpdateProfileDTO();
        request.setPhone("12345");

        assertThat(violatedFields(validator.validate(request))).contains("phone");
    }

    @Test
    void acceptsEmailWithWhitespaceAndMixedCase() {
        UpdateProfileDTO request = new UpdateProfileDTO();
        request.setEmail("  A@Example.COM  ");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsBlankEmailInsteadOfTreatingItAsUnbound() {
        UpdateProfileDTO request = new UpdateProfileDTO();
        request.setEmail("");

        assertThat(violatedFields(validator.validate(request))).contains("email");
    }

    @Test
    void rejectsMalformedEmail() {
        UpdateProfileDTO request = new UpdateProfileDTO();
        request.setEmail("not-an-email");

        assertThat(violatedFields(validator.validate(request))).contains("email");
    }

    @Test
    void rejectsEmailOverMaximumLength() {
        UpdateProfileDTO request = new UpdateProfileDTO();
        request.setEmail("a".repeat(250) + "@b.co");

        assertThat(violatedFields(validator.validate(request))).contains("email");
    }

    @Test
    void acceptsEmailLocalPartAtSixtyFourBytes() {
        UpdateProfileDTO request = new UpdateProfileDTO();
        request.setEmail("a".repeat(64) + "@example.com");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsEmailLocalPartOverSixtyFourBytes() {
        UpdateProfileDTO request = new UpdateProfileDTO();
        request.setEmail("a".repeat(65) + "@example.com");

        assertThat(violatedFields(validator.validate(request))).contains("email");
    }

    @Test
    void acceptsDomainLabelAtSixtyThreeCharacters() {
        UpdateProfileDTO request = new UpdateProfileDTO();
        request.setEmail("a@" + "b".repeat(63) + ".com");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsDomainLabelOverSixtyThreeCharacters() {
        UpdateProfileDTO request = new UpdateProfileDTO();
        request.setEmail("a@" + "b".repeat(64) + ".com");

        assertThat(violatedFields(validator.validate(request))).contains("email");
    }

    @Test
    void rejectsDomainLabelStartingWithHyphen() {
        UpdateProfileDTO request = new UpdateProfileDTO();
        request.setEmail("a@-example.com");

        assertThat(violatedFields(validator.validate(request))).contains("email");
    }

    @Test
    void rejectsDomainLabelEndingWithHyphen() {
        UpdateProfileDTO request = new UpdateProfileDTO();
        request.setEmail("a@example-.com");

        assertThat(violatedFields(validator.validate(request))).contains("email");
    }

    @Test
    void acceptsValidPasswordChange() {
        ChangePasswordDTO request = changePassword("oldpass1", "newpass1", "newpass1");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsMismatchedNewPasswordConfirmation() {
        ChangePasswordDTO request = changePassword("oldpass1", "newpass1", "newpass2");

        Set<ConstraintViolation<ChangePasswordDTO>> violations = validator.validate(request);

        assertThat(violatedFields(violations)).contains("confirmPassword");
    }

    @Test
    void acceptsSingleCharacterOldPasswordForVerification() {
        ChangePasswordDTO request = changePassword("x", "newpass1", "newpass1");

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void rejectsTooLongOldPassword() {
        String oldPassword = "a".repeat(65);
        ChangePasswordDTO request = changePassword(oldPassword, "newpass1", "newpass1");

        assertThat(violatedFields(validator.validate(request))).contains("oldPassword");
    }

    private static RegisterUserDTO registration(String username, String password,
            String confirmPassword) {
        RegisterUserDTO request = new RegisterUserDTO();
        request.setUsername(username);
        request.setPassword(password);
        request.setConfirmPassword(confirmPassword);
        return request;
    }

    private static ChangePasswordDTO changePassword(String oldPassword, String newPassword,
            String confirmPassword) {
        ChangePasswordDTO request = new ChangePasswordDTO();
        request.setOldPassword(oldPassword);
        request.setNewPassword(newPassword);
        request.setConfirmPassword(confirmPassword);
        return request;
    }

    private static Set<String> violatedFields(
            Set<? extends ConstraintViolation<?>> violations) {
        return violations.stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }
}
