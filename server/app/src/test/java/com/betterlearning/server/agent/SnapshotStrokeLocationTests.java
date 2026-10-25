package com.betterlearning.server.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.betterlearning.server.learning.dao.LearningDao;
import com.betterlearning.server.learning.model.answer.AnswerModels;
import com.betterlearning.server.learning.service.impl.AnswerFileStore;
import com.betterlearning.server.multiagent.MultiAgentModels;
import com.betterlearning.server.multiagent.MultiAgentModels.StrokeLocationsStatus;
import com.betterlearning.server.multiagent.MultiAgentModels.WorldBounds;
import com.betterlearning.server.multiagent.SourceStore;
import com.betterlearning.server.multiagent.TutorLedger;

import org.junit.jupiter.api.Test;

/** 模拟已授权的冻结文件读取；几何预算与服务端真实模型调用相互独立。 */
class SnapshotStrokeLocationTests {
    private static final String REQUEST_ID = "600bb1dd-7324-427e-bd15-6755bc5c2f47";
    private static final String RUN_ID = "1af7a725-3b5e-4e0f-984d-33f5156068d5";
    private static final String FILE_REF = "d20a63d2-a8f2-4357-af75-e9943366f8c2";
    private static final String SOURCE_ID = "4225e538-3866-4bd1-a4e2-a1ba2ad5f615";
    private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");

    private final TutorLedger ledger = mock(TutorLedger.class);
    private final SourceStore sources = mock(SourceStore.class);
    private final AnswerFileStore files = mock(AnswerFileStore.class);
    private final TutorRequestServiceImpl service = new TutorRequestServiceImpl(ledger, sources,
            mock(LearningDao.class), files);

    @Test
    void negativeWorldCoordinatesAndFourBlockCoverageKeepSourceStrokeMetadata() {
        AnswerModels.Stroke negative = stroke("negative", 4.0, "highlighter",
                List.of(new AnswerModels.Point(-10.0, -15.0, 0.25)));
        AnswerModels.Stroke crossing = stroke("crossing", 4.0, "fountain",
                List.of(new AnswerModels.Point(-14.0, -21.0, null),
                        new AnswerModels.Point(1009.0, 1003.0, 0.8)));
        AnswerModels.SaveManifest manifest = givenSnapshot(List.of(negative, crossing));

        MultiAgentModels.Snapshot snapshot = service.load(7, REQUEST_ID);

        assertThat(snapshot.strokeLocationsStatus()).isEqualTo(StrokeLocationsStatus.COMPLETE);
        assertThat(snapshot.strokeIds()).containsExactly("negative", "crossing");
        assertThat(snapshot.hasHandwriting()).isTrue();
        assertThat(snapshot.strokeLocations()).hasSize(2);
        assertThat(snapshot.strokeLocations().get(0).strokeId()).isEqualTo("negative");
        assertThat(snapshot.strokeLocations().get(0).worldBounds())
                .isEqualTo(new WorldBounds(-12, -17, 4, 4));
        assertThat(snapshot.strokeLocations().get(0).imageOrdinals()).containsExactly(0);
        assertThat(snapshot.strokeLocations().get(1).worldBounds())
                .isEqualTo(new WorldBounds(-16, -23, 1027, 1028));
        assertThat(snapshot.strokeLocations().get(1).imageOrdinals()).containsExactly(0, 1, 2, 3);
        assertThat(snapshot.images()).hasSize(4);
        assertThat(snapshot.images().get(3).bytes()).containsExactly((byte) 3, (byte) 4);
        assertThat(manifest.document().strokes()).containsExactly(negative, crossing);
        assertThat(manifest.document().strokes().get(0).penKind()).isEqualTo("highlighter");
        assertThat(manifest.document().strokes().get(0).points().get(0).pressure()).isEqualTo(0.25);
        assertThat(manifest.document().strokes().get(1).points().get(0).pressure()).isNull();
    }

    @Test
    void emptyStrokeListIsCompleteWithoutLocations() {
        givenSnapshot(List.of());
        MultiAgentModels.Snapshot snapshot = service.load(7, REQUEST_ID);
        assertThat(snapshot.strokeLocationsStatus()).isEqualTo(StrokeLocationsStatus.COMPLETE);
        assertThat(snapshot.strokeLocations()).isEmpty();
        assertThat(snapshot.strokeIds()).isEmpty();
        assertThat(snapshot.hasHandwriting()).isFalse();
        assertThat(snapshot.images()).hasSize(4);
    }

