package com.betterlearning.server.learning.converter;

import java.util.List;

import com.betterlearning.server.learning.model.answer.AnswerModels;

/** 保留原保存envelope，仅附加服务端快照与受认证API地址。 */
public final class AnswerConverter {
    private AnswerConverter() {
    }

    public static AnswerModels.ReadManifest read(AnswerModels.StoredManifest stored, String base) {
        AnswerModels.SaveManifest source = stored.source();
        AnswerModels.QuestionLayout layout = source.document().questionLayout();
        AnswerModels.ReadLayout readLayout = new AnswerModels.ReadLayout(layout.questionBodySha256(),
                layout.contentWidth(), layout.fontSize(), layout.rendererId(), layout.bounds(), layout.blocks(),
                blocks(layout.rasterBlocks(), base + "/question-blocks/"));
        return new AnswerModels.ReadManifest(source.schemaVersion(), source.saveRequestId(), source.baseFileRef(),
                source.canvas(), new AnswerModels.ReadDocument(source.document().schemaVersion(), readLayout, source.document().strokes()),
                blocks(source.blocks(), base + "/blocks/"), stored.questionSnapshot());
    }

    private static List<AnswerModels.ReadBlock> blocks(List<AnswerModels.Block> blocks, String base) {
        return blocks.stream().map(block -> new AnswerModels.ReadBlock(block.index(), block.partName(),
                block.x(), block.y(), block.width(), block.height(), block.pixelWidth(), block.pixelHeight(),
                block.sha256(), base + block.index())).toList();
    }
}
