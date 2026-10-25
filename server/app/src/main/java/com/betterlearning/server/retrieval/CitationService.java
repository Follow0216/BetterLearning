package com.betterlearning.server.retrieval;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.betterlearning.server.agent.retrieval.anchor.HandwritingStepLocator;
import com.betterlearning.server.agent.retrieval.anchor.MarkdownAnchorParser;
import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.learning.service.impl.AnswerFileStore;
import com.betterlearning.server.multiagent.AnchorLocators;
import com.betterlearning.server.multiagent.MultiAgentModels.AnchorLocator;
import com.betterlearning.server.multiagent.MultiAgentModels.HandwritingSegment;
import com.betterlearning.server.multiagent.MultiAgentModels.WorldBounds;
import com.betterlearning.server.retrieval.mapper.CitationMapper;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 每次读取重新执行数据库授权链，再核对旧版本原文或原图定位。 */
@Service
public class CitationService {
    private final CitationMapper mapper;
    private final AnswerFileStore files;
    private final ObjectMapper json = new ObjectMapper();

    public record Citation(String citationId, String sourceId, long version, String anchorId,
            String label, String sourceType) {
    }

    public record MarkdownLocator(int blockOrdinal, int startOffset, int endOffset) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Resolution(Citation citation, String sourceTitle, Instant sourceCreatedAt,
            Instant sourceVersionCreatedAt, long currentVersion,
            @JsonInclude(JsonInclude.Include.ALWAYS) String answerState,
            String anchorKind, String contentSha256, String markdown, String quotedText,
            MarkdownLocator markdownLocator, HandwritingStepLocator.Locator handwritingLocator) {
    }

    private record Handwriting(HandwritingStepLocator.Locator locator,
            Map<Integer, AnswerModels.Block> blocks) {
    }

    public CitationService(CitationMapper mapper, AnswerFileStore files) {
        this.mapper = mapper;
        this.files = files;
    }

    @Transactional(readOnly = false)
    public Resolution resolve(long accountId, String citationId) {
        lockAccount(accountId);
        Map<String, Object> row = authorized(accountId, citationId);
        Citation citation = citation(row);
        String kind = required(row, "anchorKind");
        String hash = required(row, "anchorSha256");
        AnchorLocator locator = locator(row);
        validateSourceKind(row, locator);
        Resolution base = new Resolution(citation, required(row, "sourceTitle"),
                instant(row, "sourceCreatedAt"), instant(row, "sourceVersionCreatedAt"),
                number(row, "currentVersion"), optional(row, "answerState"), kind, hash,
                null, null, null, null);
        if (locator.markdownLocator() != null) {
            return markdown(row, base, locator.markdownLocator());
        }
        return new Resolution(citation, base.sourceTitle(), base.sourceCreatedAt(),
                base.sourceVersionCreatedAt(), base.currentVersion(), base.answerState(),
                kind, hash, null, null, null, handwriting(row, citationId, locator).locator());
    }

