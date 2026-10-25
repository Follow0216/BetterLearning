package com.betterlearning.server.agent;

import com.betterlearning.server.multiagent.StepExecutor;
import com.betterlearning.server.multiagent.StepExecutorFactory;
import com.betterlearning.server.multiagent.TutorLedger;
import org.springframework.stereotype.Component;

/** app装配端口，agent/learning无反向模块依赖。 */
@Component
public class LedgerStepExecutorFactory implements StepExecutorFactory {
    private final TutorLedger ledger;

    public LedgerStepExecutorFactory(TutorLedger ledger) {
        this.ledger = ledger;
    }

    @Override
    public StepExecutor forRun(long accountId, String runId) {
        return new LedgerStepExecutor(ledger, accountId, runId);
    }
}
