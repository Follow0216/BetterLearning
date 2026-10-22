package com.betterlearning.server.dao.support;

import java.util.Map;
import java.util.Optional;

import com.betterlearning.server.enums.UserUniqueField;
import com.betterlearning.server.exception.UserUniqueConflictException;

import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * 将 PostgreSQL 用户表唯一约束冲突翻译为 {@link UserUniqueConflictException}。
 *
 * <p>仅当 SQLState 为 23505 且服务器返回的约束名与预期完全一致时翻译，不解析异常文本。其他异常返回空，
 * 由调用方原样抛出。</p>
 *
 * @author BetterLearning
 * @since 2026-10-22
 */
public final class UserConstraintTranslator {

    private static final String UNIQUE_VIOLATION_SQL_STATE = "23505";

    private static final Map<String, UserUniqueField> CONSTRAINT_FIELDS = Map.of(
            "uk_app_user_username", UserUniqueField.USERNAME,
            "uk_app_user_phone", UserUniqueField.PHONE,
            "uk_app_user_email", UserUniqueField.EMAIL);

    /**
     * 工具类，禁止实例化。
     */
    private UserConstraintTranslator() {
    }

    /**
     * 尝试翻译唯一约束冲突。
     *
     * @param exception 数据完整性异常
     * @return 识别成功时返回对应冲突，否则返回空
     */
    public static Optional<UserUniqueConflictException> translate(
            DataIntegrityViolationException exception) {
        Throwable cause = NestedExceptionUtils.getMostSpecificCause(exception);
        if (!(cause instanceof PSQLException psqlException)
                || !UNIQUE_VIOLATION_SQL_STATE.equals(psqlException.getSQLState())) {
            return Optional.empty();
        }
        ServerErrorMessage serverError = psqlException.getServerErrorMessage();
        if (serverError == null) {
            return Optional.empty();
        }
        String constraint = serverError.getConstraint();
        if (constraint == null) {
            return Optional.empty();
        }
        UserUniqueField field = CONSTRAINT_FIELDS.get(constraint);
        return field == null
                ? Optional.empty()
                : Optional.of(new UserUniqueConflictException(field, exception));
    }
}
