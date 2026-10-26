package com.betterlearning.server.agent.batch;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import com.betterlearning.server.agent.batch.mapper.BatchOcrMapper;
import com.betterlearning.server.agent.model.BatchOcrModels.Result;
import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;

import tools.jackson.databind.json.JsonMapper;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 短事务建立唯一身份与执行围栏；模型调用永远在事务外。 */
@Repository
public class MybatisBatchOcrStore implements BatchOcrStore {
    private final BatchOcrMapper mapper;
    private final JsonMapper json;
    private final TransactionTemplate transactions;

    public MybatisBatchOcrStore(BatchOcrMapper mapper, JsonMapper json, PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.json = json;
        this.transactions = new TransactionTemplate(manager);
    }

    @Override
    public Snapshot find(long accountId, String requestId) {
        mapper.expire(accountId, requestId);
        return snapshot(mapper.find(accountId, requestId));
    }

    @Override
    public Created create(long accountId, String requestId, String fingerprint, String owner) {
        return transactions.execute(ignored -> {
            mapper.lockAdmission();
            if (mapper.lockAccount(accountId) == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND);
            }
            mapper.expire(accountId, null);
            Snapshot existing = snapshot(mapper.find(accountId, requestId));
            if (existing != null) {
                return new Created(existing, false);
            }
            if (mapper.activeCount(accountId, false) != 0) {
                throw new BusinessException(ErrorCode.TUTOR_TURN_CONFLICT, "该账号已有一个识别批次正在处理");
            }
            if (mapper.activeCount(null, false) >= 2) {
                throw new BusinessException(ErrorCode.OCR_BATCH_BUSY);
            }
            mapper.insert(accountId, requestId, fingerprint, owner);
            return new Created(snapshot(mapper.find(accountId, requestId)), true);
        });
    }

    @Override
    public Start start(long accountId, String requestId, String owner) {
        return transactions.execute(ignored -> {
            mapper.lockAdmission();
            mapper.expire(accountId, requestId);
            Map<String, Object> row = mapper.find(accountId, requestId);
            if (row == null || !"QUEUED".equals(row.get("status")) || !owner.equals(row.get("owner"))) {
                return Start.FENCED;
            }
            if (mapper.activeCount(null, true) != 0) {
                return Start.WAITING;
            }
            return mapper.start(accountId, requestId, owner) == 1 ? Start.STARTED : Start.FENCED;
        });
    }

    @Override
    public boolean heartbeat(long accountId, String requestId, String owner) {
        return mapper.heartbeat(accountId, requestId, owner) == 1;
    }

    @Override
    public boolean progress(long accountId, String requestId, String owner, int completed, int total,
            String stage, Result partial) {
        return mapper.progress(accountId, requestId, owner, completed, total, stage, encode(partial)) == 1;
    }

    @Override
    public void finish(long accountId, String requestId, String owner, String status,
            Result result, String errorCode, String message) {
        mapper.finish(accountId, requestId, owner, status, encode(result), errorCode, message);
    }

    @Override
    public Snapshot cancel(long accountId, String requestId) {
        mapper.expire(accountId, requestId);
        mapper.cancel(accountId, requestId);
        return snapshot(mapper.find(accountId, requestId));
    }

    @Override
    public void releaseCancelled(long accountId, String requestId, String owner) {
        mapper.releaseCancelled(accountId, requestId, owner);
    }

    private String encode(Result result) {
        if (result == null) {
            return null;
        }
        String encoded = json.writeValueAsString(result);
        if (encoded.getBytes(StandardCharsets.UTF_8).length > 4 * 1024 * 1024) {
            throw new IllegalArgumentException("Batch result exceeded storage budget");
        }
        return encoded;
    }

    private Snapshot snapshot(Map<String, Object> row) {
        if (row == null) {
            return null;
        }
        String encoded = (String) row.get("resultJson");
        return new Snapshot((String) row.get("requestId"), (String) row.get("fingerprint"),
                (String) row.get("status"), (String) row.get("stage"),
                ((Number) row.get("completedTiles")).intValue(), ((Number) row.get("totalTiles")).intValue(),
                encoded == null ? null : json.readValue(encoded, Result.class),
                (String) row.get("errorCode"), (String) row.get("message"));
    }
}
