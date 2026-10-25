package com.betterlearning.server.learning.mapper;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** Only pseudonymous identities and counters leave the retention boundary. */
public interface RetentionMapper {
    record Version(String sourceId, long accountId, long version, String fileRef) { }
    record Tombstone(String sourceId, long accountId) { }
    record Watermark(long sequence, String hash) { }
    record Evidence(long pendingDeletions, long overdueDeletions, long activeRunHolds,
            long purgedVersions, long retiredCandidates, long unclearedFiles, long overdueFiles, long unjournaledDeletions) { }

    @Select("SELECT account_id FROM app_user WHERE account_id=#{account} FOR UPDATE")
    Long lockAccount(@Param("account") long account);

    @Select("SELECT source_id FROM ma_source WHERE source_id=#{source} FOR UPDATE")
    String lockSource(@Param("source") String source);

    @Select("SELECT ma_apply_source_deletion(#{source},#{account},#{deleted},#{sequence},#{hash})")
    boolean revoke(@Param("source") String source, @Param("account") long account,
            @Param("deleted") Instant deleted, @Param("sequence") long sequence, @Param("hash") String hash);

    @Select("SELECT sequence,hash FROM ma_deletion_replay WHERE singleton=TRUE FOR UPDATE")
    Watermark watermark();

    @Update("UPDATE ma_deletion_replay SET sequence=#{sequence},hash=#{hash},replayed_at=CURRENT_TIMESTAMP WHERE singleton=TRUE")
    int watermarkSaved(@Param("sequence") long sequence, @Param("hash") String hash);

    @Select("SELECT source_id,account_id FROM ma_source_deletion ORDER BY checked_at NULLS FIRST,source_id LIMIT #{limit}")
    List<Tombstone> tombstones(@Param("limit") int limit);

    @Select("SELECT v.source_id,s.account_id,v.version,v.file_ref FROM ma_source_version v "
            + "JOIN ma_source s ON s.source_id=v.source_id WHERE s.source_id=#{source} AND s.status='DELETED'")
    List<Version> deletedVersions(@Param("source") String source);

    @Select("SELECT v.source_id,s.account_id,v.version,v.file_ref FROM ma_source_version v "
            + "JOIN ma_source s ON s.source_id=v.source_id WHERE s.status='ACTIVE' AND v.purged_at IS NULL "
            + "AND v.version<>s.current_version AND v.created_at<=#{before} "
            + "AND NOT EXISTS(SELECT 1 FROM ma_request q WHERE q.snapshot_source_id=v.source_id AND q.snapshot_version=v.version) "
            + "AND NOT EXISTS(SELECT 1 FROM ma_citation c WHERE c.source_id=v.source_id AND c.version=v.version) "
            + "AND NOT EXISTS(SELECT 1 FROM ma_evidence e WHERE e.source_id=v.source_id AND e.version=v.version) "
            + "ORDER BY v.created_at,v.source_id,v.version LIMIT #{limit}")
    List<Version> retired(@Param("before") Instant before, @Param("limit") int limit);

    @Select("SELECT EXISTS(SELECT 1 FROM ma_source_version v JOIN ma_source s ON s.source_id=v.source_id "
            + "WHERE v.source_id=#{source} AND v.version=#{version} AND s.status='ACTIVE' AND v.purged_at IS NULL "
            + "AND v.version<>s.current_version AND v.created_at<=#{before} "
            + "AND NOT EXISTS(SELECT 1 FROM ma_request q WHERE q.snapshot_source_id=v.source_id AND q.snapshot_version=v.version) "
            + "AND NOT EXISTS(SELECT 1 FROM ma_citation c WHERE c.source_id=v.source_id AND c.version=v.version) "
            + "AND NOT EXISTS(SELECT 1 FROM ma_evidence e WHERE e.source_id=v.source_id AND e.version=v.version))")
    boolean eligible(@Param("source") String source, @Param("version") long version, @Param("before") Instant before);

    @Select("SELECT ma_scrub_source_version(#{source},#{version})")
    boolean scrub(@Param("source") String source, @Param("version") long version);

