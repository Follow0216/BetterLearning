package com.betterlearning.server.agent.retrieval.ranking;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.betterlearning.server.agent.config.EmbeddingProperties;
import com.betterlearning.server.agent.embedding.HttpEmbeddingClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Manually invoked real-embedding seed evaluation. This class is not a JUnit test. */
public final class MathRetrievalEvaluation {
    private static final ObjectMapper JSON = new ObjectMapper();

    private MathRetrievalEvaluation() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: MathRetrievalEvaluation <fixture.json> <output-dir>");
        }
        Path fixturePath = Path.of(args[0]);
        Path outputDir = Path.of(args[1]);
        JsonNode fixture = JSON.readTree(Files.readString(fixturePath, StandardCharsets.UTF_8));
        List<Document> documents = readDocuments(fixture.path("documents"));
        List<QueryCase> queries = readQueries(fixture.path("queries"));
        validateFixture(documents, queries);

        EmbeddingProperties properties = propertiesFromEnvironment();
        List<String> inputs = new ArrayList<>();
        documents.forEach(document -> inputs.add(document.text()));
        queries.forEach(query -> inputs.add(query.text()));
        Instant started = Instant.now();
        HttpEmbeddingClient.EmbeddingResult embedding = new HttpEmbeddingClient(properties)
                .embedWithStats(inputs);
        long embeddingMillis = Duration.between(started, Instant.now()).toMillis();
        List<double[]> vectors = embedding.vectors();

        Files.createDirectories(outputDir);
        writeMetadata(outputDir, fixturePath, fixture, properties, documents.size(), queries.size(),
                embedding, embeddingMillis);
        Metrics vectorMetrics = new Metrics();
        Metrics hybridMetrics = new Metrics();
        Map<String, Metrics> vectorByFamily = new LinkedHashMap<>();
        Map<String, Metrics> hybridByFamily = new LinkedHashMap<>();
        List<String> caseLines = new ArrayList<>();
        HybridRanker ranker = new HybridRanker();
        for (int queryIndex = 0; queryIndex < queries.size(); queryIndex++) {
            QueryCase query = queries.get(queryIndex);
            double[] queryVector = vectors.get(documents.size() + queryIndex);
            List<ScoredDocument> scored = new ArrayList<>();
            List<HybridRanker.Candidate> candidates = new ArrayList<>();
            Set<String> queryTerms = ChineseMathTerms.terms(query.text());
            Set<String> queryMethods = ChineseMathTerms.methods(query.text(), query.methodTags());
            for (int documentIndex = 0; documentIndex < documents.size(); documentIndex++) {
                Document document = documents.get(documentIndex);
                double similarity = cosine(queryVector, vectors.get(documentIndex));
                Set<String> documentTerms = ChineseMathTerms.terms(document.text());
                Set<String> documentMethods = ChineseMathTerms.methods(document.text(),
                        document.methodTags());
                double lexical = overlap(queryTerms, documentTerms)
                        + overlap(queryMethods, documentMethods);
                scored.add(new ScoredDocument(document, similarity));
                candidates.add(new HybridRanker.Candidate(document.id(), 1, document.id(),
                        HybridRanker.SourceType.valueOf(document.type()), document.questionId(),
                        document.text(), document.methodTags(), similarity, lexical));
            }
            scored.sort(Comparator.comparingDouble(ScoredDocument::similarity).reversed()
                    .thenComparing(item -> item.document().id()));
            List<String> vectorTop = scored.stream().limit(5).map(item -> item.document().id()).toList();
            long rankStarted = System.nanoTime();
            HybridRanker.Ranking ranking = ranker.rank(
                    new HybridRanker.Query(query.text(), query.methodTags()), candidates,
                    HybridRanker.Limits.defaults(), null);
            long rankNanos = System.nanoTime() - rankStarted;
            List<String> hybridTop = ranking.evidence().stream()
                    .map(item -> item.candidate().sourceId()).toList();
            vectorMetrics.add(query.relevantIds(), vectorTop);
            hybridMetrics.add(query.relevantIds(), hybridTop);
            vectorByFamily.computeIfAbsent(query.family(), ignored -> new Metrics())
                    .add(query.relevantIds(), vectorTop);
            hybridByFamily.computeIfAbsent(query.family(), ignored -> new Metrics())
                    .add(query.relevantIds(), hybridTop);
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("caseId", query.id());
            line.put("reviewStatus", "PENDING");
            line.put("family", query.family());
            line.put("relevantIds", query.relevantIds().stream().sorted().toList());
            line.put("hardNegativeIds", query.hardNegativeIds().stream().sorted().toList());
            line.put("vectorTop5", scored.stream().limit(5).map(item -> Map.of(
                    "id", item.document().id(), "cosine", item.similarity(),
                    "authority", item.document().authority())).toList());
            line.put("hybridTop5", ranking.evidence().stream().map(item -> Map.of(
                    "id", item.candidate().sourceId(), "rrf", item.reciprocalRankScore(),
                    "matchedMethods", item.matchedMethods().stream().sorted().toList(),
                    "matchedTerms", item.matchedTerms().stream().sorted().toList(),
                    "authority", authority(documents, item.candidate().sourceId()))).toList());
            line.put("hybridCitationSuggestions", ranking.citationSuggestions());
            line.put("vectorRecallAt5", recall(query.relevantIds(), vectorTop));
            line.put("hybridRecallAt5", recall(query.relevantIds(), hybridTop));
            line.put("vectorHardNegativesAt5", overlap(query.hardNegativeIds(), Set.copyOf(vectorTop)));
            line.put("hybridHardNegativesAt5", overlap(query.hardNegativeIds(), Set.copyOf(hybridTop)));
            line.put("hybridRankNanos", rankNanos);
            caseLines.add(JSON.writeValueAsString(line));
        }
        Files.write(outputDir.resolve("evaluation-cases.jsonl"), caseLines, StandardCharsets.UTF_8);
        Files.writeString(outputDir.resolve("evaluation-summary.md"), summary(documents.size(),
                queries.size(), embeddingMillis, vectorMetrics, hybridMetrics, vectorByFamily,
                hybridByFamily), StandardCharsets.UTF_8);
        System.out.println("Wrote evaluation outputs to " + outputDir.toAbsolutePath());
    }

    private static EmbeddingProperties propertiesFromEnvironment() {
        EmbeddingProperties properties = new EmbeddingProperties();
        properties.setEnabled(true);
        properties.setBaseUrl(requiredEnv("APP_AGENT_EMBEDDING_BASE_URL"));
        properties.setApiKey(requiredEnv("APP_AGENT_EMBEDDING_API_KEY"));
        properties.setModel(requiredEnv("APP_AGENT_EMBEDDING_MODEL"));
        properties.setDimensions(Integer.parseInt(requiredEnv("APP_AGENT_EMBEDDING_DIMENSIONS")));
        properties.setBatchSize(Integer.parseInt(System.getenv().getOrDefault(
                "APP_AGENT_EMBEDDING_BATCH_SIZE", "10")));
        properties.setTimeoutSeconds(Integer.parseInt(System.getenv().getOrDefault(
                "APP_AGENT_EMBEDDING_TIMEOUT_SECONDS", "60")));
        properties.validate();
        return properties;
    }

    private static String requiredEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing required environment variable: " + name);
        }
        return value;
    }

    private static List<Document> readDocuments(JsonNode nodes) {
        List<Document> result = new ArrayList<>();
        for (JsonNode node : nodes) {
            result.add(new Document(required(node, "id"), required(node, "questionId"),
                    required(node, "type"), required(node, "text"), strings(node.path("methodTags")),
                    required(node, "authority")));
        }
        return List.copyOf(result);
    }

    private static List<QueryCase> readQueries(JsonNode nodes) {
        List<QueryCase> result = new ArrayList<>();
        for (JsonNode node : nodes) {
            result.add(new QueryCase(required(node, "id"), required(node, "family"),
                    required(node, "text"), strings(node.path("methodTags")),
                    strings(node.path("relevantIds")), strings(node.path("hardNegativeIds"))));
        }
        return List.copyOf(result);
    }

    private static String required(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw new IllegalArgumentException("invalid fixture field: " + field);
        }
        return value.textValue();
    }

    private static Set<String> strings(JsonNode node) {
        if (!node.isArray()) {
            throw new IllegalArgumentException("fixture field must be an array");
        }
        Set<String> result = new HashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.textValue().isBlank() || !result.add(item.textValue())) {
                throw new IllegalArgumentException("invalid or duplicate fixture array value");
            }
        }
        return Set.copyOf(result);
    }

    private static void validateFixture(List<Document> documents, List<QueryCase> queries) {
        if (documents.size() != 25 || queries.size() != 20
                || queries.stream().filter(query -> query.relevantIds().isEmpty()).count() != 5) {
            throw new IllegalArgumentException("unexpected seed fixture size or no-hit count");
        }
        Set<String> documentIds = new HashSet<>();
        for (Document document : documents) {
            if (!documentIds.add(document.id())) {
                throw new IllegalArgumentException("duplicate document id");
            }
            HybridRanker.SourceType.valueOf(document.type());
        }
        Set<String> queryIds = new HashSet<>();
        for (QueryCase query : queries) {
            if (!queryIds.add(query.id()) || !documentIds.containsAll(query.relevantIds())
                    || !documentIds.containsAll(query.hardNegativeIds())) {
                throw new IllegalArgumentException("invalid query labels");
            }
        }
    }

    private static double cosine(double[] left, double[] right) {
        if (left.length != right.length) {
            throw new IllegalArgumentException("embedding dimensions differ");
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        if (leftNorm == 0 || rightNorm == 0) {
            throw new IllegalArgumentException("zero embedding vector");
        }
        return dot / Math.sqrt(leftNorm * rightNorm);
    }

    private static int overlap(Set<String> left, Set<String> right) {
        Set<String> copy = new HashSet<>(left);
        copy.retainAll(right);
        return copy.size();
    }

    private static String authority(List<Document> documents, String id) {
        return documents.stream().filter(document -> document.id().equals(id)).findFirst()
                .orElseThrow().authority();
    }

    private static double recall(Set<String> relevant, List<String> retrieved) {
        return relevant.isEmpty() ? 0 : (double) overlap(relevant, Set.copyOf(retrieved))
                / relevant.size();
    }

    private static void writeMetadata(Path outputDir, Path fixturePath, JsonNode fixture,
            EmbeddingProperties properties, int documentCount, int queryCount,
            HttpEmbeddingClient.EmbeddingResult embedding, long embeddingMillis)
            throws IOException, NoSuchAlgorithmException {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("fixtureId", required(fixture, "fixtureId"));
        metadata.put("fixtureSha256", hex(MessageDigest.getInstance("SHA-256")
                .digest(Files.readAllBytes(fixturePath))));
        metadata.put("evaluatedAt", Instant.now().toString());
        metadata.put("configuredModel", properties.getModel());
        metadata.put("responseModel", embedding.responseModel());
        metadata.put("dimensions", properties.getDimensions());
        metadata.put("documentCount", documentCount);
        metadata.put("queryCount", queryCount);
        metadata.put("embeddingInputCount", documentCount + queryCount);
        metadata.put("embeddingRequestCount", embedding.requestCount());
        metadata.put("promptTokens", embedding.promptTokens());
        metadata.put("totalTokens", embedding.totalTokens());
        metadata.put("batchedEmbeddingLatencyMs", embeddingMillis);
        metadata.put("vectorSimilarity", "cosine");
        metadata.put("hybridVectorThreshold", null);
        metadata.put("sampleStatus", "public hand-crafted development seed; not expert dual-labelled");
        JSON.writerWithDefaultPrettyPrinter().writeValue(outputDir.resolve("evaluation-metadata.json")
                .toFile(), metadata);
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static String summary(int documentCount, int queryCount, long embeddingMillis,
            Metrics vector, Metrics hybrid, Map<String, Metrics> vectorByFamily,
            Map<String, Metrics> hybridByFamily) {
        StringBuilder result = new StringBuilder("# 数学检索开发种子评测\n\n"
                + "手工构造公开种子；未由两名专家标注，不能代表批准的 100 案例质量门槛。"
                + "未使用盲测标签调阈值；混合排序未设置向量阈值。\n\n"
                + "文档 " + documentCount + "，查询 " + queryCount + "，批量 embedding 总耗时 "
                + embeddingMillis + " ms。请求数、模型与供应商用量见 metadata。\n\n"
                + "| 指标 | 纯向量 top5（无拒绝机制） | 受控词项/方法 + RRF |\n"
                + "| --- | ---: | ---: |\n"
                + "| 有命中样本方法 Recall@5 宏平均 | " + percent(vector.meanRecall()) + " | "
                + percent(hybrid.meanRecall()) + " |\n"
                + "| 有命中样本 Hit@5 | " + percent(vector.hitRate()) + " | "
                + percent(hybrid.hitRate()) + " |\n"
                + "| 无命中误引率（返回至少一条） | " + percent(vector.noHitFalseCitationRate())
                + " | " + percent(hybrid.noHitFalseCitationRate()) + " |\n\n");
        result.append("| 家族 | 查询数 | 纯向量 Recall@5 / Hit@5 | 混合 Recall@5 / Hit@5 |\n")
                .append("| --- | ---: | ---: | ---: |\n");
        for (Map.Entry<String, Metrics> entry : vectorByFamily.entrySet()) {
            Metrics vectorFamily = entry.getValue();
            Metrics hybridFamily = hybridByFamily.get(entry.getKey());
            result.append("| ").append(entry.getKey()).append(" | ")
                    .append(vectorFamily.hitCases + vectorFamily.noHitCases).append(" | ");
            if (vectorFamily.hitCases == 0) {
                result.append("无命中 | 无命中 |\n");
            } else {
                result.append(percent(vectorFamily.meanRecall())).append(" / ")
                        .append(percent(vectorFamily.hitRate())).append(" | ")
                        .append(percent(hybridFamily.meanRecall())).append(" / ")
                        .append(percent(hybridFamily.hitRate())).append(" |\n");
            }
        }
        result.append("\n纯向量 top5 无拒绝机制，其无命中误引率仅展示直接展示 top5 的风险。")
                .append("错误旧解若标为相关，也只能用于反思，不能作为解题权威。\n");
        return result.toString();
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    private record Document(String id, String questionId, String type, String text,
            Set<String> methodTags, String authority) {
    }

    private record QueryCase(String id, String family, String text, Set<String> methodTags,
            Set<String> relevantIds, Set<String> hardNegativeIds) {
    }

    private record ScoredDocument(Document document, double similarity) {
    }

    private static final class Metrics {
        private int hitCases;
        private int hits;
        private double recallSum;
        private int noHitCases;
        private int falseCitations;

        private void add(Set<String> relevant, List<String> retrieved) {
            if (relevant.isEmpty()) {
                noHitCases++;
                if (!retrieved.isEmpty()) {
                    falseCitations++;
                }
            } else {
                hitCases++;
                int overlap = overlap(relevant, Set.copyOf(retrieved));
                recallSum += (double) overlap / relevant.size();
                if (overlap > 0) {
                    hits++;
                }
            }
        }

        private double meanRecall() {
            return recallSum / hitCases;
        }

        private double hitRate() {
            return (double) hits / hitCases;
        }

        private double noHitFalseCitationRate() {
            return (double) falseCitations / noHitCases;
        }
    }
}
