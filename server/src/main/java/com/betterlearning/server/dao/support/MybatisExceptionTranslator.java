package com.betterlearning.server.dao.support;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.springframework.dao.DataAccessResourceFailureException;

/**
 * 将 MyBatis 包装的数据库连接故障恢复为 Spring 数据访问资源异常。
 *
 * <p>仅当异常 cause 链中存在 {@link DataAccessResourceFailureException}（含
 * {@code CannotGetJdbcConnectionException}）时转换，保留原始异常作为 cause；其他 MyBatis 异常原样返回，
 * 避免把 Mapper、映射或编程错误误判为依赖不可用。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
public final class MybatisExceptionTranslator {

    /**
     * 工具类，禁止实例化。
     */
    private MybatisExceptionTranslator() {
    }

    /**
     * 尝试把 MyBatis 异常转换为连接资源故障异常。
     *
     * @param exception MyBatis 抛出的运行时异常
     * @return 连接故障时返回新的 {@link DataAccessResourceFailureException}，否则返回原异常
     */
    public static RuntimeException translate(RuntimeException exception) {
        if (!containsConnectionFailure(exception)) {
            return exception;
        }
        return new DataAccessResourceFailureException("数据库连接不可用", exception);
    }

    private static boolean containsConnectionFailure(Throwable failure) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = failure;
        while (current != null && visited.add(current)) {
            if (current instanceof DataAccessResourceFailureException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
