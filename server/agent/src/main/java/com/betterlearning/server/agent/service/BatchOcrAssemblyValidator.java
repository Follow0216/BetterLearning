package com.betterlearning.server.agent.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.betterlearning.server.agent.model.BatchOcrModels.Question;
import com.betterlearning.server.agent.model.BatchOcrModels.Source;
import com.betterlearning.server.agent.model.BatchOcrModels.Tile;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 模型只能引用给定来源和内部边缘；不信任任意坐标、覆盖声明或额外字段。 */
final class BatchOcrAssemblyValidator {
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    record Repair(String sourceId, String tileId, String edge) {
    }

    record Assembly(List<Question> questions, List<Repair> repairs, List<String> warnings, boolean incomplete) {
    }

    private BatchOcrAssemblyValidator() {
    }

    static Assembly parse(String value, List<Source> sources, List<Tile> tiles) {
        if (value == null || value.isBlank() || value.length() > 300000) {
            throw invalid();
        }
        try {
            JsonNode root = JSON.readTree(value);
            fields(root, "questions", "coverage", "repairs", "warnings");
            Map<String, Source> sourceMap = new HashMap<>();
            sources.forEach(source -> sourceMap.put(source.sourceId(), source));
            Map<String, Tile> tileMap = new HashMap<>();
            tiles.forEach(tile -> tileMap.put(tile.tileId(), tile));
            Set<String> used = new HashSet<>();
            List<Question> questions = questions(root.get("questions"), sourceMap, tileMap, used);
            List<String> warnings = new ArrayList<>(strings(root.get("warnings"), 20, 500));
            boolean incomplete = coverage(root.get("coverage"), tileMap, used, questions, warnings);
            List<Repair> repairs = repairs(root.get("repairs"), sourceMap, tileMap);
            if (questions.stream().anyMatch(Question::needsReview)) {
                incomplete = true;
            }
            if (!repairs.isEmpty()) {
                incomplete = true;
            }
            // 所有图片至少有一个基础片；coverage缺项不能以空题目掩盖。
            for (Source source : sources) {
                if (tiles.stream().noneMatch(tile -> source.sourceId().equals(tile.sourceId()) && !tile.repair())) {
                    warnings.add("原图未被覆盖：" + source.sourceId());
                    incomplete = true;
                }
            }
            questions.sort(Comparator.comparingInt(question -> firstSourceOrder(question, sourceMap)));
            List<Question> ordered = new ArrayList<>();
            for (int index = 0; index < questions.size(); index++) {
                Question item = questions.get(index);
                ordered.add(new Question("item-" + index, item.bodyMarkdown(), item.sourceIds(), item.tileIds(),
                        item.needsReview(), item.warnings()));
            }
            return new Assembly(List.copyOf(ordered), List.copyOf(repairs), List.copyOf(warnings), incomplete);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private static List<Question> questions(JsonNode node, Map<String, Source> sources, Map<String, Tile> tiles,
            Set<String> used) {
        array(node, 100);
        int totalText = 0;
        List<Question> questions = new ArrayList<>();
        for (JsonNode item : node) {
            fields(item, "bodyMarkdown", "sourceIds", "tileIds", "needsReview", "warnings");
            String body = text(item.get("bodyMarkdown"), 20000, false);
            totalText += body.length();
            if (totalText > 240000) {
                throw invalid();
            }
            List<String> sourceIds = strings(item.get("sourceIds"), 4, 64);
            List<String> tileIds = strings(item.get("tileIds"), 24, 96);
            if (sourceIds.size() != 1 || tileIds.isEmpty() || new HashSet<>(sourceIds).size() != sourceIds.size()
                    || new HashSet<>(tileIds).size() != tileIds.size()) {
                throw invalid();
            }
            Set<String> referencedSources = new HashSet<>();
            for (String tileId : tileIds) {
                Tile tile = tiles.get(tileId);
                if (tile == null) {
                    throw invalid();
                }
                referencedSources.add(tile.sourceId());
            }
            if (!referencedSources.equals(new HashSet<>(sourceIds))
                    || sourceIds.stream().anyMatch(id -> !sources.containsKey(id))) {
                throw invalid();
            }
            JsonNode review = item.get("needsReview");
            if (!review.isBoolean()) {
                throw invalid();
            }
            List<String> warnings = strings(item.get("warnings"), 20, 500);
            boolean uncertain = review.asBoolean() || !warnings.isEmpty() || body.contains("待核对")
                    || body.contains("无法辨认") || body.contains("看不清");
            used.addAll(tileIds);
            List<String> orderedSources = sourceIds.stream()
                    .sorted(Comparator.comparingInt(id -> sources.get(id).order())).toList();
            questions.add(new Question("", body, orderedSources, tileIds, uncertain, warnings));
        }
        return questions;
    }

    private static boolean coverage(JsonNode node, Map<String, Tile> tiles, Set<String> used,
            List<Question> questions, List<String> warnings) {
        array(node, 24);
        Set<String> covered = new HashSet<>();
        boolean incomplete = false;
        for (JsonNode item : node) {
            fields(item, "tileId", "status");
            String id = text(item.get("tileId"), 96, false);
            String status = text(item.get("status"), 16, false);
            Tile tile = tiles.get(id);
            if (tile == null || !covered.add(id)) {
                throw invalid();
            }
            switch (status) {
                case "USED" -> {
                    if (!used.contains(id)) {
                        throw invalid();
                    }
                }
                case "EMPTY" -> {
                    if (used.contains(id) || !empty(tile.markdown())) {
                        throw invalid();
                    }
                }
                case "UNCERTAIN" -> {
                    incomplete = true;
                    warnings.add("图块尚待校对：" + id);
                }
                default -> throw invalid();
            }
            boolean repaired = !tile.repair() && questions.stream().anyMatch(question -> !question.needsReview()
                    && question.tileIds().contains(id) && question.tileIds().stream().map(tiles::get)
                    .anyMatch(candidate -> candidate.repair() && candidate.sourceId().equals(tile.sourceId())
                    && candidate.x() < tile.x() + tile.width() && tile.x() < candidate.x() + candidate.width()
                    && candidate.y() < tile.y() + tile.height() && tile.y() < candidate.y() + candidate.height()));
            if (!repaired && (tile.markdown().contains("待核对") || tile.markdown().contains("无法辨认")
                    || tile.markdown().contains("看不清"))) {
                incomplete = true;
                warnings.add("原片存在不确定文字：" + id);
            }
        }
        for (String id : tiles.keySet()) {
            if (!covered.contains(id)) {
                warnings.add("重组未覆盖图块：" + id);
                incomplete = true;
            }
        }
        return incomplete;
    }

    private static List<Repair> repairs(JsonNode node, Map<String, Source> sources, Map<String, Tile> tiles) {
        array(node, 8);
        List<Repair> repairs = new ArrayList<>();
        Set<String> requested = new HashSet<>();
        Map<String, Integer> counts = new HashMap<>();
        for (JsonNode item : node) {
            fields(item, "sourceId", "tileId", "edge", "reason");
            String sourceId = text(item.get("sourceId"), 64, false);
            String tileId = text(item.get("tileId"), 96, false);
            String edge = text(item.get("edge"), 8, false);
            text(item.get("reason"), 500, false);
            Tile tile = tiles.get(tileId);
            Source source = sources.get(sourceId);
            if (tile == null || source == null || tile.repair() || !tile.sourceId().equals(sourceId)
                    || !BatchOcrImageSupport.internalEdge(source, tile, edge)
                    || !requested.add(tileId + ":" + edge)
                    || counts.merge(sourceId, 1, Integer::sum) > 2) {
                throw invalid();
            }
            repairs.add(new Repair(sourceId, tileId, edge));
        }
        return repairs;
    }

    private static int firstSourceOrder(Question question, Map<String, Source> sources) {
        return question.sourceIds().stream().mapToInt(id -> sources.get(id).order()).min().orElse(0);
    }

    private static boolean empty(String value) {
        return value.isBlank() || "[无文字]".equals(value.strip());
    }

    private static List<String> strings(JsonNode node, int maxCount, int maxLength) {
        array(node, maxCount);
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            values.add(text(item, maxLength, false));
        }
        return values;
    }

    private static String text(JsonNode node, int max, boolean blankAllowed) {
        if (node == null || !node.isString() || node.asString().length() > max
                || (!blankAllowed && node.asString().isBlank())) {
            throw invalid();
        }
        return node.asString();
    }

    private static void array(JsonNode node, int max) {
        if (node == null || !node.isArray() || node.size() > max) {
            throw invalid();
        }
    }

    private static void fields(JsonNode node, String... names) {
        Set<String> expected = Set.of(names);
        if (node == null || !node.isObject() || node.size() != expected.size()) {
            throw invalid();
        }
        Set<String> actual = new LinkedHashSet<>();
        node.propertyNames().forEach(actual::add);
        if (!expected.equals(actual)) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("模型重组结构或来源引用无效，保留原片供校对");
    }
}
