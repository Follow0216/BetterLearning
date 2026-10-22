package com.betterlearning.server.service;

import com.betterlearning.server.model.dto.ChangePasswordDTO;
import com.betterlearning.server.model.dto.RegisterUserDTO;
import com.betterlearning.server.model.dto.UpdateProfileDTO;
import com.betterlearning.server.model.vo.UserVO;

/**
 * 账户业务服务：注册、资料查询、资料更新与密码修改。
 *
 * <p>调用方完成字段校验；本服务在业务处理前规范化相应输入。实现负责事务边界、唯一冲突翻译
 * 与业务异常转换。对外错误码见 {@code server/docs/error-codes.md}。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
public interface UserService {

    /**
     * 注册账户并返回新用户资料。
     *
     * @param request 注册请求，调用方已完成字段校验；服务在业务处理前规范化用户名
     * @param clientIp 客户端 IP，仅用于注册限流
     * @return 新账户资料，账号 ID 以字符串返回
     * @throws com.betterlearning.server.exception.BusinessException 触发注册限流（{@code A0501}）、
     *         用户名已存在（{@code A0111}）或限流存储不可用（{@code C0001}）
     * @throws org.springframework.dao.DataAccessException 数据库访问失败；未识别的完整性异常原样传播
     * @throws IllegalStateException 插入成功但无法回读新记录
     */
    UserVO register(RegisterUserDTO request, String clientIp);

    /**
     * 查询账户资料。
     *
     * @param accountId 账户 ID
     * @return 账户资料
     * @throws com.betterlearning.server.exception.BusinessException 账户不存在时抛出
     *         {@code NOT_FOUND}（{@code A0606}）
     * @throws org.springframework.dao.DataAccessException 数据库访问失败
     */
    UserVO getProfile(long accountId);

    /**
     * 按 PATCH 语义更新联系方式。
     *
     * <p>字段缺失表示保持原值，显式 {@code null} 表示解绑；空串或非法格式在进入本方法前已被拒绝。
     * 两个字段均缺失时不写库，直接返回当前资料；任一字段冲突时整个更新回滚，原值保留。</p>
     *
     * @param accountId 账户 ID
     * @param request 联系方式更新请求
     * @return 更新后的账户资料
     * @throws com.betterlearning.server.exception.BusinessException 账户不存在（{@code A0606}）或
     *         手机号／邮箱已被占用（{@code A0154}／{@code A0155}）
     * @throws org.springframework.dao.DataAccessException 数据库访问失败；未识别的完整性异常原样传播
     */
    UserVO updateProfile(long accountId, UpdateProfileDTO request);

    /**
     * 修改密码并递增令牌版本。
     *
     * <p>仅当认证令牌中的版本与库中版本一致、且旧密码校验通过时才更新；递增版本后，
     * 旧版本令牌无法通过后续认证检查。</p>
     *
     * @param accountId 账户 ID
     * @param authenticatedTokenVersion 当前请求令牌携带的版本号
     * @param request 改密请求
     * @throws com.betterlearning.server.exception.BusinessException 账户不存在（{@code A0606}）、
     *         版本冲突（{@code A0444}）或旧密码错误（{@code A0400}）
     * @throws org.springframework.dao.DataAccessException 数据库访问失败
     */
    void changePassword(long accountId, int authenticatedTokenVersion,
            ChangePasswordDTO request);
}
