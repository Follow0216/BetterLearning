package com.betterlearning.server.learning.service.impl;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.dao.LearningDao;
import com.betterlearning.server.learning.mapper.MultiAgentMapper;
import com.betterlearning.server.learning.mapper.TabletPresenceMapper;
import com.betterlearning.server.learning.mapper.TabletPresenceMapper.Lease;
import com.betterlearning.server.multiagent.TabletPresence;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 与create持有同一账号锁；无租约是业务选择，存储故障必须向上传播。 */
@Service
@Transactional
public class PostgresTabletPresence implements TabletPresence {
    public static final int LEASE_SECONDS = 30;
    private final TabletPresenceMapper db;
    private final MultiAgentMapper accounts;
    private final LearningDao questions;

    public PostgresTabletPresence(TabletPresenceMapper db, MultiAgentMapper accounts, LearningDao questions) {
        this.db = db;
        this.accounts = accounts;
        this.questions = questions;
    }

    @Override
    public Receipt heartbeat(long accountId, Heartbeat input) {
        if (input == null || input.sessionId() == null
                || !input.sessionId().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || input.sequence() < 1 || input.sequence() > 9007199254740991L
                || input.active() != (input.questionId() != null)) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        lock(accountId);
        Lease previous = db.lease(accountId, input.sessionId());
        if (previous != null && input.sequence() <= previous.sequence()) {
            if (input.sequence() == previous.sequence()
                    && (input.active() != previous.active() || !Objects.equals(input.questionId(), questionId(previous)))) {
                throw new BusinessException(ErrorCode.TUTOR_TURN_CONFLICT);
            }
            return receipt(previous);
        }
        Long question = input.active() ? question(accountId, input.questionId()) : null;
        Instant now = Instant.now();
        Lease next = new Lease(accountId, input.sessionId(), input.sequence(), question,
                input.active(), input.active() ? now.plusSeconds(LEASE_SECONDS) : now);
        db.upsert(next);
        return receipt(next);
    }

    @Override
    public Availability availability(long accountId, String questionId) {
        lock(accountId);
        long question = question(accountId, questionId);
        return select(db.leases(accountId), question, Instant.now());
    }

    static Availability select(List<Lease> leases, long question, Instant now) {
        Lease usable = leases.stream().filter(lease -> lease.active() && lease.leaseExpiresAt().isAfter(now)
                && Objects.equals(lease.questionId(), question))
                .max(Comparator.comparing(Lease::leaseExpiresAt)).orElse(null);
        if (usable != null) {
            return new Availability(true, "TABLET_AVAILABLE", usable.leaseExpiresAt());
        }
        if (leases.stream().anyMatch(lease -> lease.active() && lease.leaseExpiresAt().isAfter(now))) {
            return new Availability(false, "OTHER_QUESTION", null);
        }
        return new Availability(false, leases.isEmpty() ? "NO_DEVICE" : "OFFLINE", null);
    }

    private long question(long accountId, String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        try {
            long question = Long.parseLong(value);
            questions.findById(accountId, question).orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
            return question;
        } catch (NumberFormatException exception) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
    }

    private void lock(long accountId) {
        if (accounts.lock(accountId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    private static String questionId(Lease lease) {
        return lease.questionId() == null ? null : Long.toString(lease.questionId());
    }

    private static Receipt receipt(Lease lease) {
        return new Receipt(lease.sessionId(), lease.sequence(), questionId(lease), lease.active(), lease.leaseExpiresAt());
    }
}
