package com.betterlearning.server.security;

import java.util.Objects;
import java.util.Optional;

import com.betterlearning.server.dao.UserDao;
import com.betterlearning.server.manager.SessionManager;
import com.betterlearning.server.model.dataobject.UserDO;

import org.springframework.stereotype.Component;

/**
 * 校验已解码令牌对应的账号当前是否仍可认证。
 *
 * <p>固定先检查会话是否有效，再读取账号；角色每次取自数据库，不使用令牌中的角色或缓存，也不续期。
 * 本组件不依赖 Servlet、过滤器链或 {@code SecurityContextHolder}，异常向上传播，由调用方决定响应。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Component
public class AccountAuthenticationChecker {

    private final SessionManager sessionManager;

    private final UserDao userDao;

    /**
     * 创建账号认证检查器。
     *
     * @param sessionManager 会话存储，用于判断 jti 是否仍活跃
     * @param userDao 用户数据访问，用于读取最新账号状态
     */
    public AccountAuthenticationChecker(SessionManager sessionManager, UserDao userDao) {
        this.sessionManager = sessionManager;
        this.userDao = userDao;
    }

    /**
     * 按会话、账号、令牌版本与角色的顺序校验认证状态。
     *
     * @param accountId 已解码的账号 ID
     * @param jwtId 已解码的令牌标识
     * @param tokenVersion 已解码的令牌版本
     * @return 校验通过时返回账号快照；会话不活跃、账号不存在、账号 ID 或版本缺失、
     *         版本不匹配，或角色为空或空白时返回空
     * @throws com.betterlearning.server.exception.BusinessException 会话存储不可用
     * @throws org.springframework.dao.DataAccessException 账号读取失败
     * @throws org.springframework.transaction.CannotCreateTransactionException 无法创建数据库事务
     */
    public Optional<AuthenticatedAccount> authenticate(long accountId, String jwtId,
            int tokenVersion) {
        if (!sessionManager.isActive(jwtId, accountId)) {
            return Optional.empty();
        }
        UserDO user = userDao.findByAccountId(accountId).orElse(null);
        if (user == null
                || !Objects.equals(user.getAccountId(), accountId)
                || !Objects.equals(user.getTokenVersion(), tokenVersion)
                || user.getRole() == null || user.getRole().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new AuthenticatedAccount(user.getAccountId(), user.getUsername(),
                user.getRole(), user.getTokenVersion(), jwtId));
    }
}
