package com.betterlearning.server.learning.retention;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Durable, independent intent journal. An append must precede the database withdrawal commit. */
public final class DeletionJournal {
    private static final String GENESIS = "0".repeat(64);
    private static final Map<Path, Object> JVM_LOCKS = new ConcurrentHashMap<>();
    private final Path root;
    private final Path log;
    private final Path head;
    private final Path lock;
    private final Object jvmLock;

    public record Entry(long sequence, String sourceId, long accountId, Instant deletedAt, String hash) {
    }

    public DeletionJournal(Path root) throws IOException {
        this(root, false);
    }

    /** Explicit one-time initialization for a new deployment, never use during backup restore. */
    public static DeletionJournal initialize(Path root) throws IOException {
        return new DeletionJournal(root, true);
    }

    private DeletionJournal(Path root, boolean initialize) throws IOException {
        if (root == null || !root.isAbsolute()) {
            throw new IllegalArgumentException("Deletion journal root must be an absolute path");
        }
        this.root = root.normalize();
        if (Files.isSymbolicLink(this.root)) {
            throw new IOException("Deletion journal root is a symlink");
        }
        if (Files.notExists(this.root, LinkOption.NOFOLLOW_LINKS)) {
            if (!initialize) {
                throw new IOException("Deletion journal root is missing");
            }
            Files.createDirectory(this.root);
            forceDirectory(this.root.getParent());
        }
        if (!Files.isDirectory(this.root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Deletion journal root is not a directory");
        }
        this.log = this.root.resolve("deletions.tsv");
        this.head = this.root.resolve("head.tsv");
        this.lock = this.root.resolve("journal.lock");
        if (!initialize && (!Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(head, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Deletion journal is not initialized");
        }
        this.jvmLock = JVM_LOCKS.computeIfAbsent(this.root.toRealPath(), ignored -> new Object());
        withLock(() -> {
            if (initialize) {
                initializeFiles();
            }
            readValidated();
            return null;
        });
    }

    public Entry append(long accountId, String sourceId, Instant deletedAt) throws IOException {
        if (accountId <= 0 || sourceId == null || deletedAt == null) {
            throw new IllegalArgumentException("Invalid deletion identity or time");
        }
        String canonicalId;
        try {
            canonicalId = UUID.fromString(sourceId).toString();
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("sourceId must be a UUID", exception);
        }
        if (!canonicalId.equals(sourceId)) {
            throw new IllegalArgumentException("sourceId must be a canonical UUID");
        }
        return withLock(() -> {
            List<Entry> entries = readValidated();
            long sequence = entries.size() + 1L;
            String previous = entries.isEmpty() ? GENESIS : entries.get(entries.size() - 1).hash();
            String prefix = sequence + "\t" + accountId + "\t" + canonicalId + "\t"
                    + deletedAt + "\t" + previous;
            String hash = sha256(prefix);
            byte[] row = (prefix + "\t" + hash + "\n").getBytes(StandardCharsets.UTF_8);
            checkRegular(log);
            try (FileChannel channel = FileChannel.open(log, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                writeFully(channel, ByteBuffer.wrap(row));
                channel.force(true);
            }
            writeHead(sequence, hash);
            return new Entry(sequence, canonicalId, accountId, deletedAt, hash);
        });
    }

    public List<Entry> readAll() throws IOException {
        return withLock(() -> List.copyOf(readValidated()));
    }

    private void initializeFiles() throws IOException {
        boolean logExists = Files.exists(log, LinkOption.NOFOLLOW_LINKS);
        boolean headExists = Files.exists(head, LinkOption.NOFOLLOW_LINKS);
        if (logExists != headExists) {
            throw new IOException("Deletion journal pair is incomplete");
        }
        if (!logExists) {
            try (var files = Files.list(root)) {
                if (files.anyMatch(path -> !path.equals(lock))) {
                    throw new IOException("Deletion journal initialization requires an empty directory");
                }
            }
            Files.createFile(log);
            try (FileChannel channel = FileChannel.open(log, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            writeHead(0, GENESIS);
        }
        checkRegular(log);
        checkRegular(head);
    }

    private List<Entry> readValidated() throws IOException {
        checkRegular(log);
        checkRegular(head);
        byte[] bytes = Files.readAllBytes(log);
        if (bytes.length > 0 && bytes[bytes.length - 1] != '\n') {
            throw new IOException("Deletion journal has an incomplete tail");
        }
        String content = decode(bytes);
        List<Entry> entries = new ArrayList<>();
        String previous = GENESIS;
        String[] lines = content.isEmpty() ? new String[0] : content.substring(0, content.length() - 1).split("\n", -1);
        for (String line : lines) {
            if (line.isEmpty()) {
                throw new IOException("Deletion journal contains a blank row");
            }
            String[] fields = line.split("\t", -1);
            if (fields.length != 6) {
                throw new IOException("Invalid deletion journal row");
            }
            try {
                long sequence = Long.parseLong(fields[0]);
                long accountId = Long.parseLong(fields[1]);
                String sourceId = UUID.fromString(fields[2]).toString();
                Instant deletedAt = Instant.parse(fields[3]);
                String prefix = String.join("\t", fields[0], fields[1], fields[2], fields[3], fields[4]);
                if (sequence != entries.size() + 1L || accountId <= 0 || !sourceId.equals(fields[2])
                        || !deletedAt.toString().equals(fields[3]) || !previous.equals(fields[4])
                        || !sha256(prefix).equals(fields[5])) {
                    throw new IOException("Deletion journal chain validation failed");
                }
                previous = fields[5];
                entries.add(new Entry(sequence, sourceId, accountId, deletedAt, previous));
            } catch (IllegalArgumentException exception) {
                throw new IOException("Invalid deletion journal row", exception);
            }
        }
        String expected = entries.size() + "\t" + previous + "\n";
        if (!expected.equals(decode(Files.readAllBytes(head)))) {
            throw new IOException("Deletion journal head mismatch");
        }
        return entries;
    }

    private void writeHead(long sequence, String hash) throws IOException {
        Path temporary = Files.createTempFile(root, "head-", ".tmp");
        try {
            byte[] value = (sequence + "\t" + hash + "\n").getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                writeFully(channel, ByteBuffer.wrap(value));
                channel.force(true);
            }
            checkRegular(head, true);
            Files.move(temporary, head, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            forceDirectory(root);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private <T> T withLock(IoAction<T> action) throws IOException {
        synchronized (jvmLock) {
            if (Files.isSymbolicLink(root)) {
                throw new IOException("Deletion journal root is a symlink");
            }
            checkRegular(lock, true);
            try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                    FileLock ignored = channel.lock()) {
                forceDirectory(root);
                return action.run();
            }
        }
    }

    private static void checkRegular(Path path) throws IOException {
        checkRegular(path, false);
    }

    private static void checkRegular(Path path, boolean allowMissing) throws IOException {
        if (Files.isSymbolicLink(path) || (!allowMissing && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))) {
            throw new IOException("Deletion journal path is unsafe: " + path.getFileName());
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) {
            channel.write(bytes);
        }
    }

    private static void forceDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static String decode(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("Deletion journal is not UTF-8", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    @FunctionalInterface
    private interface IoAction<T> {
        T run() throws IOException;
    }
}
