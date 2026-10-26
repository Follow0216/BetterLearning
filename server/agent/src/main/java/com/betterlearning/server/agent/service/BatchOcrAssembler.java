package com.betterlearning.server.agent.service;

import java.util.List;

import com.betterlearning.server.agent.model.BatchOcrModels.Source;
import com.betterlearning.server.agent.model.BatchOcrModels.Tile;

/** 独立的固定结构转写重组端口；不调用解题流水线、不创建正式题目。 */
public interface BatchOcrAssembler {
    String assemble(List<Source> sources, List<Tile> tiles, boolean finalRound);
}
