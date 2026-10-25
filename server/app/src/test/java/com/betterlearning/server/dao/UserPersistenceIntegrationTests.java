package com.betterlearning.server.dao;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import com.betterlearning.server.enums.UserUniqueField;
import com.betterlearning.server.exception.UserUniqueConflictException;
import com.betterlearning.server.model.dataobject.UserDO;
import com.betterlearning.server.support.MybatisTestContext;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@Tag("integration")
class UserPersistenceIntegrationTests {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:18.3")
            .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("betterlearning")
            .withUsername("test")
            .withPassword("test");

    private static JdbcTemplate jdbcTemplate;

    private static UserDao userDao;

    private static MybatisTestContext mybatisContext;

    private static MigrateResult migrateResult;

    @BeforeAll
    static void setUpDatabase() {
        DataSource dataSource = dataSource();
        migrateResult = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbcTemplate = new JdbcTemplate(dataSource);
        mybatisContext = MybatisTestContext.create(dataSource);
        userDao = mybatisContext.userDao();
    }

    @AfterAll
    static void closeMybatisContext() {
        if (mybatisContext != null) {
            mybatisContext.close();
        }
    }

    @BeforeEach
    void cleanTable() {
        com.betterlearning.server.support.TestData.cleanDatabase(jdbcTemplate);
    }

    @Test
    void migratesEmptyDatabaseWithExpectedSchema() {
        assertThat(migrateResult.success).isTrue();
        assertThat(migrateResult.migrationsExecuted).isEqualTo(10);
        assertThat(jdbcTemplate.queryForList("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class)).containsExactly("1", "2", "3", "4", "5", "2026102501", "2026102503", "2026102504", "2026102505", "2026102506");

        List<String> columns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = 'app_user'",
                String.class);
        assertThat(columns).containsExactlyInAnyOrder(
                "account_id", "username", "password_hash", "phone", "email",
                "role", "token_version", "created_at", "updated_at");

        String identity = jdbcTemplate.queryForObject(
                "SELECT is_identity FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = 'app_user' "
                + "AND column_name = 'account_id'",
                String.class);
        assertThat(identity).isEqualTo("YES");

        Map<String, Integer> lengths = new HashMap<>();
        jdbcTemplate.query(
                "SELECT column_name, character_maximum_length FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = 'app_user'",
                (RowCallbackHandler) resultSet -> lengths.put(resultSet.getString("column_name"),
                resultSet.getObject("character_maximum_length", Integer.class)));
        assertThat(lengths).containsEntry("username", 32)
                .containsEntry("password_hash", 255)
                .containsEntry("phone", 16)
                .containsEntry("email", 254)
                .containsEntry("role", 16);

        List<String> uniqueConstraints = jdbcTemplate.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints "
                + "WHERE table_schema = 'public' AND table_name = 'app_user' "
                + "AND constraint_type = 'UNIQUE'",
                String.class);
        assertThat(uniqueConstraints).containsExactlyInAnyOrder(
                "uk_app_user_username", "uk_app_user_phone", "uk_app_user_email");

        List<String> checkConstraints = jdbcTemplate.queryForList(
                "SELECT constraint_name FROM information_schema.table_constraints "
                + "WHERE table_schema = 'public' AND table_name = 'app_user' "
                + "AND constraint_type = 'CHECK'",
                String.class);
        assertThat(checkConstraints).contains(
                "ck_app_user_role", "ck_app_user_token_version");

        Map<String, String> defaults = new HashMap<>();
        jdbcTemplate.query(
                "SELECT column_name, column_default FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND table_name = 'app_user'",
                (RowCallbackHandler) resultSet -> defaults.put(resultSet.getString("column_name"),
                resultSet.getString("column_default")));
        assertThat(defaults.get("role")).contains("USER");
        assertThat(defaults.get("token_version")).isEqualTo("0");
        assertThat(defaults.get("created_at")).containsIgnoringCase("CURRENT_TIMESTAMP");
        assertThat(defaults.get("updated_at")).containsIgnoringCase("CURRENT_TIMESTAMP");
    }

    @Test
    void insertsUserWithGeneratedIdentityAndDefaults() {
        long firstAccountId = userDao.insert("alice", "hash-a");
        long secondAccountId = userDao.insert("bob", "hash-b");

        assertThat(firstAccountId).isPositive();
        assertThat(secondAccountId).isGreaterThan(firstAccountId);

        UserDO alice = userDao.findByAccountId(firstAccountId).orElseThrow();
        assertThat(alice.getAccountId()).isEqualTo(firstAccountId);
        assertThat(alice.getUsername()).isEqualTo("alice");
        assertThat(alice.getPasswordHash()).isEqualTo("hash-a");
        assertThat(alice.getRole()).isEqualTo("USER");
        assertThat(alice.getTokenVersion()).isZero();
        assertThat(alice.getPhone()).isNull();
        assertThat(alice.getEmail()).isNull();
        assertThat(alice.getCreatedAt()).isNotNull();
        assertThat(alice.getUpdatedAt()).isNotNull();
    }

