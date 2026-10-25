package com.betterlearning.server.learning.mapper;

import java.time.Instant;
import org.apache.ibatis.annotations.*;

/** Privileged recovery uses identities and timestamps only, never model inputs or provider responses. */
public interface LegacyGenerationRecoveryMapper {
    record State(String status, String fileRef, Instant updatedAt, String failureCode) { }
    record Audit(String fingerprint) { }

    @Select("SELECT mode FROM ma_rollout_gate WHERE singleton_id=1 FOR SHARE")
    String sharedMode();

    @Select("SELECT account_id FROM app_user WHERE account_id=#{account} FOR UPDATE")
    Long lockAccount(@Param("account") long account);

    @Select("SELECT status,file_ref,updated_at,failure_code FROM tutor_turn WHERE account_id=#{account} "
            + "AND question_id=#{question} AND turn_id=#{turn}")
    State state(@Param("account") long account, @Param("question") long question, @Param("turn") String turn);

    @Select("SELECT clock_timestamp()")
    Instant databaseNow();

    @Select("SELECT fingerprint FROM ma_legacy_generation_recovery WHERE operation_id=#{operation}")
    Audit audit(@Param("operation") String operation);

    @Update("UPDATE tutor_turn SET status='FAILED',failure_code='GENERATION_OUTCOME_UNKNOWN',"
            + "failure_message='生成结果未知，已停止此轮重试',updated_at=clock_timestamp() "
            + "WHERE account_id=#{account} AND question_id=#{question} AND turn_id=#{turn} "
            + "AND status='GENERATING' AND file_ref=#{file} AND updated_at=#{observed}")
    int recover(@Param("account") long account, @Param("question") long question, @Param("turn") String turn,
            @Param("file") String file, @Param("observed") Instant observed);

    @Insert("INSERT INTO ma_legacy_generation_recovery(operation_id,turn_id,account_id,question_id,file_ref,"
            + "observed_updated_at,fingerprint,operator_ref,incident_ref,database_principal) "
            + "VALUES(#{operation},#{turn},#{account},#{question},#{file},#{observed},#{fingerprint},"
            + "#{operator},#{incident},current_user)")
    int record(@Param("operation") String operation, @Param("turn") String turn, @Param("account") long account,
            @Param("question") long question, @Param("file") String file, @Param("observed") Instant observed,
            @Param("fingerprint") String fingerprint, @Param("operator") String operator, @Param("incident") String incident);
}
