package com.betterlearning.server.multiagent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import com.betterlearning.server.multiagent.MultiAgentModels.Image;
import com.betterlearning.server.multiagent.MultiAgentModels.Snapshot;
import com.betterlearning.server.multiagent.MultiAgentModels.StrokeLocation;
import com.betterlearning.server.multiagent.MultiAgentModels.StrokeLocationsStatus;
import com.betterlearning.server.multiagent.MultiAgentModels.WorldBounds;

import org.junit.jupiter.api.Test;

class SnapshotContractTests {
    private static final WorldBounds FIRST = new WorldBounds(-8.0, -8.0, 10.0, 12.0);
    private static final WorldBounds SECOND = new WorldBounds(2.0, 4.0, 5.0, 6.0);

    @Test
    void oldConstructorsKeepTheirPayloadAndDefaultToUnavailableLocations() {
        Image image = image();
        Snapshot three = new Snapshot("file-ref", "题面", List.of(image));
        assertThat(three.strokeIds()).isEmpty();
        assertThat(three.hasHandwriting()).isNull();
        assertThat(three.ocrText()).isNull();
        assertThat(three.ocrConfidence()).isNull();
        assertThat(three.strokeLocationsStatus()).isEqualTo(StrokeLocationsStatus.UNAVAILABLE);
        assertThat(three.strokeLocations()).isEmpty();

        Snapshot seven = new Snapshot("file-ref", "题面", List.of(image),
                List.of("stroke-1"), true, "识别文本", 0.8);
        assertThat(seven.strokeIds()).containsExactly("stroke-1");
        assertThat(seven.hasHandwriting()).isTrue();
        assertThat(seven.ocrText()).isEqualTo("识别文本");
        assertThat(seven.ocrConfidence()).isEqualTo(0.8);
        assertThat(seven.strokeLocationsStatus()).isEqualTo(StrokeLocationsStatus.UNAVAILABLE);
        assertThat(seven.strokeLocations()).isEmpty();
    }

