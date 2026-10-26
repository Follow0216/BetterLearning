package com.betterlearning.server.learning.mapper;

import java.time.Instant;
import java.util.List;
import com.betterlearning.server.learning.model.dataobject.MultiAgentRows.*;
import com.betterlearning.server.multiagent.MultiAgentModels.*;
import org.apache.ibatis.annotations.*;

/** 有界SQL操作，调用方持有账号行锁；CAS与当前run条件在SQL中复核。 */
public interface MultiAgentMapper {
    String ATTEMPT_COLUMNS = "a.attempt_id,a.step_id,a.attempt_no,a.status,a.provider_call_state,a.fence,"
            + "a.started_at,a.ended_at,a.output,a.output_digest";

    @Select("SELECT 1 FROM (SELECT pg_advisory_xact_lock(67191025)) locked")
    Integer lockCallBudget();

    @Select("SELECT COUNT(*) FROM ma_attempt a JOIN ma_step s ON s.step_id=a.step_id JOIN ma_run r ON r.run_id=s.run_id "
            + "WHERE (CAST(#{account} AS bigint) IS NULL OR r.account_id=#{account}) AND a.provider_deadline_at>CURRENT_TIMESTAMP "
            + "AND a.provider_finished_at IS NULL")
    int inFlight(@Param("account") Long account);

    @Update("UPDATE ma_attempt SET provider_finished_at=CURRENT_TIMESTAMP WHERE attempt_id=#{id} "
            + "AND provider_deadline_at IS NOT NULL AND provider_finished_at IS NULL")
    int finishProvider(@Param("id") String id);

    @Select("SELECT * FROM ma_cursor WHERE cursor_id=#{id} AND account_id=#{account} AND question_id=#{question} AND expires_at>CURRENT_TIMESTAMP")
    CursorRow cursor(@Param("id") String id, @Param("account") long account, @Param("question") long question);

    @Insert("INSERT INTO ma_cursor(cursor_id,account_id,question_id,before_time,before_id) VALUES(#{cursorId},#{accountId},#{questionId},#{beforeTime},#{beforeId})")
    int insertCursor(CursorRow row);

    @Select("SELECT account_id FROM app_user WHERE account_id=#{account} FOR UPDATE")
    Long lock(@Param("account") long account);

    @Select("SELECT * FROM ma_request WHERE account_id=#{account} AND request_id=#{id}")
    RequestRow request(@Param("account") long account, @Param("id") String id);

    @Select("SELECT * FROM ma_request WHERE account_id=#{account} AND status IN ('WAITING_INPUT','QUEUED','RUNNING','CANCELLING')")
    List<RequestRow> active(@Param("account") long account);

    @Select("SELECT EXISTS(SELECT 1 FROM tutor_turn WHERE account_id=#{account} AND status IN ('WAITING_ANSWER','GENERATING'))")
    boolean legacyActive(@Param("account") long account);

    @Select("SELECT * FROM ma_request WHERE account_id=#{account} AND question_id=#{question} "
            + "AND (CAST(#{before} AS timestamptz) IS NULL OR (created_at,request_id) < (#{before},#{beforeId})) "
            + "ORDER BY created_at DESC,request_id DESC LIMIT #{limit}")
    List<RequestRow> page(@Param("account") long account, @Param("question") long question,
            @Param("before") Instant before, @Param("beforeId") String beforeId, @Param("limit") int limit);

    @Insert("INSERT INTO ma_request(request_id,account_id,question_id,prompt,mode,snapshot_kind,source_request_id,status,input_deadline_at,context_parent_request_id,conversation_context_json) "
            + "VALUES(#{requestId},#{accountId},#{questionId},#{prompt},#{mode},#{snapshotKind},#{sourceRequestId},'WAITING_INPUT',#{inputDeadlineAt},#{contextParentRequestId},#{conversationContextJson})")
    int insertRequest(RequestRow row);

    @Update("UPDATE ma_request SET snapshot_origin_request_id=#{origin},snapshot_file_ref=#{file},snapshot_sha256=#{sha}, "
            + "snapshot_source_id=#{source},snapshot_version=#{version},revision=revision+1,updated_at=CURRENT_TIMESTAMP "
            + "WHERE account_id=#{account} AND request_id=#{id} AND status='WAITING_INPUT' AND snapshot_file_ref IS NULL")
    int bind(@Param("account") long account, @Param("id") String id, @Param("origin") String origin,
            @Param("file") String file, @Param("sha") String sha, @Param("source") String source, @Param("version") long version);

    @Update("UPDATE ma_request SET current_run_id=#{run},status=#{status},revision=revision+1,updated_at=CURRENT_TIMESTAMP "
            + "WHERE account_id=#{account} AND request_id=#{id}")
    int project(@Param("account") long account, @Param("id") String id, @Param("run") String run, @Param("status") String status);

    @Select("SELECT * FROM ma_run WHERE account_id=#{account} AND run_id=#{id}")
    RunRow run(@Param("account") long account, @Param("id") String id);