    @Test
    void invalidGeometryReturnsUnavailableButKeepsOriginalImagesAndStrokeIds() {
        givenSnapshot(List.of(stroke("outside", 2.0, "ballpoint",
                List.of(new AnswerModels.Point(2000.0, 2000.0, null)))));
        MultiAgentModels.Snapshot snapshot = service.load(7, REQUEST_ID);
        assertThat(snapshot.strokeLocationsStatus()).isEqualTo(StrokeLocationsStatus.UNAVAILABLE);
        assertThat(snapshot.strokeLocations()).isEmpty();
        assertThat(snapshot.strokeIds()).containsExactly("outside");
        assertThat(snapshot.images()).hasSize(4);
    }

    @Test
    void twoThousandShortStrokesAreCompleteAndTwoThousandOneAreTooLarge() {
        List<AnswerModels.Stroke> strokes = new ArrayList<>();
        for (int index = 0; index < 2000; index++) {
            strokes.add(stroke(String.format(Locale.ROOT, "s%04d", index), 2.0, "ballpoint",
                    List.of(new AnswerModels.Point(10.0, 10.0, null))));
        }
        givenSnapshot(strokes);
        MultiAgentModels.Snapshot limit = service.load(7, REQUEST_ID);
        assertThat(limit.strokeLocationsStatus()).isEqualTo(StrokeLocationsStatus.COMPLETE);
        assertThat(limit.strokeLocations()).hasSize(2000);
        assertThat(limit.strokeIds()).hasSize(2000);
        assertThat(limit.images()).hasSize(4);

        strokes.add(stroke("s2000", 2.0, "ballpoint",
                List.of(new AnswerModels.Point(10.0, 10.0, null))));
        givenSnapshot(strokes);
        MultiAgentModels.Snapshot overflow = service.load(7, REQUEST_ID);
        assertThat(overflow.strokeLocationsStatus()).isEqualTo(StrokeLocationsStatus.TOO_LARGE);
        assertThat(overflow.strokeLocations()).isEmpty();
        assertThat(overflow.strokeIds()).hasSize(2001);
        assertThat(overflow.images()).hasSize(4);
    }

    @Test
    void utf8LocationBudgetRejectsLongChineseIdsWithoutTruncatingSource() {
        List<AnswerModels.Stroke> strokes = new ArrayList<>();
        for (int index = 0; index < 2000; index++) {
            String id = String.format(Locale.ROOT, "%04d", index) + "学".repeat(60);
            strokes.add(stroke(id, 2.0, "ballpoint",
                    List.of(new AnswerModels.Point(1008.0, 1000.0, null))));
        }
        givenSnapshot(strokes);
        MultiAgentModels.Snapshot snapshot = service.load(7, REQUEST_ID);
        assertThat(snapshot.strokeLocationsStatus()).isEqualTo(StrokeLocationsStatus.TOO_LARGE);
        assertThat(snapshot.strokeLocations()).isEmpty();
        assertThat(snapshot.strokeIds()).hasSize(2000);
        assertThat(snapshot.strokeIds().get(1999)).startsWith("1999");
        assertThat(snapshot.images()).hasSize(4);
        assertThat(snapshot.images().get(0).bytes()).containsExactly((byte) 0, (byte) 1);
    }

    @Test
    void incompleteOrDisplacedCompositeGridIsUnavailableEvenForStrokeInFirstTile() {
        AnswerModels.Stroke local = stroke("first-tile", 2.0, "ballpoint",
                List.of(new AnswerModels.Point(10.0, 10.0, null)));
        AnswerModels.SaveManifest complete = givenSnapshot(List.of(local));
        List<AnswerModels.Block> missing = complete.blocks().subList(0, 3);
        AnswerModels.SaveManifest missingTile = new AnswerModels.SaveManifest(1, REQUEST_ID, null,
                complete.canvas(), complete.document(), missing);
        assertThat(StrokeLocationMapper.map(missingTile).status())
                .isEqualTo(StrokeLocationsStatus.UNAVAILABLE);
        assertThat(StrokeLocationMapper.map(missingTile).locations()).isEmpty();

        List<AnswerModels.Block> displaced = new ArrayList<>(complete.blocks());
        displaced.set(3, block(3, "chunk_0003", 1016, 1024, 8, 16));
        AnswerModels.SaveManifest wrongTile = new AnswerModels.SaveManifest(1, REQUEST_ID, null,
                complete.canvas(), complete.document(), displaced);
        assertThat(StrokeLocationMapper.map(wrongTile).status())
                .isEqualTo(StrokeLocationsStatus.UNAVAILABLE);
        assertThat(StrokeLocationMapper.map(wrongTile).locations()).isEmpty();
    }

