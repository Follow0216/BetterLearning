package com.betterlearning.server.learning.validation;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.learning.service.impl.AnswerFileStore;
import com.betterlearning.server.multiagent.AnchorLocators;
import com.betterlearning.server.multiagent.MultiAgentModels.Anchor;
import com.betterlearning.server.multiagent.MultiAgentModels.AnchorLocator;
import com.betterlearning.server.multiagent.MultiAgentModels.HandwritingSegment;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceContent;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceType;
import com.betterlearning.server.multiagent.MultiAgentModels.WorldBounds;
import com.betterlearning.server.multiagent.SourceTextBlocks;

import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/** 校验锚点所存定位器；资产地址由鉴权 HTTP 层生成，绝不从 JSON 接收。 */
@Component
public class SourceAnchorValidator {
    private static final Set<String> ROOT_KEYS = Set.of("schemaVersion", "markdownLocator", "handwritingLocator");
    private static final Set<String> MARKDOWN_KEYS = Set.of("blockOrdinal", "startOffset", "endOffset");
    private static final Set<String> HANDWRITING_KEYS = Set.of("segments");
    private static final Set<String> SEGMENT_KEYS = Set.of("blockIndex", "questionRaster", "blockWorldBounds",
            "pixelWidth", "pixelHeight", "regionWorldBounds", "strokeIds");
    private static final Set<String> BOUNDS_KEYS = Set.of("x", "y", "width", "height");

    private final AnswerFileStore files;
    private final JsonMapper json;

    public SourceAnchorValidator(AnswerFileStore files, JsonMapper json) {
        this.files = files;
        this.json = json;
    }

    public void validate(Anchor anchor, SourceContent content) {
        if (anchor == null || content == null || content.source() == null || content.version() == null
                || !"ACTIVE".equals(content.source().status())
                || !content.source().sourceId().equals(anchor.sourceId())
                || !content.version().sourceId().equals(anchor.sourceId())
                || anchor.version() != content.version().version() || anchor.locatorJson() == null
                || anchor.locatorJson().length() > 65536 || anchor.contentSha256() == null
                || !anchor.contentSha256().matches("[0-9a-f]{64}")) {
            throw invalid();
        }
        AnchorLocator typed = parse(anchor.locatorJson());
        String checkedText = anchor.text();
        if (typed.markdownLocator() != null) {
            SourceType type = content.source().type();
            if (type != SourceType.NOTE && type != SourceType.QUESTION && type != SourceType.TUTOR_REPLY) {
                throw invalid();
            }
            if (content.version().bodyMarkdown() == null || anchor.text() == null
                    || !"SOURCE_TEXT".equals(anchor.textOrigin())) {
                throw invalid();
            }
            List<String> blocks = SourceTextBlocks.split(content.version().bodyMarkdown());
            int ordinal = typed.markdownLocator().blockOrdinal();
            if (ordinal < 0 || ordinal >= blocks.size()) {
                throw invalid();
            }
            checkedText = blocks.get(ordinal);
            if (!checkedText.equals(anchor.text().replace("\r\n", "\n"))) {
                throw invalid();
            }
        }
        try {
            AnchorLocators.validate(anchor.kind(), checkedText, typed);
        } catch (IllegalArgumentException exception) {
            throw invalid();
        }
        if (typed.markdownLocator() != null) {
            String digest = AnswerManifestValidator.sha256(checkedText.getBytes(StandardCharsets.UTF_8));
            if (!digest.equals(anchor.contentSha256())) {
                throw invalid();
            }
        } else {
            if (anchor.text() != null && !"HANDWRITING_STEP".equals(anchor.kind())) {
                throw invalid();
            }
            verifyHandwriting(typed.handwritingLocator().segments(), content.version().fileRef());
        }
    }

