package com.betterlearning.server.service;

import com.betterlearning.server.model.dto.CreateSessionDTO;
import com.betterlearning.server.model.vo.SessionVO;

/**
 * 会话业务服务：登录签发令牌与退出注销当前会话。
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
public interface SessionService {

    /**
     * 校验凭据并签发会话令牌。
     *
     * <p>账号不存在与密码错误统一返回同一 401 响应，不暴露账号是否存在。只有令牌签发且会话
     * 写入成功后才会返回令牌；写入失败时调用方收不到令牌。</p>
     *
     * @param request 登录请求，调用方已完成字段校验；服务在限流前规范化用户名
     * @param clientIp 客户端 IP，用于按 IP 与用户名双维度限流
     * @return 令牌、剩余有效秒数与账户资料
     * @throws com.betterlearning.server.exception.BusinessException 触发登录限流（{@code A0501}）、
     *         凭据无效（{@code A0200}），或限流存储／会话存储不可用（{@code C0001}）
     * @throws org.springframework.dao.DataAccessException 读取用户时数据库访问失败
     * @throws IllegalStateException 用户记录缺少账户 ID 或令牌版本，无法签发
     */
    SessionVO login(CreateSessionDTO request, String clientIp);

    /**
     * 注销当前令牌对应的会话。
     *
     * <p>只删除入参 jti 对应的会话，不撤销同一账户通过其他登录获得的有效令牌。</p>
     *
     * @param jwtId 当前令牌的 jti
     * @throws com.betterlearning.server.exception.BusinessException 会话存储不可用（{@code C0001}）
     */
    void logout(String jwtId);
}
