package com.betterlearning.server.multiagent;

import java.util.List;
import com.betterlearning.server.multiagent.MultiAgentModels.Evidence;
import com.betterlearning.server.multiagent.MultiAgentModels.RetrievalQuery;

/** 受控账号内检索；无命中返回空列表，绝不伪造历史。 */
public interface EvidenceRetriever {
    List<Evidence> retrieve(RetrievalQuery query);
}