    @Transactional(readOnly = false)
    public byte[] asset(long accountId, String citationId, int blockIndex) {
        if (blockIndex < 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        lockAccount(accountId);
        Map<String, Object> row = authorized(accountId, citationId);
        AnchorLocator locator = locator(row);
        validateSourceKind(row, locator);
        if (locator.handwritingLocator() == null) {
            throw notFound();
        }
        Handwriting handwriting = handwriting(row, citationId, locator);
        boolean linked = handwriting.locator().segments().stream()
                .anyMatch(segment -> segment.blockIndex() == blockIndex);
        if (!linked) {
            throw notFound();
        }
        String fileRef = required(row, "fileRef");
        byte[] requested = null;
        for (HandwritingStepLocator.Segment segment : handwriting.locator().segments()) {
            byte[] bytes = readVerifiedImage(fileRef, handwriting.blocks().get(segment.blockIndex()));
            if (segment.blockIndex() == blockIndex) {
                requested = bytes;
            }
        }
        if (requested == null) {
            throw conflict();
        }
        return requested;
    }

    private void lockAccount(long accountId) {
        if (mapper.lockAccount(accountId) == null) {
            throw notFound();
        }
    }

    private static void validateSourceKind(Map<String, Object> row, AnchorLocator locator) {
        String sourceType = required(row, "sourceType");
        String kind = required(row, "anchorKind");
        if (locator.markdownLocator() != null) {
            if (!"NOTE".equals(sourceType) && !"QUESTION".equals(sourceType)
                    && !"TUTOR_REPLY".equals(sourceType)) {
                throw conflict();
            }
        }
        if ("SAVED_ANSWER".equals(sourceType) && !"HANDWRITING_STEP".equals(kind)) {
            throw conflict();
        }
        if ("QUESTION_BLOCK".equals(kind)
                && (locator.markdownLocator() != null && !"QUESTION".equals(sourceType)
                || locator.handwritingLocator() != null && !"TUTOR_SNAPSHOT".equals(sourceType))) {
            throw conflict();
        }
    }

    private Map<String, Object> authorized(long accountId, String citationId) {
        if (citationId == null || !citationId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        Map<String, Object> row = mapper.authorized(accountId, citationId);
        if (row == null || row.isEmpty()) {
            throw notFound();
        }
        if (!required(row, "citationSha256").equals(required(row, "anchorSha256"))) {
            throw conflict();
        }
        return row;
    }

    private Resolution markdown(Map<String, Object> row, Resolution base,
            com.betterlearning.server.multiagent.MultiAgentModels.MarkdownLocator locator) {
        try {
            String body = required(row, "bodyMarkdown");
            int ordinal = Math.toIntExact(number(row, "anchorOrdinal"));
            List<MarkdownAnchorParser.Block> blocks = MarkdownAnchorParser.parse(body);
            if (ordinal < 0 || ordinal >= blocks.size()) {
                throw conflict();
            }
            MarkdownAnchorParser.Block block = blocks.get(ordinal);
            int start = locator.startOffset();
            int end = locator.endOffset();
            if (locator.blockOrdinal() != ordinal
                    || !block.contentSha256().equals(base.contentSha256())
                    || row.get("anchorText") != null && !block.text().equals(row.get("anchorText"))
                    || start < 0 || end <= start || end > block.text().length()
                    || splitsSurrogate(block.text(), start) || splitsSurrogate(block.text(), end)) {
                throw conflict();
            }
            return new Resolution(base.citation(), base.sourceTitle(), base.sourceCreatedAt(),
                    base.sourceVersionCreatedAt(), base.currentVersion(), base.answerState(),
                    base.anchorKind(), base.contentSha256(), block.text(), block.text().substring(start, end),
                    new MarkdownLocator(ordinal, start, end), null);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw conflict();
        }
    }

    private Handwriting handwriting(Map<String, Object> row, String citationId, AnchorLocator locator) {
        try {
            String kind = required(row, "anchorKind");
            if ("HANDWRITING_STEP".equals(kind)) {
                String anchorText = required(row, "anchorText").replace("\r\n", "\n");
                if (!sha256(anchorText.getBytes(StandardCharsets.UTF_8))
                        .equals(required(row, "anchorSha256"))) {
                    throw conflict();
                }
            } else if (!"QUESTION_BLOCK".equals(kind) || row.get("anchorText") != null) {
                throw conflict();
            }
            String fileRef = required(row, "fileRef");
            AnswerModels.StoredManifest manifest;
            try {
                manifest = files.manifest(fileRef);
            } catch (BusinessException exception) {
                throw conflict();
            }
            Set<String> manifestStrokeIds = manifestStrokeIds(manifest);
            Set<String> expectedStrokeIds = null;
            Map<Integer, AnswerModels.Block> byIndex = new HashMap<>();
            List<HandwritingStepLocator.Segment> valid = new ArrayList<>();
            List<HandwritingStepLocator.Block> locatorBlocks = new ArrayList<>();
            Set<Integer> seen = new HashSet<>();
            for (HandwritingSegment segment : locator.handwritingLocator().segments()) {
                int index = segment.blockIndex();
                boolean raster = segment.questionRaster();
                if ("HANDWRITING_STEP".equals(kind) && raster
                        || "QUESTION_BLOCK".equals(kind) && !raster) {
                    throw conflict();
                }
                List<AnswerModels.Block> sourceBlocks = raster
                        ? manifest.source().document().questionLayout().rasterBlocks()
                        : manifest.source().blocks();
                if (sourceBlocks == null || index >= sourceBlocks.size() || !seen.add(index)) {
                    throw conflict();
                }
                AnswerModels.Block block = sourceBlocks.get(index);
                if (block == null || block.index() == null || block.index() != index) {
                    throw conflict();
                }
                byIndex.put(index, block);
                // Composite chunks are canvas-local. Question raster blocks already use world coordinates.
                HandwritingStepLocator.Bounds actual = raster
                        ? bounds(block.x(), block.y(), block.width(), block.height())
                        : compositeWorld(manifest.source(), block);
                HandwritingStepLocator.Bounds stored = bounds(segment.blockWorldBounds());
                if (!actual.equals(stored) || block.pixelWidth() == null || block.pixelHeight() == null
                        || block.pixelWidth() != segment.pixelWidth()
                        || block.pixelHeight() != segment.pixelHeight()) {
                    throw conflict();
                }
                String url = "/api/v2/learning/citations/" + citationId + "/assets/" + index;
                HandwritingStepLocator.Bounds region = bounds(segment.regionWorldBounds());
                List<String> strokes = segment.strokeIds();
                if (strokes != null) {
                    Set<String> supplied = Set.copyOf(strokes);
                    if (raster && !supplied.isEmpty() || !manifestStrokeIds.containsAll(supplied)
                            || expectedStrokeIds != null && !expectedStrokeIds.equals(supplied)) {
                        throw conflict();
                    }
                    expectedStrokeIds = supplied;
                }
                valid.add(new HandwritingStepLocator.Segment(index, url, actual,
                        block.pixelWidth(), block.pixelHeight(), region, strokes));
                locatorBlocks.add(new HandwritingStepLocator.Block(index, url, actual,
                        block.pixelWidth(), block.pixelHeight()));
            }
            HandwritingStepLocator.Bounds target = envelope(valid);
            List<HandwritingStepLocator.Segment> reconstructed = HandwritingStepLocator.locate(
                    target, locatorBlocks, null).segments();
            if (reconstructed.size() != valid.size()) {
                throw conflict();
            }
            for (int index = 0; index < valid.size(); index++) {
                if (!valid.get(index).regionWorldBounds().equals(reconstructed.get(index).regionWorldBounds())) {
                    throw conflict();
                }
            }
            return new Handwriting(new HandwritingStepLocator.Locator(valid), byIndex);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw conflict();
        }
    }

    private static Set<String> manifestStrokeIds(AnswerModels.StoredManifest manifest) {
        Set<String> ids = new HashSet<>();
        if (manifest.source().document() == null || manifest.source().document().strokes() == null) {
            return ids;
        }
        for (AnswerModels.Stroke stroke : manifest.source().document().strokes()) {
            if (stroke == null || stroke.strokeId() == null || stroke.strokeId().isBlank()
                    || !ids.add(stroke.strokeId())) {
                throw conflict();
            }
        }
        return ids;
    }

    private static HandwritingStepLocator.Bounds compositeWorld(AnswerModels.SaveManifest manifest,
            AnswerModels.Block block) {
        if (manifest.canvas() == null || manifest.canvas().originX() == null
                || manifest.canvas().originY() == null) {
            throw conflict();
        }
        return bounds(manifest.canvas().originX() + block.x(),
                manifest.canvas().originY() + block.y(), block.width(), block.height());
    }

    private byte[] readVerifiedImage(String fileRef, AnswerModels.Block block) {
        if (block.partName() == null || !block.partName().matches("[A-Za-z0-9_-]{1,80}")) {
            throw conflict();
        }
        byte[] bytes;
        try {
            bytes = files.block(fileRef, block.partName());
        } catch (BusinessException exception) {
            throw conflict();
        }
        if (block.sha256() == null || !block.sha256().equals(sha256(bytes))
                || !pngDimensionsMatch(bytes, block.pixelWidth(), block.pixelHeight())) {
            throw conflict();
        }
        return bytes;
    }

    private static boolean pngDimensionsMatch(byte[] bytes, Integer width, Integer height) {
        byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        if (bytes.length < 24 || width == null || height == null) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if (bytes[index] != signature[index]) {
                return false;
            }
        }
        return bytes[12] == 'I' && bytes[13] == 'H' && bytes[14] == 'D' && bytes[15] == 'R'
                && ByteBuffer.wrap(bytes, 16, 4).getInt() == width
                && ByteBuffer.wrap(bytes, 20, 4).getInt() == height;
    }

    private static HandwritingStepLocator.Bounds envelope(List<HandwritingStepLocator.Segment> segments) {
        double left = Double.POSITIVE_INFINITY;
        double top = Double.POSITIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY;
        double bottom = Double.NEGATIVE_INFINITY;
        for (HandwritingStepLocator.Segment segment : segments) {
            HandwritingStepLocator.Bounds bounds = segment.regionWorldBounds();
            left = Math.min(left, bounds.x());
            top = Math.min(top, bounds.y());
            right = Math.max(right, bounds.x() + bounds.width());
            bottom = Math.max(bottom, bounds.y() + bounds.height());
        }
        return new HandwritingStepLocator.Bounds(left, top, right - left, bottom - top);
    }

    private static HandwritingStepLocator.Bounds bounds(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw conflict();
        }
        return bounds(exactDouble(node.path("x")), exactDouble(node.path("y")),
                exactDouble(node.path("width")), exactDouble(node.path("height")));
    }

