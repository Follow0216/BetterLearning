package com.betterlearning.server.learning.service;

import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.mapper.RolloutGateMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 进程共用的单写发布门禁。 */
@Service
public class RolloutGate {
    private final RolloutGateMapper mapper;

    public RolloutGate(RolloutGateMapper mapper) {
        this.mapper = mapper;
    }

    /** 写入层须在事务内调用并持锁至提交；HTTP 提前检查只用于快速拒绝。 */
    public void require(String version) {
        if (!version.equals(mapper.sharedMode())) {
            throw new BusinessException(ErrorCode.TUTOR_TURN_CONFLICT);
        }
    }

    /** 部署时申请目标模式；活动任务只能先进入 DRAIN。 */
    @Transactional
    public void switchTo(String target) {
        switchFrom(null, target);
    }

    /** 显式 CAS 发布切换；无 transition-from 时只核对当前模式。 */
    @Transactional
    public void switchFrom(String expected, String target) {
        if (!"LEGACY".equals(target) && !"V2".equals(target) && !"DRAIN".equals(target)) {
            throw new IllegalArgumentException("无效的多 Agent 发布模式");
        }
        String current = mapper.exclusiveMode();
        if (current == null) {
            throw new IllegalStateException("多 Agent 发布门禁未初始化");
        }
        if (current.equals(target)) {
            return;
        }
        if (expected != null && !expected.equals(current)) {
            throw new IllegalStateException("多 Agent 发布模式已变化，拒绝过期切换");
        }
        if (!"DRAIN".equals(target) && mapper.anyActive()) {
            throw new IllegalStateException("仍有活动辅导任务，请先进入 DRAIN 并排空或取消");
        }
        mapper.setMode(target);
    }

    @Transactional
    public void assertMode(String expected) {
        if (!expected.equals(mapper.sharedMode())) {
            throw new IllegalStateException("部署发布模式与数据库不一致，请先完成显式切换");
        }
    }
}