    @Test
    void mapsTokenVersionOutsideIntegerCacheWithoutConversion() {
        long accountId = userDao.insert("alice", "hash-a");
        jdbcTemplate.update("UPDATE app_user SET token_version = ? WHERE account_id = ?",
                1000, accountId);

        UserDO user = userDao.findByAccountId(accountId).orElseThrow();

        assertThat(user.getAccountId()).isInstanceOf(Long.class).isEqualTo(accountId);
        assertThat(user.getTokenVersion()).isInstanceOf(Integer.class).isEqualTo(1000);
    }

    @Test
    void findsByNormalizedUsernameAndAccountId() {
        long accountId = userDao.insert("alice", "hash-a");

        assertThat(userDao.findByUsername("alice")).isPresent();
        assertThat(userDao.findByUsername("ALICE")).isEmpty();
        assertThat(userDao.findByAccountId(accountId)).isPresent();
        assertThat(userDao.findByAccountId(accountId + 1000)).isEmpty();
    }

    @Test
    void rejectsDuplicateUsername() {
        userDao.insert("alice", "hash-a");

        assertThatThrownBy(() -> userDao.insert("alice", "hash-b"))
                .isInstanceOfSatisfying(UserUniqueConflictException.class,
                        exception -> assertThat(exception.getField())
                                .isEqualTo(UserUniqueField.USERNAME))
                .hasCauseInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void rejectsDuplicatePhoneAndKeepsPreviousValue() {
        long aliceId = userDao.insert("alice", "hash-a");
        long bobId = userDao.insert("bob", "hash-b");
        userDao.updateContactInfo(aliceId, true, "+8613800000001", false, null);
        userDao.updateContactInfo(bobId, true, "+8613800000002", true, "bob@example.com");
        UserDO before = userDao.findByAccountId(bobId).orElseThrow();

        assertThatThrownBy(() -> userDao.updateContactInfo(
                bobId, true, "+8613800000001", true, "changed@example.com"))
                .isInstanceOfSatisfying(UserUniqueConflictException.class,
                        exception -> assertThat(exception.getField())
                                .isEqualTo(UserUniqueField.PHONE))
                .hasCauseInstanceOf(DuplicateKeyException.class);

        UserDO after = userDao.findByAccountId(bobId).orElseThrow();
        assertThat(after.getPhone()).isEqualTo("+8613800000002");
        assertThat(after.getEmail()).isEqualTo("bob@example.com");
        assertThat(after.getUpdatedAt()).isEqualTo(before.getUpdatedAt());
    }

    @Test
    void rejectsDuplicateEmail() {
        long aliceId = userDao.insert("alice", "hash-a");
        long bobId = userDao.insert("bob", "hash-b");
        userDao.updateContactInfo(aliceId, false, null, true, "alice@example.com");
        userDao.updateContactInfo(bobId, false, null, true, "bob@example.com");

        assertThatThrownBy(() -> userDao.updateContactInfo(
                bobId, false, null, true, "alice@example.com"))
                .isInstanceOfSatisfying(UserUniqueConflictException.class,
                        exception -> assertThat(exception.getField())
                                .isEqualTo(UserUniqueField.EMAIL))
                .hasCauseInstanceOf(DuplicateKeyException.class);

        assertThat(userDao.findByAccountId(bobId).orElseThrow().getEmail())
                .isEqualTo("bob@example.com");
    }

    @Test
    void allowsMultipleRowsWithNullContacts() {
        userDao.insert("alice", "hash-a");
        userDao.insert("bob", "hash-b");
        userDao.insert("carol", "hash-c");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM app_user WHERE phone IS NULL AND email IS NULL",
                Integer.class);
        assertThat(count).isEqualTo(3);
    }

    @Test
    void updatesOnlyProvidedContactFields() {
        long accountId = userDao.insert("alice", "hash-a");
        userDao.updateContactInfo(accountId, true, "+8613800000001", true, "alice@example.com");

        assertThat(userDao.updateContactInfo(accountId, false, null, true, "new@example.com"))
                .isEqualTo(1);
        UserDO afterEmailChange = userDao.findByAccountId(accountId).orElseThrow();
        assertThat(afterEmailChange.getPhone()).isEqualTo("+8613800000001");
        assertThat(afterEmailChange.getEmail()).isEqualTo("new@example.com");

        assertThat(userDao.updateContactInfo(accountId, true, "+8613800000009", false, null))
                .isEqualTo(1);
        UserDO afterPhoneChange = userDao.findByAccountId(accountId).orElseThrow();
        assertThat(afterPhoneChange.getPhone()).isEqualTo("+8613800000009");
        assertThat(afterPhoneChange.getEmail()).isEqualTo("new@example.com");
    }

