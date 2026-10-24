package com.betterlearning.server.learning.model.tutor;

import java.util.List;

import com.betterlearning.server.learning.model.dataobject.TutorTurnDO;

/** learning与app之间的小型存储结果，不引用agent类型。 */
public final class TutorStorageModels {
    private TutorStorageModels() {
    }

    public record Creation(TutorTurnDO turn, boolean created) {
    }

    public record Claim(TutorTurnDO turn, boolean claimed) {
    }

    public record Acceptance(TutorTurnDO turn, boolean newlyBound) {
    }

    public record Image(byte[] bytes, String position) {
    }

    public record Frozen(String fileRef, String questionMarkdown, List<Image> images) {
        @Override
        public String toString() {
            return "Frozen[fileRef=" + fileRef + ", images=" + images.size() + "]";
        }
    }
}
