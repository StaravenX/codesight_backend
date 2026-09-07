-- 1. 用户关注表
CREATE TABLE IF NOT EXISTS user_following (
    id BIGINT UNSIGNED NOT NULL COMMENT '主键 ID',
    from_user_id BIGINT UNSIGNED NOT NULL COMMENT '发起关注的用户 ID',
    to_user_id BIGINT UNSIGNED NOT NULL COMMENT '被关注的目标用户 ID',
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '关注创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_from_to (from_user_id, to_user_id),
    KEY idx_from_time (from_user_id, created_time DESC, to_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户关注表';

-- 2. 用户粉丝表
CREATE TABLE IF NOT EXISTS user_follower (
    id BIGINT UNSIGNED NOT NULL COMMENT '主键 ID',
    to_user_id BIGINT UNSIGNED NOT NULL COMMENT '被关注的目标用户 ID',
    from_user_id BIGINT UNSIGNED NOT NULL COMMENT '关注者用户 ID',
    created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '关注创建时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_to_from (to_user_id, from_user_id),
    KEY idx_to_time (to_user_id, created_time DESC, from_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户粉丝表';
