package com.betterlearning.server.agent.service;

import java.util.List;

import com.betterlearning.server.agent.model.ModelImage;

/** SAVED_ANSWER 索引专用单图 OCR；方法仅为可追溯候选，不代表作答正确或学生已掌握。 */
public interface HandwritingIndexOcr {
    String SCHEMA_VERSION = "handwriting-ocr-v1";

    String model();

    Result recognize(ModelImage image, int stepOrdinal, int pixelWidth, int pixelHeight);

    record Result(String schemaVersion, String taxonomyVersion, int stepOrdinal, String transcript,
            String uncertainty, List<MethodCandidate> methodCandidates) {
    }

    record MethodCandidate(String method, int evidenceStartUtf16, int evidenceEndUtf16,
            Region region, String state, String reason) {
    }

    record Region(int x, int y, int width, int height) {
    }
}
