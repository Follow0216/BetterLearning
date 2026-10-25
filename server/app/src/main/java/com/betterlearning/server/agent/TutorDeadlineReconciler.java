package com.betterlearning.server.agent;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.betterlearning.server.multiagent.TutorLedger;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

/** 业务期限不依赖 Temporal 可用性，输入/排队超时与取消清理持续释放交互槽位。 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "app.agent.workflow", name = "dispatcher-enabled", havingValue = "false", matchIfMissing = true)
public class TutorDeadlineReconciler {
    private static final Logger LOGGER = LoggerFactory.getLogger(TutorDeadlineReconciler.class);
    private final TutorLedger ledger;

    public TutorDeadlineReconciler(TutorLedger ledger) {
        this.ledger = ledger;
    }

    @Scheduled(fixedDelay = 5000, initialDelay = 5000)
    public void reconcile() {
        try {
            ledger.reconcileDeadlines(Instant.now(), 100);
        } catch (RuntimeException exception) {
            // 不输出可能带 SQL 参数、正文或供应商诊断的异常链。下一轮继续收敛。
            LOGGER.warn("辅导期限对账暂不可用，将在下一轮重试");
        }
    }
}
