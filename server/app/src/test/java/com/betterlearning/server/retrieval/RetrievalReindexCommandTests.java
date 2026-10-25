package com.betterlearning.server.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RetrievalReindexCommandTests {
    @Test
    void requiresExplicitConsentBeforeReadingDatabaseConfiguration() {
        Result result = run("--account", "7", "--source", UUID.randomUUID().toString(),
                "--version", "1", "--expected-index-revision", "1");

        assertThat(result.code()).isEqualTo(2);
        assertThat(result.error()).contains("CONFIRM_REQUIRED", "可能产生 OCR 或 embedding 费用");
    }

    @Test
    void rejectsMissingOrDuplicateTargetArgumentsWithoutOpeningDatabase() {
        String source = UUID.randomUUID().toString();
        Result missing = run("--account", "7", "--source", source,
                "--version", "1", "--confirm-new-paid-attempt");
        Result duplicate = run("--account", "7", "--account", "8", "--source", source,
                "--version", "1", "--expected-index-revision", "1", "--confirm-new-paid-attempt");

        assertThat(missing.code()).isEqualTo(2);
        assertThat(missing.error()).contains("ARGUMENT_INVALID");
        assertThat(duplicate.code()).isEqualTo(2);
        assertThat(duplicate.error()).contains("ARGUMENT_INVALID");
    }

    @Test
    void doesNotExposeConnectionSecretsInConfigurationErrors() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int code = RetrievalReindexCommand.execute(new String[] {"--account", "7", "--source",
                UUID.randomUUID().toString(), "--version", "1", "--expected-index-revision", "1",
                "--confirm-new-paid-attempt"}, Map.of("SPRING_DATASOURCE_URL", "secret-url"),
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8));

        assertThat(code).isEqualTo(2);
        assertThat(error.toString(StandardCharsets.UTF_8)).contains("CONFIG_INVALID")
                .doesNotContain("secret-url");
        assertThat(output.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    @Test
    void requiresIndependentExistingJournalBeforeAnyDatabaseAccess(@TempDir Path filesRoot) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        String[] arguments = {"--account", "7", "--source", UUID.randomUUID().toString(),
                "--version", "1", "--expected-index-revision", "1", "--confirm-new-paid-attempt"};
        Map<String, String> base = Map.of("SPRING_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:1/test",
                "SPRING_DATASOURCE_USERNAME", "test", "SPRING_DATASOURCE_PASSWORD", "secret-password",
                "APP_FILES_ROOT", filesRoot.toString());
        int missing = RetrievalReindexCommand.execute(arguments, base,
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8));
        assertThat(missing).isEqualTo(2);
        assertThat(error.toString(StandardCharsets.UTF_8)).contains("CONFIG_INVALID")
                .doesNotContain("secret-password");
        assertThat(output.toString(StandardCharsets.UTF_8)).isEmpty();

        error.reset();
        Map<String, String> overlap = new java.util.HashMap<>(base);
        overlap.put("APP_AGENT_RETENTION_DELETE_LOG_ROOT", filesRoot.resolve("journal").toString());
        int invalid = RetrievalReindexCommand.execute(arguments, overlap,
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8));
        assertThat(invalid).isEqualTo(2);
        assertThat(error.toString(StandardCharsets.UTF_8)).contains("JOURNAL_ROOT_INVALID");
        assertThat(output.toString(StandardCharsets.UTF_8)).isEmpty();
    }

    private static Result run(String... args) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int code = RetrievalReindexCommand.execute(args, Map.of(),
                new PrintStream(output, true, StandardCharsets.UTF_8),
                new PrintStream(error, true, StandardCharsets.UTF_8));
        return new Result(code, error.toString(StandardCharsets.UTF_8));
    }

    private record Result(int code, String error) {
    }
}
