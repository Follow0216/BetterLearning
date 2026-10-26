package com.betterlearning.server.agent;

import com.betterlearning.server.learning.service.RolloutGate;
import com.betterlearning.server.multiagent.TabletPresence;
import com.betterlearning.server.security.AuthenticatedAccount;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** 账号来自认证上下文；客户端只能声明其当前前台可冻结题目。 */
@RestController
public class TabletPresenceController {
    private final TabletPresence presence;
    private final RolloutGate rollout;

    public TabletPresenceController(TabletPresence presence, RolloutGate rollout) {
        this.presence = presence;
        this.rollout = rollout;
    }

    @PostMapping("/api/v2/learning/tablet-presence")
    public TabletPresence.Receipt heartbeat(@AuthenticationPrincipal AuthenticatedAccount account,
            @RequestBody TabletPresence.Heartbeat input) {
        rollout.require("V2");
        return presence.heartbeat(account.accountId(), input);
    }

    @GetMapping("/api/v2/learning/questions/{questionId}/tablet-availability")
    public TabletPresence.Availability availability(@AuthenticationPrincipal AuthenticatedAccount account,
            @PathVariable String questionId) {
        rollout.require("V2");
        return presence.availability(account.accountId(), questionId);
    }
}