    @Test
    void unbindsContactWhenNullIsProvided() {
        long accountId = userDao.insert("alice", "hash-a");
        userDao.updateContactInfo(accountId, true, "+8613800000001", true, "alice@example.com");

        assertThat(userDao.updateContactInfo(accountId, true, null, false, null)).isEqualTo(1);
        UserDO afterPhoneUnbind = userDao.findByAccountId(accountId).orElseThrow();
        assertThat(afterPhoneUnbind.getPhone()).isNull();
        assertThat(afterPhoneUnbind.getEmail()).isEqualTo("alice@example.com");

        assertThat(userDao.updateContactInfo(accountId, false, null, true, null)).isEqualTo(1);
        UserDO afterEmailUnbind = userDao.findByAccountId(accountId).orElseThrow();
        assertThat(afterEmailUnbind.getPhone()).isNull();
        assertThat(afterEmailUnbind.getEmail()).isNull();
    }

    @Test
    void returnsZeroWhenNoContactFieldIsProvided() {
        long accountId = userDao.insert("alice", "hash-a");

        assertThat(userDao.updateContactInfo(accountId, false, null, false, null)).isZero();
    }

    @Test
    void returnsZeroWhenAccountDoesNotExist() {
        assertThat(userDao.updateContactInfo(999999L, true, "+8613800000001", false, null)).isZero();
        assertThat(userDao.updatePassword(999999L, "old-hash", 0, "new-hash")).isZero();
    }

    @Test
    void passwordChangeUsesCompareAndSwap() {
        long accountId = userDao.insert("alice", "old-hash");
        OffsetDateTime createdAt = userDao.findByAccountId(accountId).orElseThrow().getCreatedAt();

        assertThat(userDao.updatePassword(accountId, "old-hash", 0, "new-hash")).isEqualTo(1);
        UserDO afterFirstChange = userDao.findByAccountId(accountId).orElseThrow();
        assertThat(afterFirstChange.getPasswordHash()).isEqualTo("new-hash");
        assertThat(afterFirstChange.getTokenVersion()).isEqualTo(1);
        assertThat(afterFirstChange.getCreatedAt()).isEqualTo(createdAt);
        assertThat(afterFirstChange.getUpdatedAt()).isAfterOrEqualTo(createdAt);

        assertThat(userDao.updatePassword(accountId, "old-hash", 0, "stale-hash")).isZero();
        UserDO afterStaleAttempt = userDao.findByAccountId(accountId).orElseThrow();
        assertThat(afterStaleAttempt.getPasswordHash()).isEqualTo("new-hash");
        assertThat(afterStaleAttempt.getTokenVersion()).isEqualTo(1);

        assertThat(userDao.updatePassword(accountId, "wrong-hash", 1, "other-hash")).isZero();
        assertThat(userDao.findByAccountId(accountId).orElseThrow().getTokenVersion()).isEqualTo(1);

        assertThat(userDao.updatePassword(accountId, "new-hash", 1, "third-hash")).isEqualTo(1);
        UserDO afterThirdChange = userDao.findByAccountId(accountId).orElseThrow();
        assertThat(afterThirdChange.getPasswordHash()).isEqualTo("third-hash");
        assertThat(afterThirdChange.getTokenVersion()).isEqualTo(2);
    }

    @Test
    void rejectsRoleOutsideAllowedValues() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO app_user (username, password_hash, role) VALUES (?, ?, ?)",
                "alice", "hash-a", "SUPERUSER"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_app_user_role");
    }

    @Test
    void rejectsNegativeTokenVersion() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO app_user (username, password_hash, token_version) VALUES (?, ?, ?)",
                "alice", "hash-a", -1))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_app_user_token_version");
    }

    @Test
    void rejectsExplicitAccountIdBecauseItIsAlwaysGenerated() {
        Throwable failure = catchThrowable(() -> jdbcTemplate.update(
                "INSERT INTO app_user (account_id, username, password_hash) VALUES (?, ?, ?)",
                1000L, "alice", "hash-a"));

        assertThat(failure).isInstanceOf(DataAccessException.class);
        assertThat(failure.getCause()).hasMessageContaining("account_id");
    }

    private static DataSource dataSource() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(POSTGRES.getDriverClassName());
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        return dataSource;
    }
}