    @Test
    void nonFinitePointOrPenRadiusPastCanvasReturnsUnavailableWithImagesAndIds() {
        givenSnapshot(List.of(stroke("nan-point", 2.0, "ballpoint",
                List.of(new AnswerModels.Point(Double.NaN, 10.0, null)))));
        MultiAgentModels.Snapshot nonFinite = service.load(7, REQUEST_ID);
        assertThat(nonFinite.strokeLocationsStatus()).isEqualTo(StrokeLocationsStatus.UNAVAILABLE);
        assertThat(nonFinite.strokeLocations()).isEmpty();
        assertThat(nonFinite.strokeIds()).containsExactly("nan-point");
        assertThat(nonFinite.images()).hasSize(4);

        givenSnapshot(List.of(stroke("radius-outside", 4.0, "ballpoint",
                List.of(new AnswerModels.Point(-15.0, 10.0, null)))));
        MultiAgentModels.Snapshot outside = service.load(7, REQUEST_ID);
        assertThat(outside.strokeLocationsStatus()).isEqualTo(StrokeLocationsStatus.UNAVAILABLE);
        assertThat(outside.strokeLocations()).isEmpty();
        assertThat(outside.strokeIds()).containsExactly("radius-outside");
        assertThat(outside.images()).hasSize(4);
        assertThat(outside.images().get(0).bytes()).containsExactly((byte) 0, (byte) 1);
    }

    private AnswerModels.SaveManifest givenSnapshot(List<AnswerModels.Stroke> strokes) {
        MultiAgentModels.Request request = new MultiAgentModels.Request(REQUEST_ID, "91", "提示",
                MultiAgentModels.Mode.HINT, new MultiAgentModels.SnapshotInput("NEW_CAPTURE", null),
                REQUEST_ID, FILE_REF, RUN_ID, MultiAgentModels.Status.QUEUED, 1, NOW, NOW);
        MultiAgentModels.Execution execution = new MultiAgentModels.Execution(7, REQUEST_ID, "91", RUN_ID,
                MultiAgentModels.Mode.HINT, "提示", FILE_REF, SOURCE_ID, 1,
                MultiAgentModels.Status.QUEUED, 1, NOW.plusSeconds(120), NOW.plusSeconds(600), null, 0);
        MultiAgentModels.Source source = new MultiAgentModels.Source(SOURCE_ID, 7,
                MultiAgentModels.SourceType.TUTOR_SNAPSHOT, REQUEST_ID, "冻结", "ACTIVE", 1, NOW);
        MultiAgentModels.SourceVersion version = new MultiAgentModels.SourceVersion(SOURCE_ID, 1,
                "0".repeat(64), "READY", "DRAFT", FILE_REF, null, NOW);
        List<AnswerModels.Block> blocks = List.of(
                block(0, "chunk_0000", 0, 0, 1024, 1024),
                block(1, "chunk_0001", 1024, 0, 8, 1024),
                block(2, "chunk_0002", 0, 1024, 1024, 16),
                block(3, "chunk_0003", 1024, 1024, 8, 16));
        AnswerModels.QuestionLayout layout = new AnswerModels.QuestionLayout("0".repeat(64), 24.0, 16.0,
                "renderer-v1", new AnswerModels.Bounds(-8.0, -12.0, 24.0, 20.0),
                List.of(new AnswerModels.LayoutBlock("body", -8.0, -12.0, 24.0, 20.0)), List.of());
        AnswerModels.SaveManifest manifest = new AnswerModels.SaveManifest(1, REQUEST_ID, null,
                new AnswerModels.Canvas(1032.0, 1040.0, -16.0, -24.0, "logical"),
                new AnswerModels.Document(1, layout, List.copyOf(strokes)), blocks);
        when(ledger.getByRequestId(7, REQUEST_ID)).thenReturn(request);
        when(ledger.execution(7, RUN_ID)).thenReturn(execution);
        when(sources.get(7, SOURCE_ID, 1)).thenReturn(new MultiAgentModels.SourceContent(source, version, List.of()));
        when(files.manifest(FILE_REF)).thenReturn(new AnswerModels.StoredManifest(manifest,
                new AnswerModels.QuestionSnapshot("91", "OTHER", "题面")));
        when(files.block(FILE_REF, "chunk_0000")).thenReturn(new byte[] {0, 1});
        when(files.block(FILE_REF, "chunk_0001")).thenReturn(new byte[] {1, 2});
        when(files.block(FILE_REF, "chunk_0002")).thenReturn(new byte[] {2, 3});
        when(files.block(FILE_REF, "chunk_0003")).thenReturn(new byte[] {3, 4});
        return manifest;
    }

    private static AnswerModels.Block block(int index, String part, double x, double y,
            double width, double height) {
        return new AnswerModels.Block(index, part, x, y, width, height, (int) width, (int) height,
                "0".repeat(64));
    }

    private static AnswerModels.Stroke stroke(String id, double width, String penKind,
            List<AnswerModels.Point> points) {
        return new AnswerModels.Stroke(id, "#152744", width, points, penKind);
    }
}