    String RELATED = "(SELECT run_id FROM ma_evidence WHERE source_id=#{source} UNION SELECT r.run_id "
            + "FROM ma_run r JOIN ma_request q ON q.account_id=r.account_id AND q.request_id=r.request_id "
            + "WHERE q.snapshot_source_id=#{source})";

    @Update("UPDATE ma_attempt SET output=NULL,output_digest=NULL WHERE step_id IN "
            + "(SELECT step_id FROM ma_step WHERE run_id IN " + RELATED + ") AND step_id IN "
            + "(SELECT s.step_id FROM ma_step s JOIN ma_run r ON r.run_id=s.run_id "
            + "WHERE r.status IN ('SUCCEEDED','FAILED','CANCELLED','TIMED_OUT'))")
    int scrubAttempts(@Param("source") String source);

    @Update("UPDATE ma_result SET body_markdown='' WHERE status='WITHHELD' AND run_id IN " + RELATED
            + " AND run_id IN (SELECT run_id FROM ma_run WHERE status IN ('SUCCEEDED','FAILED','CANCELLED','TIMED_OUT'))")
    int scrubWithheld(@Param("source") String source);

    @Update("UPDATE ma_evidence SET claim='' WHERE run_id IN " + RELATED)
    int scrubClaims(@Param("source") String source);

    @Select("SELECT EXISTS(SELECT 1 FROM ma_run WHERE run_id IN " + RELATED
            + " AND status IN ('QUEUED','RUNNING','CANCELLING'))")
    boolean activeRunHold(@Param("source") String source);

    @Update("UPDATE ma_source_deletion SET checked_at=CURRENT_TIMESTAMP,hold_reason=#{hold},"
            + "database_purged_at=CASE WHEN CAST(#{hold} AS varchar) IS NULL THEN CURRENT_TIMESTAMP ELSE NULL END "
            + "WHERE source_id=#{source}")
    int checked(@Param("source") String source, @Param("hold") String hold);

    @Select("SELECT (SELECT count(*) FROM ma_source_deletion WHERE database_purged_at IS NULL) pending_deletions,"
            + "(SELECT count(*) FROM ma_source_deletion WHERE database_purged_at IS NULL "
            + "AND deleted_at<CURRENT_TIMESTAMP - INTERVAL '7 days') overdue_deletions,"
            + "(SELECT count(*) FROM ma_source_deletion WHERE hold_reason='ACTIVE_RUN') active_run_holds,"
            + "(SELECT count(*) FROM ma_source_version WHERE purged_at IS NOT NULL) purged_versions,"
            + "(SELECT count(*) FROM ma_source_version v JOIN ma_source s ON s.source_id=v.source_id "
            + "WHERE v.purged_at IS NULL AND s.status='ACTIVE' AND v.version<>s.current_version "
            + "AND v.created_at<CURRENT_TIMESTAMP - INTERVAL '30 days') retired_candidates,"
            + "(SELECT count(DISTINCT v.file_ref) FROM ma_source_version v JOIN ma_source_deletion d ON d.source_id=v.source_id "
            + "LEFT JOIN ma_file_gc f ON f.file_ref=v.file_ref WHERE v.file_ref IS NOT NULL AND f.physical_deleted_at IS NULL) uncleared_files,"
            + "(SELECT count(DISTINCT v.file_ref) FROM ma_source_version v JOIN ma_source_deletion d ON d.source_id=v.source_id "
            + "LEFT JOIN ma_file_gc f ON f.file_ref=v.file_ref WHERE v.file_ref IS NOT NULL AND f.physical_deleted_at IS NULL "
            + "AND d.deleted_at<CURRENT_TIMESTAMP - INTERVAL '7 days') overdue_files,"
            + "(SELECT count(*) FROM ma_source s WHERE s.status='DELETED' AND NOT EXISTS "
            + "(SELECT 1 FROM ma_source_deletion d WHERE d.source_id=s.source_id)) unjournaled_deletions")
    Evidence evidence();
}
