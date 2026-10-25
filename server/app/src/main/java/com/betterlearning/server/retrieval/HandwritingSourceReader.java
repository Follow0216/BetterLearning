package com.betterlearning.server.retrieval;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

import javax.imageio.ImageIO;

import com.betterlearning.server.agent.model.ModelImage;
import com.betterlearning.server.agent.retrieval.anchor.HandwritingStepLocator;
import com.betterlearning.server.agent.retrieval.anchor.HandwritingStepLocator.Bounds;
import com.betterlearning.server.agent.retrieval.anchor.HandwritingStepLocator.Locator;
import com.betterlearning.server.agent.service.OcrModel;
import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.learning.service.impl.AnswerFileStore;
import com.betterlearning.server.learning.validation.AnswerManifestValidator;
import com.betterlearning.server.learning.validation.SourceAnchorValidator;
import com.betterlearning.server.multiagent.AnchorLocators;
import com.betterlearning.server.multiagent.MultiAgentModels.Anchor;
import com.betterlearning.server.multiagent.MultiAgentModels.AnchorLocator;
import com.betterlearning.server.multiagent.MultiAgentModels.HandwritingLocator;
import com.betterlearning.server.multiagent.MultiAgentModels.HandwritingSegment;
import com.betterlearning.server.multiagent.MultiAgentModels.WorldBounds;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceContent;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceType;

import tools.jackson.databind.json.JsonMapper;

/** Reads an already-authorized immutable SAVED_ANSWER fileRef for indexing. */
public final class HandwritingSourceReader {
    private static final int MAX_STEPS = 16;
    private static final int MAX_IMAGE_SIDE = 4096;
    private static final long MAX_IMAGE_PIXELS = 4_000_000;
    private static final int MAX_OCR_CHARS = 8192;
    private final AnswerFileStore files;
    private final OcrModel ocr;
    private final JsonMapper json;

    public HandwritingSourceReader(AnswerFileStore files, OcrModel ocr, JsonMapper json) {
        this.files = Objects.requireNonNull(files);
        this.ocr = ocr;
        this.json = Objects.requireNonNull(json);
    }

