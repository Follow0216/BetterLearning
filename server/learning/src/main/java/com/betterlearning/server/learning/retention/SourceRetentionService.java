package com.betterlearning.server.learning.retention;

import com.betterlearning.server.learning.mapper.RetentionMapper;
import com.betterlearning.server.learning.service.impl.FileRetentionService;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Bounded sweeps; database copies and physical objects have separate completion evidence. */
@Service
public class SourceRetentionService {
    private final RetentionMapper mapper;
    private final TransactionTemplate transactions;
    private final FileRetentionService files;

    public SourceRetentionService(RetentionMapper mapper, org.springframework.transaction.PlatformTransactionManager manager,
            FileRetentionService files) {
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(manager);
        this.files = files;
    }

    public RetentionMapper.Evidence sweep(int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("Retention batch must be 1..1000");
        }
        for (RetentionMapper.Tombstone tombstone : mapper.tombstones(limit)) {
            List<String> references = transactions.execute(status -> {
                mapper.lockAccount(tombstone.accountId());
                mapper.lockSource(tombstone.sourceId());
                List<String> candidates = new ArrayList<>();
                for (RetentionMapper.Version version : mapper.deletedVersions(tombstone.sourceId())) {
                    mapper.scrub(version.sourceId(), version.version());
                    if (version.fileRef() != null) {
                        candidates.add(version.fileRef());
                    }
                }
                mapper.scrubAttempts(tombstone.sourceId());
                mapper.scrubWithheld(tombstone.sourceId());
                mapper.scrubClaims(tombstone.sourceId());
                mapper.checked(tombstone.sourceId(), mapper.activeRunHold(tombstone.sourceId()) ? "ACTIVE_RUN" : null);
                return candidates;
            });
            if (references != null) {
                references.stream().distinct().forEach(this::collectSafely);
            }
        }
        Instant before = Instant.now().minus(Duration.ofDays(30));
        for (RetentionMapper.Version version : mapper.retired(before, limit)) {
            Boolean purged = transactions.execute(status -> {
                mapper.lockAccount(version.accountId());
                mapper.lockSource(version.sourceId());
                return mapper.eligible(version.sourceId(), version.version(), before)
                        && mapper.scrub(version.sourceId(), version.version());
            });
            if (Boolean.TRUE.equals(purged) && version.fileRef() != null) {
                collectSafely(version.fileRef());
            }
        }
        files.pendingFileRefs(limit).forEach(this::collectSafely);
        return mapper.evidence();
    }

    private void collectSafely(String fileRef) {
        try {
            files.collect(fileRef);
        } catch (com.betterlearning.server.exception.BusinessException failure) {
            // FileRetentionService records FILE_IO before throwing. Continue other sources.
        }
    }

    public RetentionMapper.Evidence evidence() {
        return mapper.evidence();
    }
}
