package com.betterlearning.server.agent.pipeline;

import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntFunction;

import com.betterlearning.server.agent.model.ModelImage;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Input;
import com.betterlearning.server.agent.pipeline.RoleProtocol.PipelineFailure;
import com.betterlearning.server.agent.pipeline.RoleProtocol.Reason;
import com.betterlearning.server.multiagent.MultiAgentModels.Snapshot;
import com.betterlearning.server.multiagent.MultiAgentModels.StrokeLocationsStatus;
import com.betterlearning.server.multiagent.TutorInputBudget;

/** 只统计实际送入模型的完整作答图；存储预算与题面栅格不混入模型预算。 */
public final class TutoringInputBudget {
    public static final int MAX_IMAGE_COUNT = 256;
    public static final long MAX_TOTAL_IMAGE_BYTES = TutorInputBudget.MAX_IMAGE_BYTES;
    static final String LOCATION_LIMIT_CLARIFICATION = "本次作答的完整笔划定位数据超过当前处理范围，暂时无法逐笔判断对错。"
            + "请保留原作答；重试同一份内容或仅提升图片清晰度不能解除容量限制。"
            + "若需继续精确检查，请将目标步骤另行写成较小范围的独立作答后重新发起辅导。";

    private TutoringInputBudget() {
    }

    /** 绑定前可用实际作答块数量/字节数预检；不得用整个multipart大小代替。 */
    public static boolean isWithinImageBudget(int imageCount, long totalImageBytes) {
        return imageCount > 0 && imageCount <= MAX_IMAGE_COUNT
                && totalImageBytes > 0 && totalImageBytes <= MAX_TOTAL_IMAGE_BYTES;
    }

    static boolean locationBudgetExceeded(Input input) {
        return !input.strokeIds().isEmpty() && input.strokeLocationsStatus() == StrokeLocationsStatus.TOO_LARGE;
    }

    public static void validateSnapshot(Snapshot snapshot) {
        if (snapshot == null) {
            throw new PipelineFailure(Reason.INVALID_INPUT);
        }
        validate(snapshot.images(), image -> image.bytes().length, image -> image.mediaType());
    }

    public static void validateModelImages(List<ModelImage> images) {
        validate(images, ModelImage::byteCount, ModelImage::mediaType);
    }

    private static <T> void validate(List<T> images, ToIntFunction<T> byteCount, Function<T, String> mediaType) {
        if (images == null || images.isEmpty()) {
            throw new PipelineFailure(Reason.INVALID_INPUT);
        }
        if (images.size() > MAX_IMAGE_COUNT) {
            throw new PipelineFailure(Reason.MODEL_INPUT_TOO_LARGE);
        }
        long totalBytes = 0;
        for (T image : images) {
            if (image == null) {
                throw new PipelineFailure(Reason.INVALID_INPUT);
            }
            int bytes = byteCount.applyAsInt(image);
            String type = mediaType.apply(image);
            if (bytes <= 0 || (!"image/png".equals(type) && !"image/jpeg".equals(type))) {
                throw new PipelineFailure(Reason.INVALID_INPUT);
            }
            totalBytes += bytes;
            if (totalBytes > MAX_TOTAL_IMAGE_BYTES) {
                throw new PipelineFailure(Reason.MODEL_INPUT_TOO_LARGE);
            }
        }
    }
}
