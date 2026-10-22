CREATE TABLE app_user (
    account_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username VARCHAR(32) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    phone VARCHAR(16),
    email VARCHAR(254),
    role VARCHAR(16) NOT NULL DEFAULT 'USER',
    token_version INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_app_user_username UNIQUE (username),
    CONSTRAINT uk_app_user_phone UNIQUE (phone),
    CONSTRAINT uk_app_user_email UNIQUE (email),
    CONSTRAINT ck_app_user_role CHECK (role IN ('USER', 'ADMIN')),
    CONSTRAINT ck_app_user_token_version CHECK (token_version >= 0)
);