    private static HandwritingStepLocator.Bounds bounds(WorldBounds value) {
        if (value == null) {
            throw conflict();
        }
        return new HandwritingStepLocator.Bounds(value.x(), value.y(), value.width(), value.height());
    }

    private static HandwritingStepLocator.Bounds bounds(Double x, Double y, Double width, Double height) {
        if (x == null || y == null || width == null || height == null) {
            throw conflict();
        }
        return new HandwritingStepLocator.Bounds(x, y, width, height);
    }

    private AnchorLocator locator(Map<String, Object> row) {
        try {
            String raw = required(row, "locatorJson");
            if (raw.length() > 65536) {
                throw conflict();
            }
            JsonNode root = json.readTree(raw);
            objectKeys(root, Set.of("schemaVersion", "markdownLocator", "handwritingLocator"),
                    Set.of("schemaVersion"));
            if (exactInt(root.path("schemaVersion")) != 1) {
                throw conflict();
            }
            JsonNode markdown = root.path("markdownLocator");
            JsonNode handwriting = root.path("handwritingLocator");
            boolean hasMarkdown = !markdown.isMissingNode() && !markdown.isNull();
            boolean hasHandwriting = !handwriting.isMissingNode() && !handwriting.isNull();
            if (hasMarkdown == hasHandwriting) {
                throw conflict();
            }
            if (hasMarkdown) {
                objectKeys(markdown, Set.of("blockOrdinal", "startOffset", "endOffset"),
                        Set.of("blockOrdinal", "startOffset", "endOffset"));
                exactInt(markdown.path("blockOrdinal"));
                exactInt(markdown.path("startOffset"));
                exactInt(markdown.path("endOffset"));
            } else {
                objectKeys(handwriting, Set.of("segments"), Set.of("segments"));
                JsonNode segments = handwriting.path("segments");
                if (!segments.isArray() || segments.isEmpty() || segments.size() > 256) {
                    throw conflict();
                }
                for (JsonNode segment : segments) {
                    objectKeys(segment, Set.of("blockIndex", "questionRaster", "blockWorldBounds",
                            "pixelWidth", "pixelHeight", "regionWorldBounds", "strokeIds"),
                            Set.of("blockIndex", "questionRaster", "blockWorldBounds",
                            "pixelWidth", "pixelHeight", "regionWorldBounds"));
                    exactInt(segment.path("blockIndex"));
                    exactInt(segment.path("pixelWidth"));
                    exactInt(segment.path("pixelHeight"));
                    if (!segment.path("questionRaster").isBoolean()) {
                        throw conflict();
                    }
                    boundsKeys(segment.path("blockWorldBounds"));
                    boundsKeys(segment.path("regionWorldBounds"));
                    if (segment.has("strokeIds")) {
                        JsonNode ids = segment.path("strokeIds");
                        if (!ids.isArray() || ids.size() > 10000) {
                            throw conflict();
                        }
                        for (JsonNode id : ids) {
                            if (!id.isTextual() || id.textValue().isBlank()) {
                                throw conflict();
                            }
                        }
                    }
                }
            }
            AnchorLocator parsed = json.treeToValue(root, AnchorLocator.class);
            AnchorLocators.validate(required(row, "anchorKind"), optional(row, "anchorText"), parsed);
            return parsed;
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw conflict();
        }
    }

