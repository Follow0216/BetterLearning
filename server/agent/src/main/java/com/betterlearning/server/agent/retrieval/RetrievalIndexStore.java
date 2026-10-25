package com.betterlearning.server.agent.retrieval;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.betterlearning.server.agent.retrieval.ranking.HybridRanker.Candidate;
import com.betterlearning.server.multiagent.MultiAgentModels.Anchor;

/** agent 的索引存储端口；app 使用现有 MyBatis 装配，外部模型调用不持有事务。 */
public interface RetrievalIndexStore {
    record Job(String eventId, long accountId, String sourceId, long version, String kind, long fence,
            long indexRevision, int attempts) {
        public Job(String eventId, long accountId, String sourceId, long version, String kind, long fence) {
            this(eventId, accountId, sourceId, version, kind, fence, 1, 1);
        }
        public Job(String eventId, long accountId, String sourceId, long version, String kind, long fence,
                long indexRevision) {
            this(eventId, accountId, sourceId, version, kind, fence, indexRevision, 1);
        }
    }
    record Chunk(Anchor anchor, String questionId, String text, Set<String> methodTags,
            Set<String> terms, double[] embedding) { }
    record QueryEmbeddingKey(long accountId, String runId, String stepId, String querySha256,
            String model, int dimensions) { }
    record QueryEmbeddingResult(double[] vector, int requestCount, Long totalTokens) { }
    record QueryEmbeddingDecision(boolean shouldCall, QueryEmbeddingResult confirmed) { }

    Optional<Job> claim();
    boolean complete(Job job, List<Chunk> chunks, String model, int dimensions);
    void fail(Job job, String reason);
    boolean renew(Job job);
    /** 独立事务提交外呼意图后才允许发起 OCR 或 embedding 请求。 */
    boolean beginExternalCall(Job job);
    /** 已拿到外呼结果且已记录可确认用量后确认本次外呼。 */
    boolean confirmExternalCall(Job job);
    void recordUsage(Job job, int ocrCalls, int embeddingRequests, Long tokens, boolean uncertain);
    /** 唯一新插入的 step 才获得外呼权；已有意图必须按未知结果失败。 */
    QueryEmbeddingDecision beginQueryEmbedding(QueryEmbeddingKey key);
    /** 仅已确认记录可读；缺失返回 empty，未确认或参数不符抛 OUTCOME_UNKNOWN。 */
    Optional<QueryEmbeddingResult> readQueryEmbedding(QueryEmbeddingKey key);
    /** 持久向量与已知用量。失败意味着结果未知，调用方不可再次外呼。 */
    boolean confirmQueryEmbedding(QueryEmbeddingKey key, double[] vector, int requestCount, Long totalTokens);
    void obsolete(Job job);
    List<Candidate> candidates(long accountId, Set<String> terms, Set<String> methodTags,
            double[] queryVector, String model, int dimensions);
    String retrievalStepId(long accountId, String runId);
    boolean authorized(long accountId, String sourceId, long version, String anchorId);
}
