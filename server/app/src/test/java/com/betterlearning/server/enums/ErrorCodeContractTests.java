package com.betterlearning.server.enums;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class ErrorCodeContractTests {

    private static final Pattern CODE_PATTERN = Pattern.compile("^[ABC][0-9]{4}$");

    @Test
    void exposesExactlyTheAgreedNameCodeAndStatusMappings() {
        Map<String, ExpectedCode> expected = Map.ofEntries(
                entry("INVALID_PARAM", "A0400", HttpStatus.BAD_REQUEST),
                entry("INVALID_JSON", "A0427", HttpStatus.BAD_REQUEST),
                entry("UNAUTHORIZED", "A0200", HttpStatus.UNAUTHORIZED),
                entry("FORBIDDEN", "A0300", HttpStatus.FORBIDDEN),
                entry("NOT_FOUND", "A0606", HttpStatus.NOT_FOUND),
                entry("METHOD_NOT_ALLOWED", "A0507", HttpStatus.METHOD_NOT_ALLOWED),
                entry("UNSUPPORTED_MEDIA_TYPE", "A0428", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
                entry("NOT_ACCEPTABLE", "A0429", HttpStatus.NOT_ACCEPTABLE),
                entry("USERNAME_EXISTS", "A0111", HttpStatus.CONFLICT),
                entry("PHONE_ALREADY_BOUND", "A0154", HttpStatus.CONFLICT),
                entry("EMAIL_ALREADY_BOUND", "A0155", HttpStatus.CONFLICT),
                entry("PASSWORD_CHANGE_CONFLICT", "A0444", HttpStatus.CONFLICT),
                entry("CONFIRM_REQUEST_CONFLICT", "A0445", HttpStatus.CONFLICT),
                entry("COLLECTION_NAME_EXISTS", "A0450", HttpStatus.CONFLICT),
                entry("ANSWER_CHANGED", "A0451", HttpStatus.CONFLICT),
                entry("ANSWER_NOT_SAVED", "A0452", HttpStatus.CONFLICT),
                entry("ANSWER_COMPLETED", "A0453", HttpStatus.CONFLICT),
                entry("ANSWER_SAVE_CONFLICT", "A0454", HttpStatus.CONFLICT),
                entry("UPLOAD_TOO_LARGE", "A0430", HttpStatus.PAYLOAD_TOO_LARGE),
                entry("RATE_LIMITED", "A0501", HttpStatus.TOO_MANY_REQUESTS),
                entry("SERVICE_UNAVAILABLE", "C0001", HttpStatus.SERVICE_UNAVAILABLE),
                entry("INTERNAL_ERROR", "B0001", HttpStatus.INTERNAL_SERVER_ERROR));

        Map<String, ExpectedCode> actual = Arrays.stream(ErrorCode.values())
                .collect(Collectors.toMap(ErrorCode::name, code -> new ExpectedCode(code.getCode(), code.getStatus())));

        assertThat(ErrorCode.values()).hasSize(22);
        assertThat(actual).isEqualTo(expected);
    }

    @Test
    void usesUniqueFiveCharacterCodesWithIndependentStatuses() {
        List<String> codes = Arrays.stream(ErrorCode.values())
                .map(ErrorCode::getCode)
                .toList();

        assertThat(codes).doesNotHaveDuplicates().hasSize(22);
        assertThat(codes).allMatch(code -> CODE_PATTERN.matcher(code).matches());
        assertThat(codes).allMatch(code -> code.length() == 5);

        assertThat(ErrorCode.USERNAME_EXISTS.getStatus())
                .isEqualTo(ErrorCode.PHONE_ALREADY_BOUND.getStatus())
                .isEqualTo(ErrorCode.EMAIL_ALREADY_BOUND.getStatus())
                .isEqualTo(ErrorCode.PASSWORD_CHANGE_CONFLICT.getStatus());
        assertThat(ErrorCode.INVALID_PARAM.getStatus())
                .isEqualTo(ErrorCode.INVALID_JSON.getStatus());
        assertThat(ErrorCode.SERVICE_UNAVAILABLE.getStatus())
                .isNotEqualTo(ErrorCode.INTERNAL_ERROR.getStatus());
    }

    @Test
    void keepsNonBlankDefaultMessages() {
        assertThat(Arrays.stream(ErrorCode.values()).map(ErrorCode::getMessage))
                .allMatch(message -> message != null && !message.isBlank());
    }

    private static Map.Entry<String, ExpectedCode> entry(String name, String code,
            HttpStatus status) {
        return Map.entry(name, new ExpectedCode(code, status));
    }

    private record ExpectedCode(String code, HttpStatus status) {
    }
}
