package com.betterlearning.server.learning.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 单行持久门禁；共享锁保持到写入事务结束，切换使用排他锁。 */
public interface RolloutGateMapper {
    @Select("SELECT mode FROM ma_rollout_gate WHERE singleton_id=1 FOR SHARE")
    String sharedMode();

    @Select("SELECT mode FROM ma_rollout_gate WHERE singleton_id=1 FOR UPDATE")
    String exclusiveMode();

    @Select("SELECT EXISTS(SELECT 1 FROM tutor_turn WHERE status IN ('WAITING_ANSWER','GENERATING') "
            + "UNION ALL SELECT 1 FROM ma_request WHERE status IN ('WAITING_INPUT','QUEUED','RUNNING','CANCELLING'))")
    boolean anyActive();

    @Update("UPDATE ma_rollout_gate SET mode=#{mode},revision=revision+1,updated_at=CURRENT_TIMESTAMP "
            + "WHERE singleton_id=1")
    int setMode(@Param("mode") String mode);
}