    private AnchorLocator parse(String raw) {
        try {
            Object parsed = json.readValue(raw, Object.class);
            Map<?, ?> root = object(parsed, ROOT_KEYS, Set.of("schemaVersion"));
            integer(root.get("schemaVersion"));
            Object markdown = root.get("markdownLocator");
            Object handwriting = root.get("handwritingLocator");
            if ((markdown == null) == (handwriting == null)) {
                throw invalid();
            }
            if (markdown != null) {
                Map<?, ?> value = object(markdown, MARKDOWN_KEYS, MARKDOWN_KEYS);
                for (String key : MARKDOWN_KEYS) {
                    integer(value.get(key));
                }
            }
            if (handwriting != null) {
                Map<?, ?> value = object(handwriting, HANDWRITING_KEYS, HANDWRITING_KEYS);
                if (!(value.get("segments") instanceof List<?> segments)) {
                    throw invalid();
                }
                for (Object item : segments) {
                    Map<?, ?> segment = object(item, SEGMENT_KEYS, Set.of("blockIndex", "questionRaster",
                            "blockWorldBounds", "pixelWidth", "pixelHeight", "regionWorldBounds"));
                    integer(segment.get("blockIndex"));
                    integer(segment.get("pixelWidth"));
                    integer(segment.get("pixelHeight"));
                    if (!(segment.get("questionRaster") instanceof Boolean)) {
                        throw invalid();
                    }
                    bounds(segment.get("blockWorldBounds"));
                    bounds(segment.get("regionWorldBounds"));
                    if (segment.get("strokeIds") != null) {
                        if (!(segment.get("strokeIds") instanceof List<?> ids)
                                || ids.stream().anyMatch(id -> !(id instanceof String text) || text.isBlank())) {
                            throw invalid();
                        }
                    }
                }
            }
            return json.readValue(raw, AnchorLocator.class);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private void verifyHandwriting(List<HandwritingSegment> segments, String fileRef) {
        if (!AnswerManifestValidator.uuid(fileRef) || segments.size() != Set.copyOf(segments).size()) {
            throw invalid();
        }
        AnswerModels.StoredManifest stored = files.manifest(fileRef);
        AnswerManifestValidator.validate(stored.source(), stored.questionSnapshot().bodyMarkdown());
        List<AnswerModels.Block> composite = stored.source().blocks();
        List<AnswerModels.Block> raster = stored.source().document().questionLayout().rasterBlocks();
        Set<String> existingStrokes = stored.source().document().strokes().stream()
                .map(AnswerModels.Stroke::strokeId).collect(java.util.stream.Collectors.toSet());
        for (HandwritingSegment segment : segments) {
            List<AnswerModels.Block> blocks = segment.questionRaster() ? raster : composite;
            if (segment.blockIndex() >= blocks.size()) {
                throw invalid();
            }
            AnswerModels.Block block = blocks.get(segment.blockIndex());
            WorldBounds expected = new WorldBounds(block.x(), block.y(), block.width(), block.height());
            if (block.index() != segment.blockIndex() || !expected.equals(segment.blockWorldBounds())
                    || block.pixelWidth() != segment.pixelWidth() || block.pixelHeight() != segment.pixelHeight()) {
                throw invalid();
            }
            byte[] image = files.block(fileRef, block.partName());
            if (image == null || !block.sha256().equals(AnswerManifestValidator.sha256(image))) {
                throw invalid();
            }
            // P4 负责证明一步笔迹跨块 segments 完整；此处只验证所声明区域均属原始块。
            if (segment.strokeIds() != null && segment.strokeIds().size() != Set.copyOf(segment.strokeIds()).size()) {
                throw invalid();
            }
            if (segment.strokeIds() != null && (!existingStrokes.containsAll(segment.strokeIds())
                    || (segment.questionRaster() && !segment.strokeIds().isEmpty()))) {
                throw invalid();
            }
        }
    }

    private static Map<?, ?> object(Object value, Set<String> allowed, Set<String> required) {
        if (!(value instanceof Map<?, ?> map) || !map.keySet().stream().allMatch(allowed::contains)
                || !map.keySet().containsAll(required)) {
            throw invalid();
        }
        return map;
    }

    private static void integer(Object value) {
        if (!(value instanceof Integer)) {
            throw invalid();
        }
    }

    private static void bounds(Object value) {
        Map<?, ?> map = object(value, BOUNDS_KEYS, BOUNDS_KEYS);
        for (String key : BOUNDS_KEYS) {
            if (!(map.get(key) instanceof Number)) {
                throw invalid();
            }
        }
    }

    private static BusinessException invalid() {
        return new BusinessException(ErrorCode.INVALID_PARAM);
    }
}
