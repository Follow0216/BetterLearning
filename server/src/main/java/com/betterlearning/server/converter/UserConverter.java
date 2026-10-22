package com.betterlearning.server.converter;

import com.betterlearning.server.model.dataobject.UserDO;
import com.betterlearning.server.model.vo.UserVO;

import org.springframework.stereotype.Component;

/**
 * 用户持久化对象到响应对象的转换器。
 *
 * <p>只做对象字段映射，不查询数据库、不修改入参。账号 ID 在响应中统一转为字符串。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
@Component
public class UserConverter {

    /**
     * 将用户记录转换为对外资料。
     *
     * @param user 用户记录，可为 {@code null}
     * @return 资料对象；入参为 {@code null} 时返回 {@code null}
     * @throws IllegalStateException 记录缺少账号 ID，无法生成稳定标识
     */
    public UserVO toVO(UserDO user) {
        if (user == null) {
            return null;
        }
        Long accountId = user.getAccountId();
        if (accountId == null) {
            throw new IllegalStateException("用户记录缺少 account_id，无法转换为 UserVO");
        }
        return new UserVO(
                Long.toString(accountId),
                user.getUsername(),
                user.getPhone(),
                user.getEmail(),
                user.getRole(),
                user.getCreatedAt(),
                user.getUpdatedAt());
    }
}
