package com.betterlearning.server.learning.mapper;

import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface FileRetentionMapper {
    @Insert("INSERT INTO ma_file_gc(file_ref,state) VALUES (#{fileRef},'TRACKED') "
            + "ON CONFLICT (file_ref) DO NOTHING")
    void register(@Param("fileRef") String fileRef);

    @Insert("INSERT INTO ma_file_gc(file_ref,state,hold_reason) VALUES (#{fileRef},'HOLD','UNKNOWN') "
            + "ON CONFLICT (file_ref) DO UPDATE SET state=CASE WHEN ma_file_gc.state='DELETED' "
            + "THEN 'DELETED' ELSE 'HOLD' END, hold_reason=CASE WHEN ma_file_gc.state='DELETED' "
            + "THEN ma_file_gc.hold_reason ELSE 'UNKNOWN' END, updated_at=CURRENT_TIMESTAMP")
    void holdUnknown(@Param("fileRef") String fileRef);

    @Select("SELECT state FROM ma_file_gc WHERE file_ref=#{fileRef} FOR UPDATE")
    String lockState(@Param("fileRef") String fileRef);

    @Select("SELECT state FROM ma_file_gc WHERE file_ref=#{fileRef}")
    String state(@Param("fileRef") String fileRef);

    @Select("SELECT hold_reason FROM ma_file_gc WHERE file_ref=#{fileRef}")
    String ledgerReason(@Param("fileRef") String fileRef);

    @Select("SELECT state='DELETED' FROM ma_file_gc WHERE file_ref=#{fileRef}")
    Boolean tombstoned(@Param("fileRef") String fileRef);

    @Select("SELECT physical_deleted_at IS NOT NULL FROM ma_file_gc WHERE file_ref=#{fileRef}")
    Boolean physicallyDeleted(@Param("fileRef") String fileRef);

    @Select("SELECT CASE "
            + "WHEN EXISTS (SELECT 1 FROM question_answer WHERE file_ref=#{fileRef}) THEN 'CURRENT' "
            + "WHEN EXISTS (SELECT 1 FROM tutor_turn WHERE file_ref=#{fileRef}) THEN 'V1' "
            + "WHEN EXISTS (SELECT 1 FROM ma_request q "
            + "LEFT JOIN ma_source s ON s.source_id=q.snapshot_source_id "
            + "LEFT JOIN ma_source_version v ON v.source_id=q.snapshot_source_id "
            + "AND v.version=q.snapshot_version "
            + "WHERE q.snapshot_file_ref=#{fileRef} AND (s.source_id IS NULL OR v.source_id IS NULL "
            + "OR v.purged_at IS NULL OR s.status='ACTIVE' "
            + "OR q.status IN ('WAITING_INPUT','QUEUED','RUNNING','CANCELLING'))) THEN 'SNAPSHOT' "
            + "WHEN EXISTS (SELECT 1 FROM ma_source_version v JOIN ma_source s ON s.source_id=v.source_id "
            + "WHERE v.file_ref=#{fileRef} AND s.status='ACTIVE' AND v.purged_at IS NULL) "
            + "THEN 'ACTIVE_VERSION' "
            + "WHEN EXISTS (SELECT 1 FROM ma_source_version WHERE file_ref=#{fileRef} AND purged_at IS NULL) "
            + "THEN 'UNPURGED_VERSION' ELSE NULL END")
    String holdReason(@Param("fileRef") String fileRef);

    @Select("SELECT EXISTS (SELECT 1 FROM ma_source_version WHERE file_ref=#{fileRef})")
    boolean knownSourceFile(@Param("fileRef") String fileRef);

    @Select("SELECT v.file_ref FROM ma_source_version v "
            + "JOIN ma_source s ON s.source_id=v.source_id "
            + "LEFT JOIN ma_file_gc gc ON gc.file_ref=v.file_ref "
            + "WHERE v.file_ref IS NOT NULL AND v.purged_at IS NOT NULL "
            + "AND (gc.file_ref IS NULL OR (gc.state<>'DELETED' OR gc.physical_deleted_at IS NULL)) "
            + "AND (gc.hold_reason IS NULL OR gc.hold_reason<>'UNKNOWN') "
            + "GROUP BY v.file_ref ORDER BY MIN(gc.updated_at) NULLS FIRST,v.file_ref LIMIT #{limit}")
    List<String> pendingFileRefs(@Param("limit") int limit);

    @Select("SELECT CASE WHEN physical_deleted_at IS NOT NULL THEN 'DELETED' "
            + "WHEN state='DELETED' THEN COALESCE(hold_reason,'PENDING_IO') "
            + "ELSE COALESCE(hold_reason,'TRACKED') END AS outcome, COUNT(*) AS total "
            + "FROM ma_file_gc GROUP BY 1")
    List<Map<String, Object>> groupedCounts();

    @Select("SELECT refs.file_ref FROM ("
            + "SELECT file_ref FROM question_answer UNION SELECT file_ref FROM tutor_turn WHERE file_ref IS NOT NULL "
            + "UNION SELECT snapshot_file_ref AS file_ref FROM ma_request WHERE snapshot_file_ref IS NOT NULL "
            + "UNION SELECT file_ref FROM ma_source_version WHERE file_ref IS NOT NULL"
            + ") refs LEFT JOIN ma_file_gc gc ON gc.file_ref=refs.file_ref "
            + "WHERE gc.physical_deleted_at IS NULL ORDER BY refs.file_ref LIMIT #{limit}")
    List<String> knownFileRefsForAudit(@Param("limit") int limit);

    @Update("UPDATE ma_file_gc SET state='HOLD',hold_reason=#{reason},updated_at=CURRENT_TIMESTAMP "
            + "WHERE file_ref=#{fileRef} AND state<>'DELETED'")
    void hold(@Param("fileRef") String fileRef, @Param("reason") String reason);

    @Update("UPDATE ma_file_gc SET state='DELETED',hold_reason=NULL,updated_at=CURRENT_TIMESTAMP "
            + "WHERE file_ref=#{fileRef} AND state<>'DELETED'")
    void tombstone(@Param("fileRef") String fileRef);

    @Update("UPDATE ma_file_gc SET physical_deleted_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP "
            + "WHERE file_ref=#{fileRef} AND state='DELETED'")
    void markPhysicallyDeleted(@Param("fileRef") String fileRef);

    @Update("UPDATE ma_file_gc SET hold_reason='FILE_IO',updated_at=CURRENT_TIMESTAMP "
            + "WHERE file_ref=#{fileRef} AND state='DELETED' AND physical_deleted_at IS NULL")
    void fileIoFailed(@Param("fileRef") String fileRef);

    @Update("UPDATE ma_file_gc SET hold_reason='UNKNOWN',updated_at=CURRENT_TIMESTAMP "
            + "WHERE file_ref=#{fileRef} AND state='DELETED' AND physical_deleted_at IS NULL")
    void deletedUnknownHold(@Param("fileRef") String fileRef);
}
