package com.betterlearning.server.converter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import com.betterlearning.server.model.dataobject.UserDO;
import com.betterlearning.server.model.vo.UserVO;

import org.junit.jupiter.api.Test;

class UserConverterTests {

    private final UserConverter userConverter = new UserConverter();

    @Test
    void mapsPublicFieldsAndFormatsAccountIdAsString() {
        UserDO user = new UserDO();
        user.setAccountId(42L);
        user.setUsername("alice_01");
        user.setPasswordHash("$2a$10$hash");
        user.setPhone("+8613800138000");
        user.setEmail("alice@example.com");
        user.setRole("USER");
        user.setTokenVersion(3);
        user.setCreatedAt(OffsetDateTime.parse("2026-10-22T10:00:00Z"));
        user.setUpdatedAt(OffsetDateTime.parse("2026-10-22T11:00:00Z"));

        UserVO result = userConverter.toVO(user);

        assertThat(result.getAccountId()).isEqualTo("42");
        assertThat(result.getUsername()).isEqualTo("alice_01");
        assertThat(result.getPhone()).isEqualTo("+8613800138000");
        assertThat(result.getEmail()).isEqualTo("alice@example.com");
        assertThat(result.getRole()).isEqualTo("USER");
        assertThat(result.getCreatedAt())
                .isEqualTo(OffsetDateTime.parse("2026-10-22T10:00:00Z"));
        assertThat(result.getUpdatedAt())
                .isEqualTo(OffsetDateTime.parse("2026-10-22T11:00:00Z"));
    }

    @Test
    void userVoExposesOnlyWhitelistedFields() {
        Set<String> fields = Arrays.stream(UserVO.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toSet());

        assertThat(fields).containsExactlyInAnyOrder(
                "accountId", "username", "phone", "email", "role", "createdAt", "updatedAt");
    }

    @Test
    void returnsNullForNullUser() {
        assertThat(userConverter.toVO(null)).isNull();
    }

    @Test
    void rejectsUserWithoutAccountIdInsteadOfProducingNullText() {
        UserDO user = new UserDO();
        user.setUsername("alice_01");

        assertThatThrownBy(() -> userConverter.toVO(user))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("account_id");
    }
}