    private static void boundsKeys(JsonNode node) {
        objectKeys(node, Set.of("x", "y", "width", "height"), Set.of("x", "y", "width", "height"));
        bounds(node);
    }

    private static void objectKeys(JsonNode node, Set<String> allowed, Set<String> required) {
        if (node == null || !node.isObject()) {
            throw conflict();
        }
        for (String key : required) {
            if (!node.has(key)) {
                throw conflict();
            }
        }
        var keys = node.fieldNames();
        while (keys.hasNext()) {
            if (!allowed.contains(keys.next())) {
                throw conflict();
            }
        }
    }

    private static boolean splitsSurrogate(String value, int offset) {
        return offset > 0 && offset < value.length()
                && Character.isHighSurrogate(value.charAt(offset - 1))
                && Character.isLowSurrogate(value.charAt(offset));
    }

    private static Citation citation(Map<String, Object> row) {
        return new Citation(required(row, "citationId"), required(row, "sourceId"),
                number(row, "version"), required(row, "anchorId"),
                required(row, "label"), required(row, "sourceType"));
    }

    private static String required(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw conflict();
        }
        return text;
    }

    private static String optional(Map<String, Object> row, String key) {
        return row.get(key) == null ? null : required(row, key);
    }

    private static long number(Map<String, Object> row, String key) {
        if (!(row.get(key) instanceof Number value)) {
            throw conflict();
        }
        return value.longValue();
    }

    private static Instant instant(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof OffsetDateTime dateTime) {
            return dateTime.toInstant();
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof Instant time) {
            return time;
        }
        throw conflict();
    }

    private static int exactInt(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw conflict();
        }
        return value.intValue();
    }

    private static double exactDouble(JsonNode value) {
        if (!value.isNumber() || !Double.isFinite(value.doubleValue())) {
            throw conflict();
        }
        return value.doubleValue();
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private static BusinessException notFound() {
        return new BusinessException(ErrorCode.NOT_FOUND);
    }

    private static BusinessException conflict() {
        return new BusinessException(ErrorCode.TUTOR_TURN_CONFLICT);
    }
}
