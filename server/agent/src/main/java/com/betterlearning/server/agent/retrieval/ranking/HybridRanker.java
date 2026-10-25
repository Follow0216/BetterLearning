package com.betterlearning.server.agent.retrieval.ranking;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Pure ranking of candidates that have already passed account, status and version authorization. */
public final class HybridRanker {
    private static final int RRF_K = 60;

    public Ranking rank(Query query, List<Candidate> authorizedCandidates, Limits limits,
            Double calibratedVectorThreshold) {
        Objects.requireNonNull(query, "query");
        Objects.requireNonNull(authorizedCandidates, "authorizedCandidates");
        Objects.requireNonNull(limits, "limits");
        if (calibratedVectorThreshold != null && !Double.isFinite(calibratedVectorThreshold)) {
            throw new IllegalArgumentException("calibratedVectorThreshold must be finite");
        }
        boolean explicitMethods = !query.methodTags().isEmpty();
        Map<String, Set<String>> queryAliases = explicitMethods
                ? ChineseMathTerms.queryAliases(query.methodTags()) : Map.of();
        Set<String> queryMethods = explicitMethods
                ? queryAliases.keySet()
                : ChineseMathTerms.methodsInText(query.text());
        if (explicitMethods && queryMethods.isEmpty()) {
            return new Ranking(List.of(), List.of());
        }
        Set<String> negatedMethods = explicitMethods ? Set.of()
                : ChineseMathTerms.negatedMethods(query.text());
        Set<String> queryTerms = new HashSet<>(ChineseMathTerms.terms(query.text()));
        queryTerms.removeAll(negatedMethods);
        List<Scored> eligible = new ArrayList<>();
        for (Candidate candidate : authorizedCandidates) {
            Objects.requireNonNull(candidate, "candidate");
            if (!candidate.type().retrievable()) {
                continue;
            }
            Set<String> candidateMethods = new HashSet<>(ChineseMathTerms.methods(candidate.text(),
                    candidate.methodTags()));
            if (explicitMethods) {
                candidateMethods.addAll(ChineseMathTerms.literalMethodsInText(
                        candidate.text(), queryAliases));
                if (candidate.type() == SourceType.SAVED_ANSWER) {
                    Set<String> inferred = new HashSet<>(candidate.inferredMethods());
                    inferred.retainAll(HandwritingMethodTaxonomy.methods());
                    inferred.retainAll(queryMethods);
                    inferred.removeAll(ChineseMathTerms.negatedMethods(candidate.text()));
                    candidateMethods.addAll(inferred);
                }
            }
            Set<String> methods = intersection(queryMethods, candidateMethods);
            if (!explicitMethods && methods.isEmpty()
                    && !intersection(negatedMethods, candidateMethods).isEmpty()) {
                continue;
            }
            Set<String> terms = intersection(queryTerms, ChineseMathTerms.terms(candidate.text()));
            boolean calibratedVectorHit = calibratedVectorThreshold != null
                    && candidate.vectorScore() != null
                    && candidate.vectorScore() >= calibratedVectorThreshold;
            if (explicitMethods ? !methods.isEmpty()
                    : !methods.isEmpty() || !terms.isEmpty() || calibratedVectorHit) {
                eligible.add(new Scored(candidate, methods, terms));
            }
        }
        if (eligible.isEmpty()) {
            return new Ranking(List.of(), List.of());
        }
        List<Scored> vectorOrder = new ArrayList<>(eligible);
        vectorOrder.sort(Comparator.comparing((Scored scored) -> scored.candidate.vectorScore(),
                Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(Scored::stableKey));
        for (int index = 0; index < vectorOrder.size(); index++) {
            if (vectorOrder.get(index).candidate.vectorScore() != null) {
                vectorOrder.get(index).vectorRank = index + 1;
            }
        }
        List<Scored> lexicalOrder = new ArrayList<>(eligible);
        lexicalOrder.sort(Comparator.comparing((Scored scored) -> scored.candidate.lexicalScore(),
                Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(Scored::stableKey));
        for (int index = 0; index < lexicalOrder.size(); index++) {
            if (lexicalOrder.get(index).candidate.lexicalScore() != null) {
                lexicalOrder.get(index).lexicalRank = index + 1;
            }
        }
        eligible.sort(Comparator.comparingInt((Scored scored) -> scored.methods.size()).reversed()
                .thenComparing(Comparator.comparingDouble(Scored::rrf).reversed())
                .thenComparing(Comparator.comparingInt((Scored scored) -> scored.terms.size()).reversed())
                .thenComparing(Scored::stableKey));

        List<RankedEvidence> evidence = new ArrayList<>();
        List<CitationSuggestion> citations = new ArrayList<>();
        Set<String> selectedQuestions = new HashSet<>();
        int remaining = limits.maxTextChars();
        for (Scored scored : eligible) {
            if (evidence.size() == limits.maxEvidence() || remaining == 0) {
                break;
            }
            Candidate candidate = scored.candidate;
            String questionId = candidate.questionId();
            if (questionId != null && !questionId.isBlank()
                    && selectedQuestions.contains(questionId)) {
                continue;
            }
            String excerpt = safePrefix(candidate.text(), remaining);
            if (excerpt.isEmpty()) {
                continue;
            }
            evidence.add(new RankedEvidence(candidate, excerpt, scored.methods, scored.terms,
                    scored.rrf()));
            remaining -= excerpt.length();
            if (questionId != null && !questionId.isBlank()) {
                selectedQuestions.add(questionId);
            }
            if (citations.size() < limits.maxCitations()) {
                citations.add(new CitationSuggestion(candidate.sourceId(), candidate.version(),
                        candidate.anchorId()));
            }
        }
        return new Ranking(List.copyOf(evidence), List.copyOf(citations));
    }

    private static Set<String> intersection(Set<String> left, Set<String> right) {
        Set<String> result = new HashSet<>(left);
        result.retainAll(right);
        return Set.copyOf(result);
    }

    private static String safePrefix(String text, int maxChars) {
        int end = Math.min(text.length(), maxChars);
        if (end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end))) {
            end--;
        }
        return text.substring(0, end);
    }

