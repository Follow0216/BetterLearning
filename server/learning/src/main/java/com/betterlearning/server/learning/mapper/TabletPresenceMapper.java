package com.betterlearning.server.learning.mapper;

import java.time.Instant;
import java.util.List;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 仅映射已认证账号的会话租约，释放记录保留sequence以屏蔽迟到心跳。 */
public interface TabletPresenceMapper {
    record Lease(long accountId, String sessionId, long sequence, Long questionId, boolean active, Instant leaseExpiresAt) { }

    @Select("SELECT * FROM ma_tablet_presence WHERE account_id=#{account} AND session_id=#{session}")
    Lease lease(@Param("account") long account, @Param("session") String session);

    @Select("SELECT * FROM ma_tablet_presence WHERE account_id=#{account}")
    List<Lease> leases(@Param("account") long account);

    @Insert("INSERT INTO ma_tablet_presence(account_id,session_id,sequence,question_id,active,lease_expires_at) "
            + "VALUES(#{accountId},#{sessionId},#{sequence},#{questionId},#{active},#{leaseExpiresAt}) "
            + "ON CONFLICT(account_id,session_id) DO UPDATE SET sequence=EXCLUDED.sequence,question_id=EXCLUDED.question_id, "
            + "active=EXCLUDED.active,lease_expires_at=EXCLUDED.lease_expires_at "
            + "WHERE ma_tablet_presence.sequence < EXCLUDED.sequence")
    int upsert(Lease lease);
}
