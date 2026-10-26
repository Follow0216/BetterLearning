package com.betterlearning.server.agent.service;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.betterlearning.server.agent.model.AgentModelException;
import com.betterlearning.server.agent.model.AgentModelException.Failure;
import com.betterlearning.server.agent.model.BatchOcrCancelledException;
import com.betterlearning.server.agent.model.BatchOcrModels.InputImage;
import com.betterlearning.server.agent.model.BatchOcrModels.Question;
import com.betterlearning.server.agent.model.BatchOcrModels.Result;
import com.betterlearning.server.agent.model.BatchOcrModels.Source;
import com.betterlearning.server.agent.model.BatchOcrModels.Tile;
import com.betterlearning.server.agent.model.BatchOcrProcessingException;
import com.betterlearning.server.agent.service.BatchOcrAssemblyValidator.Assembly;
import com.betterlearning.server.agent.service.BatchOcrAssemblyValidator.Repair;

import org.springframework.stereotype.Component;

/** 有界的图片切片、忠实转写和独立模型重组；不写正式题库，不自动重试模型。 */
@Component
public final class BatchOcrEngine {
    private static final int MAX_TILE_TEXT = 20000;
    private static final int MAX_TOTAL_TEXT = 240000;
    private final OcrModel ocr;
    private final BatchOcrAssembler assembler;

    public BatchOcrEngine(OcrModel ocr, BatchOcrAssembler assembler) {
        this.ocr = ocr;
        this.assembler = assembler;
    }

    public interface Observer {
        default boolean shouldCancel() {
            return false;
        }

        default void progress(int completed, int total, String stage) {
        }

        default void partial(Result result) {
        }
    }

    public List<Source> validateInputs(List<InputImage> images) {
        return BatchOcrImageSupport.validate(images);
    }

    public Result process(List<InputImage> images, Observer observer) {
        Observer events = observer == null ? new Observer() { } : observer;
        if (events.shouldCancel() || Thread.currentThread().isInterrupted()) {
            throw new BatchOcrCancelledException(new Result(List.of(), List.of(), List.of(),
                    List.of("批量识别已取消，原图尚未处理"), true));
        }
        State state = new State(validateInputs(images), events);
        List<Tile> planned = BatchOcrImageSupport.plan(state.sources);
        state.tiles.addAll(planned);
        state.total = planned.size();
        state.publish("OCR");
        state.checkCancel();
        try {
            recognize(images, planned, state, "OCR");
            Assembly first = assemble(state, false);
            if (first == null) {
                return state.result(true);
            }
            state.questions = first.questions();
            if (first.repairs().isEmpty()) {
                state.warnings.addAll(first.warnings());
                return state.finish(first.incomplete());
            }
            state.warnings.addAll(first.warnings());
            state.publish("REPAIR");
            List<Tile> repairs = repairs(first.repairs(), state);
            if (repairs.isEmpty()) {
                state.warnings.add("无法在补识别限额内修复跨缝内容，请按原片校对");
                return state.finish(true);
            }
            state.tiles.addAll(repairs);
            state.total += repairs.size();
            recognize(images, repairs, state, "REPAIR");
            Assembly last = assemble(state, true);
            if (last == null) {
                return state.result(true);
            }
            state.questions = last.questions();
            state.warnings.removeAll(first.warnings());
            state.warnings.addAll(last.warnings());
            if (!last.repairs().isEmpty()) {
                state.warnings.add("已完成唯一补识别轮次，仍有跨缝内容需要人工校对");
            }
            return state.finish(last.incomplete() || !last.repairs().isEmpty());
        } catch (BatchOcrCancelledException exception) {
            throw exception;
        } catch (AgentModelException exception) {
            state.warnings.add("模型调用未完成，已识别的原片文字仍可校对");
            Result partial = state.finish(true);
            throw new BatchOcrProcessingException(exception.failure(), partial);
        } catch (RuntimeException exception) {
            state.warnings.add("处理未完成，已识别的原片文字仍可校对");
            throw new BatchOcrProcessingException(Failure.MODEL_UNAVAILABLE, state.finish(true));
        }
    }

