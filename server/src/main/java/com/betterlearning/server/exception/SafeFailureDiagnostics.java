package com.betterlearning.server.exception;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.slf4j.Logger;

/**
 * 生成脱敏的异常诊断信息用于故障日志。
 *
 * <p>只读取异常类名、{@link StackTraceElement} 的类／方法／文件／行号以及 cause、suppressed 关系，
 * 不读取或输出 message、localizedMessage、toString、SQL、请求参数等可能含敏感值的内容。
 * 对 cause 深度、每个异常的栈帧数量、suppressed 数量与总节点数设置上限，并通过身份集合阻断循环引用。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
public final class SafeFailureDiagnostics {

    private static final String LINE_SEPARATOR = System.lineSeparator();

    private static final int MAX_CAUSE_DEPTH = 8;

    private static final int MAX_FRAMES_PER_EXCEPTION = 12;

    private static final int MAX_SUPPRESSED_PER_EXCEPTION = 4;

    private static final int MAX_NODES = 32;

    /**
     * 工具类，禁止实例化。
     */
    private SafeFailureDiagnostics() {
    }

    /**
     * 生成不含异常消息的异常链诊断文本。
     *
     * @param failure 原始异常，可为空
     * @return 脱敏诊断文本；异常为空时返回 {@code none}
     */
    public static String describe(Throwable failure) {
        if (failure == null) {
            return "none";
        }
        StringBuilder builder = new StringBuilder(512);
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        append(builder, failure, 0, "exception", visited, new int[] {0});
        return builder.toString();
    }

    /**
     * 在请求出口记录一次脱敏故障日志。
     *
     * @param logger 目标日志器
     * @param operation 固定操作名
     * @param traceId 请求 traceId
     * @param failure 原始异常，可为空
     */
    public static void logError(Logger logger, String operation, String traceId,
            Throwable failure) {
        logger.error("operation={}, traceId={}{}{}", operation, traceId, LINE_SEPARATOR,
                describe(failure));
    }

    private static void append(StringBuilder builder, Throwable failure, int depth, String relation,
            Set<Throwable> visited, int[] nodeCount) {
        if (failure == null || depth > MAX_CAUSE_DEPTH || nodeCount[0] >= MAX_NODES) {
            return;
        }
        String indent = "  ".repeat(depth);
        if (!visited.add(failure)) {
            builder.append(indent).append(relation).append(": [cycle] ")
                    .append(failure.getClass().getName()).append(LINE_SEPARATOR);
            return;
        }
        nodeCount[0]++;
        builder.append(indent).append(relation).append(": ")
                .append(failure.getClass().getName()).append(LINE_SEPARATOR);
        appendFrames(builder, failure.getStackTrace(), depth);
        appendSuppressed(builder, failure.getSuppressed(), depth, visited, nodeCount);
        Throwable cause = failure.getCause();
        if (cause == null) {
            return;
        }
        if (depth >= MAX_CAUSE_DEPTH) {
            builder.append(indent).append("  cause: [depth limit]")
                    .append(LINE_SEPARATOR);
            return;
        }
        append(builder, cause, depth + 1, "cause", visited, nodeCount);
    }

    private static void appendFrames(StringBuilder builder, StackTraceElement[] frames, int depth) {
        String indent = "  ".repeat(depth + 1);
        int limit = Math.min(frames.length, MAX_FRAMES_PER_EXCEPTION);
        for (int index = 0; index < limit; index++) {
            StackTraceElement frame = frames[index];
            builder.append(indent).append("at ").append(frame.getClassName()).append('.')
                    .append(frame.getMethodName()).append('(')
                    .append(frame.getFileName() == null ? "Unknown" : frame.getFileName())
                    .append(':').append(frame.getLineNumber()).append(')')
                    .append(LINE_SEPARATOR);
        }
        if (frames.length > limit) {
            builder.append(indent).append("... ").append(frames.length - limit)
                    .append(" more frames").append(LINE_SEPARATOR);
        }
    }

    private static void appendSuppressed(StringBuilder builder, Throwable[] suppressed, int depth,
            Set<Throwable> visited, int[] nodeCount) {
        int limit = Math.min(suppressed.length, MAX_SUPPRESSED_PER_EXCEPTION);
        for (int index = 0; index < limit; index++) {
            append(builder, suppressed[index], depth + 1, "suppressed[" + (index + 1) + "]",
                    visited, nodeCount);
        }
        if (suppressed.length > limit) {
            builder.append("  ".repeat(depth + 1)).append("... ")
                    .append(suppressed.length - limit).append(" more suppressed")
                    .append(LINE_SEPARATOR);
        }
    }
}