    @Select("SELECT * FROM ma_run WHERE account_id=#{account} AND request_id=#{id} ORDER BY run_no")
    List<RunRow> runs(@Param("account") long account, @Param("id") String id);

    @Insert("INSERT INTO ma_run(run_id,request_id,account_id,run_no,retry_of_run_id,status,queue_deadline_at,run_deadline_at) "
            + "VALUES(#{runId},#{requestId},#{accountId},#{runNo},#{retryOfRunId},'QUEUED',#{queueDeadlineAt},#{runDeadlineAt})")
    int insertRun(RunRow row);

    @Update("UPDATE ma_run SET status=#{status},reason=#{reason},revision=revision+1,updated_at=CURRENT_TIMESTAMP, "
            + "cancel_requested_at=CASE WHEN #{status}='CANCELLING' THEN CURRENT_TIMESTAMP ELSE cancel_requested_at END "
            + "WHERE account_id=#{account} AND run_id=#{id} AND revision=#{revision} "
            + "AND status IN ('QUEUED','RUNNING','CANCELLING') "
            + "AND EXISTS(SELECT 1 FROM ma_request q WHERE q.account_id=#{account} AND q.current_run_id=#{id})")
    int transition(@Param("account") long account, @Param("id") String id, @Param("revision") long revision,
            @Param("status") String status, @Param("reason") String reason);

    @Select("SELECT * FROM ma_retry WHERE account_id=#{account} AND idempotency_key=#{key}")
    RetryRow retry(@Param("account") long account, @Param("key") String key);

    @Insert("INSERT INTO ma_retry(account_id,idempotency_key,request_id,target_run_id,run_id) "
            + "VALUES(#{accountId},#{idempotencyKey},#{requestId},#{targetRunId},#{runId})")
    int insertRetry(RetryRow row);

    @Select("SELECT * FROM ma_step WHERE run_id=#{run} AND role=#{role} AND sequence=#{sequence}")
    StepRow step(@Param("run") String run, @Param("role") String role, @Param("sequence") int sequence);

    @Select("SELECT * FROM ma_step WHERE run_id=#{run} ORDER BY sequence,step_id")
    List<StepRow> steps(@Param("run") String run);

    @Insert("INSERT INTO ma_step(step_id,run_id,role,sequence,status,input_digest) "
            + "VALUES(#{stepId},#{runId},#{role},#{sequence},'RUNNING',#{inputDigest})")
    int insertStep(StepRow row);

    @Update("UPDATE ma_step SET status=#{status},output_digest=#{digest},revision=revision+1 WHERE step_id=#{id}")
    int stepStatus(@Param("id") String id, @Param("status") String status, @Param("digest") String digest);

    @Select("SELECT " + ATTEMPT_COLUMNS + " FROM ma_attempt a WHERE a.step_id=#{step} ORDER BY a.attempt_no DESC LIMIT 1")
    Attempt latestAttempt(@Param("step") String step);

    @Select("SELECT " + ATTEMPT_COLUMNS + " FROM ma_attempt a JOIN ma_step s ON s.step_id=a.step_id WHERE s.run_id=#{run} AND a.attempt_id=#{id}")
    Attempt attempt(@Param("run") String run, @Param("id") String id);

    @Select("SELECT " + ATTEMPT_COLUMNS + " FROM ma_attempt a JOIN ma_step s ON s.step_id=a.step_id WHERE s.run_id=#{run} ORDER BY a.started_at,a.attempt_id")
    List<Attempt> attempts(@Param("run") String run);

    @Select("SELECT " + ATTEMPT_COLUMNS + " FROM ma_attempt a JOIN ma_step s ON s.step_id=a.step_id "
            + "WHERE s.run_id=#{run} AND a.provider_call_state='CALL_INTENT' AND a.status='RUNNING' "
            + "AND a.provider_deadline_at<=#{now}")
    List<Attempt> expiredIntents(@Param("run") String run, @Param("now") Instant now);

    @Insert("INSERT INTO ma_attempt(attempt_id,step_id,attempt_no,status,provider_call_state,fence) "
            + "VALUES(#{attemptId},#{stepId},#{attemptNo},'RUNNING','NOT_SENT',#{fence})")
    int insertAttempt(Attempt row);

    @Update("UPDATE ma_attempt SET provider_call_state='CALL_INTENT',provider_deadline_at=CURRENT_TIMESTAMP + INTERVAL '120 seconds' WHERE attempt_id=#{id} AND fence=#{fence} "
            + "AND status='RUNNING' AND provider_call_state='NOT_SENT'")
    int intent(@Param("id") String id, @Param("fence") long fence);

    @Update("UPDATE ma_run SET model_calls=model_calls+1 WHERE run_id=#{id} AND model_calls<8 AND status='RUNNING'")
    int incrementCalls(@Param("id") String id);

