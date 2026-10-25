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
            RetentionMapper.Watermark previous = mapper.watermark();
            if (previous.sequence() > entries.size() || (previous.sequence() > 0
                    && !entries.get(Math.toIntExact(previous.sequence() - 1)).hash().equals(previous.hash()))) {
                throw new IllegalStateException("Deletion journal is behind database watermark");
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
}