    /** 重建索引复用旧 OCR 文字前重新校验原 manifest、定位与每张引用原图；不调用 OCR。 */
    public void validateReuse(SourceContent content) {
        if (content == null || content.source() == null || content.version() == null
                || content.source().type() != SourceType.SAVED_ANSWER
                || content.anchors() == null || content.anchors().isEmpty()
                || content.anchors().size() > 100) {
            throw failure("SOURCE_REUSE_INVALID", 0, null);
        }
        String fileRef = content.version().fileRef();
        try {
            AnswerModels.StoredManifest stored = files.manifest(fileRef);
            if (stored == null || stored.source() == null || stored.questionSnapshot() == null
                    || stored.questionSnapshot().bodyMarkdown() == null) {
                throw failure("SOURCE_REUSE_INVALID", 0, null);
            }
            AnswerManifestValidator.validate(stored.source(), stored.questionSnapshot().bodyMarkdown());
            SourceAnchorValidator validator = new SourceAnchorValidator(files, json);
            for (Anchor anchor : content.anchors()) {
                validator.validate(anchor, content);
                AnchorLocator locator = json.readValue(anchor.locatorJson(), AnchorLocator.class);
                if (!"HANDWRITING_STEP".equals(anchor.kind()) || locator.handwritingLocator() == null
                        || anchor.text() == null || !AnswerManifestValidator.sha256(
                        anchor.text().replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8))
                        .equals(anchor.contentSha256())) {
                    throw failure("SOURCE_REUSE_INVALID", 0, null);
                }
                validateSegments(fileRef, stored.source(), locator.handwritingLocator().segments());
            }
        } catch (HandwritingReadException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw failure("SOURCE_REUSE_INVALID", 0, exception);
        }
    }

    private void validateSegments(String fileRef, AnswerModels.SaveManifest manifest,
            List<HandwritingSegment> storedSegments) {
        List<AnswerModels.Block> blocks = manifest.blocks();
        List<HandwritingStepLocator.Block> locatorBlocks = new ArrayList<>();
        List<HandwritingStepLocator.Segment> segments = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (HandwritingSegment segment : storedSegments) {
            if (segment == null || segment.questionRaster() || segment.blockIndex() < 0
                    || segment.blockIndex() >= blocks.size() || !seen.add(segment.blockIndex())) {
                throw failure("SOURCE_REUSE_INVALID", 0, null);
            }
            AnswerModels.Block block = blocks.get(segment.blockIndex());
            Bounds actual = compositeWorld(manifest, block);
            Bounds region = new Bounds(segment.regionWorldBounds().x(), segment.regionWorldBounds().y(),
                    segment.regionWorldBounds().width(), segment.regionWorldBounds().height());
            Bounds recorded = new Bounds(segment.blockWorldBounds().x(), segment.blockWorldBounds().y(),
                    segment.blockWorldBounds().width(), segment.blockWorldBounds().height());
            if (block.index() != segment.blockIndex() || !actual.equals(recorded)
                    || block.pixelWidth() != segment.pixelWidth()
                    || block.pixelHeight() != segment.pixelHeight()) {
                throw failure("SOURCE_REUSE_INVALID", 0, null);
            }
            String marker = "/_source-block/" + segment.blockIndex();
            locatorBlocks.add(new HandwritingStepLocator.Block(segment.blockIndex(), marker, actual,
                    block.pixelWidth(), block.pixelHeight()));
            segments.add(new HandwritingStepLocator.Segment(segment.blockIndex(), marker, actual,
                    block.pixelWidth(), block.pixelHeight(), region, segment.strokeIds()));
            sourceImage(fileRef, block, 0).flush();
        }
        Bounds step = envelope(segments);
        List<HandwritingStepLocator.Segment> complete = HandwritingStepLocator.locate(
                step, locatorBlocks, null).segments();
        if (complete.size() != segments.size()) {
            throw failure("INCOMPLETE_BLOCK_COVERAGE", 0, null);
        }
        for (int index = 0; index < segments.size(); index++) {
            if (!segments.get(index).regionWorldBounds().equals(complete.get(index).regionWorldBounds())) {
                throw failure("INCOMPLETE_BLOCK_COVERAGE", 0, null);
            }
        }
    }

    private static Bounds envelope(List<HandwritingStepLocator.Segment> segments) {
        double left = Double.POSITIVE_INFINITY;
        double top = Double.POSITIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY;
        double bottom = Double.NEGATIVE_INFINITY;
        for (HandwritingStepLocator.Segment segment : segments) {
            Bounds region = segment.regionWorldBounds();
            left = Math.min(left, region.x());
            top = Math.min(top, region.y());
            right = Math.max(right, region.x() + region.width());
            bottom = Math.max(bottom, region.y() + region.height());
        }
        return new Bounds(left, top, right - left, bottom - top);
    }

    /** The caller authorizes SourceContent; this prepares immutable step images without any OCR call. */
    public List<PreparedStep> prepare(String fileRef) {
        AnswerModels.StoredManifest stored;
        try {
            stored = files.manifest(fileRef);
        } catch (RuntimeException exception) {
            throw failure("SOURCE_UNAVAILABLE", 0, exception);
        }
        AnswerModels.SaveManifest manifest;
        try {
            if (stored == null || stored.source() == null || stored.questionSnapshot() == null
                    || stored.questionSnapshot().bodyMarkdown() == null) {
                throw new IllegalArgumentException("missing immutable manifest or question snapshot");
            }
            manifest = stored.source();
            AnswerManifestValidator.validate(manifest, stored.questionSnapshot().bodyMarkdown());
        } catch (RuntimeException exception) {
            throw failure("INVALID_MANIFEST", 0, exception);
        }
        List<StepGroup> steps = groupSteps(manifest.document().strokes());
        if (steps.isEmpty()) {
            return List.of();
        }
        if (steps.size() > MAX_STEPS) {
            throw failure("TOO_MANY_STEPS", 0, null);
        }
        List<HandwritingStepLocator.Block> locatorBlocks = new ArrayList<>();
        Map<Integer, AnswerModels.Block> sourceBlocks = new HashMap<>();
        try {
            for (AnswerModels.Block block : manifest.blocks()) {
                Bounds world = compositeWorld(manifest, block);
                // Used only by the geometry helper; the stored locator never contains a URL.
                String assetMarker = "/_source-block/" + block.index();
                locatorBlocks.add(new HandwritingStepLocator.Block(block.index(), assetMarker,
                        world, block.pixelWidth(), block.pixelHeight()));
                if (sourceBlocks.putIfAbsent(block.index(), block) != null) {
                    throw new IllegalArgumentException("duplicate block index");
                }
            }
        } catch (RuntimeException exception) {
            throw failure("INVALID_MANIFEST", 0, exception);
        }

        List<PreparedStep> prepared = new ArrayList<>();
        for (int index = 0; index < steps.size(); index++) {
            StepGroup step = steps.get(index);
            Locator locator;
            try {
                locator = HandwritingStepLocator.locate(step.bounds, locatorBlocks, step.strokeIds);
            } catch (IllegalArgumentException exception) {
                throw failure("INCOMPLETE_BLOCK_COVERAGE", 0, exception);
            }
            CroppedImage cropped = crop(fileRef, step.bounds, locator, sourceBlocks, 0);
            String locatorJson;
            try {
                AnchorLocator typed = storedLocator(locator);
                AnchorLocators.validate("HANDWRITING_STEP", "prepared-step", typed);
                locatorJson = json.writeValueAsString(typed);
            } catch (RuntimeException exception) {
                throw failure("LOCATOR_SERIALIZATION_FAILED", 0, exception);
            }
            prepared.add(new PreparedStep(index, new ModelImage("image/png", cropped.bytes(),
                    "handwriting-step-" + index), cropped.width(), cropped.height(),
                    AnswerManifestValidator.sha256(cropped.bytes()), locatorJson));
        }
        return List.copyOf(prepared);
    }

    /** Create only the OCR-derived anchor text; the locator comes exclusively from prepare. */
    public AnchorDraft anchorDraft(PreparedStep step, String transcript) {
        if (step == null || transcript == null || transcript.isBlank()
                || transcript.length() > MAX_OCR_CHARS) {
            throw failure("OCR_INVALID_OUTPUT", 0, null);
        }
        String text = transcript.replace("\r\n", "\n");
        try {
            AnchorLocator locator = json.readValue(step.locatorJson(), AnchorLocator.class);
            AnchorLocators.validate("HANDWRITING_STEP", text, locator);
        } catch (RuntimeException exception) {
            throw failure("LOCATOR_SERIALIZATION_FAILED", 0, exception);
        }
        return new AnchorDraft("HANDWRITING_STEP", step.ordinal(), text,
                AnswerManifestValidator.sha256(text.getBytes(StandardCharsets.UTF_8)),
                step.locatorJson());
    }

    /** Legacy OCR entrypoint; new structured OCR callers use prepare and anchorDraft. */
    public ParsedHandwriting read(String fileRef) {
        List<PreparedStep> prepared = prepare(fileRef);
        if (prepared.isEmpty()) {
            return new ParsedHandwriting(List.of(), 0);
        }
        if (ocr == null) {
            throw failure("OCR_UNAVAILABLE", 0, null);
        }
        List<AnchorDraft> anchors = new ArrayList<>();
        int ocrCalls = 0;
        for (PreparedStep step : prepared) {
            String text;
            ocrCalls++;
            try {
                text = ocr.recognize(step.image());
            } catch (RuntimeException exception) {
                throw failure("OCR_FAILED", ocrCalls, exception);
            }
            try {
                anchors.add(anchorDraft(step, text));
            } catch (HandwritingReadException exception) {
                throw failure(exception.reason(), ocrCalls, exception);
            }
        }
        return new ParsedHandwriting(List.copyOf(anchors), ocrCalls);
    }

    private static AnchorLocator storedLocator(Locator geometry) {
        List<HandwritingSegment> segments = geometry.segments().stream().map(segment ->
        new HandwritingSegment(segment.blockIndex(), false,
                world(segment.blockWorldBounds()), segment.pixelWidth(),
                segment.pixelHeight(), world(segment.regionWorldBounds()),
                segment.strokeIds())).toList();
        return new AnchorLocator(1, null, new HandwritingLocator(segments));
    }

    private static WorldBounds world(Bounds bounds) {
        return new WorldBounds(bounds.x(), bounds.y(), bounds.width(), bounds.height());
    }

    private static Bounds compositeWorld(AnswerModels.SaveManifest manifest, AnswerModels.Block block) {
        if (manifest.canvas() == null || manifest.canvas().originX() == null
                || manifest.canvas().originY() == null) {
            throw new IllegalArgumentException("missing canvas origin");
        }
        return new Bounds(manifest.canvas().originX() + block.x(),
                manifest.canvas().originY() + block.y(), block.width(), block.height());
    }

    private CroppedImage crop(String fileRef, Bounds step, Locator locator,
            Map<Integer, AnswerModels.Block> sourceBlocks, int completedCalls) {
        double scaleX = 0;
        double scaleY = 0;
        for (HandwritingStepLocator.Segment segment : locator.segments()) {
            scaleX = Math.max(scaleX, segment.pixelWidth() / segment.blockWorldBounds().width());
            scaleY = Math.max(scaleY, segment.pixelHeight() / segment.blockWorldBounds().height());
        }
        int width = dimension(step.width(), scaleX, completedCalls);
        int height = dimension(step.height(), scaleY, completedCalls);
        if ((long) width * height > MAX_IMAGE_PIXELS) {
            throw failure("OCR_IMAGE_TOO_LARGE", completedCalls, null);
        }
        BufferedImage composite = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = composite.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            for (HandwritingStepLocator.Segment segment : locator.segments()) {
                AnswerModels.Block block = sourceBlocks.get(segment.blockIndex());
                if (block == null) {
                    throw failure("INCOMPLETE_BLOCK_COVERAGE", completedCalls, null);
                }
                BufferedImage original = sourceImage(fileRef, block, completedCalls);
                Bounds region = segment.regionWorldBounds();
                Bounds blockWorld = segment.blockWorldBounds();
                int sourceLeft = (int) Math.floor((region.x() - blockWorld.x())
                        * segment.pixelWidth() / blockWorld.width());
                int sourceTop = (int) Math.floor((region.y() - blockWorld.y())
                        * segment.pixelHeight() / blockWorld.height());
                int sourceRight = (int) Math.ceil((region.x() + region.width() - blockWorld.x())
                        * segment.pixelWidth() / blockWorld.width());
                int sourceBottom = (int) Math.ceil((region.y() + region.height() - blockWorld.y())
                        * segment.pixelHeight() / blockWorld.height());
                int targetLeft = (int) Math.floor((region.x() - step.x()) * scaleX);
                int targetTop = (int) Math.floor((region.y() - step.y()) * scaleY);
                int targetRight = (int) Math.ceil((region.x() + region.width() - step.x()) * scaleX);
                int targetBottom = (int) Math.ceil((region.y() + region.height() - step.y()) * scaleY);
                graphics.drawImage(original, targetLeft, targetTop, targetRight, targetBottom,
                        Math.max(0, sourceLeft), Math.max(0, sourceTop),
                        Math.min(original.getWidth(), sourceRight),
                        Math.min(original.getHeight(), sourceBottom), null);
                original.flush();
            }
        } finally {
            graphics.dispose();
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(composite, "png", output)) {
                throw failure("OCR_IMAGE_FAILED", completedCalls, null);
            }
            return new CroppedImage(output.toByteArray(), width, height);
        } catch (IOException exception) {
            throw failure("OCR_IMAGE_FAILED", completedCalls, exception);
        } finally {
            composite.flush();
        }
    }

    private BufferedImage sourceImage(String fileRef, AnswerModels.Block block, int completedCalls) {
        try {
            byte[] bytes = files.block(fileRef, block.partName());
            if (!AnswerManifestValidator.sha256(bytes).equals(block.sha256())) {
                throw failure("SOURCE_BLOCK_CHANGED", completedCalls, null);
            }
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || image.getWidth() != block.pixelWidth()
                    || image.getHeight() != block.pixelHeight()) {
                throw failure("SOURCE_BLOCK_INVALID", completedCalls, null);
            }
            return image;
        } catch (HandwritingReadException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw failure("SOURCE_BLOCK_UNAVAILABLE", completedCalls, exception);
        }
    }

    private static int dimension(double worldSize, double pixelsPerWorld, int calls) {
        double dimension = Math.ceil(worldSize * pixelsPerWorld);
        if (!Double.isFinite(dimension) || dimension < 1 || dimension > MAX_IMAGE_SIDE) {
            throw failure("OCR_IMAGE_TOO_LARGE", calls, null);
        }
        return (int) dimension;
    }

    private static List<StepGroup> groupSteps(List<AnswerModels.Stroke> strokes) {
        List<StrokeBox> boxes = new ArrayList<>();
        try {
            for (AnswerModels.Stroke stroke : strokes) {
                boxes.add(new StrokeBox(stroke.strokeId(), strokeBounds(stroke)));
            }
        } catch (RuntimeException exception) {
            throw failure("INVALID_STROKES", 0, exception);
        }
        boxes.sort(Comparator.comparingDouble((StrokeBox box) -> box.bounds.y())
                .thenComparingDouble(box -> box.bounds.x()).thenComparing(StrokeBox::id));
        List<StepGroup> groups = new ArrayList<>();
        for (StrokeBox box : boxes) {
            StepGroup nearest = null;
            for (StepGroup group : groups) {
                double verticalGap = gap(group.bounds.y(), group.bounds.y() + group.bounds.height(),
                        box.bounds.y(), box.bounds.y() + box.bounds.height());
                double horizontalGap = gap(group.bounds.x(), group.bounds.x() + group.bounds.width(),
                        box.bounds.x(), box.bounds.x() + box.bounds.width());
                if (verticalGap <= Math.max(2, Math.min(group.bounds.height(),
                        box.bounds.height()) * 0.35)
                        && horizontalGap <= Math.max(16, Math.max(group.bounds.height(),
                        box.bounds.height()) * 3)) {
                    nearest = group;
                    break;
                }
            }
            if (nearest == null) {
                groups.add(new StepGroup(box.bounds, new ArrayList<>(List.of(box.id))));
            } else {
                nearest.bounds = union(nearest.bounds, box.bounds);
                nearest.strokeIds.add(box.id);
            }
        }
        groups.sort(Comparator.comparingDouble((StepGroup group) -> group.bounds.y())
                .thenComparingDouble(group -> group.bounds.x()));
        return groups;
    }

    private static Bounds strokeBounds(AnswerModels.Stroke stroke) {
        if (stroke == null || stroke.strokeId() == null || stroke.strokeId().isBlank()
                || stroke.width() == null || stroke.width() <= 0 || stroke.points() == null
                || stroke.points().isEmpty()) {
            throw new IllegalArgumentException("invalid stroke");
        }
        double left = Double.POSITIVE_INFINITY;
        double top = Double.POSITIVE_INFINITY;
        double right = Double.NEGATIVE_INFINITY;
        double bottom = Double.NEGATIVE_INFINITY;
        double radius = stroke.width() / 2;
        for (AnswerModels.Point point : stroke.points()) {
            if (point == null || point.x() == null || point.y() == null) {
                throw new IllegalArgumentException("invalid stroke point");
            }
            left = Math.min(left, point.x() - radius);
            top = Math.min(top, point.y() - radius);
            right = Math.max(right, point.x() + radius);
            bottom = Math.max(bottom, point.y() + radius);
        }
        return new Bounds(left, top, right - left, bottom - top);
    }

    private static Bounds union(Bounds left, Bounds right) {
        double x = Math.min(left.x(), right.x());
        double y = Math.min(left.y(), right.y());
        double endX = Math.max(left.x() + left.width(), right.x() + right.width());
        double endY = Math.max(left.y() + left.height(), right.y() + right.height());
        return new Bounds(x, y, endX - x, endY - y);
    }

    private static double gap(double startA, double endA, double startB, double endB) {
        return Math.max(0, Math.max(startA - endB, startB - endA));
    }

    private static HandwritingReadException failure(String reason, int calls, Throwable cause) {
        return new HandwritingReadException(reason, calls, cause);
    }

    public record AnchorDraft(String kind, int ordinal, String text, String contentSha256,
            String locatorJson) {
    }

    public record PreparedStep(int ordinal, ModelImage image, int pixelWidth, int pixelHeight,
            String imageSha256, String locatorJson) {
    }

    public record ParsedHandwriting(List<AnchorDraft> anchors, int ocrCalls) {
    }

    public static final class HandwritingReadException extends RuntimeException {
        private final String reason;
        private final int ocrCalls;

        private HandwritingReadException(String reason, int ocrCalls, Throwable cause) {
            super(reason, cause);
            this.reason = reason;
            this.ocrCalls = ocrCalls;
        }

        public String reason() {
            return reason;
        }

        public int ocrCalls() {
            return ocrCalls;
        }
    }

    private record StrokeBox(String id, Bounds bounds) {
    }

    private record CroppedImage(byte[] bytes, int width, int height) {
    }

    private static final class StepGroup {
        private Bounds bounds;
        private final List<String> strokeIds;

        private StepGroup(Bounds bounds, List<String> strokeIds) {
            this.bounds = bounds;
            this.strokeIds = strokeIds;
        }
    }
}