    @Test
    void completeLocationsAndAllNestedListsAreDefensivelyCopied() {
        byte[] pixels = {1, 2, 3};
        List<Image> images = new ArrayList<>(List.of(new Image(pixels, "image/png", "current")));
        List<String> ids = new ArrayList<>(List.of("stroke-1"));
        List<Integer> ordinals = new ArrayList<>(List.of(0));
        List<StrokeLocation> locations = new ArrayList<>(List.of(
                new StrokeLocation("stroke-1", FIRST, ordinals)));
        Snapshot snapshot = complete(images, ids, locations);

        pixels[0] = 9;
        images.clear();
        ids.clear();
        ordinals.clear();
        locations.clear();
        assertThat(snapshot.images()).hasSize(1);
        assertThat(snapshot.images().get(0).bytes()).containsExactly((byte) 1, (byte) 2, (byte) 3);
        assertThat(snapshot.strokeIds()).containsExactly("stroke-1");
        assertThat(snapshot.strokeLocations()).hasSize(1);
        assertThat(snapshot.strokeLocations().get(0).imageOrdinals()).containsExactly(0);
        assertThatThrownBy(() -> snapshot.images().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.strokeIds().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.strokeLocations().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.strokeLocations().get(0).imageOrdinals().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        byte[] returned = snapshot.images().get(0).bytes();
        returned[0] = 8;
        assertThat(snapshot.images().get(0).bytes()[0]).isEqualTo((byte) 1);
    }

    @Test
    void completeAllowsNoHandwritingAndMatchesMultipleStrokesToImages() {
        Snapshot empty = complete(List.of(), List.of(), List.of());
        assertThat(empty.strokeLocationsStatus()).isEqualTo(StrokeLocationsStatus.COMPLETE);
        assertThat(empty.strokeIds()).isEmpty();
        assertThat(empty.strokeLocations()).isEmpty();

        Snapshot two = complete(List.of(image(), image()), List.of("a", "b"),
                List.of(new StrokeLocation("a", FIRST, List.of(0, 1)),
                        new StrokeLocation("b", SECOND, List.of(1))));
        assertThat(two.strokeLocations()).extracting(StrokeLocation::strokeId).containsExactly("a", "b");
        assertThat(two.strokeLocations().get(0).imageOrdinals()).containsExactly(0, 1);
    }

    @Test
    void completeRequiresExactlyOneLocationForEachUniqueStrokeId() {
        List<Image> images = List.of(image());
        assertInvalid(() -> complete(images, List.of("a", "a"),
                List.of(location("a", FIRST, List.of(0)))));
        assertInvalid(() -> complete(images, List.of("a", "b"),
                List.of(location("a", FIRST, List.of(0)))));
        assertInvalid(() -> complete(images, List.of("a"),
                List.of(location("a", FIRST, List.of(0)), location("a", SECOND, List.of(0)))));
        assertInvalid(() -> complete(images, List.of("a"),
                List.of(location("b", FIRST, List.of(0)))));
        assertInvalid(() -> complete(List.of(), List.of("a"), List.of()));
    }

    @Test
    void completeRejectsInvalidWorldBounds() {
        List<Image> images = List.of(image());
        for (WorldBounds bounds : List.of(
                new WorldBounds(Double.NaN, 0, 1, 1),
                new WorldBounds(0, Double.POSITIVE_INFINITY, 1, 1),
                new WorldBounds(0, 0, 0, 1),
                new WorldBounds(0, 0, -1, 1),
                new WorldBounds(0, 0, 1, 0),
                new WorldBounds(0, 0, 1, -1))) {
            assertInvalid(() -> complete(images, List.of("a"),
                    List.of(location("a", bounds, List.of(0)))));
        }
    }

    @Test
    void completeRejectsEmptyDuplicateAndOutOfRangeImageOrdinals() {
        List<Image> images = List.of(image(), image());
        for (List<Integer> ordinals : List.of(
                List.<Integer>of(), List.of(0, 0), List.of(-1), List.of(2))) {
            assertInvalid(() -> complete(images, List.of("a"),
                    List.of(location("a", FIRST, ordinals))));
        }
    }

    @Test
    void unavailableAndTooLargeCannotClaimToHaveStrokeLocations() {
        for (StrokeLocationsStatus status : List.of(
                StrokeLocationsStatus.UNAVAILABLE, StrokeLocationsStatus.TOO_LARGE)) {
            assertInvalid(() -> new Snapshot("file-ref", "题面", List.of(image()),
                    List.of("a"), true, null, null, status,
                    List.of(location("a", FIRST, List.of(0)))));
            Snapshot noLocations = new Snapshot("file-ref", "题面", List.of(image()),
                    List.of("a"), true, null, null, status, List.of());
            assertThat(noLocations.strokeLocations()).isEmpty();
        }
    }

    @Test
    void canonicalConstructorRejectsNullLists() {
        assertThatThrownBy(() -> new Snapshot("file-ref", "题面", null,
                List.of(), false, null, null, StrokeLocationsStatus.COMPLETE, List.of()))
                .isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
        assertThatThrownBy(() -> new Snapshot("file-ref", "题面", List.of(),
                null, false, null, null, StrokeLocationsStatus.COMPLETE, List.of()))
                .isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
        assertThatThrownBy(() -> new Snapshot("file-ref", "题面", List.of(),
                List.of(), false, null, null, StrokeLocationsStatus.COMPLETE, null))
                .isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
        assertThatThrownBy(() -> new StrokeLocation("a", FIRST, null))
                .isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
    }

    private static Snapshot complete(List<Image> images, List<String> ids, List<StrokeLocation> locations) {
        return new Snapshot("file-ref", "题面", images, ids, !ids.isEmpty(), null, null,
                StrokeLocationsStatus.COMPLETE, locations);
    }

    private static StrokeLocation location(String id, WorldBounds bounds, List<Integer> ordinals) {
        return new StrokeLocation(id, bounds, ordinals);
    }

    private static Image image() {
        return new Image(new byte[] {1, 2, 3}, "image/png", "current");
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
    }
}
