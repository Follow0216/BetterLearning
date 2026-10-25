package com.betterlearning.server.learning.retention;

import com.betterlearning.server.learning.mapper.RetentionMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Independent intent before database commit; replay is invoked before serving requests. */
public final class SourceDeletionService {
    private final DeletionJournal journal;
    private final RetentionMapper mapper;
    private final TransactionTemplate transactions;

    public SourceDeletionService(DeletionJournal journal, RetentionMapper mapper, TransactionTemplate transactions) {
        this.journal = java.util.Objects.requireNonNull(journal, "Independent deletion journal required");
        this.mapper = mapper;
        this.transactions = transactions;
    }

    public void withdraw(long accountId, String sourceId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Independent deletion journal and source transaction required");
        }
        try {
            DeletionJournal.Entry entry = journal.append(accountId, sourceId, Instant.now());
            mapper.revoke(sourceId, accountId, entry.deletedAt(), entry.sequence(), entry.hash());
        } catch (IOException failure) {
            throw new IllegalStateException("Deletion journal unavailable; withdrawal not committed", failure);
        }
    }

    public void recoverBeforeServing() {
        final List<DeletionJournal.Entry> entries;
        try {
            entries = journal.readAll();
        } catch (IOException failure) {
            throw new IllegalStateException("Deletion journal validation failed; serving must remain closed", failure);
        }
        transactions.executeWithoutResult(status -> {
            verifyRecordedPrefix(entries, mapper.watermark());
            // Live withdrawals commit tombstones without advancing the startup replay watermark.
            // Use the greatest journal sequence, not transaction commit order; extra intents may be replayed.
            RetentionMapper.Watermark committed = mapper.highestCommittedDeletion();
            if (committed != null) {
                verifyRecordedPrefix(entries, committed);
            }
            // Reapply all intents, including rolled-back deletion transactions and absent-source tombstones.
            for (DeletionJournal.Entry entry : entries) {
                mapper.lockAccount(entry.accountId());
                mapper.revoke(entry.sourceId(), entry.accountId(), entry.deletedAt(), entry.sequence(), entry.hash());
            }
            if (!entries.isEmpty()) {
                DeletionJournal.Entry head = entries.get(entries.size() - 1);
                mapper.watermarkSaved(head.sequence(), head.hash());
            }
        });
    }

    private static void verifyRecordedPrefix(List<DeletionJournal.Entry> entries, RetentionMapper.Watermark watermark) {
        if (watermark.sequence() > entries.size() || (watermark.sequence() > 0
                && !entries.get(Math.toIntExact(watermark.sequence() - 1)).hash().equals(watermark.hash()))) {
            throw new IllegalStateException("Deletion journal is behind database watermark");
        }
    }
}