    @Update("UPDATE ma_attempt SET status=#{status},provider_call_state=#{state},output=#{output},output_digest=#{digest}, "
            + "ended_at=CURRENT_TIMESTAMP WHERE attempt_id=#{id} AND fence=#{fence} AND status='RUNNING'")
    int endAttempt(@Param("id") String id, @Param("fence") long fence, @Param("status") String status,
            @Param("state") String state, @Param("output") String output, @Param("digest") String digest);

    @Update("UPDATE ma_attempt SET fence=fence+1,status='CANCELLED',ended_at=CURRENT_TIMESTAMP "
            + "WHERE step_id IN (SELECT step_id FROM ma_step WHERE run_id=#{run}) AND status='RUNNING'")
    int fence(@Param("run") String run);

    @Update("UPDATE ma_step SET status='CANCELLED',revision=revision+1 WHERE run_id=#{run} AND status IN ('PENDING','RUNNING')")
    int closeSteps(@Param("run") String run);

    @Insert("INSERT INTO ma_evidence(evidence_id,run_id,step_id,type,source_id,version,anchor_id,claim,confidence) "
            + "VALUES(#{evidenceId},#{runId},#{stepId},#{type},#{sourceId},#{version},#{anchorId},#{claim},#{confidence})")
    int insertEvidence(Evidence row);

    @Select("SELECT * FROM ma_evidence WHERE run_id=#{run} ORDER BY evidence_id")
    List<Evidence> evidence(@Param("run") String run);

    @Insert("INSERT INTO ma_result(result_id,run_id,mode,status,body_markdown) VALUES(#{resultId},#{runId},#{mode},'VERIFIED',#{bodyMarkdown})")
    int insertResult(ResultRow row);

    @Select("SELECT * FROM ma_result WHERE run_id=#{run} AND status='VERIFIED'")
    ResultRow result(@Param("run") String run);

    @Insert("INSERT INTO ma_citation(citation_id,result_id,source_id,version,anchor_id,label,source_type) "
            + "VALUES(#{c.citationId},#{result},#{c.sourceId},#{c.version},#{c.anchorId},#{c.label},#{c.sourceType})")
    int insertCitation(@Param("result") String result, @Param("c") Citation citation);

    @Select("SELECT citation_id,source_id,version,anchor_id,label,source_type FROM ma_citation WHERE result_id=#{result} ORDER BY label")
    List<Citation> citations(@Param("result") String result);

    @Insert("INSERT INTO ma_outbox(event_id,account_id,aggregate_type,aggregate_id,aggregate_version,kind,payload_digest,status) "
            + "VALUES(#{eventId},#{accountId},#{aggregateType},#{aggregateId},#{aggregateVersion},#{kind},#{payloadDigest},'PENDING') "
            + "ON CONFLICT(aggregate_type,aggregate_id,aggregate_version,kind,index_revision) DO NOTHING")
    int outbox(OutboxEvent event);

    @Select("SELECT event_id,account_id,aggregate_type,aggregate_id,aggregate_version,kind,payload_digest,status,index_revision "
            + "FROM ma_outbox WHERE status IN ('PENDING','FAILED') ORDER BY created_at,event_id LIMIT #{limit}")
    List<OutboxEvent> outstanding(@Param("limit") int limit);

    @Select("<script>SELECT event_id,account_id,aggregate_type,aggregate_id,aggregate_version,kind,payload_digest,status,index_revision "
            + "FROM ma_outbox WHERE status IN ('PENDING','FAILED') AND kind IN "
            + "<foreach collection='kinds' item='kind' open='(' separator=',' close=')'>#{kind}</foreach> "
            + "ORDER BY created_at,event_id LIMIT #{limit}</script>")
    List<OutboxEvent> outstandingKinds(@Param("kinds") java.util.Set<String> kinds, @Param("limit") int limit);

    @Update("UPDATE ma_outbox SET status='DELIVERED' WHERE event_id=#{id} AND status IN ('PENDING','FAILED')")
    int acknowledge(@Param("id") String id);

    @Select("SELECT DISTINCT q.account_id FROM ma_request q LEFT JOIN ma_run r ON r.run_id=q.current_run_id "
            + "WHERE (q.status='WAITING_INPUT' AND q.input_deadline_at<=#{now}) "
            + "OR (r.status='QUEUED' AND r.queue_deadline_at<=#{now}) "
            + "OR (r.status='RUNNING' AND r.run_deadline_at<=#{now}) "
            + "OR (r.status='RUNNING' AND EXISTS(SELECT 1 FROM ma_attempt a JOIN ma_step s ON s.step_id=a.step_id "
            + "WHERE s.run_id=r.run_id AND a.provider_call_state='CALL_INTENT' AND a.status='RUNNING' AND a.provider_deadline_at<=#{now})) "
            + "OR (r.status='CANCELLING' AND r.cancel_requested_at<=#{cancelBefore}) ORDER BY q.account_id LIMIT #{limit}")
    List<Long> expiredAccounts(@Param("now") Instant now, @Param("cancelBefore") Instant cancelBefore, @Param("limit") int limit);
}
