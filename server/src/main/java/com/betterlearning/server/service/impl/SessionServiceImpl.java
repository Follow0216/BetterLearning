package com.betterlearning.server.service.impl;

import java.time.Duration;

import com.betterlearning.server.converter.UserConverter;

import com.betterlearning.server.dao.UserDao;
import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.manager.RateLimitManager;
import com.betterlearning.server.manager.SessionManager;
import com.betterlearning.server.model.dataobject.UserDO;
import com.betterlearning.server.model.dto.CreateSessionDTO;
import com.betterlearning.server.model.vo.SessionVO;
import com.betterlearning.server.security.IssuedToken;
import com.betterlearning.server.security.JwtTokenService;
import com.betterlearning.server.service.SessionService;
import com.betterlearning.server.validation.UserInputNormalizer;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * {@link SessionService} 的默认实现。
 *
 * <p>登录不声明数据库事务：先限流，再单次查询用户并校验密码，最后签发令牌并写入会话。
 * 令牌只在会话写入成功后返回；退出仅删除当前 jti 对应的会话。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Service
public class SessionServiceImpl implements SessionService {

    private static final String INVALID_CREDENTIALS_MESSAGE = "用户名或密码错误";

    private static final String DUMMY_PASSWORD = "betterlearning-dummy-password";

    private final UserDao userDao;

    private final PasswordEncoder passwordEncoder;

    private final JwtTokenService jwtTokenService;

    private final SessionManager sessionManager;

    private final RateLimitManager rateLimitManager;

    private final UserConverter userConverter;

    private final String dummyPasswordHash;

    /**
     * 创建会话业务实现。
     *
     * <p>构造时生成固定长度的假密码哈希，用于账号不存在时仍执行一次密码校验，使失败耗时更接近。</p>
     *
     * @param userDao 用户数据访问
     * @param passwordEncoder 密码编码器
     * @param jwtTokenService 令牌签发服务
     * @param sessionManager 会话存储
     * @param rateLimitManager 登录限流
     * @param userConverter 用户对象转换器
     */
    public SessionServiceImpl(UserDao userDao, PasswordEncoder passwordEncoder,
            JwtTokenService jwtTokenService, SessionManager sessionManager,
            RateLimitManager rateLimitManager, UserConverter userConverter) {
        this.userDao = userDao;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenService = jwtTokenService;
        this.sessionManager = sessionManager;
        this.rateLimitManager = rateLimitManager;
        this.userConverter = userConverter;
        this.dummyPasswordHash = passwordEncoder.encode(DUMMY_PASSWORD);
    }

    /**
     * {@inheritDoc}
     *
     * <p>先按 IP 与用户名限流，再查询用户；账号不存在时仍对假哈希执行一次密码校验，并返回与密码
     * 错误相同的业务异常。记录缺少账户 ID 或令牌版本时拒绝签发，抛出 {@link IllegalStateException}。
     * 会话写入失败时异常向上传播，令牌不会返回给调用方。</p>
     */
    @Override
    public SessionVO login(CreateSessionDTO request, String clientIp) {
        String username = UserInputNormalizer.normalizeUsername(request.getUsername());
        rateLimitManager.checkLogin(clientIp, username);
        UserDO user = userDao.findByUsername(username).orElse(null);
        String passwordHash = user == null ? dummyPasswordHash : user.getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(request.getPassword(), passwordHash);
        if (user == null || !passwordMatches) {
            throw new BusinessException(ErrorCode.UNAUTHORIZED, INVALID_CREDENTIALS_MESSAGE);
        }
        Long accountId = user.getAccountId();
        Integer tokenVersion = user.getTokenVersion();
        if (accountId == null || tokenVersion == null) {
            throw new IllegalStateException("用户记录缺少 account_id 或 token_version，拒绝签发令牌");
        }
        IssuedToken issuedToken = jwtTokenService.issue(accountId, tokenVersion);
        sessionManager.create(issuedToken, accountId);
        long expiresIn = Duration.between(issuedToken.getIssuedAt(),
                issuedToken.getExpiresAt()).getSeconds();
        return new SessionVO(issuedToken.getToken(), expiresIn,
                userConverter.toVO(user));
    }

    /**
     * {@inheritDoc}
     *
     * <p>直接委托会话存储删除当前 jti，不影响同一账户的其他会话。</p>
     */
    @Override
    public void logout(String jwtId) {
        sessionManager.delete(jwtId);
    }
}
