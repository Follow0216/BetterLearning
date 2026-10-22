package com.betterlearning.server.service.impl;

import java.util.Map;

import com.betterlearning.server.converter.UserConverter;

import com.betterlearning.server.dao.UserDao;
import com.betterlearning.server.enums.ErrorCode;

import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.exception.UserUniqueConflictException;
import com.betterlearning.server.manager.RateLimitManager;
import com.betterlearning.server.model.dataobject.UserDO;
import com.betterlearning.server.model.dto.ChangePasswordDTO;
import com.betterlearning.server.model.dto.RegisterUserDTO;
import com.betterlearning.server.model.dto.UpdateProfileDTO;
import com.betterlearning.server.model.vo.UserVO;
import com.betterlearning.server.service.UserService;
import com.betterlearning.server.validation.UserInputNormalizer;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link UserService} 的事务实现。
 *
 * <p>写操作在方法级事务中执行；持久层唯一冲突在此转换为带错误码的业务异常并保留 cause，
 * 事务随运行时异常回滚。查询只读事务不产生写操作。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Service
public class UserServiceImpl implements UserService {

    private final UserDao userDao;

    private final PasswordEncoder passwordEncoder;

    private final RateLimitManager rateLimitManager;

    /**
     * 创建账户业务实现。
     *
     * @param userDao 用户数据访问
     * @param passwordEncoder 密码编码器
     * @param rateLimitManager 注册限流
     */
    public UserServiceImpl(UserDao userDao, PasswordEncoder passwordEncoder,
            RateLimitManager rateLimitManager) {
        this.userDao = userDao;
        this.passwordEncoder = passwordEncoder;
        this.rateLimitManager = rateLimitManager;
    }

    /**
     * {@inheritDoc}
     *
     * <p>整个方法在事务中执行：先限流，再插入并回读；用户名唯一冲突转换为业务异常后插入回滚。
     * 插入成功但回读不到记录属于内部状态不一致，抛出 {@link IllegalStateException}。</p>
     */
    @Override
    @Transactional
    public UserVO register(RegisterUserDTO request, String clientIp) {
        rateLimitManager.checkRegistration(clientIp);
        String username = UserInputNormalizer.normalizeUsername(request.getUsername());
        String passwordHash = passwordEncoder.encode(request.getPassword());
        long accountId;
        try {
            accountId = userDao.insert(username, passwordHash);
        } catch (UserUniqueConflictException exception) {
            throw toBusinessException(exception);
        }
        UserDO created = userDao.findByAccountId(accountId)
                .orElseThrow(() -> new IllegalStateException(
                "创建用户后未能读取记录: " + accountId));
        return UserConverter.toVO(created);
    }

    /**
     * {@inheritDoc}
     *
     * <p>以只读事务执行单次查询，不产生写操作。</p>
     */
    @Override
    @Transactional(readOnly = true)
    public UserVO getProfile(long accountId) {
        return UserConverter.toVO(requireUser(accountId));
    }

    /**
     * {@inheritDoc}
     *
     * <p>方法在事务中执行。两个字段均缺失时只做查询并直接返回；有字段更新时，唯一冲突或
     * 受影响行数为 0 都会抛出业务异常并使更新回滚。</p>
     */
    @Override
    @Transactional
    public UserVO updateProfile(long accountId, UpdateProfileDTO request) {
        boolean phoneProvided = request.isPhoneProvided();
        boolean emailProvided = request.isEmailProvided();
        if (!phoneProvided && !emailProvided) {
            return UserConverter.toVO(requireUser(accountId));
        }
        String phone = phoneProvided
                ? UserInputNormalizer.normalizePhone(request.getPhone())
                : null;
        String email = emailProvided
                ? UserInputNormalizer.normalizeEmail(request.getEmail())
                : null;
        int updated;
        try {
            updated = userDao.updateContactInfo(accountId, phoneProvided, phone,
                    emailProvided, email);
        } catch (UserUniqueConflictException exception) {
            throw toBusinessException(exception);
        }
        if (updated == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return UserConverter.toVO(requireUser(accountId));
    }

    /**
     * {@inheritDoc}
     *
     * <p>事务内依次校验版本与旧密码，再执行带版本和旧哈希条件的更新；更新 0 行视为并发冲突，
     * 抛出业务异常并回滚。新哈希在通过校验后才计算。</p>
     */
    @Override
    @Transactional
    public void changePassword(long accountId, int authenticatedTokenVersion,
            ChangePasswordDTO request) {
        UserDO user = requireUser(accountId);
        Integer storedTokenVersion = user.getTokenVersion();
        if (storedTokenVersion == null || storedTokenVersion != authenticatedTokenVersion) {
            throw new BusinessException(ErrorCode.PASSWORD_CHANGE_CONFLICT);
        }
        if (!passwordEncoder.matches(request.getOldPassword(), user.getPasswordHash())) {
            throw new BusinessException(ErrorCode.INVALID_PARAM,
                    ErrorCode.INVALID_PARAM.getMessage(),
                    Map.of("oldPassword", "旧密码错误"));
        }
        String newPasswordHash = passwordEncoder.encode(request.getNewPassword());
        int updated = userDao.updatePassword(accountId, user.getPasswordHash(),
                authenticatedTokenVersion, newPasswordHash);
        if (updated == 0) {
            throw new BusinessException(ErrorCode.PASSWORD_CHANGE_CONFLICT);
        }
    }

    private UserDO requireUser(long accountId) {
        return userDao.findByAccountId(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND));
    }

    private static BusinessException toBusinessException(UserUniqueConflictException exception) {
        ErrorCode errorCode = switch (exception.getField()) {
            case USERNAME -> ErrorCode.USERNAME_EXISTS;
            case PHONE -> ErrorCode.PHONE_ALREADY_BOUND;
            case EMAIL -> ErrorCode.EMAIL_ALREADY_BOUND;
        };
        return new BusinessException(errorCode, errorCode.getMessage(), null, exception);
    }
}