    public enum SourceType {
        QUESTION, SAVED_ANSWER, NOTE, TUTOR_REPLY, TUTOR_SNAPSHOT;

        public boolean retrievable() {
            return this != TUTOR_SNAPSHOT;
        }
    }

    public record Query(String text, Set<String> methodTags) {
        public Query {
            text = text == null ? "" : text;
            methodTags = methodTags == null ? Set.of() : Set.copyOf(methodTags);
        }
    }

    /** One SQL candidate row; the caller must first enforce current READY version and live authorization. */
    public record Candidate(String sourceId, long version, String anchorId, SourceType type,
            String questionId, String text, Set<String> methodTags, Double vectorScore,
            Double lexicalScore, String answerState, Set<String> inferredMethods) {
        public Candidate(String sourceId, long version, String anchorId, SourceType type,
                String questionId, String text, Set<String> methodTags, Double vectorScore,
                Double lexicalScore) {
            this(sourceId, version, anchorId, type, questionId, text, methodTags,
                    vectorScore, lexicalScore, null, Set.of());
        }

        public Candidate(String sourceId, long version, String anchorId, SourceType type,
                String questionId, String text, Set<String> methodTags, Double vectorScore,
                Double lexicalScore, String answerState) {
            this(sourceId, version, anchorId, type, questionId, text, methodTags,
                    vectorScore, lexicalScore, answerState, Set.of());
        }

        public Candidate {
            if (sourceId == null || sourceId.isBlank() || version < 1
                    || anchorId == null || anchorId.isBlank() || type == null) {
                throw new IllegalArgumentException("candidate identity and type are required");
            }
            if (vectorScore != null && !Double.isFinite(vectorScore)
                    || lexicalScore != null && !Double.isFinite(lexicalScore)) {
                throw new IllegalArgumentException("candidate scores must be finite");
            }
            if (answerState != null && !"DRAFT".equals(answerState)
                    && !"COMPLETED".equals(answerState)) {
                throw new IllegalArgumentException("invalid answer state");
            }
            text = text == null ? "" : text;
            methodTags = methodTags == null ? Set.of() : Set.copyOf(methodTags);
            inferredMethods = inferredMethods == null ? Set.of() : Set.copyOf(inferredMethods);
            if (!inferredMethods.isEmpty() && (type != SourceType.SAVED_ANSWER
                    || !HandwritingMethodTaxonomy.methods().containsAll(inferredMethods))) {
                throw new IllegalArgumentException("invalid inferred handwriting methods");
            }
        }
    }

    public record Limits(int maxEvidence, int maxCitations, int maxTextChars) {
        public Limits {
            if (maxEvidence < 1 || maxEvidence > 5 || maxCitations < 0 || maxCitations > 3
                    || maxTextChars < 1) {
                throw new IllegalArgumentException("invalid evidence or citation limits");
            }
        }

        public static Limits defaults() {
            return new Limits(5, 3, 4000);
        }
    }

    public record RankedEvidence(Candidate candidate, String excerpt, Set<String> matchedMethods,
            Set<String> matchedTerms, double reciprocalRankScore) {
    }

    /** A suggestion only; publication still requires real-time citation authorization and review. */
    public record CitationSuggestion(String sourceId, long version, String anchorId) {
    }

    public record Ranking(List<RankedEvidence> evidence, List<CitationSuggestion> citationSuggestions) {
    }

    private static final class Scored {
        private final Candidate candidate;
        private final Set<String> methods;
        private final Set<String> terms;
        private int vectorRank;
        private int lexicalRank;

        private Scored(Candidate candidate, Set<String> methods, Set<String> terms) {
            this.candidate = candidate;
            this.methods = methods;
            this.terms = terms;
        }

        private String stableKey() {
            return candidate.sourceId() + ":" + candidate.version() + ":" + candidate.anchorId()
                    + ":" + candidate.text();
        }

        private double rrf() {
            return (vectorRank == 0 ? 0 : 1.0 / (RRF_K + vectorRank))
                    + (lexicalRank == 0 ? 0 : 1.0 / (RRF_K + lexicalRank));
        }
    }
}
