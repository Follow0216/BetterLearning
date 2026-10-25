package com.betterlearning.server.learning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.betterlearning.server.learning.retention.DeletionJournal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DeletionJournalTests {
    private static final String SOURCE = "1c6c965d-77a6-4b2e-86fd-bdefa9f8826a";
    private static final Instant TIME = Instant.parse("2026-10-25T00:00:00Z");

    @TempDir
    Path temporary;

    @Test
    void persistsOnlyDeletionIdentityAndValidatesAcrossReopen() throws IOException {
        Path root = temporary.resolve("separate-journal");
        assertThrows(IOException.class, () -> new DeletionJournal(root));
        DeletionJournal journal = DeletionJournal.initialize(root);
        assertEquals(List.of(), journal.readAll());
        DeletionJournal.Entry first = journal.append(31, SOURCE, TIME);
        DeletionJournal.Entry second = journal.append(31, SOURCE, TIME.plusSeconds(1));
        assertEquals(1, first.sequence());
        assertEquals(2, second.sequence());
        assertEquals(List.of(first, second), new DeletionJournal(root).readAll());
        String disk = Files.readString(root.resolve("deletions.tsv"));
        assertFalse(disk.contains("title"));
        assertFalse(disk.contains("body"));
    }

    @Test
    void ordinaryOpenRejectsAnEmptyMountedDirectory() throws IOException {
        Path root = temporary.resolve("wrong-empty-volume");
        Files.createDirectory(root);
        assertThrows(IOException.class, () -> new DeletionJournal(root));
        try (var files = Files.list(root)) {
            assertEquals(0, files.count());
        }
        DeletionJournal.initialize(root);
        assertEquals(List.of(), new DeletionJournal(root).readAll());
    }

    @Test
    void explicitInitializationRejectsIncompleteExistingPair() throws IOException {
        Path root = temporary.resolve("incomplete");
        Files.createDirectory(root);
        Files.writeString(root.resolve("deletions.tsv"), "");
        assertThrows(IOException.class, () -> DeletionJournal.initialize(root));
    }

    @Test
    void rejectsTruncatedTailAndCannotAppendOverIt() throws IOException {
        Path root = temporary.resolve("journal");
        DeletionJournal journal = DeletionJournal.initialize(root);
        journal.append(1, SOURCE, TIME);
        Path log = root.resolve("deletions.tsv");
        byte[] original = Files.readAllBytes(log);
        Files.write(log, java.util.Arrays.copyOf(original, original.length - 1));
        assertThrows(IOException.class, journal::readAll);
        assertThrows(IOException.class, () -> journal.append(1, SOURCE, TIME));
    }

    @Test
    void rejectsWholeRowTruncationUsingHeadAndRejectsTampering() throws IOException {
        Path root = temporary.resolve("journal");
        DeletionJournal journal = DeletionJournal.initialize(root);
        journal.append(1, SOURCE, TIME);
        Path log = root.resolve("deletions.tsv");
        String original = Files.readString(log);
        Files.writeString(log, "");
        assertThrows(IOException.class, journal::readAll);
        Files.writeString(log, original.replace("\t1\t", "\t2\t"), StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> new DeletionJournal(root));
    }

    @Test
    void concurrentInstancesSerializeAndKeepAContinuousChain() throws Exception {
        Path root = temporary.resolve("journal");
        DeletionJournal first = DeletionJournal.initialize(root);
        DeletionJournal second = new DeletionJournal(root);
        ExecutorService workers = Executors.newFixedThreadPool(4);
        try {
            List<Callable<DeletionJournal.Entry>> tasks = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                DeletionJournal instance = index % 2 == 0 ? first : second;
                int offset = index;
                tasks.add(() -> instance.append(1, SOURCE, TIME.plusSeconds(offset)));
            }
            List<Future<DeletionJournal.Entry>> results = workers.invokeAll(tasks);
            for (Future<DeletionJournal.Entry> result : results) {
                result.get();
            }
            List<DeletionJournal.Entry> entries = new DeletionJournal(root).readAll();
            assertEquals(12, entries.size());
            for (int index = 0; index < entries.size(); index++) {
                assertEquals(index + 1L, entries.get(index).sequence());
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void refusesSymlinkRootAndLog() throws IOException {
        Path real = temporary.resolve("real");
        DeletionJournal journal = DeletionJournal.initialize(real);
        Path alias = temporary.resolve("alias");
        Files.createSymbolicLink(alias, real);
        assertThrows(IOException.class, () -> new DeletionJournal(alias));
        Files.delete(real.resolve("deletions.tsv"));
        Path target = temporary.resolve("target");
        Files.writeString(target, "secret");
        Files.createSymbolicLink(real.resolve("deletions.tsv"), target);
        assertThrows(IOException.class, journal::readAll);
        assertThrows(IOException.class, () -> journal.append(1, SOURCE, TIME));
    }
}
