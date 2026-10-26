package com.betterlearning.server.multiagent;

import java.time.Instant;

/** 账号内最近可冻结的前台平板租约；这不是物理设备在线探测。 */
public interface TabletPresence {
    record Heartbeat(String sessionId, long sequence, String questionId, boolean active) { }
    record Receipt(String sessionId, long sequence, String questionId, boolean active, Instant leaseExpiresAt) { }
    record Availability(boolean available, String reason, Instant leaseExpiresAt) { }

    Receipt heartbeat(long accountId, Heartbeat input);
    Availability availability(long accountId, String questionId);
}
