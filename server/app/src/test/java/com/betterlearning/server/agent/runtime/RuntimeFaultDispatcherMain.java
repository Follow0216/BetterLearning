package com.betterlearning.server.agent.runtime;

import com.betterlearning.server.multiagent.TutorLedger;
import com.betterlearning.worker.AgentWorkerApplication;
import java.time.Clock;
import java.util.concurrent.CountDownLatch;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** 独立 dispatcher 子 JVM；父测试控制是否在启动后投递一次 outbox。 */
public final class RuntimeFaultDispatcherMain {
    private RuntimeFaultDispatcherMain() {
    }

    public static void main(String[] args) throws InterruptedException {
        boolean dispatchNow = Boolean.parseBoolean(System.getenv("P2_DISPATCH_NOW"));
        ConfigurableApplicationContext context = new SpringApplicationBuilder(AgentWorkerApplication.class)
                .web(WebApplicationType.NONE)
                .run("--app.agent.workflow.enabled=true",
                "--app.agent.workflow.worker-enabled=false",
                "--app.agent.workflow.dispatcher-enabled=false",
                "--spring.main.banner-mode=off");
        Runtime.getRuntime().addShutdownHook(new Thread(context::close, "p2-dispatcher-context-close"));
        System.out.println("P2_DISPATCHER_READY");
        System.out.flush();
        try {
            if (dispatchNow) {
                TutorLedger ledger = context.getBean(TutorLedger.class);
                TemporalRunGateway gateway = context.getBean(TemporalRunGateway.class);
                new WorkflowOutboxDispatcher(ledger, gateway, Clock.systemUTC(), 50).run();
                System.out.println("P2_DISPATCHED");
                System.out.flush();
            } else {
                new CountDownLatch(1).await();
            }
        } finally {
            context.close();
        }
    }
}