    private void recognize(List<InputImage> images, List<Tile> requests, State state, String stage) {
        for (Source source : state.sources) {
            List<Tile> sourceTiles = requests.stream()
                    .filter(tile -> tile.sourceId().equals(source.sourceId())).toList();
            if (sourceTiles.isEmpty()) {
                continue;
            }
            state.checkCancel();
            InputImage input = images.stream().filter(image -> source.sourceId().equals(image.sourceId()))
                    .findFirst().orElseThrow();
            BufferedImage decoded = BatchOcrImageSupport.decode(input);
            try {
                for (Tile tile : sourceTiles) {
                    state.checkCancel();
                    String markdown = ocr.recognizeTile(BatchOcrImageSupport.crop(decoded, tile));
                    // 调用期间的取消也保留刚返回的片段，然后阻止下一次模型调用。
                    if (markdown == null) {
                        throw new AgentModelException(Failure.MODEL_EMPTY_RESPONSE);
                    }
                    int remaining = MAX_TOTAL_TEXT
                            - state.tiles.stream().mapToInt(item -> item.markdown().length()).sum();
                    int allowed = Math.min(MAX_TILE_TEXT, Math.max(0, remaining));
                    if (markdown.length() > allowed) {
                        state.replace(tile, markdown.substring(0, allowed));
                        state.warnings.add("原片文字超过长度上限，仅保留可校对前缀：" + tile.tileId());
                        throw new AgentModelException(Failure.MODEL_RESPONSE_TOO_LARGE);
                    }
                    state.replace(tile, markdown);
                    state.completed++;
                    state.publish(stage);
                    state.checkCancel();
                }
            } finally {
                decoded.flush();
            }
        }
    }

    private Assembly assemble(State state, boolean finalRound) {
        state.checkCancel();
        state.publish("ASSEMBLE");
        String reply = assembler.assemble(state.sources, List.copyOf(state.tiles), finalRound);
        state.checkCancel();
        try {
            return BatchOcrAssemblyValidator.parse(reply, state.sources, state.tiles);
        } catch (IllegalArgumentException exception) {
            state.warnings.add("模型重组结构或来源引用无效，保留原片供人工校对");
            state.finish(true);
            return null;
        }
    }

    private List<Tile> repairs(List<Repair> requests, State state) {
        Map<String, Integer> counts = new HashMap<>();
        List<Tile> tiles = new ArrayList<>();
        for (Repair request : requests) {
            if (state.tiles.size() + tiles.size() >= BatchOcrImageSupport.MAX_TILES) {
                state.warnings.add("整批已达24个图块上限，仍有区域未完成补识别");
                break;
            }
            Source source = state.sources.stream().filter(item -> item.sourceId().equals(request.sourceId()))
                    .findFirst().orElseThrow();
            Tile original = state.tiles.stream().filter(item -> item.tileId().equals(request.tileId()))
                    .findFirst().orElseThrow();
            int index = counts.merge(source.sourceId(), 1, Integer::sum) - 1;
            Tile repair = BatchOcrImageSupport.repair(source, original, request.edge(), index);
            boolean duplicate = tiles.stream().anyMatch(item -> item.sourceId().equals(repair.sourceId())
                    && item.x() == repair.x() && item.y() == repair.y()
                    && item.width() == repair.width() && item.height() == repair.height());
            if (!duplicate) {
                tiles.add(repair);
            }
        }
        return tiles;
    }

    private static final class State {
        private final List<Source> sources;
        private final List<Tile> tiles = new ArrayList<>();
        private final List<String> warnings = new ArrayList<>();
        private final Observer observer;
        private List<Question> questions = List.of();
        private int total;
        private int completed;

        private State(List<Source> sources, Observer observer) {
            this.sources = sources;
            this.observer = observer;
        }

        private void replace(Tile tile, String markdown) {
            int index = tiles.indexOf(tile);
            tiles.set(index, new Tile(tile.tileId(), tile.sourceId(), tile.x(), tile.y(), tile.width(),
                    tile.height(), tile.repair(), markdown));
        }

        private Result result(boolean incomplete) {
            List<String> bounded = warnings.size() <= 20 ? List.copyOf(warnings)
                    : new ArrayList<>(warnings.subList(0, 19));
            if (warnings.size() > 20) {
                bounded.add("另有校对提示，请逐一核对全部原片文字");
            }
            return new Result(questions, sources, tiles, bounded, incomplete || completed < total);
        }

        private void publish(String stage) {
            observer.partial(result(true));
            observer.progress(completed, total, stage);
        }

        private Result finish(boolean incomplete) {
            Result result = result(incomplete || !warnings.isEmpty());
            observer.partial(result);
            return result;
        }

        private void checkCancel() {
            if (observer.shouldCancel() || Thread.currentThread().isInterrupted()) {
                warnings.add("批量识别已取消，未完成部分请人工校对或重新识别");
                throw new BatchOcrCancelledException(finish(true));
            }
        }
    }
}
